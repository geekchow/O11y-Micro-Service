# Stage 1 — WHY: why 13 containers, and not 3

> **Where you are:** Stage 1 of 4.
> **What you'll know after this file:** the one-to-one mapping between the concepts you've already learned and the containers actually running — and why none of them can be merged away without losing something.

The stack looks heavy for a demo — a lone Spring Boot app with a Prometheus sidecar would be "observable" too. Every extra container exists because one of the guide's concepts *demands its own process to be visible*. That's the pack's organizing idea: **the stack is the concept list, instantiated.**

| Concept (where it was introduced) | Container(s) here | Why it can't be merged away |
|---|---|---|
| Microservices make requests cross processes ([parent 01](../01-why.md)) | `gateway, auth, cart, inventory, payment` | With one service there is nothing to propagate context *across* — the whole trace story would be invisible |
| Instrumentation, API/SDK split ([deep dive 03](../otel-deep-dive/03-how.md)) | the **javaagent inside** each service (not a container, a jar) | Auto-instrumentation must live in-process; merging it into a sidecar is impossible by design |
| Telemetry pipeline, agent tier ([deep dive 03c](../otel-deep-dive/03c-collector.md)) | `otel-agent` | Apps must offload fast to something node-local; skip it and every service holds vendor/routing config |
| Pipeline, gateway/policy tier ([deep dive 03c](../otel-deep-dive/03c-collector.md)) | `otel-gateway` | Tail sampling and backend routing are *fleet-wide policy* — one place to change, one place to watch |
| Sampling ([deep dive 03d](../otel-deep-dive/03d-sampling.md)) | lives inside `otel-gateway` (`tail_sampling`) | Needs whole traces, so it must sit *after* all services' spans converge |
| Signal-specialized backends ([parent 03](../03-how.md), concept 5) | `tempo` + `prometheus` + `loki` | The whole point of concept 5: one store per signal shape. Merging them is the anti-pattern the parent guide warns about |
| Consumption / correlation surface ([parent 03](../03-how.md), concept 6) | `grafana` | Stores nothing, queries all three — proof that the UI can be swapped without touching data |
| Someone must generate traffic | `loadgen` | Observability of an idle system shows nothing; loadgen is the stand-in for users |
| The shop needs real work to do | `postgres` | Gives payment a genuine bottleneck resource (the connection pool) so latency has a *cause*, not a `sleep()` |

Two containers you might expect but won't find, and why:

- **No Alertmanager** — the alert *rule* lives in Prometheus (detection); only the delivery plumbing is omitted. Adding it changes who gets paged, not how the system works.
- **No synthetics/RUM** — outside-in signals ([parent 03](../03-how.md), concept 7) need a probe *outside* the compose network; a container in the same network would be lying about what it measures.

**Quality bar check:** for any container you can now name the concept that demands it — and for any concept in the parent guide, point at where it runs.

➡ **Next:** [02-what.md](02-what.md)
