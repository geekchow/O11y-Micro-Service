# Stage 4 — WALKTHROUGH: one checkout, followed with your own hands

> **Where you are:** Stage 4 of 4. Everything from [03](03-how.md), verified live.
> **Prereq:** the stack is up (`cd stack && docker compose up -d --build`) and has run ~2 minutes. Every command below is copy-pasteable from the repo root.

We'll push **one declined checkout** through the system (declines are ideal tour subjects: the error policy keeps them, so tail sampling can't hide ours) and then find its footprint in every component.

## Step 0 — send it and keep the evidence

```bash
curl -s -X POST localhost:8080/checkout -H 'Content-Type: application/json' \
  -d '{"cartId":"tour","amountCents":47,"tenant":"acme"}'
# → {"orderId":"<UUID>","status":"declined"}     amount ends in 7 ⇒ declined
```

Note the `orderId` — it's our join key into the logs, from which we'll get the trace_id.

## Step 1 — the producers: what the javaagent emitted

The five JVMs already did their work invisibly: Tomcat/RestClient auto-spans, the manual `checkout` span, the `orders_placed` counter (not incremented — declined!), a WARN LogRecord in payment and gateway. Nothing to see *in* the services — by design. What you *can* see is their only telemetry contract, the env in [docker-compose.yml](../../stack/docker-compose.yml): one OTLP endpoint, nothing else.

## Step 2 — the log path pays out first: Loki has the trace_id

```bash
ORDER=<paste the orderId>
curl -s -G 'localhost:3100/loki/api/v1/query_range' \
  --data-urlencode "query={service_name=\"payment\"} |= \"$ORDER\"" \
  --data-urlencode 'limit=1' \
  | python3 -c "import json,sys; s=json.load(sys.stdin)['data']['result'][0]['stream']; print('trace_id:', s['trace_id']); print('line   :', 'found')"
```

The `trace_id` came back as **structured metadata**, not text — the appender stamped it because the WARN happened inside the span's Context ([03 §3.3](03-how.md)).

## Step 3 — the trace path: Tempo has the whole story

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

Expect spans from **four services** (gateway incl. the manual `checkout` span, auth, cart→inventory, payment) under one trace_id — context propagation, demonstrated after the fact. This trace exists in Tempo *because* the `status_code: ERROR` policy matched; a healthy sibling has only a 25% chance.

## Step 4 — the metric path: it's already a number in Prometheus

```bash
open 'http://localhost:9090/graph?g0.expr=sum%20by%20(service_name%2C%20status_code)%20(rate(traces_span_metrics_calls_total%7Bspan_kind%3D%22SPAN_KIND_SERVER%22%7D%5B2m%5D))'
```

Your checkout is one anonymous increment in `traces_span_metrics_calls_total{status_code="STATUS_CODE_ERROR"}` — produced by the **spanmetrics connector**, *before* tail sampling, from the same spans you just saw in Tempo. Individual identity gone, rate preserved: that's the metrics trade-off, mechanized.

## Step 5 — watch the machinery: the pipeline counted your spans

```bash
# agent tier accepted them…
curl -s localhost:8888/metrics | grep -E '^otelcol_receiver_accepted_spans'
# …gateway tier accepted them, and exported FEWER than it accepted:
curl -s localhost:8889/metrics | grep -E '^otelcol_(receiver_accepted|exporter_sent)_spans'
```

The gap between the gateway's accepted and sent span counts **is the tail sampler discarding healthy traces** — the only place in the stack where telemetry is deliberately destroyed. For the live view: zpages at http://localhost:55680/debug/pipelinez.

## Step 6 — the query path: close the loop in Grafana

In http://localhost:3000 → **Checkout — RED**:

1. Error-rate panel: your decline is in the red series (metric view).
2. p99 panel → click an exemplar dot → the trace opens (metric→trace pivot).
3. In the waterfall, payment span → **Logs for this span** → your WARN line (trace→log pivot).
4. In the log line's details, `trace_id` → **View trace** (log→trace pivot — you've gone full circle).

## Recap

One HTTP request became: 10-ish spans converging in Tempo under one trace_id, one increment in three Prometheus counters plus an exemplar pointing back at the trace, and two WARN lines in Loki carrying the same trace_id as metadata — with the two Collectors visibly counting, enriching, and (for other traces) culling along the way. Every container in [02-what.md](02-what.md)'s table just did the one job it owns, and you watched each do it.

➡ **Next:** [05-next-steps.md](05-next-steps.md) — now break things on purpose.
