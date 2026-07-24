package io.jethro.app.training;

/**
 * A deliberately SIMPLE model for the ADR-0053 learned advisory signal: multinomial (3-class) logistic
 * regression with L2, trained by deterministic full-batch gradient descent. ADR-0053 mandates a simple,
 * interpretable, cost-honest baseline first — gradient-boosted trees / logistic regression on engineered
 * features — with deep sequence models deferred behind measured evidence of durable edge. Logistic
 * regression is the most transparent of those: linear log-odds, calibrated class probabilities, and it
 * cannot memorise the way a deep net does, which is exactly what you want when the honest expectation is
 * a 51–53% net hit-rate, not 70%.
 *
 * <p>Features are standardised internally on the TRAINING data only (mean/σ stored, applied at predict)
 * so a walk-forward fold can never leak test-set scale into training. Deterministic (zero init,
 * full-batch) so a run is reproducible and exactly testable. Pure analytics — the class probabilities
 * are an advisory signal, NEVER a number that sizes a position or feeds PnL/risk (ADR-0016 / invariant
 * 1 / 7); they only enter the system behind the deterministic OOS gate (ADR-0049).
 */
public final class LogisticSignalModel {

    /** Class indices — must match {@link #labelIndex}. */
    public static final int UP = 0, DOWN = 1, FLAT = 2, CLASSES = 3;

    private final int iterations;
    private final double learningRate;
    private final double l2;

    private double[] mean;
    private double[] std;
    private double[][] w; // [CLASSES][features]
    private double[] b;   // [CLASSES]
    private int d;

    public LogisticSignalModel(int iterations, double learningRate, double l2) {
        this.iterations = Math.max(1, iterations);
        this.learningRate = learningRate;
        this.l2 = Math.max(0, l2);
    }

    /** Maps a builder label to a class index (UP/DOWN/FLAT), keeping the encoding in one place. */
    public static int labelIndex(FeatureBuilder.Label label) {
        return switch (label) {
            case UP -> UP;
            case DOWN -> DOWN;
            case FLAT -> FLAT;
        };
    }

    /** Fits on standardised features. {@code y[i]} in [0,CLASSES). Full-batch, deterministic. */
    public void fit(double[][] x, int[] y) {
        int n = x.length;
        d = n == 0 ? 0 : x[0].length;
        mean = new double[d];
        std = new double[d];
        for (double[] row : x) {
            for (int j = 0; j < d; j++) {
                mean[j] += row[j];
            }
        }
        for (int j = 0; j < d; j++) {
            mean[j] /= Math.max(1, n);
        }
        for (double[] row : x) {
            for (int j = 0; j < d; j++) {
                double dv = row[j] - mean[j];
                std[j] += dv * dv;
            }
        }
        for (int j = 0; j < d; j++) {
            std[j] = Math.sqrt(std[j] / Math.max(1, n));
            if (!(std[j] > 0)) {
                std[j] = 1.0; // a constant feature standardises to 0; guard the divide
            }
        }
        double[][] xs = new double[n][d];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < d; j++) {
                xs[i][j] = (x[i][j] - mean[j]) / std[j];
            }
        }
        w = new double[CLASSES][d];
        b = new double[CLASSES];
        double[] p = new double[CLASSES];
        for (int it = 0; it < iterations; it++) {
            double[][] gw = new double[CLASSES][d];
            double[] gb = new double[CLASSES];
            for (int i = 0; i < n; i++) {
                softmax(xs[i], p);
                for (int c = 0; c < CLASSES; c++) {
                    double err = p[c] - (y[i] == c ? 1.0 : 0.0);
                    gb[c] += err;
                    double[] gwc = gw[c];
                    double[] xsi = xs[i];
                    for (int j = 0; j < d; j++) {
                        gwc[j] += err * xsi[j];
                    }
                }
            }
            double inv = 1.0 / Math.max(1, n);
            for (int c = 0; c < CLASSES; c++) {
                b[c] -= learningRate * gb[c] * inv;
                for (int j = 0; j < d; j++) {
                    w[c][j] -= learningRate * (gw[c][j] * inv + l2 * w[c][j]);
                }
            }
        }
    }

    /** Class probabilities for a RAW (un-standardised) feature vector. */
    public double[] predict(double[] x) {
        double[] xs = new double[d];
        for (int j = 0; j < d; j++) {
            xs[j] = (x[j] - mean[j]) / std[j];
        }
        double[] p = new double[CLASSES];
        softmax(xs, p);
        return p;
    }

    /** argmax class for a raw feature vector (UP/DOWN/FLAT). */
    public int classify(double[] x) {
        double[] p = predict(x);
        int best = 0;
        for (int c = 1; c < CLASSES; c++) {
            if (p[c] > p[best]) {
                best = c;
            }
        }
        return best;
    }

    /** Softmax of the class logits for an already-standardised row, into {@code out} (max-shift stable). */
    private void softmax(double[] xs, double[] out) {
        double max = Double.NEGATIVE_INFINITY;
        for (int c = 0; c < CLASSES; c++) {
            double z = b[c];
            double[] wc = w[c];
            for (int j = 0; j < d; j++) {
                z += wc[j] * xs[j];
            }
            out[c] = z;
            if (z > max) {
                max = z;
            }
        }
        double sum = 0;
        for (int c = 0; c < CLASSES; c++) {
            out[c] = Math.exp(out[c] - max);
            sum += out[c];
        }
        for (int c = 0; c < CLASSES; c++) {
            out[c] /= sum;
        }
    }
}
