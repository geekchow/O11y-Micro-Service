# Deployable Observability Stack — docs/04-walkthrough.md made real

## Context

The repo is currently docs-only: an observability guide pack in `docs/` plus an OTel deep dive in `docs/otel-deep-dive/`. [docs/04-walkthrough.md](/Users/phil/O11y-Micro-Service/docs/04-walkthrough.md) narrates a "checkout is slow" incident (payment-service deploy halves the Hikari pool 20→10) through a full observability funnel. Prompt.md item 2 asks to make that walkthrough actually deployable with open-source tools. The user's stack: **Spring Boot shop + OTel Java agent + agent/gateway Collectors + Tempo + Prometheus + Loki + Grafana, via docker-compose** (Loki replaces the doc's Splunk; AppDynamics/synthetics out of scope). Decisions confirmed: **full 5-service topology**, **alert rule only (no Alertmanager)**.

The deliverable: `stack/` directory where `docker compose up` brings up everything, a load generator makes telemetry flow, one env-var change reproduces the incident, and the metric → exemplar → trace → log pivot works in Grafana exactly as the doc describes.

## Directory layout to create

```
stack/
├── docker-compose.yml
├── .env                          # POOL_SIZE=20, image tags
├── README.md                     # runbook: start → load → break → diagnose (mirrors walkthrough timeline)
├── services/
│   ├── pom.xml                   # parent POM (Spring Boot 3.5.x, Java 21)
│   ├── Dockerfile                # one multi-stage Dockerfile, ARG SERVICE (maven build → temurin-21-jre + otel javaagent)
│   ├── gateway/    (src + module pom)
│   ├── auth/
│   ├── cart/
│   ├── inventory/
│   └── payment/
├── loadgen/                      # tiny curl-loop container (shell script, alpine/curl image)
├── otel/
│   ├── collector-agent.yaml
│   └── collector-gateway.yaml
├── tempo/tempo.yaml
├── prometheus/prometheus.yml
├── prometheus/rules.yml          # CheckoutP99Slow alert rule
├── loki/loki.yaml
└── grafana/provisioning/
    ├── datasources/datasources.yaml
    └── dashboards/{dashboards.yaml, checkout-red.json}
```

## Spring Boot services (the shop)

All five: Spring Boot 3.5.x web apps, Java 21, built by one parent Maven POM; each depends only on `opentelemetry-api` (+ `opentelemetry-instrumentation-annotations` for `@WithSpan`) — the javaagent supplies the SDK, per the API/SDK split in docs/otel-deep-dive/03-how.md.

- **gateway** (port 8080, exposed): `POST /checkout` → calls auth `/verify`, cart `/cart/{id}`, payment `/charge` via `RestClient`. Manual business instrumentation matching otel-deep-dive/04-walkthrough §0: a `checkout` span with `cart.item_count` attribute, `tenant.id` baggage, an `orders_placed` Counter. Also `GET /health`.
- **auth**: `GET /verify` — ~30 ms simulated work.
- **cart**: `GET /cart/{id}` — returns fake items, calls inventory `/stock/{sku}` (extra hop for trace depth).
- **inventory**: `GET /stock/{sku}` — trivial.
- **payment**: `POST /charge` — the incident engine. HikariCP → Postgres; each charge runs `SELECT pg_sleep(0.25)` then `INSERT INTO payments ...` (holds a connection ~300 ms). Pool size from `HIKARI_MAX_POOL` env (compose passes `${POOL_SIZE}`). ~10% of charges (amount-triggered) return `card_declined` → `span.setStatus(ERROR)` + `log.warn(...)` — feeds the tail-sampler's error policy. Schema auto-created via `schema.sql`.
- Logging: plain Logback; the javaagent's auto-installed appender exports logs via OTLP (no logback config needed). Agent env in compose: `OTEL_SERVICE_NAME`, `OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-agent:4318`, `OTEL_TRACES_SAMPLER=parentbased_always_on` (head sampling stays off so tail policies are observable; README notes how to add ratio sampling).

Dockerfile: multi-stage — `maven:3.9-eclipse-temurin-21` build (parent + one module via `-pl`), runtime `eclipse-temurin:21-jre` with `opentelemetry-javaagent.jar` downloaded (pinned 2.x release) and `JAVA_TOOL_OPTIONS=-javaagent:...`.

## Collectors (agent + gateway, per otel-deep-dive/03c–03d)

Image: `otel/opentelemetry-collector-contrib` (pinned). Both configs follow the canonical processor order (memory_limiter first, batch last).

