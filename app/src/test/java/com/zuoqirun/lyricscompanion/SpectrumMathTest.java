package com.zuoqirun.lyricscompanion;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class SpectrumMathTest {
    @Test public void logarithmicBandsStayOrderedAndBounded() {
        int low = SpectrumMath.bandForFrequency(80f, 48_000f);
        int mid = SpectrumMath.bandForFrequency(1_000f, 48_000f);
        int high = SpectrumMath.bandForFrequency(12_000f, 48_000f);

        assertTrue(low >= 0);
        assertTrue(mid > low);
        assertTrue(high > mid);
        assertEquals(-1, SpectrumMath.bandForFrequency(30f, 48_000f));
    }

    @Test public void fftEnergyAppearsInItsFrequencyBand() {
        byte[] fft = new byte[256];
        // The first usable FFT point is 48 kHz / 256 = 187.5 Hz. Its display band
        // must also populate the leading low-frequency bands that have no FFT point.
        fft[2] = 100;
        float[] levels = new SpectrumMath.Analyzer().process(fft, 48_000_000);
        int expected = SpectrumMath.bandForFrequency(187.5f, 48_000f);

        assertTrue(levels[expected] > 0f);
        assertTrue("empty low-frequency display bands inherit nearby FFT energy", levels[0] > 0f);
    }

    @Test public void linearMagnitudesLandInTheirBandAndFillEmptyNeighbours() {
        // Folded FFT output: entry i covers i * 44100 / 512 Hz. Entry 1 (about 86 Hz) is the
        // lowest populated point, so the display bands beneath it must inherit its energy —
        // mirroring what the byte path does with its first usable FFT point.
        float[] linear = new float[256];
        linear[1] = 0.8f;
        float[] levels = new SpectrumMath.Analyzer().processLinear(linear, 44_100f);
        int expected = SpectrumMath.bandForFrequency(44_100f / 512f, 44_100f);

        assertTrue(levels[expected] > 0f);
        assertTrue("empty low-frequency display bands inherit nearby energy", levels[0] > 0f);
    }

    @Test public void silenceDetectionMatchesTheWatchdogThreshold() {
        assertTrue(SpectrumMath.isSilent(new float[SpectrumMath.BAND_COUNT]));
        assertTrue(SpectrumMath.isSilent(null));
        float[] levels = new float[SpectrumMath.BAND_COUNT];
        levels[7] = 0.005f;
        assertTrue(!SpectrumMath.isSilent(levels));
    }
}
