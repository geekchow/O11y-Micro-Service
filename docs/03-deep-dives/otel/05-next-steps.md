# Next steps — exercises, doc entry points, and your TODOs

> **Where you are:** done with the deep dive. This file turns understanding into muscle memory.

## Exercises (in rising order of effort)

1. **Read a traceparent cold.** Take `00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01` and write down every field and what the last byte implies for a `ParentBased` downstream service. *(5 min — [03b](03b-context.md))*
2. **Run the smallest possible pipeline.** `docker run` the contrib Collector with an `otlp` receiver and `debug` exporter; point any auto-instrumented app at it; watch spans print. Then add a `filter` processor that drops `/health` spans and verify they vanish. *(30 min — [03c](03c-collector.md))*
3. **Break context on purpose.** In a Spring Boot app, run part of a request through a hand-made `ExecutorService` and find the orphan span; fix it with `Context.current().wrap(runnable)`. Nothing teaches propagation like repairing it. *(1 h — [03b](03b-context.md))*
4. **Prove head sampling's blindness.** Set `traceidratio` to 0.1, generate 100 requests with 10 forced errors, count how many error traces survive. Then add a `tail_sampling` `status_code` policy at a Collector and repeat. *(1–2 h — [03d](03d-sampling.md))*
5. **The big one — your Prompt.md item #2:** ✅ built — see [../../stack/](../../../stack/README.md): Spring Boot shop + OTel Java agent + agent/gateway Collectors + Tempo + Prometheus + Loki + Grafana via docker-compose, with the pool-exhaustion incident reproducible via one env var. Remaining exercise: run the incident drill in its README §3 and walk every pivot yourself.

## Where to read next, in dependency order

| Topic | Entry point |
|---|---|
| Concepts refresher | [opentelemetry.io/docs/concepts](https://opentelemetry.io/docs/concepts/) |
| Semantic conventions (the attribute names to use) | [opentelemetry.io/docs/specs/semconv](https://opentelemetry.io/docs/specs/semconv/) |
| Java agent & Spring Boot specifics | [opentelemetry.io/docs/zero-code/java](https://opentelemetry.io/docs/zero-code/java/) |
| Collector configuration deep end | [opentelemetry.io/docs/collector/configuration](https://opentelemetry.io/docs/collector/configuration/) |
| Every existing receiver/processor/exporter | [OTel Registry](https://opentelemetry.io/ecosystem/registry/) + the [contrib repo](https://github.com/open-telemetry/opentelemetry-collector-contrib) READMEs (check per-component stability!) |
| Sampling spec (the deep math) | [opentelemetry.io/docs/concepts/sampling](https://opentelemetry.io/docs/concepts/sampling/) |
| Scaling the Collector / production ops | [opentelemetry.io/docs/collector/scaling](https://opentelemetry.io/docs/collector/scaling/) |

## Open threads from your [Prompt.md](../../../Prompt.md)

- **Synthetics** (item 1) — deliberately *not* in this pack: synthetics is an outside-in signal ([parent guide, concept 7](../../01-concepts/03-how.md)) that OTel doesn't produce; it's a consumer-side concern (Grafana Synthetic Monitoring, Elastic Synthetics, Checkly...). Worth its own short study.
- **Deployable Spring Boot example** (item 2) — exercise 5 above; this pack now gives you the vocabulary to build it without cargo-culting configs.

⬅ Back to [00-overview.md](00-overview.md) · ⬆ Up to the [parent observability guide](../../01-concepts/00-overview.md)
