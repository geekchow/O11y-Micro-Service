# Next steps — exercises and further reading

> **Where you are:** end of the guide. The mental model is built; now make it durable by using it.

## Exercises (in increasing depth)

1. **Whiteboard test (10 min).** Without looking, redraw the Layer 1–4 diagram from [02-what.md](02-what.md) and place all five tools. Justify each box by naming the Stage-1 pain it addresses.
2. **Run the composable stack locally (2–3 h).** Docker-compose: an OTel-instrumented demo app (the official `opentelemetry-demo` is ideal), OTel Collector, Prometheus, Grafana, and Tempo. Reproduce the metric → exemplar → trace pivot from the walkthrough.
3. **Add the log leg (1–2 h).** Route the demo's logs through the Collector to a Splunk free-trial (or Loki as a stand-in) and verify `trace_id` appears in log lines; build the trace→log pivot in Grafana.
4. **Break it on purpose.** Throttle the demo's DB (e.g., `tc` latency injection or shrink a pool) and run the full incident funnel yourself: alert → dashboard → trace → log. Time your MTTR.
5. **Write one good alert.** Convert a raw threshold into a *burn-rate SLO alert* (Google SRE Workbook, ch. 5) and observe how much noise disappears.
6. **Compare philosophies.** If you have access to AppDynamics (or Splunk Observability Cloud trial), instrument the same demo and compare: time-to-first-insight, flexibility, and what the auto-baselining catches that your hand-written rules missed.

## Further reading — source-of-truth order

| Topic | Where |
|---|---|
| Observability vs monitoring, the "arbitrary questions" framing | *Observability Engineering* (Majors, Fong-Jones, Miranda — O'Reilly) |
| OTel concepts: signals, context, Collector, sampling | opentelemetry.io/docs — "Concepts" section, then the Collector docs |
| Prometheus data model, PromQL, why pull | prometheus.io/docs — "Concepts" + Brian Brazil's *Prometheus: Up & Running* |
| Alerting philosophy (pages vs tickets, burn rates) | Google SRE Book ch. 6 "Monitoring Distributed Systems"; SRE Workbook ch. 5 |
| Splunk SPL and log-index architecture | Splunk Docs — "Search Manual"; *Exploring Splunk* (free e-book) |
| Grafana correlations: exemplars, trace-to-logs | grafana.com/docs — "Explore" and "Correlations" |
| AppDynamics model: Business Transactions, baselines, EUM | docs.appdynamics.com — "APM Overview" |
| The demo app used in exercises | github.com/open-telemetry/opentelemetry-demo |

## Source-code entry points (if you want to go deep)

- **OTel Collector:** `service/` (pipeline assembly) and `processor/tailsamplingprocessor/` — read how sampling policies compose.
- **Prometheus:** `tsdb/` (the storage engine) and `rules/` (alert-rule evaluation loop).
- **Grafana:** `pkg/tsdb/` datasource plugins — see how one UI speaks PromQL, SPL, and TraceQL.

⬅ Back to [00-overview.md](00-overview.md)
