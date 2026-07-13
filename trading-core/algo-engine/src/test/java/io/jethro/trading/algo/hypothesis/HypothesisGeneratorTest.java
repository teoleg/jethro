package io.jethro.trading.algo.hypothesis;

import io.jethro.domain.Side;
import io.jethro.messaging.AiDecision;
import io.jethro.trading.algo.agent.DecisionSink;
import io.jethro.trading.algo.inference.InferenceRequest;
import io.jethro.trading.algo.inference.InferenceResult;
import io.jethro.trading.algo.inference.ModelInferenceClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generator's safety-by-construction: strict-JSON parse, drop hallucinated instruments
 * and bad enums, and audit every run — the model never emits a number we act on (ADR-0022).
 */
class HypothesisGeneratorTest {

    private static final class FakeModel implements ModelInferenceClient {
        private final String reply;
        FakeModel(String reply) { this.reply = reply; }
        @Override public String modelId() { return "fake"; }
        @Override public InferenceResult complete(InferenceRequest request) {
            return new InferenceResult(reply, "fake", 1, 10, 5);
        }
    }

    private final List<AiDecision> audited = new ArrayList<>();
    private final DecisionSink sink = audited::add;

    private HypothesisContext context() {
        return new HypothesisContext(
                List.of(new HypothesisContext.MarkView("AAPL", "EQUITY", "USD", "Apple Inc. common stock", "190.00", false),
                        new HypothesisContext.MarkView("ES", "FUTURE", "USD", "E-mini S&P 500 future", "5450.00", false)),
                List.of(new NarrativeItem("sim-news-1", 0, NarrativeItem.Category.EARNINGS,
                        "AAPL", NarrativeItem.Sentiment.BULLISH, "AAPL beats")),
                List.of(), Set.of("AAPL", "ES"));
    }

    @Test
    void parsesValidHypothesesAndDropsBadOnes() {
        String reply = """
                Here you go:
                [
                  {"instrument":"AAPL","direction":"BUY","horizon":"SWING","conviction":"MEDIUM",
                   "thesis":"Earnings beat supports upside","sources":["sim-news-1"]},
                  {"instrument":"ZZZZ","direction":"BUY","horizon":"SWING","conviction":"LOW",
                   "thesis":"hallucinated ticker","sources":[]},
                  {"instrument":"ES","direction":"SELL","horizon":"INTRADAY","conviction":"NOPE",
                   "thesis":"bad enum","sources":[]}
                ]
                """;
        var generator = new HypothesisGenerator(new FakeModel(reply), sink, 3, 400);
        List<Hypothesis> out = generator.generate(context());

        assertEquals(1, out.size(), "only the valid, in-universe hypothesis survives");
        Hypothesis h = out.get(0);
        assertEquals("AAPL", h.instrumentId());
        assertEquals(Side.BUY, h.direction());
        assertEquals(Hypothesis.Conviction.MEDIUM, h.conviction());
        assertEquals(List.of("sim-news-1"), h.sources());
        assertEquals(1, audited.size(), "every generation run is one audited AiDecision");
        assertEquals("fake", audited.get(0).getModelId());
    }

    @Test
    void nonJsonReplyYieldsNoHypothesesButStillAudits() {
        var generator = new HypothesisGenerator(new FakeModel("I have no strong views today."), sink, 3, 400);
        assertTrue(generator.generate(context()).isEmpty());
        assertEquals(1, audited.size());
    }

    @Test
    void respectsMaxPerCycle() {
        String reply = """
                [
                  {"instrument":"AAPL","direction":"BUY","horizon":"SWING","conviction":"HIGH","thesis":"a","sources":[]},
                  {"instrument":"ES","direction":"SELL","horizon":"SWING","conviction":"HIGH","thesis":"b","sources":[]},
                  {"instrument":"AAPL","direction":"SELL","horizon":"SWING","conviction":"LOW","thesis":"c","sources":[]}
                ]
                """;
        var generator = new HypothesisGenerator(new FakeModel(reply), sink, 2, 400);
        assertEquals(2, generator.generate(context()).size());
    }
}
