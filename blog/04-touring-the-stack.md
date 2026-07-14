# Touring the Stack: One Checkout, Traced Through Every Container

*Part 4 of a series on observability for microservices. [Part 3](03-building-a-runnable-stack.md) built the stack; this post drives it. Every command below is copy-pasteable against the [companion repo's `stack/`](../stack/) once it's running. [Series index](00-index.md).*

📦 GitHub: [https://github.com/geekchow/O11y-Micro-Service](https://github.com/geekchow/O11y-Micro-Service)

Reading a pipeline diagram only gets you so far. This post pushes one HTTP request through a live 13-container stack and finds its footprint in every component — trace, metrics, and logs — with real commands.

We'll deliberately trigger a **declined checkout**: any `amountCents` ending in `7` gets rejected by the payment service. Error traces are ideal tour subjects because the Collector's tail-sampling policy always keeps them — there's no chance our request gets sampled away before we can find it.

## Step 0 — send the request

```bash
curl -s -X POST localhost:8080/checkout -H 'Content-Type: application/json' \
  -d '{"cartId":"tour","amountCents":47,"tenant":"acme"}'
# → {"orderId":"<UUID>","status":"declined"}     amount ends in 7 ⇒ declined
```

Note the `orderId` in the response — it's our join key into the logs, from which we'll pull the `trace_id`.

## Step 1 — what the JVMs just did, invisibly

The five services already did their work without any code watching: Tomcat and RestClient auto-instrumentation created spans, the manual `checkout` span ran, and a WARN log line fired in both `payment` and `gateway`. There's nothing to see *inside* the services — by design, that work happens off to the side, buffered and shipped asynchronously so it never blocks the response the customer sees.

## Step 2 — Loki has the trace_id first

```bash
ORDER=<paste the orderId>
curl -s -G 'localhost:3100/loki/api/v1/query_range' \
  --data-urlencode "query={service_name=\"payment\"} |= \"$ORDER\"" \
  --data-urlencode 'limit=1' \
  | python3 -c "import json,sys; s=json.load(sys.stdin)['data']['result'][0]['stream']; print('trace_id:', s['trace_id'])"
```

The `trace_id` comes back as **structured metadata**, not as text inside the log line — the OTel Logback appender stamped it because the `WARN` happened inside the span's active Context. This is the mechanism, not a coincidence: `log.warn(...)` never mentions trace IDs anywhere in the application code.

## Step 3 — Tempo has the whole story

```bash
TID=<paste the trace_id>
curl -s "localhost:3200/api/traces/$TID" | python3 -c "
import json,sys
t=json.load(sys.stdin)
for b in t['batches']:
    svc=[a['value']['stringValue'] for a in b['resource']['attributes'] if a['key']=='service.name'][0]
    for ss in b.get('scopeSpans',[]):
        for s in ss['spans']: print(f\"{svc:10s} {s['name']}\")"
```

Expect spans from **four services** — `gateway` (including the manual `checkout` span), `auth`, `cart` → `inventory`, and `payment` — all under one `trace_id`. This is context propagation, made visible after the fact. This trace exists in Tempo because the Collector's `status_code: ERROR` policy matched it; a *healthy* sibling checkout only has a 25% chance of surviving the same tail-sampling filter in this demo config (1% in a production-like ratio).

## Step 4 — it's already a number in Prometheus

```bash
open 'http://localhost:9090/graph?g0.expr=sum%20by%20(service_name%2C%20status_code)%20(rate(traces_span_metrics_calls_total%7Bspan_kind%3D%22SPAN_KIND_SERVER%22%7D%5B2m%5D))'
```

Your checkout is one anonymous increment inside `traces_span_metrics_calls_total{status_code="STATUS_CODE_ERROR"}`, produced by the `spanmetrics` connector from the *same* spans you just saw in Tempo — but generated **before** tail sampling ran. Individual identity is gone; the rate is preserved. That's the metrics trade-off, mechanized in one connector.

## Step 5 — watch the pipeline count your spans

```bash
# agent tier accepted them…
curl -s localhost:8888/metrics | grep -E '^otelcol_receiver_accepted_spans'
# …gateway tier accepted them, and exported FEWER than it accepted:
curl -s localhost:8889/metrics | grep -E '^otelcol_(receiver_accepted|exporter_sent)_spans'
```

The gap between the gateway's accepted and sent span counts **is the tail sampler discarding healthy traces** — the only place in the whole stack where telemetry is deliberately thrown away. Watch it live at the zpages debug endpoint: http://localhost:55680/debug/pipelinez.

## Step 6 — close the loop in Grafana

Open http://localhost:3000 → **Checkout — RED** dashboard:

1. **Error-rate panel** — your decline shows up in the red series (the metric view).
2. **p99 panel → click an exemplar dot** — the trace opens directly (metric → trace pivot).
3. **In the waterfall, click the payment span → "Logs for this span"** — your WARN line appears (trace → log pivot).
4. **In the log line's details, click `trace_id` → "View trace"** — you're back at the trace (log → trace pivot — full circle).

None of those three pivots involve a plugin or a glue service. Each one is a single provisioning key in Grafana's datasource config:

| Click | Wiring key | What it does |
|---|---|---|
| exemplar dot → trace | `exemplarTraceIdDestinations` on the Prometheus datasource | reads the exemplar's `trace_id` label, opens it in Tempo |
| span → its logs | `tracesToLogsV2.query` on the Tempo datasource | templated Loki query: `{service_name=…} \| trace_id = <span's trace id>` |
| log line → trace | `derivedFields` on the Loki datasource | lifts the `trace_id` metadata into a **View trace** link |

The pivots exist because **every signal carries the same `trace_id`** — Grafana just needs to be told which field holds it in each store.

## What one request became

One HTTP call turned into: roughly ten spans converging in Tempo under a single `trace_id`, one increment across three Prometheus counters plus an exemplar pointing back at the trace, and two `WARN` lines in Loki carrying that same `trace_id` as structured metadata — while both Collectors visibly counted, enriched, and (for other, healthier traces) culled data along the way. Every container did exactly the one job it owns, and you just watched each of them do it.

The next post takes this further: reproducing a real production incident on purpose, by flipping one environment variable, and watching the entire detection-to-rollback loop fire on your own machine.

➡️ **Next:** [Part 5 — Reproducing a Production Incident on Purpose](05-reproducing-an-incident.md)
