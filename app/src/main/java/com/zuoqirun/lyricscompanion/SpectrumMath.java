package com.zuoqirun.lyricscompanion;

/** Pure spectrum conversion and animation state, kept independent from Android audio APIs. */
final class SpectrumMath {
    static final int BAND_COUNT = 32;
    private static final float LOWEST_FREQUENCY_HZ = 60f;
    private static final float HIGHEST_FREQUENCY_HZ = 16_000f;

    private SpectrumMath() { }

    static int bandForFrequency(float frequencyHz, float sampleRateHz) {
        float highest = Math.min(HIGHEST_FREQUENCY_HZ, sampleRateHz * 0.48f);
        if (highest <= LOWEST_FREQUENCY_HZ || frequencyHz < LOWEST_FREQUENCY_HZ) return -1;
        if (frequencyHz >= highest) return BAND_COUNT - 1;
        float normalized = (float) (Math.log(frequencyHz / LOWEST_FREQUENCY_HZ)
                / Math.log(highest / LOWEST_FREQUENCY_HZ));
        return Math.max(0, Math.min(BAND_COUNT - 1, (int) (normalized * BAND_COUNT)));
    }

    static final class Analyzer {
        private final float[] smoothed = new float[BAND_COUNT];
        private final float[] sums = new float[BAND_COUNT];
        private final int[] counts = new int[BAND_COUNT];
        private final float[] magnitudes = new float[BAND_COUNT];
        private float referenceLevel;

        float[] process(byte[] fft, int samplingRateMilliHz) {
            java.util.Arrays.fill(sums, 0f);
            java.util.Arrays.fill(counts, 0);
            float sampleRateHz = samplingRateMilliHz / 1000f;
            if (fft == null || fft.length < 4 || sampleRateHz <= 0f) return new float[BAND_COUNT];
            int complexBins = fft.length / 2;
            for (int bin = 1; bin < complexBins; bin++) {
                int index = bin * 2;
                if (index + 1 >= fft.length) break;
                int band = bandForFrequency(bin * sampleRateHz / fft.length, sampleRateHz);
                if (band < 0) continue;
                float real = fft[index] / 128f;
                float imaginary = fft[index + 1] / 128f;
                sums[band] += real * real + imaginary * imaginary;
                counts[band]++;
            }
            java.util.Arrays.fill(magnitudes, 0f);
            for (int i = 0; i < BAND_COUNT; i++) {
                if (counts[i] > 0) magnitudes[i] = (float) Math.sqrt(sums[i] / counts[i]);
            }
            return finalizeBands();
        }

        /**
         * Folds linear FFT magnitudes into the logarithmic display bands by per-band peak.
         * Entry {@code i} of a size-N FFT output covers {@code i * sampleRateHz / N} Hz, which
         * holds both for one-entry-per-bin arrays and for audio_flux-style folded groups
         * ({@link SpectrumFft#magnitudes}).
         */
        float[] processLinear(float[] linearMagnitudes, float sampleRateHz) {
            java.util.Arrays.fill(counts, 0);
            java.util.Arrays.fill(magnitudes, 0f);
            if (linearMagnitudes == null || linearMagnitudes.length < 2 || sampleRateHz <= 0f) {
                return new float[BAND_COUNT];
            }
            for (int bin = 1; bin < linearMagnitudes.length; bin++) {
                int band = bandForFrequency(
                        bin * sampleRateHz / (2f * linearMagnitudes.length), sampleRateHz);
                if (band < 0) continue;
                counts[band]++;
                if (linearMagnitudes[bin] > magnitudes[band]) {
                    magnitudes[band] = linearMagnitudes[bin];
                }
            }
            return finalizeBands();
        }

        private float[] finalizeBands() {
            // The low end of a small FFT can have fewer bins than our logarithmic display
            // bands (for example, 44.1 kHz / 1024 first jumps from 43 Hz to 86 Hz). Fill
            // those display-only gaps from their closest sampled neighbours so the left edge
            // never contains permanently dead bars. Silence remains zero throughout.
            interpolateEmptyBands(magnitudes, counts);
            float strongest = 0f;
            for (int i = 0; i < BAND_COUNT; i++) {
                strongest = Math.max(strongest, magnitudes[i]);
            }
            if (strongest < 0.006f) {
                for (int i = 0; i < BAND_COUNT; i++) smoothed[i] *= 0.72f;
                return smoothed.clone();
            }
            referenceLevel = strongest >= referenceLevel
                    ? strongest : Math.max(strongest, referenceLevel * 0.96f);
            float intensity = clamp((float) (Math.log1p(strongest * 8f) / Math.log1p(4f)));
            for (int i = 0; i < BAND_COUNT; i++) {
                float desired = clamp((float) Math.sqrt(magnitudes[i]
                        / Math.max(0.012f, referenceLevel)) * intensity);
                // Rise fast so beats hit immediately; fall a bit quicker than before so the
                // bars read as "locked to the music" rather than drifting behind it.
                float smoothing = desired >= smoothed[i] ? 0.82f : 0.20f;
                smoothed[i] += (desired - smoothed[i]) * smoothing;
            }
            return smoothed.clone();
        }

        private static void interpolateEmptyBands(float[] magnitudes, int[] counts) {
            int previous = -1;
            for (int current = 0; current < magnitudes.length; current++) {
                if (counts[current] == 0) continue;
                if (previous < 0) {
                    for (int i = 0; i < current; i++) magnitudes[i] = magnitudes[current];
                } else if (current - previous > 1) {
                    for (int i = previous + 1; i < current; i++) {
                        float fraction = (i - previous) / (float) (current - previous);
                        magnitudes[i] = magnitudes[previous]
                                + (magnitudes[current] - magnitudes[previous]) * fraction;
                    }
                }
                previous = current;
            }
            if (previous >= 0) {
                for (int i = previous + 1; i < magnitudes.length; i++) {
                    magnitudes[i] = magnitudes[previous];
                }
            }
        }
    }

    /** True when every level sits at the noise floor; the capture watchdog's silence test. */
    static boolean isSilent(float[] levels) {
        if (levels == null) return true;
        for (float level : levels) {
            if (level > 0.004f) return false;
        }
        return true;
    }

    static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
