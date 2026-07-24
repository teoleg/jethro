package io.jethro.app.trading;

import io.jethro.app.trading.HistoryClient.History;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing Tiingo's prices JSON: equities prefer adjClose + carry volume; FX uses close, no volume. */
class TiingoHistoryClientTest {

    @Test
    void parsesEquityRowsUsingAdjustedClose() {
        String json = """
                [ {"date":"2024-01-02T00:00:00.000Z","close":190.0,"adjClose":189.5,"volume":1000000},
                  {"date":"2024-01-03T00:00:00.000Z","close":191.0,"adjClose":190.4,"volume":1200000} ]""";
        History h = TiingoHistoryClient.parse(json, false).orElseThrow();
        assertEquals(2, h.closes().length);
        assertEquals(189.5, h.closes()[0], 1e-9); // adjClose preferred for equities
        assertEquals(1_200_000L, h.volumes()[1]);
        assertEquals(LocalDate.of(2024, 1, 2).toEpochDay(), h.epochDays()[0]);
    }

    @Test
    void parsesFxRowsUsingCloseWithZeroVolume() {
        String json = """
                [ {"date":"2024-01-02T00:00:00.000Z","close":1.0935},
                  {"date":"2024-01-03T00:00:00.000Z","close":1.0951} ]""";
        History h = TiingoHistoryClient.parse(json, true).orElseThrow();
        assertEquals(1.0935, h.closes()[0], 1e-9);
        assertEquals(0L, h.volumes()[0]);
    }

    @Test
    void skipsNonPositiveOrMalformedRows() {
        String json = """
                [ {"date":"2024-01-02T00:00:00.000Z","close":0.0},
                  {"date":"2024-01-03T00:00:00.000Z","close":191.0,"adjClose":190.4,"volume":10},
                  {"date":"bad"} ]""";
        // only one usable row → < 2 → empty (a single point can't form a return).
        assertTrue(TiingoHistoryClient.parse(json, false).isEmpty());
    }

    @Test
    void emptyOrNonArrayIsEmpty() {
        assertEquals(Optional.empty(), TiingoHistoryClient.parse("[]", false));
        assertEquals(Optional.empty(), TiingoHistoryClient.parse("not json", false));
        assertEquals(Optional.empty(), TiingoHistoryClient.parse("{\"detail\":\"Not found\"}", false));
    }
}
