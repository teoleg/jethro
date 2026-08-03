package io.muniworld.web;

import io.muniworld.domain.PriceQuote;
import io.muniworld.price.MuniPriceStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Current-price ingest/read (ADR-0015). {@code POST /api/muni/prices} stores real market quotes (e.g. MSRB
 * trade prints fetched on the Pi) so a terms-only bond's economics compute; {@code GET /api/muni/prices/{cusip}}
 * reads the latest. Prices are a SEPARATE, provenance-tracked source from the OS terms — a quote carries its
 * own as-of date and source, and nothing is invented: no quote ⇒ blank economics.
 */
@RestController
public final class MuniPriceController {

    private final MuniPriceStore prices;

    public MuniPriceController(MuniPriceStore prices) {
        this.prices = prices;
    }

    /** Store market quotes (latest-wins per CUSIP). Body: a list of {cusip, price, asOf, source}. */
    @PostMapping("/api/muni/prices")
    public Map<String, Object> put(@RequestBody List<PriceQuote> quotes) {
        quotes.forEach(prices::put);
        return Map.of("stored", quotes.size());
    }

    /** The latest quote for a CUSIP, or 404-ish empty. */
    @GetMapping("/api/muni/prices/{cusip}")
    public PriceQuote get(@PathVariable String cusip) {
        return prices.get(cusip).orElse(null);
    }
}
