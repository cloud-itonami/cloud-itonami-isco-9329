(ns manufacturing-labour.advisor
  "Manufacturing Labour Advisor — the proposal layer for the ISCO-08 9329
  independent manufacturing support labour practice actor. Proposes
  coordination operations grounded in what an independent manufacturing
  support labourer's own work day actually looks like: logging a completed
  work assignment, acknowledging a pre-shift PPE/safety briefing,
  coordinating a task handoff to another worker or shift, and flagging a
  safety concern. This advisor NEVER operates manufacturing machinery or
  equipment directly, and NEVER makes a payroll/wage/employment-classification
  determination — it only ever proposes a record for the governor to
  evaluate; it never commits records or makes governance decisions itself.")

(defprotocol Advisor
  "Advisor protocol for proposing manufacturing-labour coordination operations."
  (propose [advisor request context]
    "Propose a coordination operation from a request. Returns a proposal map
     with :op, :effect (always :propose), :confidence, and supporting data."))

(defn mock-advisor
  "Default deterministic advisor that proposes one of a closed set of
   coordination operations based on request type. Always returns a
   :propose effect. Unrecognized request types propose :unknown with
   confidence 0.0, which the governor's spec-basis check will always hold."
  []
  (reify Advisor
    (propose [this request context]
      (let [req-type (:type request)
            op (case req-type
                 :log-work-assignment
                 {:op :log-work-assignment
                  :confidence 0.9
                  :task-type (:task-type request)
                  :hours (:hours request)
                  :location (:location request)
                  :date (:date request)}

                 :acknowledge-safety-briefing
                 {:op :acknowledge-safety-briefing
                  :confidence 0.95
                  :briefing-id (:briefing-id request)
                  :ppe-checklist (:ppe-checklist request)}

                 :coordinate-task-handoff
                 {:op :coordinate-task-handoff
                  :confidence 0.8
                  :from-task (:from-task request)
                  :to-practitioner (:to-practitioner request)
                  :notes (:notes request)}

                 :flag-safety-concern
                 {:op :flag-safety-concern
                  :confidence 0.7
                  :hazard-type (:hazard-type request)
                  :description (:description request)
                  :location (:location request)}

                 {:op :unknown :confidence 0.0})]
        (assoc op :effect :propose)))))

(defn llm-advisor
  "Advisor backed by an LLM (ChatModel). Always returns :propose effect;
   LLM parse failures yield confidence 0.0 (forces escalation, never a
   fabricated confidence)."
  [chat-model]
  (reify Advisor
    (propose [this request context]
      ; Placeholder: a real implementation would call chat-model and parse
      ; its response to extract :op, :confidence, and supporting data.
      ; On any parse error, this must still return confidence 0.0.
      {:op :unknown :effect :propose :confidence 0.0})))
