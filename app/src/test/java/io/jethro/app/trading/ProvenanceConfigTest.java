package io.jethro.app.trading;

import io.jethro.messaging.FeedMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProvenanceConfigTest {

    @Test
    void mapsProviderToFeedMode() {
        assertEquals(FeedMode.SIM, ProvenanceConfig.feedModeFor("sim"));
        assertEquals(FeedMode.SIM, ProvenanceConfig.feedModeFor(null));
        assertEquals(FeedMode.LIVE, ProvenanceConfig.feedModeFor("yahoo"));
        assertEquals(FeedMode.LIVE, ProvenanceConfig.feedModeFor("finnhub"));
        assertEquals(FeedMode.REPLAY, ProvenanceConfig.feedModeFor("replay"));
    }
}
