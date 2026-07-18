package io.jethro.app.trading;

import io.jethro.messaging.FeedMode;
import io.jethro.messaging.Provenance;
import io.jethro.refdata.RefDataRepository;
import io.jethro.trading.marketdata.sim.SimControl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The sim control REST surface is hard-gated to {@code feedMode == SIM} (ADR-0029/0031): a
 * dial must be reachable in sim and 403 in live/replay so it can never perturb a real tape.
 */
class SimControlControllerTest {

    @AfterEach
    void restoreProvenance() {
        Provenance.configure(FeedMode.SIM, ""); // tests default to SIM
    }

    @SuppressWarnings("unchecked")
    private static SimControlController controller(SimControl control) {
        TradingCoreLifecycle core = mock(TradingCoreLifecycle.class);
        when(core.simControl()).thenReturn(control);
        ObjectProvider<TradingCoreLifecycle> coreProvider = mock(ObjectProvider.class);
        when(coreProvider.getIfAvailable()).thenReturn(core);
        ObjectProvider<RefDataRepository> refProvider = mock(ObjectProvider.class);
        when(refProvider.getIfAvailable()).thenReturn(null);
        return new SimControlController(coreProvider, refProvider);
    }

    @Test
    void liveModeRejectsEveryDial() {
        Provenance.configure(FeedMode.LIVE, "epoch-live");
        var controller = controller(new SimControl(1, List.of("ES")));
        var ex = assertThrows(ResponseStatusException.class, controller::state);
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertThrows(ResponseStatusException.class,
                () -> controller.pause(new SimControlController.PauseRequest(true)));
    }

    @Test
    void simModeWithoutCorrelatedEngineIsAlsoForbidden() {
        Provenance.configure(FeedMode.SIM, "epoch-sim");
        var controller = controller(null); // legacy/non-correlated sim: no control handle
        var ex = assertThrows(ResponseStatusException.class, controller::state);
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void simModeExposesAndMutatesDials() {
        Provenance.configure(FeedMode.SIM, "epoch-sim");
        var control = new SimControl(1, List.of("ES", "AAPL"));
        var controller = controller(control);

        var state = controller.state();
        assertTrue(state.enabled());
        assertEquals(2, state.instruments().size());
        assertEquals("AUTO", state.regimeOverride());

        controller.pause(new SimControlController.PauseRequest(true));
        assertTrue(control.paused());

        controller.drift("ES", new SimControlController.ValueRequest(0.001));
        assertEquals(0.001, control.driftBiasFor("ES"), 1e-9);

        var after = controller.speed(new SimControlController.SpeedRequest(3.0));
        assertEquals(3.0, after.speedMultiplier(), 1e-9);
        assertTrue(after.active(), "a moved dial must flag the session as panel-driven");
    }

    @Test
    void unknownInstrumentIs404() {
        Provenance.configure(FeedMode.SIM, "epoch-sim");
        var controller = controller(new SimControl(1, List.of("ES")));
        var ex = assertThrows(ResponseStatusException.class,
                () -> controller.nudge("NOPE", new SimControlController.ValueRequest(0.01)));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void newsShockRejectedOutsideSim() {
        Provenance.configure(FeedMode.LIVE, "epoch-live");
        var controller = controller(new SimControl(1, List.of("ES")));
        var ex = assertThrows(ResponseStatusException.class,
                () -> controller.news(new SimControlController.NewsRequest("ES", "BULL", null)));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void newsShockUnknownInstrumentIs404() {
        Provenance.configure(FeedMode.SIM, "epoch-sim");
        var controller = controller(new SimControl(1, List.of("ES")));
        var ex = assertThrows(ResponseStatusException.class,
                () -> controller.news(new SimControlController.NewsRequest("NOPE", "BULL", null)));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void newsShockUnknownDirectionIs400() {
        Provenance.configure(FeedMode.SIM, "epoch-sim");
        var controller = controller(new SimControl(1, List.of("ES")));
        var ex = assertThrows(ResponseStatusException.class,
                () -> controller.news(new SimControlController.NewsRequest("ES", "sideways", null)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void newsShockFiresThroughLifecycle() {
        Provenance.configure(FeedMode.SIM, "epoch-sim");
        var control = new SimControl(1, List.of("ES", "AAPL"));
        TradingCoreLifecycle core = mock(TradingCoreLifecycle.class);
        when(core.simControl()).thenReturn(control);
        when(core.fireSimNews("AAPL", -1, 0.02)).thenReturn(true);
        ObjectProvider<TradingCoreLifecycle> coreProvider = mock(ObjectProvider.class);
        when(coreProvider.getIfAvailable()).thenReturn(core);
        ObjectProvider<RefDataRepository> refProvider = mock(ObjectProvider.class);
        when(refProvider.getIfAvailable()).thenReturn(null);
        var controller = new SimControlController(coreProvider, refProvider);

        var state = controller.news(new SimControlController.NewsRequest("AAPL", "BEAR", 0.02));
        assertTrue(state.enabled(), "a fired shock returns the fresh control state");
        org.mockito.Mockito.verify(core).fireSimNews("AAPL", -1, 0.02);
    }
}
