# Stage 1 — WHY: The problem OpenTelemetry solves

> **Where you are:** Stage 1 of 4. The parent guide's [01-why](../../01-concepts/01-why.md) explained why *observability* exists; this file explains why a *standard for producing telemetry* had to exist on top of that.
> **What you'll know after this file:** the three pains — lock-in, the N×M matrix, and siloed signals — that make OTel's design inevitable.

## The world before OTel

By ~2016 the observability *backends* were maturing (Prometheus, Jaeger, commercial APMs), but the *production* side of telemetry was a mess:

**Pain 1 — Instrumentation lock-in.** Instrumentation is the most invasive dependency a vendor can have: it's woven through your codebase as thousands of API calls (`statsd.increment(...)`, `newrelic.startSegment(...)`, Zipkin's Brave spans...). Switching vendors meant re-instrumenting every service. Practically nobody did, so vendors could price accordingly. Telemetry data was the hostage.

**Pain 2 — The N×M matrix.** Every library author faced an impossible choice: N telemetry systems × M libraries. Should the Kafka client emit Zipkin spans? Jaeger spans? OpenCensus stats? Usually the answer was "none of the above," so the most valuable instrumentation points in the ecosystem — HTTP clients, DB drivers, message queues — stayed dark, and every company re-instrumented the same libraries privately.

**Pain 3 — Siloed signals.** Metrics, logs, and traces grew up as three separate ecosystems (StatsD/Prometheus, log4j/syslog, Zipkin/Dapper-descendants) with three separate in-process contexts. Nothing at the *production* layer stamped a shared identity onto all three — so correlation ("show me the logs for *this* slow trace") required custom glue in every company, or didn't exist.

## Why existing solutions weren't enough

| Predecessor | What it got right | Fatal limitation |
|---|---|---|
| **Vendor SDKs / agents** (New Relic, AppD...) | Deep, zero-effort auto-instrumentation | Proprietary end to end — pain 1 in its purest form |
| **OpenTracing** (2016) | Vendor-neutral *tracing API* | API only — no SDK, no wire format, and traces only. Every vendor still shipped its own incompatible implementation |
| **OpenCensus** (Google, 2017) | API *and* SDK, traces *and* metrics | Competing standard — library authors now had to pick a side, which re-created pain 2 one level up |
| **Prometheus client libs** | De-facto metrics standard | Metrics only, pull-model-specific, no context propagation |

The OpenTracing/OpenCensus split was the proximate trigger: two vendor-neutral standards is zero standards. In 2019 they merged into **OpenTelemetry** — a CNCF project that is, by design, the only game in town (both predecessors are officially frozen and archived).

## Constraints that shaped the design

These four constraints explain almost every design decision you'll meet in Stage 3:

1. **The API must be free to depend on.** A library author must be able to instrument against OTel with *zero* runtime cost when no SDK is present → the API/SDK split, with a no-op default. (Solves pain 2.)
2. **Vendor choice must be a config change, not a code change.** → exporters are pluggable, and one standard protocol (OTLP) plus a routing tier (the Collector) sits between apps and backends. (Solves pain 1.)
3. **All signals share one context.** Traces, metrics, and logs must be producible from the same in-process context so trace_id lands on all of them. (Solves pain 3.)
4. **Telemetry must never take down the app.** Everything is async, buffered, and drop-on-overflow; instrumentation failures must be invisible to business logic.

**Quality bar check:** if OTel vanished tomorrow, you'd have to reinvent: a no-op-capable facade API, a shared context carrying trace identity, a neutral wire protocol, and a routing middle tier. That is exactly the parts list of Stage 3.

➡ **Next:** [02-what.md](02-what.md)
