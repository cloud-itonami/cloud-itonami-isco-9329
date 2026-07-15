# cloud-itonami-isco-9329

Open Occupation Blueprint for **ISCO-08 9329**: Manufacturing Labourers Not Elsewhere Classified.

This repository designs a forkable OSS business for an independent manufacturing support labour practice: a material-handling robot manages production-line support under a governor-gated actor, so the practice keeps its own site records instead of renting a closed labour-staffing SaaS.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot performs
the physical domain work**. Here a material-handling robot performs component staging, line-side replenishment and debris clearing under an actor that proposes
actions and an independent **Manufacturing Labour Governor** that gates them. The governor never
dispatches hardware itself; `:high`/`:safety-critical` actions (such as
work on an unmarked hazardous machine) require human sign-off.

A live sample of the operator console (robotics safety console, shared template) is rendered in [docs/samples/operator-console.html](docs/samples/operator-console.html) — pure-data HTML output of `kotoba.robotics.ui`.

## Core Contract

```text
production work order + safety plan + crew assignment
        |
        v
Labour Advisor -> Manufacturing Labour Governor -> dispatch crew/approve task, or human sign-off
        |
        v
robot actions (gated) + operating records + audit ledger
```

No automated advice can dispatch a robot action the governor refuses, suppress
an operating record, or disclose sensitive data without governor approval and
audit evidence.

## Capability layer

Resolves via [`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation)
(ISCO-08 `9329`). Required capabilities:

- :robotics
- :forms
- :audit-ledger

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
