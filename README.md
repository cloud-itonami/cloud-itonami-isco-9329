# cloud-itonami-isco-9329

Open Occupation Blueprint for **ISCO-08 9329**: Manufacturing Labourers Not Elsewhere Classified.

This repository designs a forkable OSS business for an **independent, self-employed manufacturing support labour practice** — a single practitioner doing general manual manufacturing-support work (material handling, basic assembly support, line-side cleanup; not a skilled trade with its own ISCO code). An LLM advisor helps the practitioner coordinate their own work-assignment logging, safety-briefing acknowledgments, task handoffs, and safety-incident reporting, sealed behind an independent governor, so the practitioner keeps their own auditable work records instead of relying on a closed labour-staffing SaaS.

## IMPORTANT: SCOPE BOUNDARIES

**This actor is EXPLICITLY NOT an equipment-control system, a payroll/employment-classification system, or a labour-dispatch/staffing-agency system.**

### What this actor DOES

- Coordination and record-keeping for the practitioner's own work, only:
  - Logging a completed work assignment (task type, hours, location, date)
  - Recording a personal-protective-equipment / safety-briefing acknowledgment before a shift
  - Coordinating a task handoff to another worker or the next shift
  - Flagging a safety concern for review (hazard, near-miss, unsafe condition)

### What this actor DOES NOT (hard boundaries, permanently out of scope)

These operations are **permanently forbidden** — they are not gated by risk level or approval hierarchy, they cannot be escalated for human override, and the actor's proposal vocabulary has no path to construct them. A closed allowlist plus an explicit denylist enforce this at the governance layer:

- **Operating manufacturing machinery or equipment directly** — this actor is "policy, not control": it never dispatches, drives, or actuates any physical machine. Any real hardware action stays with the practitioner and the equipment's own controls, not this actor.
- **Payroll, wage, or employment-classification determinations** — no classification of the practitioner as employee/contractor, no wage-rate setting, no payroll processing. Those are legal/financial determinations outside this actor's design vocabulary entirely.
- **Overriding a safety-briefing requirement** — a required pre-shift safety briefing can never be waived or bypassed by this actor.

The governor will **permanently `:hold`** any proposal that touches these categories — it is not a matter of confidence or approval chain.

### Always-escalate (human sign-off required, no exceptions)

- Any flagged safety concern **always** escalates to a human, regardless of the advisor's confidence.
- Logged hours outside a plausible single-shift bound escalate for human review rather than being silently trusted.
- Low advisor confidence escalates.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot performs
the physical domain work** — here, a material-handling robot may perform component
staging, line-side replenishment, or debris clearing. This actor itself never
dispatches that robot or any other equipment; it only proposes coordination
records (work-assignment logs, safety-briefing acknowledgments, task handoffs,
safety-concern flags) for the independent **Manufacturing Labour Governor** to
gate. The governor never dispatches hardware itself, and safety-critical
proposals always require human sign-off.

## Core Contract

```text
practitioner's work-assignment / safety-briefing / handoff / safety-concern request
        |
        v
Manufacturing Labour Advisor -> Manufacturing Labour Governor -> commit record, or human sign-off, or permanent hold
        |
        v
coordination record (gated) + append-only audit ledger
```

No automated advice can commit a coordination record the governor refuses,
suppress a safety concern, operate equipment, or make a payroll/employment
determination.

## Capability layer

Resolves via [`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation)
(ISCO-08 `9329`). Required capabilities:

- :robotics
- :forms
- :audit-ledger

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## Reference implementation (`:maturity :implemented`)

Full itonami Actor pattern (per ADR-2607011000 / CLAUDE.md's Actors
section; this repo per ADR-2607999999): a real
[`kotoba-lang/langgraph`](https://github.com/kotoba-lang/langgraph)
`StateGraph`, with the Advisor and Governor as distinct graph nodes and
human-in-the-loop interrupt/resume via checkpointing.

```text
:intake -> :advise -> :govern -> :decide -+-> :commit            (:ok? true)
                                           +-> :request-approval   (:escalate? true, interrupt-before)
                                           +-> :hold               (:hard? true)
```

- `src/manufacturing_labour/store.cljk` — `Store` protocol + `MemStore`:
  registered practitioners/worksites, committed coordination records, an
  append-only audit ledger.
- `src/manufacturing_labour/advisor.cljk` — `Advisor` protocol; `mock-advisor`
  (deterministic, default) proposes one of the four coordination operations
  above from a request; `llm-advisor` wraps a `langchain.model/ChatModel` —
  either way the advisor only ever produces a `:propose`-effect proposal,
  never a committed record or an equipment action, and LLM parse failures
  always yield `confidence 0.0` (forces escalation, never fabricated
  confidence).
- `src/manufacturing_labour/governor.cljk` — `ManufacturingLabourGovernor/check`:
  a pure function, wired as its own `:govern` node. Hard invariants
  (unregistered practitioner, a proposal whose `:effect` isn't `:propose`, an
  operation outside the closed known-ops vocabulary, or any proposal touching
  direct machinery/equipment operation, payroll/wage/employment-classification
  determinations, or overriding a safety-briefing requirement) always route
  to `:hold`. Escalation invariants (a safety-concern flag — always,
  unconditionally — logged hours outside a plausible single-shift bound, or
  low advisor confidence) always route to `:request-approval` — an
  `interrupt-before` node that the graph checkpoints and only resumes on
  explicit human approval (`actor/approve!`).
- `src/manufacturing_labour/actor.cljk` — `build-graph`, `run-request!`,
  `approve!`: the `langgraph.graph/state-graph` wiring itself.

```bash
clojure -M:test
```

This is what backs this repo's `:maturity :implemented` entry in
[`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation).

## License

AGPL-3.0-or-later.
