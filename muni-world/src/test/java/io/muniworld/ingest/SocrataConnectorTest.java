package io.muniworld.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline proof of the ADR-0004 connector + ADR-0005 landing: with a stub {@link HttpFetcher} returning
 * canned Socrata JSON, the connector builds the right resource URL, lands the bytes with a correct SHA-256,
 * and parses the rows. The live fetch (real HTTP) runs on a networked host; the logic is proven here.
 */
class SocrataConnectorTest {

    @Test
    void buildsUrlLandsAndParses() {
        byte[] json = "[{\"cusip\":\"64972RAA0\",\"coupon\":\"5.0\"},{\"cusip\":\"64972RAB8\",\"coupon\":\"4.0\"}]"
                .getBytes(StandardCharsets.UTF_8);
        HttpFetcher stub = (sourceId, url) -> RawArtifact.of(sourceId, url, "application/json", json);

        SocrataConnector c = new SocrataConnector("s076", "data.cityofnewyork.us", "abcd-1234", 5000, stub);
        assertTrue(c.url().contains("data.cityofnewyork.us/resource/abcd-1234.json"));
        assertTrue(c.url().contains("$limit=5000"));

        List<RawArtifact> arts = c.fetch();
        assertEquals(1, arts.size());
        RawArtifact a = arts.get(0);
        assertEquals(RawArtifact.sha256(json), a.sha256(), "provenance hash matches the bytes");
        assertEquals(json.length, a.size());
        assertEquals("s076", a.sourceId());

        List<Map<String, Object>> rows = SocrataConnector.parse(a, new ObjectMapper());
        assertEquals(2, rows.size());
        assertEquals("64972RAA0", rows.get(0).get("cusip"));
    }
}
