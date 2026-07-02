(ns aimscope.coach.db
  "Store of record do estado do jogador — SQLite APPEND-ONLY (ADR 0001).

  Regra de ouro: nunca UPDATE — só INSERT com tx_time. Uma tabela genérica de
  fatos com payload EDN e chave natural p/ dedupe idempotente (re-ingestão de
  sessão não duplica). Snapshot derivado (skill atual etc.) é SEMPRE
  recomputável do log; datascript entra em memória p/ as queries datalog."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [datascript.core :as d])
  (:import [java.time Instant]))

(defn default-db-path []
  (str (io/file (System/getenv "LOCALAPPDATA") "aimscope" "user.db")))

(defn connect
  "Abre (criando se preciso) o user.db e garante o schema."
  [path]
  (io/make-parents (io/file path))
  (let [ds (jdbc/get-datasource {:dbtype "sqlite" :dbname path})]
    (jdbc/execute! ds ["CREATE TABLE IF NOT EXISTS facts (
                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                          tx_time TEXT NOT NULL,
                          kind TEXT NOT NULL,
                          natural_key TEXT,
                          payload TEXT NOT NULL)"])
    (jdbc/execute! ds ["CREATE UNIQUE INDEX IF NOT EXISTS facts_nk
                          ON facts(kind, natural_key)
                          WHERE natural_key IS NOT NULL"])
    ds))

(defn append!
  "INSERT OR IGNORE (dedupe pela chave natural). Devolve true se inseriu."
  [ds kind natural-key payload]
  (pos? (:next.jdbc/update-count
         (first (jdbc/execute! ds ["INSERT OR IGNORE INTO facts
                                      (tx_time, kind, natural_key, payload)
                                      VALUES (?,?,?,?)"
                                   (str (Instant/now)) (name kind)
                                   natural-key (pr-str payload)])))))

(defn facts
  "Todos os fatos de um kind, em ordem de inserção, payloads lidos de volta."
  [ds kind]
  (mapv (fn [{:keys [facts/tx_time facts/payload facts/natural_key]}]
          (assoc (edn/read-string payload)
                 :fact/tx-time tx_time
                 :fact/key natural_key))
        (jdbc/execute! ds ["SELECT tx_time, natural_key, payload
                              FROM facts WHERE kind = ? ORDER BY id" (name kind)]
                       {:builder-fn rs/as-kebab-maps})))

;; ---------------------------------------------------------------------------
;; datascript: projeção em memória p/ queries datalog (mesma API do Datomic)
;; ---------------------------------------------------------------------------

(def ^:private ds-schema
  {:score/scenario   {}
   :score/catalog-id {}
   :score/value      {}
   :score/played-at  {}
   :kin/session      {}
   :skill/name       {}
   :skill/value      {}})

(defn ->datascript
  "Carrega scores + skills num db datascript. Uso: joins catálogo × histórico."
  [scores skills]
  (let [conn (d/create-conn ds-schema)]
    (d/transact! conn
      (concat
       (map (fn [s] {:score/scenario   (:scenario s)
                     :score/catalog-id (or (:catalog-id s) :unknown)
                     :score/value      (:score s)
                     :score/played-at  (str (:played-at s))})
            scores)
       ;; skills sem evidência (value nil) ficam fora do db — honestidade
       (keep (fn [[k v]] (when (:value v) {:skill/name k :skill/value (:value v)})) skills)))
    @conn))
