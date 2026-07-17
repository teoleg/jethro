package io.jethro.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.avro.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link SchemaRegistryClient} over the Confluent-compatible REST API that Redpanda serves
 * on :8081 (ADR-0012/0030). Registrations and lookups are cached; a network round-trip only
 * happens the first time a given schema/id is seen. Uses the JDK HTTP client and Avro's own
 * transitive Jackson — no third-party schema-registry dependency in the data-path module.
 */
public final class HttpSchemaRegistry implements SchemaRegistryClient {

    private static final Logger log = LoggerFactory.getLogger(HttpSchemaRegistry.class);
    private static final String CT = "application/vnd.schemaregistry.v1+json";

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final ConcurrentHashMap<String, Integer> idCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Schema> schemaCache = new ConcurrentHashMap<>();

    public HttpSchemaRegistry(String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        setGlobalCompatibility("BACKWARD"); // best-effort; Redpanda already defaults to BACKWARD
    }

    @Override
    public int idFor(String subject, Schema schema) {
        return idCache.computeIfAbsent(subject + " " + schema, key -> register(subject, schema));
    }

    @Override
    public Schema schemaById(int id) {
        return schemaCache.computeIfAbsent(id, this::fetch);
    }

    private int register(String subject, Schema schema) {
        String body = write(Map.of("schemaType", "AVRO", "schema", schema.toString()));
        HttpResponse<String> resp = send(HttpRequest.newBuilder(
                        URI.create(baseUrl + "/subjects/" + enc(subject) + "/versions"))
                .header("Content-Type", CT)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("schema-registry register " + subject + " -> HTTP "
                    + resp.statusCode() + ": " + resp.body());
        }
        return readInt(resp.body(), "id");
    }

    private Schema fetch(int id) {
        HttpResponse<String> resp = send(HttpRequest.newBuilder(
                URI.create(baseUrl + "/schemas/ids/" + id)).GET().build());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("schema-registry get id " + id + " -> HTTP "
                    + resp.statusCode() + ": " + resp.body());
        }
        return new Schema.Parser().parse(readString(resp.body(), "schema"));
    }

    private void setGlobalCompatibility(String level) {
        try {
            HttpResponse<String> resp = send(HttpRequest.newBuilder(URI.create(baseUrl + "/config"))
                    .header("Content-Type", CT)
                    .PUT(HttpRequest.BodyPublishers.ofString(write(Map.of("compatibility", level)))).build());
            if (resp.statusCode() / 100 != 2) {
                log.warn("could not set schema-registry compatibility={} (HTTP {}); leaving broker default",
                        level, resp.statusCode());
            }
        } catch (RuntimeException e) {
            log.warn("schema registry unreachable at {} while setting compatibility ({}); "
                    + "register/fetch will retry on first use", baseUrl, e.getMessage());
        }
    }

    private HttpResponse<String> send(HttpRequest req) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("schema registry I/O to " + baseUrl + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted talking to schema registry " + baseUrl, e);
        }
    }

    private String write(Map<String, ?> obj) {
        try {
            return json.writeValueAsString(obj);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("could not serialise schema-registry request", e);
        }
    }

    private int readInt(String body, String field) {
        try {
            return json.readTree(body).get(field).asInt();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("bad schema-registry response: " + body, e);
        }
    }

    private String readString(String body, String field) {
        try {
            return json.readTree(body).get(field).asText();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("bad schema-registry response: " + body, e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
