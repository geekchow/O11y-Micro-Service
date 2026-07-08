# Stage 4 — WALKTHROUGH: one checkout request, atom by atom

> **Where you are:** Stage 4 of 4. Every concept and component from [03](03-how.md)–[03d](03d-sampling.md) appears below **in bold** the first time it acts — check them off as you read.
> **The scenario:** Spring Boot shop, two services (`gateway-svc` → `payment-svc`), OTel Java agent, agent+gateway Collectors, tail sampling, Tempo + Mimir backends. A customer clicks *Buy* at 14:02:07. The payment card is declined — making this one of the traces worth keeping.

## 0 — Standing start: how the services were instrumented

No code changes for the baseline — the **auto-instrumentation agent** is attached at startup, and env vars configure the **SDK** it installs behind the **API**:

```bash
java -javaagent:opentelemetry-javaagent.jar \
  -Dotel.service.name=gateway-svc \                    # → Resource
  -Dotel.exporter.otlp.endpoint=http://localhost:4318 \ # → node-local Collector agent
  -Dotel.traces.sampler=parentbased_traceidratio \      # → head sampling
  -Dotel.traces.sampler.arg=0.25 \
  -jar gateway-svc.jar
```

The only hand-written instrumentation is business-level (the thing auto-instrumentation *deliberately does not do*):

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

The HTTP request hits Tomcat. The agent's Spring MVC instrumentation asks the **Propagator** to *extract* — no `traceparent` header from the browser, so this becomes a **root span**: `POST /checkout`, kind `SERVER`, fresh `trace_id = T1`. Being a root, the **head sampler** `ParentBased(TraceIdRatioBased(0.25))` consults its delegate: T1 hashes under 25% → **sampled**, flag set. The span carries the **Resource** (`service.name=gateway-svc`, `service.version=1.4.2`, from startup) and becomes current in the **Context**.

Our manual `checkout` span starts as its child; **baggage** `tenant.id=acme` enters the Context beside it.

## 2 — The hop (14:02:07.135)

`paymentClient.charge()` runs; the agent's HTTP-client instrumentation opens a `CLIENT` span and has the Propagator *inject*:

```text
POST /api/charge HTTP/1.1
traceparent: 00-<T1>-<client-span-id>-01     ← the 01: head decision rides along
baggage: tenant.id=acme
```

In payment-svc, *extract* rebuilds the Context; its `SERVER` span joins **trace T1** as a remote child. Its `ParentBased` sampler sees a parent flagged `01` and **obeys without rolling dice** — the trace stays whole.

## 3 — The decline (payment-svc, 14:02:07.480)

The card processor returns `card_declined`. Three signals fire from the same Context in the same millisecond:

- The span records `span.recordException(e)` (an **event**) and `span.setStatus(ERROR)` — the field tail sampling will pounce on.
- `log.warn("charge declined for order {}", id)` goes through Logback → the OTel **appender** (Logs Bridge) → a **LogRecord** automatically stamped `trace_id=T1, span_id=…` — plus `tenant.id` copied from baggage by a log enricher.
- The `http.server.duration` **Histogram** records 345 ms into its buckets, attaching an **exemplar** pointing at T1.

## 4 — Leaving the processes (14:02:07 → :12)

Spans end and land in each SDK's **BatchSpanProcessor** queue (the app thread returned long ago — the customer already sees "card declined" at 14:02:07.6). Within ~5 s, batches leave as **OTLP**/HTTP to each node's **Collector agent**, whose pipeline runs `memory_limiter` (accept — RAM is fine) → `k8sattributes` (**processor** enriches with `k8s.pod.name` — locality the gateway couldn't know) → `batch` → **exporter** to the gateway tier. Metrics take the sibling path on the **MetricReader**'s 60 s cadence; the LogRecord takes the logs pipeline toward Loki/Splunk, trace_id and all.

## 5 — Judgment at the gateway (14:02:22)

Tier-1's **`loadbalancing` exporter** hashes T1 → all spans from both services converge on tail-instance **B**. There the **`tail_sampling` processor** buffers them; 10 s of `decision_wait` after the trace quiets, the policies vote:

```text
keep-all-errors  (status_code=ERROR)  → MATCH   (payment span says so)
keep-slow        (latency>2000ms)     → no      (620 ms total)
keep-1pct        (probabilistic)      → not consulted; already kept
```

**Kept — whole.** The neighboring 300 healthy checkouts from the same minute mostly lose the 1% roll and are dropped here, *after* the **`spanmetrics` connector** already counted them into RED metrics (so dashboards still show true rates). Batched, exported: spans → Tempo, metrics → Mimir.

## 6 — The payoff (14:09, an engineer looks)

A declined-payments panel (fed by the **Counter** and spanmetrics) shows a blip. Click the **exemplar** dot → Tempo opens **trace T1**: gateway `SERVER` → `checkout` → `CLIENT` → payment `SERVER`, with the exception event in the waterfall. "Logs for this span" → the LogRecord, matched by `trace_id=T1`, `tenant.id=acme` attached. Metric → trace → log, zero grep, exactly the pivot the [parent guide's incident flow](../03-how.md) promised.

## Recap — the Stage-1 pains, paid off in one request

Trace T1 was produced by **vendor-neutral** instrumentation (swap Tempo for any OTLP backend by editing one Collector exporter — *pain 1, lock-in*), mostly by auto-instrumentation the ecosystem wrote once against the free API (*pain 2, the N×M matrix*), and its trace_id landed on the span, the log line, and the metric exemplar because all three read one Context (*pain 3, silos*). Sampling kept this errored trace at full fidelity while discarding 99% of its boring neighbors — the whole pipeline never once making the customer wait.

**Checklist check:** API ✓ SDK ✓ agent ✓ Resource ✓ span/events/status ✓ Counter/Histogram/exemplar ✓ LogRecord/bridge ✓ baggage ✓ Context ✓ propagator/traceparent ✓ head sampler/ParentBased ✓ OTLP ✓ agent Collector/memory_limiter/k8sattributes/batch ✓ loadbalancing ✓ tail_sampling policies ✓ spanmetrics connector ✓ exporters ✓ — every actor from Stage 3 has now been seen working.

➡ **Next:** [05-next-steps.md](05-next-steps.md)
