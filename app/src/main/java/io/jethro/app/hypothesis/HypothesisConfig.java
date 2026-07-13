package io.jethro.app.hypothesis;

import io.jethro.app.ai.BufferingDecisionSink;
import io.jethro.app.kafka.KafkaEventPublisher;
import io.jethro.app.strategy.StrategyProperties;
import io.jethro.app.trading.TradingCoreLifecycle;
import io.jethro.app.trading.TradingCoreProperties;
import io.jethro.domain.Instrument;
import io.jethro.messaging.Topics;
import io.jethro.order.OrderService;
import io.jethro.refdata.RefDataRepository;
import io.jethro.trading.algo.agent.DecisionSink;
import io.jethro.trading.algo.hypothesis.HypothesisGenerator;
import io.jethro.trading.algo.inference.ModelInferenceClient;
import io.jethro.trading.riskpnl.InstrumentRefSource;
import io.jethro.trading.riskpnl.PreTradeGuardrail;
import io.jethro.trading.riskpnl.RiskProjection;
import io.jethro.uigateway.AttentionFeed;
import io.jethro.uigateway.SseBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM hypothesis layer wiring (ADR-0022). Gated on jethro.hypothesis.enabled. The evaluator
 * and REST surface are always present; the generator lifecycle needs a model, so it's also
 * gated on jethro.ai.enabled — without one, the deterministic pieces stand and the feed just
 * has no hypotheses (the market path never depends on the model, invariant 7).
 */
@Configuration
@EnableConfigurationProperties(HypothesisProperties.class)
@ConditionalOnProperty(prefix = "jethro.hypothesis", name = "enabled", havingValue = "true", matchIfMissing = true)
public class HypothesisConfig {

    private static final Logger log = LoggerFactory.getLogger(HypothesisConfig.class);

    @Bean
    HypothesisEvaluator hypothesisEvaluator(InstrumentRefSource refs, PreTradeGuardrail guardrail,
                                            StrategyProperties sizing) {
        return new HypothesisEvaluator(refs, guardrail, sizing);
    }

    /**
     * The qualitative feed the LLM reads. Real Finnhub news (general market + per-company,
     * ADR-0024) whenever a Finnhub token is set — independent of the price provider, so a
     * Yahoo/sim price run can still get real headlines; the seedable sim fixtures otherwise
     * (and always in CI, which has no token). Company news needs the 'finnhub' symbology
     * (invariant 2); general-market news works even with none mapped.
     */
    @Bean
    NarrativeFeed narrativeFeed(HypothesisProperties props, TradingCoreProperties trading,
                                io.jethro.app.trading.FinnhubRateLimiter rateLimiter,
                                ObjectProvider<RefDataRepository> refData) {
        String token = trading.finnhubTokenOrEmpty();
        if (!token.isEmpty()) {
            Map<String, String> names = finnhubNewsSymbols(refData.getIfAvailable());
            log.warn("NARRATIVE: real Finnhub news feeding the hypothesis layer — general market + "
                    + "{} company name(s), refresh ≥{}s (ADR-0024).", names.size(),
                    props.narrativeRefreshSecondsOrDefault());
            var client = new FinnhubNewsClient(token, Duration.ofSeconds(10), rateLimiter);
            return new FinnhubNarrativeFeed(client, names, props.narrativeRefreshSecondsOrDefault() * 1_000);
        }
        return new SimNarrativeFeed(props.narrativeSeedOrDefault());
    }

    /** internal instrumentId → Finnhub symbol for the covered names (invariant 2 — symbols are
     *  used only to query news, never surfaced). Empty when refdata is off/unseeded. */
    private static Map<String, String> finnhubNewsSymbols(RefDataRepository refData) {
        Map<String, String> map = new LinkedHashMap<>();
        if (refData == null) {
            return map;
        }
        for (Instrument i : refData.findAllInstruments()) {
            String symbol = i.symbology().get("finnhub");
            if (symbol != null) {
                map.put(i.id().value(), symbol);
            }
        }
        return map;
    }

    @Bean
    @ConditionalOnProperty(prefix = "jethro.ai", name = "enabled", havingValue = "true", matchIfMissing = true)
    HypothesisLifecycle hypothesisLifecycle(ModelInferenceClient client, BufferingDecisionSink buffer,
                                            ObjectProvider<KafkaEventPublisher> kafka, HypothesisProperties props,
                                            HypothesisEvaluator evaluator, NarrativeFeed narrativeFeed,
                                            io.jethro.app.backtest.BacktestService backtest,
                                            TradingCoreLifecycle tradingCore, RiskProjection risk,
                                            InstrumentRefSource refs, AttentionFeed feed, SseBroadcaster sse,
                                            ObjectProvider<OrderService> orderService) {
        // Same composite sink as the commentator: in-memory buffer + ai.decisions topic when
        // the broker is wired — every hypothesis-generation run is an audited AiDecision.
        DecisionSink sink = decision -> {
            buffer.record(decision);
            var publisher = kafka.getIfAvailable();
            if (publisher != null) {
                publisher.publish(Topics.AI_DECISIONS, decision.getDecisionId(), decision);
            }
        };
        var generator = new HypothesisGenerator(client, sink,
                props.maxPerCycleOrDefault(), props.maxOutputTokensOrDefault());
        // OrderService present only when persistence is on; without it (or with autonomy off)
        // the layer is human-in-loop even if autonomy is configured on.
        return new HypothesisLifecycle(generator, evaluator, narrativeFeed, backtest,
                tradingCore, risk, refs, feed, sse, props, orderService.getIfAvailable());
    }

    @Bean
    HypothesisController hypothesisController(ObjectProvider<HypothesisLifecycle> lifecycle) {
        return new HypothesisController(lifecycle);
    }
}
