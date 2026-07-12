package io.jethro.app.chat;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Keyword-mode intent parsing (model absent) — deterministic, the fallback that always works. */
class ChatIntentParserTest {

    private final ChatIntentParser parser = new ChatIntentParser(
            null, () -> Set.of("ALPHA", "MACRO", "FIRM"), () -> Set.of("AAPL", "ES", "EURUSD"));

    @Test
    void classifiesPnlWithBookSlot() {
        ChatIntent i = parser.parse("what's my pnl for ALPHA?");
        assertEquals(ChatIntent.Kind.PNL, i.kind());
        assertEquals("ALPHA", i.book());
        assertNull(i.instrument());
    }

    @Test
    void slotMatchingIsCaseInsensitive() {
        assertEquals("ALPHA", parser.parse("pnl alpha").book());
        assertEquals("AAPL", parser.parse("exposure to aapl").instrument());
    }

    @Test
    void classifiesEachIntent() {
        assertEquals(ChatIntent.Kind.EXPOSURE, parser.parse("what's my risk on ES").kind());
        assertEquals(ChatIntent.Kind.POSITIONS, parser.parse("show positions in MACRO").kind());
        assertEquals(ChatIntent.Kind.LIMITS, parser.parse("any limits breached?").kind());
        assertEquals(ChatIntent.Kind.SIGNALS, parser.parse("what is the strategy doing").kind());
        assertEquals(ChatIntent.Kind.HELP, parser.parse("hello there").kind());
    }

    @Test
    void unknownSymbolsAreNotFilled() {
        ChatIntent i = parser.parse("pnl for ZZZZ");
        assertEquals(ChatIntent.Kind.PNL, i.kind());
        assertNull(i.book());        // ZZZZ isn't a known book
        assertNull(i.instrument());
    }

    @Test
    void blankQuestionIsHelp() {
        assertEquals(ChatIntent.Kind.HELP, parser.parse("   ").kind());
    }
}
