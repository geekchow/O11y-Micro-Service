# Next steps — poke each component until it makes sense

> **Where you are:** done with the tour. Each exercise below stresses one component so its behavior (and its failure mode) becomes muscle memory.

1. **Add your own signal end to end.** Add a `cart.value_cents` attribute to the manual `checkout` span in [CheckoutController.java](../../stack/services/gateway/src/main/java/com/o11y/shop/gateway/CheckoutController.java), rebuild (`docker compose up -d --build gateway`), find it on new traces in Tempo. Then promote it to a spanmetrics dimension in [collector-gateway.yaml](../../stack/otel/collector-gateway.yaml) and watch a new Prometheus label appear. *(producer → connector → store, one change each)*
2. **Redact something in flight.** Add an `attributes` processor to the agent's pipelines that deletes `tenant.id`-style attributes; `docker compose restart otel-agent`; confirm new traces lack it while old ones keep it. *(the pipeline as policy enforcement point)*
3. **Kill a backend, watch the blast radius.** `docker compose stop loki`, keep the load running 2–3 minutes, watch the gateway's `otelcol_exporter_send_failed_log_records` climb at :8889/metrics **while traces and metrics keep flowing** — then `start` it again. That's per-exporter queue isolation from [otel-deep-dive 03c](../otel-deep-dive/03c-collector.md), live. *(graceful degradation)*
4. **Turn head sampling on and watch tail sampling starve.** Set `OTEL_TRACES_SAMPLER=parentbased_traceidratio` + `OTEL_TRACES_SAMPLER_ARG=0.1` on the services; the gateway's accepted-span rate drops ~90%, and *error traces start going missing in Tempo* — head sampling's blindness ([otel-deep-dive 03d](../otel-deep-dive/03d-sampling.md)) demonstrated on your own errors. Revert after.
5. **Watch the sampler's ledger.** During normal load, plot `rate(otelcol_processor_tail_sampling_count_traces_sampled{sampled="true"}[2m])` vs `sampled="false"` in Prometheus — the kept/culled ratio should hover near policy expectations (~10% errors + 25% baseline).
6. **The incident drill** — when you want the *diagnostic* story rather than the component story: [stack/README.md §incident](../../stack/README.md), the T+0→T+22 reproduction of [docs/04-walkthrough.md](../04-walkthrough.md).

⬅ Back to [00-overview.md](00-overview.md) · ⬆ [parent guide](../00-overview.md) · 🔬 [OTel deep dive](../otel-deep-dive/00-overview.md)
