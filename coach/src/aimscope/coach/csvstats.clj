(ns aimscope.coach.csvstats
  "Parser (defensivo) do CSV de stats do KovaaK's — versão Clojure, espelho do
  kovaaks.py do sensor. O coach lê TODOS os CSVs de cada sessão (o sensor só
  analisa o mais recente p/ cinemática); cada CSV = um score datado."
  (:require [clojure.string :as str])
  (:import [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]))

(def ^:private fname-re
  #"(?i)(.*?)\s*-\s*.*?(\d{4})\.(\d{2})\.(\d{2})-(\d{2})\.(\d{2})\.(\d{2})\s+Stats\.csv$")

(defn- parse-double* [s]
  (when (and s (re-find #"^-?[\d.]+" (str/trim (str s))))
    (try (Double/parseDouble (re-find #"-?[\d.]+" (str/trim (str s))))
         (catch Exception _ nil))))

(defn parse-file
  "Devolve {:scenario :score :accuracy :kills :played-at :sens-scale :horiz-sens
  :file} ou nil se o arquivo não parece um stats CSV."
  [file]
  (let [fname (.getName (clojure.java.io/file file))]
    (when-let [[_ scen y mo d h mi s] (re-find fname-re fname)]
      (let [kv (into {}
                     (keep (fn [line]
                             (let [cells (str/split line #",")]
                               (when (and (>= (count cells) 2)
                                          (str/ends-with? (str/trim (first cells)) ":"))
                                 [(-> (first cells) str/trim (str/replace #":$" ""))
                                  (str/trim (second cells))])))
                           (str/split-lines (slurp file))))
            shots (parse-double* (kv "Shots"))
            hits  (parse-double* (kv "Hits"))]
        {:file       fname
         :scenario   (or (not-empty (kv "Scenario")) (str/trim scen))
         :score      (parse-double* (kv "Score"))
         :kills      (some-> (kv "Kills") parse-double* long)
         :accuracy   (when (and shots hits (pos? shots)) (/ hits shots))
         :sens-scale (kv "Sens Scale")
         :horiz-sens (parse-double* (kv "Horiz Sens"))
         ;; ISO-string (EDN round-trip seguro; ordena lexicograficamente)
         :played-at  (str (LocalDateTime/parse (format "%s-%s-%sT%s:%s:%s" y mo d h mi s)
                                               DateTimeFormatter/ISO_LOCAL_DATE_TIME))}))))
