(ns aimscope.coach.test-runner
  (:require [clojure.test :as t]
            [aimscope.coach.goap-test]
            [aimscope.coach.coach-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (t/run-tests 'aimscope.coach.goap-test
                                          'aimscope.coach.coach-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
