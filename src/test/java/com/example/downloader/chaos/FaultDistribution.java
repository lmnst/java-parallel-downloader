package com.example.downloader.chaos;

import java.util.Random;

/**
 * Probability distribution over the per-GET faults the chaos adapter can
 * apply. A {@link Fault} is sampled by drawing a uniform double and
 * walking the cumulative weights. Weights need not sum to 1.0 — they are
 * normalized internally.
 */
public final class FaultDistribution {

    private final double[] cumulative;
    private final Fault[] faults;

    private FaultDistribution(double[] cumulative, Fault[] faults) {
        this.cumulative = cumulative;
        this.faults = faults;
    }

    public Fault pick(Random rng) {
        double r = rng.nextDouble() * cumulative[cumulative.length - 1];
        for (int i = 0; i < cumulative.length; i++) {
            if (r < cumulative[i]) return faults[i];
        }
        return faults[faults.length - 1];
    }

    public static Builder builder() { return new Builder(); }

    /**
     * Default chaos distribution: ~50% pass-through, every fault class
     * represented at small but non-trivial probability. Tuned so that a
     * chunk's per-attempt fault rate is high enough to exercise retries
     * but low enough that downloads still succeed often enough to verify
     * the success branch of the invariant.
     */
    public static FaultDistribution standard() {
        return builder()
                .weight(Fault.PASS_THROUGH, 50)
                .weight(Fault.HTTP_408, 4)
                .weight(Fault.HTTP_429, 4)
                .weight(Fault.HTTP_500, 4)
                .weight(Fault.HTTP_502, 3)
                .weight(Fault.HTTP_503, 4)
                .weight(Fault.HTTP_504, 3)
                .weight(Fault.HTTP_200_ON_RANGED, 4)
                .weight(Fault.TRUNCATED_BODY, 5)
                .weight(Fault.MALFORMED_CONTENT_RANGE, 3)
                .weight(Fault.MISMATCHED_CONTENT_RANGE, 3)
                .weight(Fault.IO_MID_BODY, 5)
                .weight(Fault.SLOWLORIS, 3)
                .weight(Fault.CHUNK_DELAY_JITTER, 5)
                .build();
    }

    public static final class Builder {
        private final java.util.LinkedHashMap<Fault, Double> weights = new java.util.LinkedHashMap<>();

        public Builder weight(Fault f, double w) {
            if (w < 0) throw new IllegalArgumentException("weight < 0");
            weights.put(f, w);
            return this;
        }

        public FaultDistribution build() {
            if (weights.isEmpty()) throw new IllegalStateException("no weights configured");
            Fault[] faults = weights.keySet().toArray(Fault[]::new);
            double[] cum = new double[faults.length];
            double running = 0;
            for (int i = 0; i < faults.length; i++) {
                running += weights.get(faults[i]);
                cum[i] = running;
            }
            if (running == 0) throw new IllegalStateException("all weights zero");
            return new FaultDistribution(cum, faults);
        }
    }
}
