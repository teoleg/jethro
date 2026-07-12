package io.jethro.app.ai;

import io.jethro.messaging.AiDecision;
import io.jethro.trading.algo.agent.DecisionSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * v0 DecisionSink (ADR-0016): logs every AiDecision and buffers the most recent ones
 * in memory — the seed of the attention feed the UI will consume. Replaced by the
 * ai.decisions Kafka producer when broker wiring lands; the buffer stays as the
 * UI-side cache.
 */
public final class BufferingDecisionSink implements DecisionSink {

    private static final Logger log = LoggerFactory.getLogger(BufferingDecisionSink.class);
    private static final int MAX_BUFFERED = 50;

    private final ArrayDeque<AiDecision> recent = new ArrayDeque<>(MAX_BUFFERED);

    @Override
    public synchronized void record(AiDecision decision) {
        if (recent.size() == MAX_BUFFERED) {
            recent.removeFirst();
        }
        recent.addLast(decision);
        log.info("ai.decision model={} latencyMs={} tokens={}/{} contextHash={}",
                decision.getModelId(), decision.getLatencyMillis(),
                decision.getInputTokens(), decision.getOutputTokens(),
                decision.getContextHash().substring(0, 12));
    }

    /** Newest-last snapshot of recent decisions. */
    public synchronized List<AiDecision> recent() {
        return new ArrayList<>(recent);
    }
}
