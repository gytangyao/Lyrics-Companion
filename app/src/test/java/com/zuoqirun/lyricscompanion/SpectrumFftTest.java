package com.zuoqirun.lyricscompanion;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class SpectrumFftTest {
    @Test public void sinePeakLandsInItsFoldedBin() {
        SpectrumFft fft = new SpectrumFft(1024);
        // A sine at FFT bin 100 (100 cycles per 1024 samples) folds into output group 50.
        float[] samples = new float[1024];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = 0.5f * (float) Math.sin(2.0 * Math.PI * 100 * i / 1024);
        }
        float[] out = new float[256];
        fft.magnitudes(samples, samples.length, out, -70f);

        assertTrue("peak bin should carry strong energy", out[50] > 0.4f);
        // dB compression keeps Hann's half-amplitude neighbours fairly high, so only require
        // the tone's own group to be the strongest.
        assertTrue("left neighbour stays below the peak", out[50] > out[49]);
        assertTrue("right neighbour stays below the peak", out[50] > out[51]);
        // Far away from the tone there is nothing above the noise floor.
        assertEquals(0f, out[10], 0f);
        assertEquals(0f, out[100], 0f);
    }

    @Test public void silenceProducesZeroBins() {
        SpectrumFft fft = new SpectrumFft(256);
        float[] out = new float[64];
        fft.magnitudes(new float[256], 256, out, -70f);
        for (float value : out) assertEquals(0f, value, 0f);
    }

    @Test public void shortReadsAreZeroPaddedWithoutCrashing() {
        SpectrumFft fft = new SpectrumFft(256);
        float[] samples = new float[256];
        for (int i = 0; i < 100; i++) {
            samples[i] = (float) Math.sin(2.0 * Math.PI * 20 * i / 256);
        }
        float[] out = new float[64];
        fft.magnitudes(samples, 100, out, -70f);
        float peak = 0f;
        for (float value : out) peak = Math.max(peak, value);
        assertTrue("partial block still yields energy", peak > 0f);
    }

    @Test public void normalizedDbIsMonotonicAndClamped() {
        assertEquals(0f, SpectrumFft.toNormalizedDb(0f, -70f), 0f);
        assertEquals(1f, SpectrumFft.toNormalizedDb(1f, -70f), 0.0001f);
        assertEquals(0f, SpectrumFft.toNormalizedDb(0.0001f, -70f), 0f);
        assertTrue(SpectrumFft.toNormalizedDb(0.5f, -70f) > SpectrumFft.toNormalizedDb(0.25f, -70f));
    }

    @Test public void rmsMatchesManualComputation() {
        assertEquals(1f, SpectrumFft.rms(new float[]{1f, -1f}, 2), 0.0001f);
        assertEquals(0.5f, SpectrumFft.rms(new float[]{0.5f, 0.5f}, 2), 0.0001f);
        assertEquals(0f, SpectrumFft.rms(new float[8], 0), 0f);
    }

    @Test(expected = IllegalArgumentException.class)
    public void nonPowerOfTwoSizeIsRejected() {
        new SpectrumFft(1000);
    }
}
