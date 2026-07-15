package io.jethro.order;

import io.jethro.domain.Fill;

import java.math.BigDecimal;

/**
 * Records execution quality for a fill against its arrival price (ADR-0025 TCA). A port so
 * {@link OrderService} stays persistence-shape-agnostic; the app binds it to
 * {@link ExecutionQualityRepository}. Failures must never break the fill path — recording
 * is measurement, the fill already happened.
 */
public interface TcaRecorder {

    void record(Fill fill, BigDecimal arrivalPrice);

    /** No TCA (tests / persistence off). */
    TcaRecorder NONE = (fill, arrivalPrice) -> {
    };
}
