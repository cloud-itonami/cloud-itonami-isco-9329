(ns manufacturing-labour.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.
  This repo previously had NO demo page and no generator at all
  (cloud-itonami ISCO-08 no-demo backlog, maturity-loop iteration 15).
  This namespace drives the REAL, compiled `langgraph.graph` StateGraph
  in `manufacturing-labour.actor` (`intake -> advise -> govern -> decide
  -> commit | request-approval | hold`) through an eight-request
  scenario built from real, exercised store data and renders the
  result deterministically -- no invented numbers, no wall-clock
  content on the page (`store/add-record!` stamps a real
  `:timestamp`, deliberately omitted from the printed page so output
  stays byte-identical across reruns).

  ROOT-CAUSE FIX applied before this demo was built (see
  `manufacturing-labour.actor`'s `commit-node`/`approve!` docstrings
  for the full detail): `commit-node` previously only updated the
  ephemeral in-state `:records` counter and never called
  `store/add-record!`, so the persistent audit ledger
  (`store/records`) was silently never populated; `approve!` accepted
  a `store` argument but never used it. Both were found during this
  demo-build's screening pass (same bug class as
  `cloud-itonami-isco-8343`, fixed independently in that sibling repo
  this same iteration) and fixed at the root cause -- mirroring the
  working `commit-node`/`approve!` pattern already proven correct in
  `packingfulfillment.actor` (`cloud-itonami-isco-9321`) -- rather than
  worked around here in the demo generator. This demo is only possible
  because of that fix: before it, every row below would show
  `auto-committed`/`approved` in the ephemeral sense but the \"Final
  audit ledger\" section would always read 0, no matter how many
  requests ran.

  Seed data: practitioner `prac-001` \"J. Rivera\" `{:status :active}`
  is the REAL fixture value already exercised in
  `test/manufacturing_labour/actor_test.cljc`'s `registered-store`
  (used verbatim here, not invented). A second practitioner `prac-002`
  \"Morgan Ito\" and a worksite `site-1` are additional demo entities
  registered here via the store's own real
  `register-practitioner!`/`register-worksite!` protocol calls --
  disclosed plainly as additions beyond the existing test fixture. Note
  the worksite is registered for entity richness only:
  `manufacturing-labour.governor/check` never reads worksite records at
  all (no `store/worksite` calls anywhere in `governor.cljc`) -- it is
  NOT part of any gate, and this is disclosed rather than implied.

  Store threading: `manufacturing-labour.actor/build-graph` DOES close
  over the store instance at build time (baked into `commit-node`'s and
  `govern-node`'s closures, unlike `packingfulfillment.actor` where the
  store is threaded per-call) -- so a single compiled graph does not
  see later writes. To get a real, cumulative, growing audit ledger
  across this scenario's eight requests (rather than each row only ever
  seeing its own single resulting entry), this generator rebuilds the
  graph before every request with the latest store value returned by
  the previous request -- exactly the pattern
  `manufacturing-labour.actor/build-graph`'s own docstring implies a
  caller must use across multiple requests against an evolving store.

  Governor rule coverage (`manufacturing-labour.governor/check`: 4 hard
  invariants + 3 escalation invariants). This scenario actually
  triggers, via the real mock Advisor and real Governor: `no-
  practitioner` and `spec-basis` (via an unsupported request type,
  which the mock Advisor maps to `:unknown` -- `:unknown` is not a
  member of the closed `known-ops` vocabulary) among the hard rules,
  plus `flag-safety-concern` (always-escalate) and `shift-hours-
  implausible?` (both an over-long and a non-positive `:hours` value)
  among the escalation rules -- one shift-hours escalation is
  deliberately left un-approved in this scenario to also demonstrate
  the `:awaiting-approval` terminal state itself, not just its resolved
  outcome. TWO hard rules and ONE escalation rule are structurally
  UNREACHABLE through `manufacturing-labour.advisor/mock-advisor` and
  are disclosed here rather than silently omitted, confirmed by reading
  `governor_test.cljc` (which can only exercise them by constructing a
  proposal map directly, bypassing the advisor):
    - `no-actuation` requires a proposal `:effect` other than
      `:propose`, but every branch of `mock-advisor`'s `propose`
      unconditionally sets `:effect :propose`.
    - `scope-boundary` requires a proposal `:op` to be a member of
      `forbidden-ops` (`:operate-machinery` etc.), but `mock-advisor`'s
      `case` on the request `:type` can only ever produce one of the
      four `known-ops` values or fall through to `:unknown` -- it never
      maps any request `:type` to a `forbidden-ops` value, so this
      branch of `hard-violations` can never fire from this advisor
      (an unsupported request type instead trips `spec-basis` first,
      since `:unknown` is simply absent from `known-ops`, not present
      in `forbidden-ops`).
    - `confidence-floor` (< 0.6) requires a supported op with low
      confidence, but every supported op's mock confidence is >= 0.7,
      and the only path to a lower confidence (`:unknown`, confidence
      0.0) already trips the higher-precedence `spec-basis` hard rule
      first (`decide-router`'s `:hard?` check outranks `:escalate?`) --
      so low-confidence escalation can never be observed in isolation
      via this advisor.

  Usage: `clojure -M:render-html [out-file]` (default
  `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [manufacturing-labour.store :as store]
            [manufacturing-labour.advisor :as advisor]
            [manufacturing-labour.actor :as actor]))

(defn- seed-store
  "Real fixture value verbatim from `actor_test.cljc`'s
  `registered-store` (`prac-001`), plus a second practitioner and a
  worksite registered here via the real store API (disclosed, not
  pre-existing fixtures)."
  []
  (-> (store/create-store)
      (store/register-practitioner! "prac-001" {:name "J. Rivera" :status :active})
      (store/register-practitioner! "prac-002" {:name "Morgan Ito" :status :active})
      (store/register-worksite! "site-1" {:name "Riverside Assembly Plant" :region "Ohio Valley"})))

(def op-specs
  "The eight-request demo scenario. Each entry is
  `[label request-map approve?]`. `approve?` is only consulted when the
  request escalates (`:awaiting-approval`): `true` resolves it with a
  real `approve!` call, `false` deliberately leaves it pending (to show
  that terminal state itself), `nil` means the request is not expected
  to escalate at all."
  [["Log valid work assignment"
    {:type :log-work-assignment :practitioner-id "prac-001"
     :task-type :material-handling :hours 8 :location "Line 3" :date "2026-07-19"}
    nil]
   ["Acknowledge pre-shift safety briefing"
    {:type :acknowledge-safety-briefing :practitioner-id "prac-001"
     :briefing-id "pre-shift-ppe-042"
     :ppe-checklist [:hi-vis-vest :steel-toe-boots :hearing-protection]}
    nil]
   ["Coordinate task handoff to next shift"
    {:type :coordinate-task-handoff :practitioner-id "prac-001"
     :from-task "line-3-material-staging" :to-practitioner "prac-002"
     :notes "staging complete, 40 pallets remaining"}
    nil]
   ["Log work assignment -- hours implausible (20h, exceeds max-shift-hours 12)"
    {:type :log-work-assignment :practitioner-id "prac-002"
     :task-type :assembly-support :hours 20 :location "Line 3"}
    true]
   ["Log work assignment -- hours implausible (0h, non-positive)"
    {:type :log-work-assignment :practitioner-id "prac-001"
     :task-type :line-side-cleanup :hours 0 :location "Line 3"}
    false]
   ["Flag a safety concern"
    {:type :flag-safety-concern :practitioner-id "prac-002"
     :hazard-type :unguarded-conveyor :description "guard panel missing on conveyor B"
     :location "Line 3"}
    true]
   ["Unsupported request type -- not in the actor's closed proposal vocabulary"
    {:type :dispatch-warehouse-robot :practitioner-id "prac-001"}
    nil]
   ["Unregistered practitioner attempts a work-assignment log"
    {:type :log-work-assignment :practitioner-id "prac-999"
     :task-type :material-handling :hours 8}
    nil]])

(defn- run-op!
  "Run one request through a graph freshly built against
  `store-before` (see namespace docstring on why the graph is rebuilt
  per call). Escalated requests are either resolved with a real
  `approve!` call or deliberately left pending, per `approve?`. Returns
  a map describing the real outcome plus the store to carry forward."
  [store-before label request approve?]
  (let [graph (actor/build-graph (advisor/mock-advisor) store-before)
        result (actor/run-request! graph request {} store-before)
        phase (:phase result)]
    (cond
      (= :complete phase)
      {:label label :request request :outcome :auto-committed
       :store (:store result) :record (last (store/records (:store result)))}

      (and (= :awaiting-approval phase) approve?)
      (let [approved (actor/approve! result {:approved-by "site-supervisor-1"} store-before)]
        {:label label :request request :outcome :approved-and-committed
         :store (:store approved) :record (last (store/records (:store approved)))})

      (= :awaiting-approval phase)
      {:label label :request request :outcome :pending-approval
       :store store-before :verdict (:decision result)}

      :else
      {:label label :request request :outcome :hard-hold
       :store store-before :verdict (:decision result)
       :rule (-> result :decision :violations first :rule)})))

(defn run-demo!
  "Drive `op-specs` through the real graph, rebuilding it before each
  request against the latest store so the ledger accumulates. Returns
  `{:store <final-store> :runs [<run-result> ...]}`."
  []
  (loop [store (seed-store) specs op-specs acc []]
    (if (empty? specs)
      {:store store :runs acc}
      (let [[label request approve?] (first specs)
            run (run-op! store label request approve?)]
        (recur (:store run) (rest specs) (conj acc run))))))

(defn- esc
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(def governor-rules
  "Static description of the Governor's own contract, straight from
  `manufacturing-labour.governor`'s docstring -- not re-derived,
  quoted."
  [{:rule "no-practitioner" :kind :hard
    :desc "The request's practitioner must be registered."
    :reachable? true}
   {:rule "no-actuation" :kind :hard
    :desc "Proposal :effect must be :propose."
    :reachable? false}
   {:rule "spec-basis" :kind :hard
    :desc "Proposal :op must be a member of the actor's closed known-ops vocabulary."
    :reachable? true}
   {:rule "scope-boundary" :kind :hard
    :desc "Operating manufacturing machinery/equipment directly, payroll/wage/employment-classification determinations, and overriding a safety-briefing requirement are permanently forbidden."
    :reachable? false}
   {:rule "flag-safety-concern" :kind :escalate
    :desc "Any safety-concern flag always escalates, regardless of confidence."
    :reachable? true}
   {:rule "confidence-floor" :kind :escalate
    :desc "Escalates when proposal confidence is below the 0.6 floor."
    :reachable? false}
   {:rule "shift-hours-implausible" :kind :escalate
    :desc "A log-work-assignment's :hours must be positive and no greater than max-shift-hours (12); otherwise it escalates for human review."
    :reachable? true}])

(defn- outcome-class
  [outcome]
  (case outcome
    :auto-committed "ok"
    :approved-and-committed "warn"
    :pending-approval "critical"
    :hard-hold "err"
    "muted"))

(defn- outcome-label
  [outcome]
  (case outcome
    :auto-committed "auto-committed"
    :approved-and-committed "escalated -> approved -> committed"
    :pending-approval "escalated -> AWAITING APPROVAL (deliberately left unresolved in this scenario)"
    :hard-hold "HELD (hard violation)"
    (name outcome)))

(defn- triggered-hard-rules
  [runs]
  (into #{}
        (comp (filter #(= :hard-hold (:outcome %)))
              (map :rule))
        runs))

(defn- flag-safety-concern-triggered?
  [runs]
  (some #(= :flag-safety-concern (:type (:request %))) runs))

(defn- shift-hours-triggered?
  [runs]
  (some #(and (= :log-work-assignment (:type (:request %)))
              (contains? #{:approved-and-committed :pending-approval} (:outcome %)))
        runs))

(defn- rule-hit?
  [runs {:keys [rule kind]}]
  (case kind
    :hard (contains? (triggered-hard-rules runs) (keyword rule))
    :escalate (case rule
                "flag-safety-concern" (flag-safety-concern-triggered? runs)
                "shift-hours-implausible" (shift-hours-triggered? runs)
                false)
    false))

(defn render
  [{:keys [store runs]}]
  (let [final-ledger (store/records store)]
    (str/join
     "\n"
     (concat
      ["<html><head><meta charset=\"utf-8\">"
       "<title>Manufacturing Labour Operator Console (ISCO-08 9329)</title>"
       "<style>"
   (jp-go-dds.skin/dds+skin)
   "</style></head><body>"
       "<h1>Manufacturing Labour Operator Console</h1>"
       "<p class=\"sub\">ISCO-08 9329 &middot; Manufacturing Labourers Not Elsewhere Classified &middot; generated by <code>manufacturing-labour.render-html</code> from the real, compiled langgraph StateGraph -- no invented data.</p>"
       "<p class=\"sub\"><strong>Note:</strong> this demo required a root-cause fix to <code>commit-node</code>/<code>approve!</code> in <code>manufacturing-labour.actor</code> first -- see this namespace's docstring for the full bug/fix detail. Before the fix the audit ledger below would always read 0 records regardless of how many requests ran.</p>"

       "<h2>Registered entities (real store state, pre-scenario)</h2>"
       "<table><thead><tr><th>Kind</th><th>ID</th><th>Data</th><th>Source</th></tr></thead><tbody>"
       (str "<tr><td>practitioner</td><td>prac-001</td><td>" (esc {:name "J. Rivera" :status :active}) "</td><td class=\"muted\">real fixture (actor_test.cljc registered-store)</td></tr>")
       (str "<tr><td>practitioner</td><td>prac-002</td><td>" (esc {:name "Morgan Ito" :status :active}) "</td><td class=\"muted\">added here via register-practitioner!</td></tr>")
       (str "<tr><td>worksite</td><td>site-1</td><td>" (esc {:name "Riverside Assembly Plant" :region "Ohio Valley"}) "</td><td class=\"muted\">added here via register-worksite! (entity richness only -- governor/check never reads worksite records)</td></tr>")
       "</tbody></table>"

       "<h2>Governor action gate (manufacturing-labour.governor/check contract)</h2>"
       "<table><thead><tr><th>Rule</th><th>Kind</th><th>Description</th><th>Triggered in this run?</th></tr></thead><tbody>"]
      (for [{:keys [rule kind desc reachable?] :as r} governor-rules]
        (str "<tr><td><code>" (esc rule) "</code></td>"
             "<td>" (name kind) "</td>"
             "<td>" (esc desc) "</td>"
             "<td>"
             (cond
               (rule-hit? runs r) "<span class=\"ok\">yes</span>"
               (not reachable?) "<span class=\"muted\">structurally unreachable via mock-advisor (see namespace docstring)</span>"
               :else "<span class=\"muted\">no</span>")
             "</td></tr>"))
      ["</tbody></table>"

       "<h2>Audit trail (real outcome of each request, run in order, graph rebuilt each call against the latest store)</h2>"
       "<table><thead><tr><th>#</th><th>Scenario step</th><th>Op</th><th>Outcome</th><th>Detail</th></tr></thead><tbody>"]
      (map-indexed
       (fn [i {:keys [label request outcome record verdict rule]}]
         (str "<tr><td>" (inc i) "</td>"
              "<td>" (esc label) "</td>"
              "<td><code>" (esc (:type request)) "</code></td>"
              "<td class=\"" (outcome-class outcome) "\">" (esc (outcome-label outcome)) "</td>"
              "<td>"
              (cond
                record (esc (dissoc record :timestamp))
                rule (str "rule: <code>" (esc rule) "</code>")
                :else "")
              "</td></tr>"))
       runs)
      ["</tbody></table>"

       "<h2>Final audit ledger</h2>"
       (str "<p>" (count final-ledger) " record(s) committed to the store's own append-only ledger across this scenario (held/rejected/pending requests write nothing).</p>")

       "</body></html>"]))))

(defn -main
  [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        html (render result)]
    (spit out html)
    (println "wrote" out)))
