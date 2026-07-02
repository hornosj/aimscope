(ns aimscope.coach.ingest
  "Varre o diretório de sessões e apenda fatos novos no user.db:
  - :score       um por CSV do KovaaK's (chave natural cenário|timestamp)
  - :kinematics  um por metrics.json (chave natural = nome da sessão)
  Idempotente por construção (INSERT OR IGNORE)."
  (:require [aimscope.coach.csvstats :as csv]
            [aimscope.coach.catalog :as cat]
            [aimscope.coach.db :as db]
            [cheshire.core :as json]
            [clojure.java.io :as io]))

(defn- session-dirs [base]
  (->> (.listFiles (io/file base))
       (filter #(and (.isDirectory %) (.exists (io/file % "manifest.json"))))
       (sort-by #(.getName %))))

(defn- ingest-session! [ds catalog dir]
  (let [csvs (filter #(re-find #"(?i)\.csv$" (.getName %)) (.listFiles dir))
        scores (keep csv/parse-file csvs)
        n-scores
        (count (filter (fn [s]
                         (when (:score s)
                           (let [entry (cat/match-scenario catalog (:scenario s))]
                             (db/append! ds :score
                                         (str (:scenario s) "|" (:played-at s))
                                         (assoc s
                                                :catalog-id (:id entry)
                                                :session (.getName dir))))))
                       scores))
        mfile (io/file dir "metrics.json")
        n-kin (if (.exists mfile)
                (if (db/append! ds :kinematics (.getName dir)
                                {:session (.getName dir)
                                 :metrics (json/parse-string (slurp mfile) true)})
                  1 0)
                0)]
    {:session (.getName dir) :scores n-scores :kinematics n-kin}))

(defn ingest!
  "Ingere tudo que há de novo. Devolve resumo por sessão (só as com novidade)."
  [ds base-dir]
  (let [catalog (cat/load-catalog)]
    (->> (session-dirs base-dir)
         (map #(ingest-session! ds catalog %))
         (filter #(or (pos? (:scores %)) (pos? (:kinematics %))))
         vec)))
