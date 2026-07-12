package io.jethro.trading.algo.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jethro.messaging.AiDecision;
import io.jethro.messaging.EventMeta;
import io.jethro.trading.algo.inference.InferenceRequest;
import io.jethro.trading.algo.inference.InferenceResult;
import io.jethro.trading.algo.inference.ModelInferenceClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * First agentic element (ADR-0016): narrates computed market state on a cadence.
 * Deterministic code computes the numbers; this agent turns them into commentary.
 * Every run is recorded as an AiDecision with the exact context it saw (ADR-0010
 * audit shape: model ID, latency, tokens, context snapshot + hash).
 */
public final class RiskCommentator {

    private static final ObjectMapper JSON = new ObjectMapper();
    // Small models confabulate unless every field is spelled out and inference is
    // forbidden explicitly. Keep this prescriptive (ADR-0016: the model narrates the
    // computed data, it never computes or infers numbers).
    private static final String SYSTEM_PROMPT = """
            You are the risk commentator for a trading platform. You are given a JSON \
            snapshot of already-computed market state. The fields are:
            - marks[].instrumentId: the ticker symbol
            - marks[].price: the last price (a decimal number written as a string)
            - marks[].stale: true only if the price is old/unreliable
            - marks[].ageMillis: how many milliseconds ago the price was recorded (small = fresh)
            - feed.ticksIn: cumulative count of market updates processed since startup
            - feed.ticksDropped: count of updates dropped under load (0 is healthy)

            Write exactly TWO short sentences of plain commentary on what stands out: \
            notable prices, any stale marks, and feed health.

            Strict rules:
            - Use ONLY the values present in the JSON. Restate them; do not compute new ones.
            - Do NOT invent dates, calendar days, or times of day — there are none in the data.
            - Do NOT invent tick rates or intervals; ticksIn is a running total, not a rate.
            - Do NOT convert ageMillis into other units or describe it as a date.
            - Do NOT give trading advice.""";

    private final ModelInferenceClient client;
    private final DecisionSink sink;
    private final int maxOutputTokens;

    public RiskCommentator(ModelInferenceClient client, DecisionSink sink, int maxOutputTokens) {
        this.client = client;
        this.sink = sink;
        this.maxOutputTokens = maxOutputTokens;
    }

    /** Runs one commentary cycle; returns the commentary text. Throws InferenceException on failure. */
    public String commentOn(MarketView view) {
        String contextJson = toJson(view);
        InferenceResult result = client.complete(
                new InferenceRequest(SYSTEM_PROMPT, contextJson, maxOutputTokens));

        Instant now = Instant.now();
        sink.record(AiDecision.newBuilder()
                .setMeta(EventMeta.newBuilder()
                        .setEventId(UUID.randomUUID().toString())
                        .setProviderTimestamp(now)
                        .setIngestTimestamp(now)
                        .build())
                .setDecisionId(UUID.randomUUID().toString())
                .setModelId(result.modelId())
                .setModelVersion("")
                .setContextHash(sha256(contextJson))
                .setContextSnapshotJson(contextJson)
                .setProposedActionsJson(JSON.createObjectNode()
                        .put("type", "commentary")
                        .put("text", result.text())
                        .toString())
                .setLatencyMillis(result.latencyMillis())
                .setInputTokens(result.inputTokens())
                .setOutputTokens(result.outputTokens())
                .build());
        return result.text();
    }

    private static String toJson(MarketView view) {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode marks = root.putArray("marks");
        for (var mark : view.marks()) {
            marks.addObject()
                    .put("instrumentId", mark.instrumentId())
                    .put("price", mark.price().toPlainString()) // decimal as string: invariant 1
                    .put("stale", mark.stale())
                    .put("ageMillis", mark.ageMillis());
        }
        root.putObject("feed")
                .put("ticksIn", view.ticksIn())
                .put("ticksDropped", view.ticksDropped());
        return root.toString();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
