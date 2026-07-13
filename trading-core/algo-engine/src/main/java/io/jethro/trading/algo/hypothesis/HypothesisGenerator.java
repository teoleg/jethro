package io.jethro.trading.algo.hypothesis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jethro.domain.Side;
import io.jethro.messaging.AiDecision;
import io.jethro.messaging.EventMeta;
import io.jethro.trading.algo.agent.DecisionSink;
import io.jethro.trading.algo.inference.InferenceRequest;
import io.jethro.trading.algo.inference.InferenceResult;
import io.jethro.trading.algo.inference.ModelInferenceClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * The LLM hypothesis layer (ADR-0022): synthesises marks + narrative + portfolio into a
 * short list of structured, <b>number-free</b> {@link Hypothesis}. The model's only outputs
 * are a chosen instrument, a direction, and ordinal labels — every number stays with the
 * deterministic quant layer downstream (invariant 7 / ADR-0016).
 *
 * <p>Safety by construction, mirroring the operational-chat parser (ADR-0021): the model is
 * asked for strict JSON and may only pick instruments from the provided tradable set; the
 * output is validated field-by-field and anything malformed or off-list is dropped, never
 * traded. Every generation run is recorded as an {@link AiDecision} (ADR-0010 audit shape).
 */
public final class HypothesisGenerator {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_THESIS_CHARS = 240;

    private static final String SYSTEM_PROMPT = """
            You are a trading analyst for a multi-asset desk. You are given JSON with current \
            prices (marks), recent narrative items (news/econ/earnings), and the current \
            portfolio. Propose up to %d trade hypotheses.

            Output ONLY a JSON array, nothing else. Each element:
              {"instrument": <an id from tradableInstruments>,
               "direction": <"BUY" for bullish/long or "SELL" for bearish/short>,
               "horizon": <one of INTRADAY, SWING, POSITION>,
               "conviction": <one of LOW, MEDIUM, HIGH>,
               "thesis": <one short sentence, your reasoning>,
               "sources": <array of narrative item ids that informed this, or []>}

            Example output:
              [{"instrument":"AAPL","direction":"BUY","horizon":"SWING","conviction":"MEDIUM",
                "thesis":"Upbeat earnings headline and a firm price support a long.","sources":["sim-news-3"]}]

            Rules:
            - Choose "instrument" ONLY from tradableInstruments. Never invent a ticker.
            - Do NOT output any price, size, quantity, percentage, or numeric target. \
            Direction and conviction only — the risk system computes all numbers.
            - Base each thesis on the narrative and marks. Prefer names with the strongest recent \
            move or a matching headline; aim to return at least one hypothesis when a headline is \
            clearly directional. Use [] only if truly nothing is actionable.
            - Output the JSON array only — no prose, no markdown fences.""";

    private final ModelInferenceClient client;
    private final DecisionSink sink;
    private final int maxPerCycle;
    private final int maxOutputTokens;

    public HypothesisGenerator(ModelInferenceClient client, DecisionSink sink,
                               int maxPerCycle, int maxOutputTokens) {
        this.client = client;
        this.sink = sink;
        this.maxPerCycle = maxPerCycle;
        this.maxOutputTokens = maxOutputTokens;
    }

    /** Runs one generation cycle. Returns validated hypotheses (possibly empty). */
    public List<Hypothesis> generate(HypothesisContext context) {
        String contextJson = toJson(context);
        InferenceResult result = client.complete(new InferenceRequest(
                String.format(SYSTEM_PROMPT, maxPerCycle), contextJson, maxOutputTokens));
        List<Hypothesis> hypotheses = parse(result.text(), context);
        record(contextJson, hypotheses, result);
        return hypotheses;
    }

