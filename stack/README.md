# O11y Shop — a running observability stack you can tour

A complete, open-source observability system around a 5-service Spring Boot shop — built to show **how the components work together**: how spans, metrics, and logs are produced, piped, sampled, stored per signal, and stitched back together by one trace_id. The guided tour lives in **[docs/stack-tour/](../docs/stack-tour/00-overview.md)**; this README is the operations card.

```
loadgen ─→ gateway ─→ auth
              │  └──→ cart ─→ inventory
              └─────→ payment ─→ Postgres (HikariCP)

all services (OTel Java agent) ─OTLP→ otel-agent ─→ otel-gateway ─┬→ Tempo   (traces, tail-sampled)
                                                    (spanmetrics) ├→ Prometheus (metrics + exemplars)
                                                                  └→ Loki    (logs, trace_id attached)
                                                                       ↑ all three queried by Grafana
```

Pinned versions: OTel javaagent 2.29.0 · collector-contrib 0.156.0 · Tempo 2.8.1 · Loki 3.7.3 · Prometheus v3.13.0 · Grafana 12.0.2 · Spring Boot 3.5.6 / Java 21.

## 1. Start

```bash
cd stack
docker compose up -d --build     # first build ≈ 5–15 min (maven + image pulls)
docker compose ps                # everything Up; loadgen starts automatically at ~24 req/s
```

Give it ~2 minutes for first metrics (15 s export + flush intervals). Healthy state: p99 ~0.5 s, a ~10% stream of *declined* checkouts (amounts ending in 7) providing error traces, and Tempo holding all errors + ~25% of healthy traces.

## 2. Where to look — every window into the system

| Component | URL / command | What you see there |
|---|---|---|
| Grafana | http://localhost:3000 → **Checkout — RED** | the correlation surface: RED panels, exemplars, logs |
| Prometheus | http://localhost:9090 | raw PromQL, /alerts, /targets (who gets scraped) |
| Tempo API | http://localhost:3200 (`/api/search`, `/api/traces/<id>`) | trace store, queryable directly |
| Loki API | http://localhost:3100 (`/loki/api/v1/query_range`) | log store incl. trace_id structured metadata |
| Collector zpages | http://localhost:55679 (agent) · :55680 (gateway) `/debug/pipelinez` | live pipeline internals |
| Collector self-metrics | `curl localhost:8888/metrics` (agent) · `:8889` (gateway) | accepted vs sent counts — watch the tail sampler cull |
| Collector health | `curl localhost:13133` · `:13134` | liveness, as an orchestrator would see it |
| Shop API | `curl -X POST localhost:8080/checkout -H 'Content-Type: application/json' -d '{"cartId":"1","amountCents":42,"tenant":"acme"}'` | generate your own telemetry |

**The three pivots** (wired in [grafana/provisioning/datasources/datasources.yaml](grafana/provisioning/datasources/datasources.yaml)):
exemplar dot → trace in Tempo · span → "Logs for this span" in Loki · log line's `trace_id` → "View trace". How each is wired: [docs/stack-tour/03-how.md §3.4](../docs/stack-tour/03-how.md).

## 3. The guided tour

Follow **[docs/stack-tour/04-walkthrough.md](../docs/stack-tour/04-walkthrough.md)**: send one checkout and find its footprint in every component — trace_id from Loki metadata, full waterfall from Tempo's API, its increment in spanmetrics, the collectors' own counters moving, and the full pivot circle in Grafana. Exercises that stress each component (kill a backend, redact attributes, starve the tail sampler) are in [05-next-steps.md](../docs/stack-tour/05-next-steps.md).

## 4. Knobs

| Knob | Where | Default | Effect |
|---|---|---|---|
| `POOL_SIZE` | [.env](.env) / env override | 20 | Hikari max pool in payment — the incident switch (§5) |
| `LOAD_RPS` | [.env](.env) | 24 | open-loop request rate |
| `CHARGE_DB_SECONDS` | [.env](.env) | 0.6 | how long each charge holds a DB connection |
| tail policies | [otel/collector-gateway.yaml](otel/collector-gateway.yaml) | errors + >2 s + **25%** | 25% baseline is the exploration-friendly default; set 1% for the production-like ratio from [docs/04-walkthrough.md](../docs/04-walkthrough.md) |
| head sampler | compose `OTEL_TRACES_SAMPLER` | `parentbased_always_on` | switch to `parentbased_traceidratio` to watch head+tail compose |

## 5. Optional: the incident drill (diagnostics story)

The stack can also reproduce the [docs/04-walkthrough.md](../docs/04-walkthrough.md) incident — the deploy that halves payment's connection pool:

```bash
POOL_SIZE=10 docker compose up -d payment    # break   (T+0)
POOL_SIZE=20 docker compose up -d payment    # rollback (T+22)
```

Within ~3 minutes of breaking: p99 climbs past 2 s → **CheckoutP99Slow** fires in Prometheus /alerts → exemplar → trace waterfall shows the Hikari-acquire gap inside `charge-db` before the `INSERT` span → "logs for this span" shows the 5 s connection-timeout errors. Rollback recovers p99 and auto-resolves the alert in ~2 min.

Why the math works: loadgen is open-loop; ~22 req/s of charges × ~0.65 s connection-hold needs ~14 connections — fine at pool 20 (capacity ≈ 31 req/s), saturated at pool 10 (≈ 15 req/s), so queues grow until the 5 s Hikari timeout. If you change `LOAD_RPS` or `CHARGE_DB_SECONDS`, keep DB demand *between* the two pool capacities or the incident won't reproduce.

> ⚠️ **Gotcha:** any later `docker compose up ...` *without* `POOL_SIZE=10` in the
> shell quietly recreates payment back at pool 20 (compose reconciles dependencies
> against `.env`). Verify with:
> `docker inspect stack-payment-1 --format '{{.Config.Env}}' | tr ' ' '\n' | grep HIKARI`

## 6. What this demo deliberately simplifies

- **One gateway Collector** — several would need the two-tier `loadbalancing`-exporter architecture ([docs/otel-deep-dive/03d](../docs/otel-deep-dive/03d-sampling.md)) for tail sampling.
- **No Alertmanager** — the rule fires in Prometheus; delivery/dedup/routing is omitted.
- **`user: root` on Tempo/Loki** — local-volume convenience, not production practice.
- **No synthetics/RUM** — outside-in signals need a probe outside this network.

## Resource footprint

Everything is sized for a **2 GB** Docker VM: service JVMs run with `-Xmx160m`, the Collectors' `memory_limiter`s are set to 160/256 MiB, Tempo keeps only 2 h of traces, and the default load is 24 req/s. If containers still die with exit code 137 (OOM-kill) during long runs, give Docker Desktop more memory or lower `LOAD_RPS`.

## Teardown

```bash
docker compose down -v   # -v also deletes stored telemetry
```
