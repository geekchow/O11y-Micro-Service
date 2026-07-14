# Observability for Microservices — A 10-Part Series

A self-contained blog series on how modern observability actually works: why it exists, how OpenTelemetry, Prometheus, Grafana, and friends divide the work, a real docker-compose stack you can run, and a deep dive into OpenTelemetry's internals. Each post is standalone and includes runnable code — read them in order, or jump straight to whatever you need.

📦 GitHub: [https://github.com/geekchow/O11y-Micro-Service](https://github.com/geekchow/O11y-Micro-Service)

## Part I — Concepts

1. **[Why Monitoring Broke When We Split the Monolith](01-why-monitoring-broke.md)** — why microservices killed the old "check what you know" model, and the precise definition of observability vs. monitoring.
2. **[How the Pieces Fit Together](02-how-it-fits-together.md)** — the seven core concepts, and a full 25-minute incident (connection-pool exhaustion) traced through synthetics, metrics, traces, and logs.

## Part II — A Runnable Example

3. **[Building a Runnable Observability Stack](03-building-a-runnable-stack.md)** — a 5-service Spring Boot shop with the OTel Java agent, two Collector tiers, and Tempo/Prometheus/Loki/Grafana, wired up in docker-compose.
4. **[Touring the Stack: One Checkout, Traced Through Every Container](04-touring-the-stack.md)** — hands-on: send one request, then find its footprint in Loki, Tempo, Prometheus, and Grafana with real `curl` commands.
5. **[Reproducing a Production Incident on Purpose](05-reproducing-an-incident.md)** — flip one environment variable to reproduce Part 2's incident live, plus five exercises that break each component deliberately.

## Part III — OpenTelemetry, Deep Dive

6. **[Why OpenTelemetry Had to Exist](06-otel-why-what.md)** — instrumentation lock-in, the N×M matrix, and siloed signals — the three pains that forced a vendor-neutral standard into being.
7. **[Anatomy of a Signal: Traces, Metrics, Logs, and How They Stay Correlated](07-otel-signals-and-context.md)** — spans, instruments, the Logs Bridge, and the Context/Propagator mechanism that stitches every signal to one `trace_id`.
8. **[Inside the Collector: Pipelines, Deployment Patterns, and Failure Modes](08-otel-collector.md)** — receivers, processors, exporters, connectors, and how the pipeline degrades gracefully instead of falling over.
9. **[Sampling: Keeping the Interesting 1%](09-otel-sampling.md)** — head vs. tail sampling, why they compose, and the two-tier gateway architecture tail sampling forces on you.
10. **[One Checkout Request, Atom by Atom](10-otel-walkthrough.md)** — every concept from Parts 6–9, run once through a single real request, end to end.

---

This series accompanies a runnable repository containing the full stack, source code, and configs referenced throughout — see [`stack/`](../stack/) and [`docs/`](../docs/) for the underlying material this series is adapted from.