    private List<Hypothesis> parse(String text, HypothesisContext context) {
        List<Hypothesis> out = new ArrayList<>();
        JsonNode array = extractArray(text);
        if (array == null) {
            return out; // nothing parseable — the model produced no usable JSON
        }
        for (JsonNode node : array) {
            Hypothesis h = validate(node, context);
            if (h != null) {
                out.add(h);
                if (out.size() >= maxPerCycle) {
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Pulls a JSON array out of the model's reply. Tolerant of a small model: accepts a bare
     * array, and if it emitted a single object (no array) wraps it — so one valid hypothesis
     * isn't lost to a missing pair of brackets. Ignores prose/markdown around the JSON.
     */
    private static JsonNode extractArray(String text) {
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start >= 0 && end > start) {
            try {
                JsonNode node = JSON.readTree(text.substring(start, end + 1));
                if (node.isArray()) {
                    return node;
                }
            } catch (Exception ignored) {
                // fall through to the single-object attempt
            }
        }
        int objStart = text.indexOf('{');
        int objEnd = text.lastIndexOf('}');
        if (objStart >= 0 && objEnd > objStart) {
            try {
                JsonNode obj = JSON.readTree(text.substring(objStart, objEnd + 1));
                if (obj.isObject()) {
                    ArrayNode wrapped = JSON.createArrayNode();
                    wrapped.add(obj);
                    return wrapped;
                }
            } catch (Exception ignored) {
                // no usable JSON
            }
        }
        return null;
    }

    /** Field-by-field validation: a hallucinated instrument, bad enum, or empty thesis is dropped. */
    private static Hypothesis validate(JsonNode node, HypothesisContext context) {
        String instrument = validInstrument(node.path("instrument").asText(null), context.tradableInstruments());
        Side direction = parseSide(node.path("direction").asText(null));
        Hypothesis.Horizon horizon = parseEnum(Hypothesis.Horizon.class, node.path("horizon").asText(null));
        Hypothesis.Conviction conviction =
                parseEnum(Hypothesis.Conviction.class, node.path("conviction").asText(null));
        String thesis = node.path("thesis").asText("").strip();
        if (instrument == null || direction == null || horizon == null || conviction == null || thesis.isEmpty()) {
            return null;
        }
        if (thesis.length() > MAX_THESIS_CHARS) {
            thesis = thesis.substring(0, MAX_THESIS_CHARS);
        }
        List<String> sources = new ArrayList<>();
        JsonNode src = node.path("sources");
        if (src.isArray()) {
            src.forEach(s -> {
                String id = s.asText("").strip();
                if (!id.isEmpty()) {
                    sources.add(id);
                }
            });
        }
        return new Hypothesis(UUID.randomUUID().toString(), instrument, direction, horizon, conviction,
                thesis, List.copyOf(sources));
    }

    private static String validInstrument(String value, java.util.Set<String> tradable) {
        if (value == null) {
            return null;
        }
        for (String id : tradable) {
            if (id.equalsIgnoreCase(value.trim())) {
                return id;
            }
        }
        return null;
    }

    private static Side parseSide(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Side.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void record(String contextJson, List<Hypothesis> hypotheses, InferenceResult result) {
        ArrayNode actions = JSON.createArrayNode();
        for (Hypothesis h : hypotheses) {
            actions.addObject()
                    .put("hypothesisId", h.hypothesisId())
                    .put("instrument", h.instrumentId())
                    .put("direction", h.direction().name())
                    .put("horizon", h.horizon().name())
                    .put("conviction", h.conviction().name())
                    .put("thesis", h.thesis());
        }
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
                        .put("type", "hypotheses")
                        .set("items", actions).toString())
                .setLatencyMillis(result.latencyMillis())
                .setInputTokens(result.inputTokens())
                .setOutputTokens(result.outputTokens())
                .build());
    }

    private static String toJson(HypothesisContext context) {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode marks = root.putArray("marks");
        for (var m : context.marks()) {
            marks.addObject().put("instrumentId", m.instrumentId()).put("price", m.price()).put("stale", m.stale());
        }
        ArrayNode narrative = root.putArray("narrative");
        for (var n : context.narrative()) {
            ObjectNode item = narrative.addObject()
                    .put("id", n.id()).put("category", n.category().name())
                    .put("sentiment", n.sentiment().name()).put("headline", n.headline());
            if (n.instrumentId() != null) {
                item.put("instrumentId", n.instrumentId());
            }
        }
        ArrayNode portfolio = root.putArray("portfolio");
        for (var p : context.portfolio()) {
            portfolio.addObject().put("bookId", p.bookId()).put("instrumentId", p.instrumentId())
                    .put("quantity", p.quantity()).put("unrealizedPnl", p.unrealizedPnl());
        }
        root.putArray("tradableInstruments").addAll(
                context.tradableInstruments().stream().map(JSON.getNodeFactory()::textNode).toList());
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
