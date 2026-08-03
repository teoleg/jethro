package io.muniworld.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end: POST a couple of securities through /api/muni/index, then confirm the LMDB-backed search
 * endpoints (issuer prefix, maturity/coupon range, geography prefix, point lookup) return them. Uses its
 * own LMDB path so it can't collide with other tests' data.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "muni.lmdb.path=build/muni-lmdb-webtest")
class MuniSearchControllerTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private void index(String cusip, String maturity, double coupon, String fips) {
        rest.postForEntity(url("/api/muni/index"),
                Map.of("cusip", cusip, "maturity", maturity, "coupon", coupon, "geoFips", fips, "value", cusip),
                Map.class);
    }

    @Test
    void indexThenSearch() {
        // Distinctive CUSIP-6 (ZY990-) so residual data from other runs can't affect the assertions.
        index("ZY9900AA1", "2030-06-01", 4.0, "3403900000");
        index("ZY9900AB9", "2035-06-01", 5.0, "3403900000");
        index("ZY9911AA0", "2032-06-01", 3.5, "3603900000"); // different issuer + geography

        String[] issuer = rest.getForObject(url("/api/muni/search/issuer/ZY9900"), String[].class);
        assertEquals(2, issuer.length, "two CUSIPs under issuer ZY9900");
        assertEquals("ZY9900AA1", issuer[0]);
        assertEquals("ZY9900AB9", issuer[1]);

        String[] mat = rest.getForObject(
                url("/api/muni/search/maturity?from=2031-01-01&to=2036-01-01"), String[].class);
        assertTrue(java.util.List.of(mat).containsAll(java.util.List.of("ZY9911AA0", "ZY9900AB9")));
        assertTrue(!java.util.List.of(mat).contains("ZY9900AA1"), "2030 excluded");

        String[] coupon = rest.getForObject(url("/api/muni/search/coupon?lo=3.9&hi=4.1"), String[].class);
        assertTrue(java.util.List.of(coupon).contains("ZY9900AA1"), "4.0% in band");
        assertTrue(!java.util.List.of(coupon).contains("ZY9900AB9"), "5.0% out of band");

        String[] geo = rest.getForObject(url("/api/muni/search/geo/36"), String[].class);
        assertTrue(java.util.List.of(geo).contains("ZY9911AA0"), "NY issuer by state FIPS 36");

        ResponseEntity<Map> point = rest.getForEntity(url("/api/muni/search/cusip/ZY9900AB9"), Map.class);
        assertEquals(Boolean.TRUE, point.getBody().get("found"));
        assertEquals("ZY9900AB9", point.getBody().get("value"));
    }
}
