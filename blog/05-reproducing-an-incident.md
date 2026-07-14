# Reproducing a Production Incident on Purpose (and Five Exercises to Break the Stack)

*Part 5 of a series on observability for microservices. [Part 4](04-touring-the-stack.md) toured a healthy checkout through every container. This post breaks the stack deliberately and watches the observability pipeline catch it. [Series index](00-index.md).*

📦 GitHub: [https://github.com/geekchow/O11y-Micro-Service](https://github.com/geekchow/O11y-Micro-Service)

Part 2 walked through a narrated incident: a deploy that halves a database connection pool, and the 25 minutes it takes to find and fix it. The stack from Part 3 can reproduce that exact incident with a single environment variable — no code changes, no fake `sleep()` calls, just a real resource constraint.

## Why this reproduces cleanly

The payment service's connection pool size is wired to `HIKARI_MAX_POOL`, sourced from `POOL_SIZE` in `stack/.env`. The load generator (`loadgen`) is **open-loop** — it fires requests at a fixed rate regardless of how long previous requests take, exactly like real user traffic. That combination is what makes the math work:

- ~22 requests/sec of charges, each holding a DB connection for ~0.65s, needs about 14 connections to keep up.
- At `POOL_SIZE=20` (capacity ≈ 31 req/s), there's headroom — no incident.
- At `POOL_SIZE=10` (capacity ≈ 15 req/s), demand exceeds capacity — requests queue, then start timing out against Hikari's connection-acquisition timeout.

If you change `LOAD_RPS` or `CHARGE_DB_SECONDS`, keep the resulting DB demand *between* the two pool capacities, or the incident won't reproduce the same way.

## Breaking it

```bash
POOL_SIZE=10 docker compose up -d payment    # break   (T+0)
```

Within about three minutes:

1. p99 latency climbs past 2 seconds.
2. The `CheckoutP99Slow` alert rule fires in Prometheus (`/alerts`).
3. Click an exemplar dot on the latency panel → the trace waterfall shows a Hikari-acquire gap inside the `charge-db` span, sitting *before* the `INSERT` span.
4. "Logs for this span" shows the connection-timeout errors, with the same `trace_id` attached.

```bash
POOL_SIZE=20 docker compose up -d payment    # rollback (T+22)
```

p99 recovers and the alert auto-resolves within about two minutes.

> **Gotcha worth knowing before you try this:** any later `docker compose up ...` run *without* `POOL_SIZE=10` in the shell environment will quietly recreate `payment` back at pool size 20 — compose reconciles the service against `.env` on every apply. Verify what's actually running with:
>
> ```bash
> docker inspect stack-payment-1 --format '{{.Config.Env}}' | tr ' ' '\n' | grep HIKARI
> ```

## Five exercises that make each component's behavior muscle memory

These are ordered from cheapest to most involved. Each one stresses exactly one component so you feel, rather than just read about, what it does under pressure.

### 1. Add your own signal, end to end

Add a `cart.value_cents` attribute to the manual `checkout` span in `CheckoutController.java`:

```java
span.setAttribute("cart.item_count", itemCount);
span.setAttribute("cart.value_cents", req.amountCents());   // your addition
```

```bash
docker compose up -d --build gateway
```

Find the new attribute on fresh traces in Tempo. Then promote it to a `spanmetrics` dimension in `collector-gateway.yaml` and watch a brand-new label appear on the Prometheus side. This exercise is the whole "producer → connector → store" chain, one change at each layer.

### 2. Redact something in flight

Add an `attributes` processor to the agent Collector's pipelines that deletes any `tenant.id`-style attribute:

```yaml
processors:
  attributes/redact:
    actions:
      - key: tenant.id
        action: delete
```

```bash
docker compose restart otel-agent
```

Confirm new traces lack the attribute while old ones still carry it. This is the pipeline acting as a policy enforcement point — the kind of place PII scrubbing or compliance redaction actually lives in a real deployment.

### 3. Kill a backend, watch the blast radius

```bash
docker compose stop loki
```

Keep the load running for 2–3 minutes. Watch `otelcol_exporter_send_failed_log_records` climb on the gateway Collector (`:8889/metrics`) — **while traces and metrics keep flowing normally.** Then:

```bash
docker compose start loki
```

This is per-exporter queue isolation, live: one backend going down degrades exactly one signal type, not the whole pipeline.

### 4. Turn head sampling on and watch tail sampling starve

```bash
# on the services, set:
OTEL_TRACES_SAMPLER=parentbased_traceidratio
OTEL_TRACES_SAMPLER_ARG=0.1
```

The gateway's accepted-span rate drops roughly 90% — and **error traces start going missing in Tempo.** This is head sampling's structural blindness, demonstrated on your own errors: a root span decides "sampled or not" before anything interesting has happened, so at 10% head sampling you're keeping only 10% of your incident evidence too. Revert afterward.

### 5. Watch the sampler's ledger

During normal load, plot both series in Prometheus:

```promql
rate(otelcol_processor_tail_sampling_count_traces_sampled{sampled="true"}[2m])
rate(otelcol_processor_tail_sampling_count_traces_sampled{sampled="false"}[2m])
```

The kept-vs-culled ratio should hover near what the policies promise: all errors, plus roughly 25% of the healthy baseline (1% in a production-tuned config).

## What this buys you

Reading about tail sampling and reading `otelcol_processor_tail_sampling_count_traces_sampled` move at the same rate on the whiteboard — the difference shows up the moment something breaks in production and you already know, from having broken it on purpose, exactly which counter to check first.

So far this series has treated OpenTelemetry as one box in a bigger diagram. The rest of the series opens that box: how a span is actually born, how context survives a network hop, how the Collector's pipeline config really works, and how sampling policies compose.

➡️ **Next:** [Part 6 — Why OpenTelemetry Had to Exist](06-otel-why-what.md)
