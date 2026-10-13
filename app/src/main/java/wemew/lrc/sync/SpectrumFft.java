package wemew.lrc.sync;

/**
 * Fixed-size radix-2 FFT that turns PCM samples into normalized 0..1 magnitude bins.
 *
 * Ported from audio_flux's FftProcessor.kt for the Android 10+ playback-capture path; the
 * system-mix path reads FFT data straight from the platform Visualizer instead. Pure Java so
 * the transform stays unit-testable like {@link SpectrumMath}.
 */
final class SpectrumFft {
    private final int size;
    private final float[] real;
    private final float[] imag;
    private final float[] window;
    private final int[] reverseIndex;

    SpectrumFft(int size) {
        if (size <= 0 || (size & (size - 1)) != 0) {
            throw new IllegalArgumentException("FFT size must be a power of two");
        }
        this.size = size;
        real = new float[size];
        imag = new float[size];
        window = new float[size];
        reverseIndex = new int[size];
        // Hann window to suppress spectral leakage.
        for (int i = 0; i < size; i++) {
            window[i] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (size - 1)));
        }
        int bits = Integer.numberOfTrailingZeros(size);
        for (int i = 0; i < size; i++) {
            int value = 0;
            for (int bit = 0; bit < bits; bit++) {
                if (((i >> bit) & 1) == 1) value |= 1 << (bits - 1 - bit);
            }
            reverseIndex[i] = value;
        }
    }

    int size() {
        return size;
    }

    /**
     * Writes the magnitude spectrum of the first {@code count} samples into {@code out}: the
     * {@code size / 2} FFT bins folded down to {@code out.length} groups by per-group peak,
     * then dB-compressed into 0..1 against {@code floorDb}.
     */
    void magnitudes(float[] samples, int count, float[] out, float floorDb) {
        if (count > size) count = size;
        if (count < 0) count = 0;
        for (int i = 0; i < size; i++) {
            float sample = i < count ? samples[i] : 0f;
            int index = reverseIndex[i];
            real[index] = sample * window[i];
            imag[index] = 0f;
        }
        for (int length = 2; length <= size; length <<= 1) {
            double angle = -2.0 * Math.PI / length;
            float wReal = (float) Math.cos(angle);
            float wImag = (float) Math.sin(angle);
            for (int start = 0; start < size; start += length) {
                float curReal = 1f;
                float curImag = 0f;
                for (int k = 0; k < length / 2; k++) {
                    int evenIdx = start + k;
                    int oddIdx = evenIdx + length / 2;
                    float oddReal = real[oddIdx] * curReal - imag[oddIdx] * curImag;
                    float oddImag = real[oddIdx] * curImag + imag[oddIdx] * curReal;
                    real[oddIdx] = real[evenIdx] - oddReal;
                    imag[oddIdx] = imag[evenIdx] - oddImag;
                    real[evenIdx] += oddReal;
                    imag[evenIdx] += oddImag;
                    float nextReal = curReal * wReal - curImag * wImag;
                    curImag = curReal * wImag + curImag * wReal;
                    curReal = nextReal;
                }
            }
        }
        int bins = size / 2;
        int perOut = Math.max(1, bins / out.length);
        float scale = 2f / size;
        for (int i = 0; i < out.length; i++) {
            float peak = 0f;
            for (int j = 0; j < perOut; j++) {
                int bin = i * perOut + j;
                if (bin >= bins) break;
                float magnitude = (float) Math.hypot(real[bin], imag[bin]) * scale;
                if (magnitude > peak) peak = magnitude;
            }
            out[i] = toNormalizedDb(peak, floorDb);
        }
    }

    /** Compresses a linear magnitude into 0..1 so it can drive a bar height directly. */
    static float toNormalizedDb(float magnitude, float floorDb) {
        if (magnitude <= 0f) return 0f;
        float db = (float) (20f * Math.log10(magnitude));
        if (db <= floorDb) return 0f;
        float normalized = (db - floorDb) / -floorDb;
        return Math.min(1f, normalized);
    }

    /** Root mean square of the first {@code count} samples, for silence detection. */
    static float rms(float[] samples, int count) {
        if (count <= 0) return 0f;
        double sum = 0.0;
        for (int i = 0; i < count; i++) {
            sum += samples[i] * samples[i];
        }
        return (float) Math.sqrt(sum / count);
    }
}
