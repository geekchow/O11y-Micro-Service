# One Checkout Request, Atom by Atom

*Part 10 (final) of a series on observability for microservices. Parts 6–9 covered OpenTelemetry's design, signals, the Collector, and sampling separately. This post runs every one of those pieces through one real request, in order. [Series index](00-index.md).*

📦 GitHub: [https://github.com/geekchow/O11y-Micro-Service](https://github.com/geekchow/O11y-Micro-Service)

**The scenario:** a Spring Boot shop, two services (`gateway-svc` → `payment-svc`), the OTel Java agent, an agent + gateway Collector pair, tail sampling, and Tempo + Mimir as backends. A customer clicks *Buy* at 14:02:07. The card is declined — which, as established in Part 4, makes this one of the traces worth keeping.

## 0 — How the services were instrumented

No code changes for the baseline: the auto-instrumentation agent attaches at startup, and environment variables configure the SDK it installs behind the API.

```bash
java -javaagent:opentelemetry-javaagent.jar \
  -Dotel.service.name=gateway-svc \                    # → Resource
  -Dotel.exporter.otlp.endpoint=http://localhost:4318 \ # → node-local Collector agent
  -Dotel.traces.sampler=parentbased_traceidratio \      # → head sampling
  -Dotel.traces.sampler.arg=0.25 \
  -jar gateway-svc.jar
```

The only hand-written instrumentation anywhere is business-level — the layer auto-instrumentation deliberately can't reach:

```java
// gateway-svc — CheckoutService.java
Span span = tracer.spanBuilder("checkout").startSpan();          // OTel API
try (Scope s = span.makeCurrent()) {                             // → Context
    span.setAttribute("cart.item_count", cart.size());
    Baggage.current().toBuilder()
        .put("tenant.id", tenant).build().makeCurrent();          // Baggage
    ordersPlaced.add(1);                                          // Counter instrument
    paymentClient.charge(cart);                                   // hop happens in here
} finally { span.end(); }
```

## 1 — Birth of the trace (gateway-svc, 14:02:07.101)

The HTTP request hits Tomcat. The agent's Spring MVC instrumentation asks the Propagator to *extract* — there's no `traceparent` header from the browser, so this becomes a **root span**: `POST /checkout`, kind `SERVER`, a fresh `trace_id = T1`. Being a root, the head sampler `ParentBased(TraceIdRatioBased(0.25))` consults its delegate: `T1` hashes under 25% → **sampled**, flag set. The span carries the Resource attributes set at startup (`service.name=gateway-svc`, `service.version=1.4.2`) and becomes current in the Context.

The manual `checkout` span starts as its child; baggage `tenant.id=acme` enters the Context beside it.

## 2 — The hop (14:02:07.135)

`paymentClient.charge()` runs. The agent's HTTP-client instrumentation opens a `CLIENT` span and has the Propagator *inject*:

```text
POST /api/charge HTTP/1.1
traceparent: 00-<T1>-<client-span-id>-01     ← the 01: head decision rides along
baggage: tenant.id=acme
```

In `payment-svc`, *extract* rebuilds the Context; its `SERVER` span joins trace `T1` as a remote child. Its `ParentBased` sampler sees a parent already flagged `01` and **obeys without rolling its own dice** — the trace stays whole, exactly as Part 9 described.

## 3 — The decline (payment-svc, 14:02:07.480)

The card processor returns `card_declined`. Three signals fire from the same Context, in the same millisecond:

- The span records `span.recordException(e)` (an event) and `span.setStatus(ERROR)` — the field tail sampling is watching for.
- `log.warn("charge declined for order {}", id)` goes through Logback, into the OTel appender (the Logs Bridge from Part 7), and becomes a LogRecord automatically stamped `trace_id=T1, span_id=…` — with `tenant.id` copied from baggage by a log enricher.
- The `http.server.duration` Histogram records 345ms into its buckets, attaching an exemplar pointing at `T1`.

## 4 — Leaving the processes (14:02:07 → :12)

Spans end and land in each SDK's BatchSpanProcessor queue — the application thread already returned; the customer sees "card declined" at 14:02:07.6, well before any of this telemetry leaves the box. Within about 5 seconds, batches leave as OTLP/HTTP to each node's Collector agent, whose pipeline runs `memory_limiter` (accepts — RAM is fine) → `k8sattributes` (enriches with `k8s.pod.name`, locality the gateway tier couldn't know) → `batch` → an exporter to the gateway tier. Metrics take the sibling path on the MetricReader's 60-second cadence; the LogRecord takes the logs pipeline toward the log backend, `trace_id` and all.

## 5 — Judgment at the gateway (14:02:22)

Tier-1's `loadbalancing` exporter hashes `T1`, so every span from both services converges on the same tail-sampling instance — instance **B**. There, `tail_sampling` buffers them; 10 seconds after the trace goes quiet, the policies vote:

```text
keep-all-errors  (status_code=ERROR)  → MATCH   (payment span says so)
keep-slow        (latency>2000ms)     → no      (620 ms total)
keep-1pct        (probabilistic)      → not consulted; already kept
```

**Kept, whole.** The 300 healthy checkouts from the same minute mostly lose their 1% dice roll and get dropped here — *after* the `spanmetrics` connector already counted them into RED metrics, so dashboards still show true rates. Batched and exported: spans to Tempo, metrics to Mimir.

## 6 — The payoff (14:09, an engineer looks)

A declined-payments panel — fed by the Counter and `spanmetrics` — shows a blip. Clicking the exemplar dot opens trace `T1` in Tempo: `gateway SERVER` → `checkout` → `CLIENT` → `payment SERVER`, with the exception event visible in the waterfall. "Logs for this span" pulls the LogRecord, matched by `trace_id=T1`, with `tenant.id=acme` attached. Metric → trace → log, zero grep — exactly the pivot from Part 2's incident walkthrough, now traced down to the byte.

## What this request proved, end to end

Trace `T1` was produced by vendor-neutral instrumentation — swap Tempo for any OTLP backend by editing one Collector exporter, no application code touched (Part 6's pain 1, lock-in, solved). It was produced mostly by auto-instrumentation the ecosystem wrote once against a free API (pain 2, the N×M matrix, solved). Its `trace_id` landed on the span, the log line, and the metric exemplar because all three read one shared Context (pain 3, silos, solved). And sampling kept this errored trace at full fidelity while quietly discarding 99% of its boring neighbors — with the pipeline never once making the customer wait for any of it.

Checklist, if you want to verify you followed every piece: API ✓ SDK ✓ agent ✓ Resource ✓ span/events/status ✓ Counter/Histogram/exemplar ✓ LogRecord/bridge ✓ baggage ✓ Context ✓ propagator/traceparent ✓ head sampler/ParentBased ✓ OTLP ✓ agent Collector (memory_limiter/k8sattributes/batch) ✓ loadbalancing ✓ tail_sampling policies ✓ spanmetrics connector ✓ exporters ✓. Every actor introduced across this series just did its one job, on one real request.

## Where to go from here

- **Read a `traceparent` cold.** Take `00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01` and write down every field, and what the last byte implies for a `ParentBased` downstream service.
- **Run the smallest possible Collector.** `docker run` the contrib image with an `otlp` receiver and a `debug` exporter, point any auto-instrumented app at it, and watch spans print to the console. Then add a `filter` processor that drops `/health` spans and confirm they vanish.
- **Break context propagation on purpose.** In a Spring Boot app, run part of a request through a hand-rolled `ExecutorService` and find the resulting orphan span; fix it with `Context.current().wrap(runnable)`. Nothing teaches propagation like repairing it yourself.
- **Prove head sampling's blindness for yourself.** Set `traceidratio` to 0.1, generate 100 requests with 10 forced errors, and count how many error traces actually survive. Then add a `tail_sampling` `status_code` policy at a Collector and repeat the experiment.
- **Run the whole stack.** Everything in this series — the 5-service shop, both Collector tiers, Tempo, Prometheus, Loki, Grafana, and the reproducible connection-pool incident — is a `docker compose up` away in the [companion repo's `stack/`](../stack/). If you've read this far without running it, that's the highest-leverage next step.

That closes the series: why monitoring broke, how the pieces cooperate to solve an incident, a stack you can run, and OpenTelemetry's internals opened up one layer at a time. The rest is muscle memory — go break something.

⬅ **Series index:** [00-index.md](00-index.md)
