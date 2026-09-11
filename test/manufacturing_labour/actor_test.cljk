(ns manufacturing-labour.actor-test
  "Integration tests: build the real `langgraph.graph` StateGraph and run
  a request through it end-to-end, exercising all three terminal routes
  (:commit / :request-approval / :hold). Public functions only."
  (:require [clojure.test :refer [deftest is testing]]
            [manufacturing-labour.actor :as actor]
            [manufacturing-labour.advisor :as advisor]
            [manufacturing-labour.store :as store]))

;; NOTE: `test-run-request-commit-persists-to-store-ledger` and
;; `test-approve-persists-to-store-ledger` below were added to prove the
;; commit-node/approve! store-persistence fix (see
;; `manufacturing-labour.actor`'s `commit-node`/`approve!` docstrings):
;; before the fix, `(:records final-state)` (the ephemeral in-state
;; counter, asserted by the pre-existing test above) was populated but
;; the STORE's own audit ledger (`store/records`) was never written to,
;; because `commit-node` never called `store/add-record!` and
;; `approve!` silently ignored its `store` argument. These new tests
;; assert against `store/records` directly, which is the persistent
;; ledger a real caller would rely on across requests -- this is the
;; regression-proof for the fix, additive to (not a replacement of) the
;; existing tests above.

(defn- registered-store []
  (-> (store/create-store)
      (store/register-practitioner! "prac-001" {:name "J. Rivera" :status :active})))

(deftest test-run-request-commits-valid-work-assignment
  (testing "A valid work-assignment request runs the graph to :complete"
    (let [test-store (registered-store)
          g (actor/build-graph (advisor/mock-advisor) test-store)
          final-state (actor/run-request!
                       g
                       {:practitioner-id "prac-001" :type :log-work-assignment
                        :task-type :material-handling :hours 8 :location "Line 3"}
                       {}
                       test-store)]
      (is (= :complete (:phase final-state)))
      (is (nil? (:error final-state)))
      (is (= [{:recorded true :op :log-work-assignment}] (:records final-state))))))

(deftest test-run-request-escalates-safety-concern
  (testing "A safety-concern request always routes to :awaiting-approval"
    (let [test-store (registered-store)
          g (actor/build-graph (advisor/mock-advisor) test-store)
          final-state (actor/run-request!
                       g
                       {:practitioner-id "prac-001" :type :flag-safety-concern
                        :hazard-type :unguarded-conveyor :description "guard missing"}
                       {}
                       test-store)]
      (is (= :awaiting-approval (:phase final-state)))
      (is (true? (-> final-state :decision :escalate?))))))

(deftest test-run-request-holds-unregistered-practitioner
  (testing "A request from an unregistered practitioner is permanently held"
    (let [test-store (store/create-store)
          g (actor/build-graph (advisor/mock-advisor) test-store)
          final-state (actor/run-request!
                       g
                       {:practitioner-id "ghost" :type :log-work-assignment
                        :task-type :material-handling :hours 8}
                       {}
                       test-store)]
      (is (= :rejected (:phase final-state)))
      (is (true? (-> final-state :decision :hard?)))
      (is (some? (:error final-state))))))

(deftest test-approve-transitions-held-approval-to-commit
  (testing "approve! transitions an :awaiting-approval state to :commit with the approval context attached"
    (let [test-store (registered-store)
          g (actor/build-graph (advisor/mock-advisor) test-store)
          held-state (actor/run-request!
                      g
                      {:practitioner-id "prac-001" :type :flag-safety-concern
                       :hazard-type :unguarded-conveyor}
                      {}
                      test-store)
          approved (actor/approve! held-state {:approved-by "site-supervisor-1"} test-store)]
      (is (= :commit (:phase approved)))
      (is (= {:approved-by "site-supervisor-1"} (:approval approved))))))

(deftest test-run-request-commit-persists-to-store-ledger
  (testing "commit-node actually appends to the store's own audit ledger, not just the ephemeral state counter"
    (let [test-store (registered-store)
          g (actor/build-graph (advisor/mock-advisor) test-store)
          final-state (actor/run-request!
                       g
                       {:practitioner-id "prac-001" :type :log-work-assignment
                        :task-type :material-handling :hours 8 :location "Line 3"}
                       {}
                       test-store)]
      (is (= 1 (count (store/records (:store final-state)))))
      (is (= :log-work-assignment (:type (first (store/records (:store final-state)))))))))

(deftest test-approve-persists-to-store-ledger
  (testing "approve! also appends to the store's own audit ledger upon human sign-off"
    (let [test-store (registered-store)
          g (actor/build-graph (advisor/mock-advisor) test-store)
          held-state (actor/run-request!
                      g
                      {:practitioner-id "prac-001" :type :flag-safety-concern
                       :hazard-type :unguarded-conveyor}
                      {}
                      test-store)
          approved (actor/approve! held-state {:approved-by "site-supervisor-1"} test-store)]
      (is (= 1 (count (store/records (:store approved)))))
      (is (true? (:approved (first (store/records (:store approved)))))))))
