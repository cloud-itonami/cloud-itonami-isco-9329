(ns manufacturing-labour.governor
  "ManufacturingLabourGovernor — the independent safety/traceability layer
  for the ISCO-08 9329 (Manufacturing Labourers Not Elsewhere Classified)
  independent manufacturing support labour practice actor. Wired as its own
  `:govern` node in `manufacturing-labour.actor`'s StateGraph, downstream of
  `:advise` — the Advisor has no notion of practitioner provenance or
  safety risk, so this MUST be a separate system able to reject a proposal
  (itonami actor pattern, per ADR-2607011000; this repo per
  ADR-2607999999).

  `check` is a pure function of (request, context, proposal, store) ->
  verdict; it never mutates the store. The StateGraph's `:decide` node
  routes on the verdict:
    :hard? true                → :hold  (irreversible, no write)
    :escalate? true            → :request-approval (interrupt-before)
    otherwise                  → :commit

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. practitioner provenance  — the request's practitioner must be
                                   registered.
    2. no-actuation              — proposal :effect must be :propose.
    3. spec-basis                — proposal :op must be a member of the
                                    actor's closed known-ops vocabulary
                                    (catches :unknown / malformed proposals).
    4. scope-boundary             — proposal :op must not be a member of
                                    `forbidden-ops`: operating manufacturing
                                    machinery/equipment directly, making any
                                    payroll/wage/employment-classification
                                    determination, or overriding a
                                    safety-briefing requirement are
                                    PERMANENTLY excluded from this actor's
                                    design vocabulary — not gated by risk
                                    level or confidence, no escalation path.

  ESCALATION invariants (:escalate? true, ALWAYS human sign-off, no
  exceptions):
    5. always-escalate safety concern — any :flag-safety-concern proposal
                                         escalates regardless of confidence.
    6. confidence-floor                — proposal confidence below
                                         `confidence-floor`.
    7. shift-hours ground truth        — a :log-work-assignment proposal's
                                         :hours must be a positive number no
                                         greater than `max-shift-hours`; an
                                         implausible value (non-positive, or
                                         beyond a real shift's plausible
                                         length) escalates for human review
                                         rather than being silently trusted."
  (:require [manufacturing-labour.store :as store]))

(def confidence-floor 0.6)

; A single shift longer than this is outside the plausible range for a
; manufacturing-support labour assignment (regular shift + reasonable
; overtime); such an entry needs human review rather than silent commit.
(def max-shift-hours 12)

; Known, closed proposal vocabulary this actor is designed to make.
(def ^:private known-ops #{:log-work-assignment
                            :acknowledge-safety-briefing
                            :coordinate-task-handoff
                            :flag-safety-concern})

; Permanently forbidden operation categories (out of scope entirely, never
; reachable via any advisor vocabulary expansion or confidence/approval
; override). This actor never operates manufacturing machinery/equipment
; directly ("policy, not control"), and never makes a payroll/wage/
; employment-classification determination.
(def ^:private forbidden-ops #{:operate-machinery
                                :operate-equipment
                                :classify-employment
                                :determine-wage-classification
                                :set-payroll-rate
                                :override-safety-briefing})

(defn- hard-violations [proposal practitioner-record]
  (cond-> []
    (nil? practitioner-record)
    (conj {:rule :no-practitioner :detail "practitioner not registered"})

    (not= :propose (:effect proposal))
    (conj {:rule :no-actuation :detail "effect must be :propose only (no direct store writes, no equipment control)"})

    (not (contains? known-ops (:op proposal)))
    (conj {:rule :spec-basis :detail "operation is not part of this actor's closed proposal vocabulary"})

    (contains? forbidden-ops (:op proposal))
    (conj {:rule :scope-boundary
           :detail "operation permanently forbidden: direct manufacturing machinery/equipment operation, payroll/wage/employment-classification determinations, and overriding a safety-briefing requirement are excluded from this actor's design vocabulary"})))

(defn- always-escalate-op? [op]
  ; Safety concerns escalate unconditionally, no exceptions, regardless of
  ; how confident the advisor is that it parsed the hazard correctly.
  (= :flag-safety-concern op))

(defn- shift-hours-implausible? [proposal]
  (and (= :log-work-assignment (:op proposal))
       (let [hours (:hours proposal)]
         (or (not (number? hours))
             (<= hours 0)
             (> hours max-shift-hours)))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a `store`
  implementing `manufacturing-labour.store/Store`. Returns
  `{:ok? bool :violations [...] :confidence n :hard? bool :escalate? bool}`."
  [request context proposal store]
  (let [practitioner-record (store/practitioner store (:practitioner-id request))
        hard (hard-violations proposal practitioner-record)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        safety-escalate? (always-escalate-op? (:op proposal))
        hours-escalate? (shift-hours-implausible? proposal)]
    {:ok? (and (not hard?) (not low?) (not safety-escalate?) (not hours-escalate?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? safety-escalate? hours-escalate?))}))
