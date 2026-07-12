package io.jethro.trading.algo.agent;

import io.jethro.messaging.AiDecision;

/**
 * Where AiDecision audit events go (invariant 7). v0: logging + in-memory buffer in
 * the app; replaced by the ai.decisions Kafka producer when broker wiring lands
 * (ADR-0016 follow-up). The event shape is exercised from day one either way.
 */
@FunctionalInterface
public interface DecisionSink {

    void record(AiDecision decision);
}
