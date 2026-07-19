(ns manufacturing-labour.actor
  "Manufacturing Labour Actor — the langgraph StateGraph wiring and runtime
  for the ISCO-08 9329 independent manufacturing support labour practice
  actor. Implements the itonami actor pattern with Advisor/Governor
  separation, human-in-the-loop interrupts, and append-only audit trail.

  This actor coordinates an independent practitioner's own work-assignment
  logging, safety-briefing acknowledgments, task handoffs, and safety-concern
  reporting. It never operates manufacturing machinery or equipment directly
  (policy, not control) and never makes a payroll/wage/employment-
  classification determination for the practitioner."
  (:require [langgraph.graph :as graph]
            [manufacturing-labour.store :as store]
            [manufacturing-labour.advisor :as advisor]
            [manufacturing-labour.governor :as governor]))

(def default-state
  {:phase :intake
   :request nil
   :context {}
   :proposal nil
   :decision nil
   :error nil})

(defn- intake-node
  "Intake node: accept and validate incoming request."
  [state]
  (assoc state :phase :advise))

(defn- advise-node
  "Advise node: Advisor proposes a coordination operation."
  [state advisor-instance]
  (let [request (:request state)
        context (:context state)
        proposal (advisor/propose advisor-instance request context)]
    (assoc state :proposal proposal :phase :govern)))

(defn- govern-node
  "Govern node: Governor evaluates the proposal."
  [state store-instance]
  (let [request (:request state)
        context (:context state)
        proposal (:proposal state)
        verdict (governor/check request context proposal store-instance)]
    (assoc state :decision verdict :phase :decide)))

(defn- decide-node
  "Decide node: Route based on governor verdict."
  [state]
  (let [decision (:decision state)]
    (cond
      (:hard? decision)      (assoc state :phase :hold)
      (:escalate? decision)  (assoc state :phase :request-approval)
      true                   (assoc state :phase :commit))))

(defn- commit-node
  "Commit node: append the proposal as an immutable coordination
   record to the store's audit ledger (fixed: previously only updated
   the ephemeral in-state :records counter and never called
   `store/add-record!`, so the persistent ledger (`store/records`)
   was silently never populated -- found during flagship-checklist-
   item-2 demo-build screening, fixed at the root cause here rather
   than worked around in the demo. Mirrors the working `commit-node`
   pattern in `packingfulfillment.actor` (cloud-itonami-isco-9321),
   the reference implementation this pattern was copied from)."
  [state store-instance]
  (let [proposal (:proposal state)
        op (:op proposal)
        store' (store/add-record! store-instance op proposal)]
    (-> state
        (assoc :phase :complete :store store')
        (update :records (fn [r] (conj (or r []) {:recorded true :op op}))))))

(defn- request-approval-node
  "Request approval node: interrupt-before checkpoint for human review."
  [state]
  (assoc state :phase :awaiting-approval))

(defn- hold-node
  "Hold node: Reject the proposal (hard violation)."
  [state]
  (assoc state
         :phase :rejected
         :error (str "Hard governance violation: " (-> state :decision :violations))))

(defn build-graph
  "Build and compile the StateGraph for the manufacturing labour actor
   against the real `langgraph.graph` API (`state-graph`/`add-node`/
   `add-edge`/`add-conditional-edges`/`compile-graph`). `:decide` routes
   conditionally on the state's `:phase` (set by `decide-node` to exactly
   one of `:commit`/`:request-approval`/`:hold`, which are also the target
   node names — no path-map translation needed). Each node is a plain
   1-arity fn, which `langgraph.graph` runs directly as a Runnable.
   Returns a compiled graph ready for `langgraph.graph/invoke`."
  [advisor-instance store-instance]
  (-> (graph/state-graph)
      (graph/add-node :intake (fn [s] (intake-node s)))
      (graph/add-node :advise (fn [s] (advise-node s advisor-instance)))
      (graph/add-node :govern (fn [s] (govern-node s store-instance)))
      (graph/add-node :decide (fn [s] (decide-node s)))
      (graph/add-node :commit (fn [s] (commit-node s store-instance)))
      (graph/add-node :request-approval (fn [s] (request-approval-node s)))
      (graph/add-node :hold (fn [s] (hold-node s)))
      (graph/set-entry-point :intake)
      (graph/add-edge :intake :advise)
      (graph/add-edge :advise :govern)
      (graph/add-edge :govern :decide)
      (graph/add-conditional-edges :decide (fn [state] (:phase state)))
      (graph/set-finish-point :commit)
      (graph/set-finish-point :request-approval)
      (graph/set-finish-point :hold)
      (graph/compile-graph)))

(defn run-request!
  "Run a coordination request through the actor graph to completion.
   Returns the final state; `:phase` is the terminal outcome — `:complete`
   (committed), `:awaiting-approval` (interrupt point, needs `approve!`),
   or `:rejected` (hard governance hold, permanent)."
  [graph initial-request context store]
  (let [state (assoc default-state :request initial-request :context context)]
    (graph/invoke graph state)))

(defn approve!
  "Approve a request that was held in :request-approval phase. Human
   sign-off for escalation invariants -- AND appends the approved
   proposal to the store's audit ledger (fixed: mirrors commit-node's
   fix and `packingfulfillment.actor/approve!`'s pattern -- previously
   `store` was accepted but silently unused, so approvals were never
   persisted either)."
  [state approval-context store]
  (let [proposal (:proposal state)
        op (:op proposal)
        store' (store/add-record! store op (assoc proposal :approved true :approval approval-context))]
    (assoc state :phase :commit :approval approval-context :store store')))