- **otel-agent** (`collector-agent.yaml`): the "node-local" tier. Receivers: `otlp` (grpc+http). Processors: `memory_limiter`, `resource` (adds `deployment.environment=local` — compose stand-in for k8sattributes), `batch`. Exporters: `otlp` → otel-gateway:4317. Pipelines: traces/metrics/logs.
- **otel-gateway** (`collector-gateway.yaml`): the policy tier.
  - `traces` pipeline: `memory_limiter → tail_sampling → batch` → `otlp` → Tempo. Tail policies exactly as the walkthrough spec: `status_code: ERROR`, `latency > 2000ms`, `probabilistic 1%` — README shows raising 1%→100% for quiet local exploration.
  - `traces/spanmetrics` pipeline (no sampling — the 03d caveat): `memory_limiter → batch` → **`spanmetrics` connector**, so RED metrics count pre-cull.
  - `metrics` pipeline: receivers `[otlp, spanmetrics]` → `memory_limiter → batch` → `otlphttp` to Prometheus' native OTLP endpoint (`http://prometheus:9090/api/v1/otlp`).
  - `logs` pipeline: → `otlphttp` to Loki's OTLP endpoint (`http://loki:3100/otlp`).
  - Single gateway instance, so no `loadbalancing` exporter tier; README calls this out as the scaling extension.

## Backends + Grafana

- **Tempo** (`grafana/tempo`, pinned 2.x): single-binary, local storage, OTLP receiver. Enable metrics-generator OFF (spanmetrics already handled in collector).
- **Prometheus** (`prom/prometheus` v3.x): flags `--web.enable-otlp-receiver` and `--enable-feature=exemplar-storage`; `prometheus.yml` also scrapes both collectors' own metrics (`:8888`) — the "watcher's watcher". `rules.yml`: `CheckoutP99Slow` — p99 of the spanmetrics duration histogram for `payment-service` > 2 s for 2 m (adapted from the doc's synthetic rule; annotation links to the Grafana dashboard).
- **Loki** (`grafana/loki` 3.x): single-binary default config with structured metadata on (v13/tsdb schema) so OTLP ingest keeps `trace_id` queryable.
- **Grafana** (pinned 11/12.x, anonymous admin for demo): provisioned datasources —
  - Prometheus with `exemplarTraceIdDestinations` → Tempo (the one-click exemplar→trace pivot),
  - Tempo with `tracesToLogsV2` → Loki (query by `service_name` + trace_id, the trace→logs pivot),
  - Loki.
  - One provisioned dashboard `checkout-red.json`: RED panels from spanmetrics (rate, error %, p99 by service with exemplars enabled) + orders_placed counter + Hikari pool panels if the agent's `db.client.connections.*` metrics are present.
- **postgres** (`postgres:16-alpine`), **loadgen**: curl loop POSTing `/checkout` ~5 req/s with occasional decline-triggering amounts.

## README runbook (maps the walkthrough timeline to commands)

1. `docker compose up -d --build` → open Grafana :3000. 2. Load flows automatically (loadgen). 3. Verify green state on RED dashboard. 4. **Break it**: `POOL_SIZE=10 docker compose up -d payment` → within ~2 min p99 climbs, alert fires in Prometheus. 5. **Diagnose like the doc**: dashboard knee → exemplar dot → Tempo waterfall (time spent acquiring connection) → "logs for this span" → Loki shows Hikari "pool exhausted / connection acquired after N ms" lines with trace_id. 6. **Fix**: `POOL_SIZE=20 docker compose up -d payment`, watch recovery. Plus: ports table, teardown, extensions (head-sampling env var, two-tier loadbalancing, Alertmanager).

## Doc hookups (small edits)

- `Prompt.md`: tick item 2, add pointer to `stack/README.md`.
- `docs/00-overview.md`: add a "Runnable stack" line next to the deep-dives table.
- `docs/otel-deep-dive/05-next-steps.md`: exercise 5 → link to `stack/`.

## Verification

1. `docker compose up -d --build` completes; `docker compose ps` all healthy (healthchecks on postgres, collectors, backends).
2. `curl -X POST localhost:8080/checkout ...` returns 200; loadgen logs show a mix of 200s and declines.
3. Telemetry end-to-end: Grafana Explore → Tempo search shows multi-service traces (gateway→auth/cart→inventory/payment spans in one waterfall); Prometheus has `traces_span_metrics_*` series and `orders_placed_total`; Loki has payment logs where a `trace_id` matches a Tempo trace.
4. Pivots: exemplar dot on the p99 panel opens the exact trace; Tempo "logs for this span" lands on the matching Loki lines.
5. Incident drill: rerun payment with `POOL_SIZE=10`, confirm p99 > 2 s on the dashboard, `CheckoutP99Slow` fires in Prometheus /alerts, trace waterfall shows the long connection-acquire gap inside `payment /charge`, Loki shows Hikari timeout/queued warnings; restore `POOL_SIZE=20`, confirm recovery.
6. Version risk note: image tags and the javaagent release must be pinned to current stable at build time (verify pulls succeed); Loki OTLP and Prometheus OTLP endpoints are config-sensitive — validated by step 3.