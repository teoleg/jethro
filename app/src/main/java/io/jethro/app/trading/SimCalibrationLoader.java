package io.jethro.app.trading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jethro.trading.marketdata.sim.FactorModelConfig;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses {@code sim-calibration.json} (ADR-0026) into the market-data module's plain
 * {@link FactorModelConfig} — JSON stays an app concern so trading-core keeps zero
 * dependencies. Validates shape hard (factor matrices 4×4 symmetric-ish, transition rows
 * ~row-stochastic): a bad calibration must fail at startup, never mid-tape.
 */
final class SimCalibrationLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int FACTORS = 4;

    private SimCalibrationLoader() {
    }

    /** Loads from an explicit file when configured, else the checked-in classpath default. */
    static FactorModelConfig load(String overridePath) throws Exception {
        JsonNode root;
        if (overridePath != null && !overridePath.isBlank()) {
            root = MAPPER.readTree(Files.readString(Path.of(overridePath)));
        } else {
            try (InputStream in = SimCalibrationLoader.class.getResourceAsStream("/sim-calibration.json")) {
                if (in == null) {
                    throw new IllegalStateException("sim-calibration.json missing from classpath");
                }
                root = MAPPER.readTree(in);
            }
        }
        return parse(root);
    }

    private static FactorModelConfig parse(JsonNode root) {
        List<FactorModelConfig.InstrumentSpec> instruments = new ArrayList<>();
        for (JsonNode n : root.path("instruments")) {
            instruments.add(new FactorModelConfig.InstrumentSpec(
                    req(n, "id").asText(),
                    req(n, "annualVol").asDouble(),
                    req(n, "betaEquity").asDouble(),
                    req(n, "betaUsd").asDouble()));
        }
        if (instruments.isEmpty()) {
            throw new IllegalArgumentException("calibration has no instruments");
        }
        List<FactorModelConfig.RegimeSpec> regimes = new ArrayList<>();
        for (JsonNode n : root.path("regimes")) {
            regimes.add(new FactorModelConfig.RegimeSpec(
                    req(n, "name").asText(),
                    req(n, "equityDriftAnnual").asDouble(),
                    req(n, "ratesDriftBpPerDay").asDouble(),
                    req(n, "usdDriftAnnual").asDouble(),
                    req(n, "volMultiple").asDouble(),
                    matrix(req(n, "factorCorrelation"), FACTORS, "factorCorrelation of " + n.path("name").asText())));
        }
        if (regimes.isEmpty()) {
            throw new IllegalArgumentException("calibration has no regimes");
        }
        double[][] transition = matrix(req(root, "transitionPerDay"), regimes.size(), "transitionPerDay");
        for (int i = 0; i < transition.length; i++) {
            double sum = 0;
            for (double p : transition[i]) {
                if (p < 0) {
                    throw new IllegalArgumentException("transitionPerDay row " + i + " has a negative probability");
                }
                sum += p;
            }
            if (Math.abs(sum - 1.0) > 1e-6) {
                throw new IllegalArgumentException("transitionPerDay row " + i + " sums to " + sum + ", not 1");
            }
        }
        return new FactorModelConfig(
                req(root, "equityFactorVolAnnual").asDouble(),
                req(root, "usdFactorVolAnnual").asDouble(),
                req(root, "ratesLevelVolBpPerDay").asDouble(),
                req(root, "ratesSlopeVolBpPerDay").asDouble(),
                req(root, "tDegreesOfFreedom").asDouble(),
                instruments, regimes, transition);
    }

    private static double[][] matrix(JsonNode node, int size, String what) {
        if (!node.isArray() || node.size() != size) {
            throw new IllegalArgumentException(what + " must be a " + size + "x" + size + " matrix");
        }
        double[][] m = new double[size][size];
        for (int i = 0; i < size; i++) {
            JsonNode row = node.get(i);
            if (!row.isArray() || row.size() != size) {
                throw new IllegalArgumentException(what + " row " + i + " must have " + size + " entries");
            }
            for (int j = 0; j < size; j++) {
                m[i][j] = row.get(j).asDouble();
            }
        }
        return m;
    }

    private static JsonNode req(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            throw new IllegalArgumentException("calibration is missing required field '" + field + "'");
        }
        return v;
    }
}
