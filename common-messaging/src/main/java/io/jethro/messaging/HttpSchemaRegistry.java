package io.jethro.messaging;

import org.apache.avro.Schema;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link SchemaRegistryClient} over the Confluent-compatible REST API that Redpanda serves on
 * :8081 (ADR-0012/0030). Registrations and lookups are cached; a network round-trip only happens
 * the first time a given schema/id is seen.
 *
 * <p>Deliberately JDK + Avro only (enforced by ModuleBoundariesTest): the JDK HTTP client plus a
 * tiny hand-rolled JSON reader/writer for the two trivial payloads — no third-party HTTP, JSON,
 * or logging dependency leaks into the messaging contract module.
 */
public final class HttpSchemaRegistry implements SchemaRegistryClient {

    private static final System.Logger log = System.getLogger(HttpSchemaRegistry.class.getName());
    private static final String CT = "application/vnd.schemaregistry.v1+json";
    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*(-?\\d+)");

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
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
        String body = "{\"schemaType\":\"AVRO\",\"schema\":" + jsonString(schema.toString()) + "}";
        HttpResponse<String> resp = send(HttpRequest.newBuilder(
                        URI.create(baseUrl + "/subjects/" + enc(subject) + "/versions"))
                .header("Content-Type", CT)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("schema-registry register " + subject + " -> HTTP "
                    + resp.statusCode() + ": " + resp.body());
        }
        Matcher m = ID.matcher(resp.body());
        if (!m.find()) {
            throw new IllegalStateException("no id in schema-registry response: " + resp.body());
        }
        return Integer.parseInt(m.group(1));
    }

    private Schema fetch(int id) {
        HttpResponse<String> resp = send(HttpRequest.newBuilder(
                URI.create(baseUrl + "/schemas/ids/" + id)).GET().build());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("schema-registry get id " + id + " -> HTTP "
                    + resp.statusCode() + ": " + resp.body());
        }
        return new Schema.Parser().parse(jsonStringValue(resp.body(), "schema"));
    }

    private void setGlobalCompatibility(String level) {
        try {
            HttpResponse<String> resp = send(HttpRequest.newBuilder(URI.create(baseUrl + "/config"))
                    .header("Content-Type", CT)
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"compatibility\":\"" + level + "\"}")).build());
            if (resp.statusCode() / 100 != 2) {
                log.log(System.Logger.Level.WARNING, "could not set schema-registry compatibility="
                        + level + " (HTTP " + resp.statusCode() + "); leaving broker default");
            }
        } catch (RuntimeException e) {
            log.log(System.Logger.Level.WARNING, "schema registry unreachable at " + baseUrl
                    + " while setting compatibility (" + e.getMessage() + "); register/fetch retries on first use");
        }
    }

    private HttpResponse<String> send(HttpRequest req) {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("schema registry I/O to " + baseUrl + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted talking to schema registry " + baseUrl, e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Quote and escape a string as a JSON string literal (JDK-only, no JSON library). */
    private static String jsonString(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }

    /** Extract and unescape the JSON string value for {@code key} from a small JSON object. */
    private static String jsonStringValue(String body, String key) {
        String needle = "\"" + key + "\"";
        int k = body.indexOf(needle);
        if (k < 0) {
            throw new IllegalStateException("no \"" + key + "\" in schema-registry response: " + body);
        }
        int i = body.indexOf(':', k + needle.length());
        while (i >= 0 && i + 1 < body.length() && Character.isWhitespace(body.charAt(i + 1))) {
            i++;
        }
        if (i < 0 || i + 1 >= body.length() || body.charAt(i + 1) != '"') {
            throw new IllegalStateException("\"" + key + "\" is not a string in: " + body);
        }
        StringBuilder out = new StringBuilder();
        for (int p = i + 2; p < body.length(); p++) {
            char c = body.charAt(p);
            if (c == '"') {
                return out.toString();
            }
            if (c == '\\') {
                char n = body.charAt(++p);
                switch (n) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> {
                        out.append((char) Integer.parseInt(body.substring(p + 1, p + 5), 16));
                        p += 4;
                    }
                    default -> out.append(n);
                }
            } else {
                out.append(c);
            }
        }
        throw new IllegalStateException("unterminated \"" + key + "\" string in: " + body);
    }
}
