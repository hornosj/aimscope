(ns aimscope.coach.goap-test
  (:require [clojure.test :refer [deftest is testing]]
            [aimscope.coach.goap :as goap]))

(def acts
  [{:name "barato-a"  :pre #{}          :post #{"a?"}      :cost 1.0}
   {:name "caro-a"    :pre #{}          :post #{"a?"}      :cost 9.0}
   {:name "b-gated"   :pre #{"a?"}      :post #{"b?"}      :cost 1.0}
   {:name "c-direto"  :pre #{}          :post #{"c?"}      :cost 2.0}])

(deftest escolhe-caminho-barato
  (let [r (goap/plan acts #{} {:name "g" :pre #{"a?"}})]
    (is (= :ok (:status r)))
    (is (= ["barato-a"] (:plan r)))))

(deftest respeita-pre-em-cascata
  (testing "b exige a antes — o plano encadeia"
    (let [r (goap/plan acts #{} {:name "g" :pre #{"b?"}})]
      (is (= :ok (:status r)))
      (is (= ["barato-a" "b-gated"] (:plan r))))))

(deftest goal-composto-e-estado-inicial
  (testing "condição já satisfeita no estado inicial não gera ação"
    (let [r (goap/plan acts #{"a?"} {:name "g" :pre #{"a?" "c?"}})]
      (is (= :ok (:status r)))
      (is (= ["c-direto"] (:plan r))))))

(deftest sem-plano-quando-impossivel
  (is (= :no-plan (:status (goap/plan acts #{} {:name "g" :pre #{"x?"}})))))
