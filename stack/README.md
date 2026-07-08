# O11y Shop — the walkthrough, deployable

This stack is [docs/04-walkthrough.md](../docs/04-walkthrough.md) made real with open-source parts
(Loki stands in for Splunk; AppDynamics/synthetics are out of scope):

```
loadgen ─→ gateway ─→ auth
              │  └──→ cart ─→ inventory
              └─────→ payment ─→ Postgres (HikariCP — the incident lives here)

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
docker compose ps                # everything Up; loadgen starts automatically
```

| UI | URL |
|---|---|
| Grafana (anonymous admin) | http://localhost:3000 → dashboard **Checkout — RED** |
| Prometheus (alerts under /alerts) | http://localhost:9090 |
| Gateway API | `curl -X POST localhost:8080/checkout -H 'Content-Type: application/json' -d '{"cartId":"1","amountCents":42,"tenant":"acme"}'` |

Wait ~2 minutes for first metrics (15 s export + 15 s flush intervals). Healthy state: all services ~25 req/s combined, p99 well under 1 s, a steady trickle of *declined* traces (amounts ending in 7) — those are kept by the tail sampler's error policy, so Tempo is never empty.

## 2. Verify the pivots (the point of the whole stack)

1. **Metric → trace:** on the p99 panel, enable exemplars are shown as dots; click one → *Query with Tempo* → the exact trace opens.
2. **Trace → logs:** in the trace waterfall, open the `payment` server span → **Logs for this span** → Loki shows that request's log lines (matched by `trace_id` structured metadata).
3. **Log → trace:** in any payment WARN line, expand details → `trace_id` field has a **View trace** link back to Tempo.

## 3. Reproduce the incident (docs/04-walkthrough.md, T+0 → T+22)

```bash
# T+0 — the bad deploy: pool halved 20 → 10
POOL_SIZE=10 docker compose up -d payment
```

> ⚠️ **Gotcha:** any later `docker compose up ...` *without* `POOL_SIZE=10` in the
> shell will quietly recreate payment back at pool 20 (compose reconciles
> dependencies against `.env`). Verify the active value with:
> `docker inspect stack-payment-1 --format '{{.Config.Env}}' | tr ' ' '\n' | grep HIKARI`

What you should observe over the next ~5 minutes, in the doc's order:

| Walkthrough step | Where to look here |
|---|---|
| T+4 detection | p99 panel: `payment` (and `gateway`) climb past 2 s |
| T+7 alert | Prometheus /alerts: **CheckoutP99Slow** pending → firing |
| T+9 localize | RED dashboard: only `payment` spikes; auth/cart/inventory flat |
| T+11 isolate | click an exemplar on the spike → trace waterfall: time sits in `payment /charge`, as a gap **inside `charge-db`, before the `INSERT shop.payments` span** (that gap *is* the Hikari acquire wait — e.g. a 3.2 s checkout showing 2.8 s of gap + 0.37 s of INSERT); saturated requests fail after the 5 s connection-timeout |
| T+13 explain | logs panel / "logs for this span": `HikariPool-1 - Connection is not available, request timed out after 5000ms` |
| T+22 resolve | `POOL_SIZE=20 docker compose up -d payment` → p99 falls, alert resolves, error rate back to the ~10% declines baseline |

Why it works: loadgen is **open-loop** (40 req/s regardless of latency, ~36 req/s of which reach the DB — declines don't). Each captured charge holds a pooled connection ~0.35 s, so capacity is `pool / 0.35` ≈ 57 req/s at pool 20 (healthy, ~13 connections busy) but ≈ 28 req/s at pool 10 — demand exceeds capacity, the queue grows, waits hit the 5 s Hikari timeout, and you get exactly the doc's mix of slow traces and connection errors.

## 4. Knobs

| Knob | Where | Default | Effect |
|---|---|---|---|
| `POOL_SIZE` | `.env` / env override | 20 | Hikari max pool in payment — the incident switch |
| `LOAD_RPS` | `.env` | 25 | open-loop request rate |
| `CHARGE_DB_SECONDS` | payment env | 0.35 | how long each charge holds a connection |
| tail policies | `otel/collector-gateway.yaml` | errors + >2 s + 1% | raise `sampling_percentage` to 100 to see *every* trace while exploring |
| head sampler | compose `OTEL_TRACES_SAMPLER` | `parentbased_always_on` | switch to `parentbased_traceidratio` + `OTEL_TRACES_SAMPLER_ARG` to watch head+tail compose |

## 5. Scaling out (what this demo deliberately simplifies)

- **One gateway Collector** — with several, tail sampling needs the two-tier `loadbalancing`-exporter architecture from [docs/otel-deep-dive/03d-sampling.md](../docs/otel-deep-dive/03d-sampling.md).
- **No Alertmanager** — the alert fires in Prometheus but pages no one; add Alertmanager + a `route` to complete the doc's T+7 step.
- **`user: root` on Tempo/Loki** — local-volume convenience, not production practice.
- **No synthetics/RUM** — the doc's outside-in signals need a separate product (e.g. Grafana Synthetic Monitoring).

## Teardown

```bash
docker compose down -v   # -v also deletes stored telemetry
```
