package io.jethro.app.risk;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The firm circuit breaker's switch (ADR-0027): when tripped, ALL auto-execution stops —
 * the momentum strategy and the AI autonomy both check it before submitting; manual orders
 * remain allowed (an operator de-risking must never be locked out). Deterministic code only
 * trips it; only an operator resets it. In-memory on purpose: after a restart the breaker
 * monitor re-evaluates the persisted equity peak and re-trips within one cycle if the
 * condition still holds.
 */
public final class TradingHaltSwitch {

    /** Why and when the halt tripped. */
    public record Halt(String reason, long trippedAtMillis) {
    }

    private final AtomicReference<Halt> halt = new AtomicReference<>();

    public boolean isHalted() {
        return halt.get() != null;
    }

    public Halt current() {
        return halt.get();
    }

    /** Trips the breaker (idempotent — the first reason stands until reset). */
    public void trip(String reason) {
        halt.compareAndSet(null, new Halt(reason, System.currentTimeMillis()));
    }

    /** Operator reset. @return whether a halt was actually cleared. */
    public boolean reset() {
        return halt.getAndSet(null) != null;
    }
}
