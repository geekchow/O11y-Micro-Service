# Deep dives — one key player at a time

> **Where you are:** stage 3 of the [learning path](../../README.md). Stages [1 (concepts)](../01-concepts/00-overview.md) and [2 (example)](../02-example/00-overview.md) gave every component a role and a face; here each one gets opened up on its own — same *Why → What → How → Walkthrough* format, one directory per player.

The order below follows the telemetry itself: first how it's **produced and piped**, then how each signal is **stored and queried**, then how it's **consumed**.

| # | Deep dive | Status | The depth questions it answers |
|---|---|---|---|
| 1 | [otel/](otel/00-overview.md) — OpenTelemetry | ✅ done | How is a signal born (API/SDK)? How does context cross threads and services? How does the Collector pipeline work? Head vs tail sampling? |
| 2 | prometheus/ | 📋 planned | How does a TSDB store series cheaply? Why pull + service discovery? How does PromQL evaluate? Cardinality — why it kills, how to control it? How do recording/alert rules run? |
| 3 | tempo/ (tracing backends) | 📋 planned | How do you store millions of traces with object storage + a tiny index? How does TraceQL search work? What did Tempo trade away vs Jaeger/Elastic-style indexing? |
| 4 | loki/ (log backends) | 📋 planned | Why index only labels, not content? Streams, chunks, structured metadata? How does LogQL grep at scale? Loki vs Splunk/Elasticsearch economics? |
| 5 | grafana/ | 📋 planned | How do datasource plugins, dashboards-as-JSON, and provisioning work? How are the trace/log/metric pivots implemented? Unified alerting internals? |
| 6 | alerting/ | 📋 planned | Alert rule evaluation, `for`-pending states, Alertmanager's dedup/group/route/silence model, and paging philosophy (symptom vs cause alerts, SLO burn rates) |

Each planned pack becomes real the same way the OTel one did: read the official docs' concepts section, map problem → concepts → components → coordination, and end with a walkthrough you can run against [the example stack](../../stack/README.md) — which already contains every one of these components, live.

⬅ [Back to the journey map](../../README.md)
