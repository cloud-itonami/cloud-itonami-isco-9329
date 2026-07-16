(ns manufacturing-labour.advisor-test
  (:require [clojure.test :refer [deftest is testing]]
            [manufacturing-labour.advisor :as advisor]))

(deftest test-mock-advisor-log-work-assignment
  (testing "Mock advisor proposes a work-assignment log with :propose effect"
    (let [a (advisor/mock-advisor)
          request {:type :log-work-assignment :task-type :material-handling
                    :hours 8 :location "Line 3" :date "2026-07-15"}
          proposal (advisor/propose a request {})]
      (is (= :log-work-assignment (:op proposal)))
      (is (= :propose (:effect proposal)))
      (is (= 8 (:hours proposal)))
      (is (pos? (:confidence proposal))))))

(deftest test-mock-advisor-acknowledge-safety-briefing
  (testing "Mock advisor proposes a safety-briefing acknowledgment"
    (let [a (advisor/mock-advisor)
          request {:type :acknowledge-safety-briefing :briefing-id "brief-42"
                    :ppe-checklist [:gloves :hi-vis :steel-toe]}
          proposal (advisor/propose a request {})]
      (is (= :acknowledge-safety-briefing (:op proposal)))
      (is (= :propose (:effect proposal)))
      (is (= "brief-42" (:briefing-id proposal)))
      (is (pos? (:confidence proposal))))))

(deftest test-mock-advisor-coordinate-task-handoff
  (testing "Mock advisor proposes a task handoff"
    (let [a (advisor/mock-advisor)
          request {:type :coordinate-task-handoff :from-task "line-clear-b"
                    :to-practitioner "prac-002" :notes "second half of shift"}
          proposal (advisor/propose a request {})]
      (is (= :coordinate-task-handoff (:op proposal)))
      (is (= :propose (:effect proposal)))
      (is (= "prac-002" (:to-practitioner proposal))))))

(deftest test-mock-advisor-flag-safety-concern
  (testing "Mock advisor proposes a safety-concern flag"
    (let [a (advisor/mock-advisor)
          request {:type :flag-safety-concern :hazard-type :unguarded-conveyor
                    :description "guard panel missing on conveyor 4"
                    :location "Line 3"}
          proposal (advisor/propose a request {})]
      (is (= :flag-safety-concern (:op proposal)))
      (is (= :propose (:effect proposal)))
      (is (= :unguarded-conveyor (:hazard-type proposal))))))

(deftest test-mock-advisor-unknown-request-type
  (testing "Mock advisor proposes :unknown with confidence 0.0 for out-of-vocabulary requests"
    (let [a (advisor/mock-advisor)
          request {:type :dispatch-forklift}
          proposal (advisor/propose a request {})]
      (is (= :unknown (:op proposal)))
      (is (= :propose (:effect proposal)))
      (is (= 0.0 (:confidence proposal))))))

(deftest test-llm-advisor-parse-failure-yields-zero-confidence
  (testing "LLM advisor placeholder always yields :propose effect and confidence 0.0 (forces escalation, never fabricated)"
    (let [a (advisor/llm-advisor nil)
          proposal (advisor/propose a {:type :log-work-assignment} {})]
      (is (= :propose (:effect proposal)))
      (is (= 0.0 (:confidence proposal))))))
