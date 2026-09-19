package com.zuoqirun.lyricscompanion;

/** Converts word timing into a Unicode-safe, continuously moving text boundary. */
final class KaraokeProgress {
    private KaraokeProgress() {}

    static Boundary boundary(String value, int progressPermille) {
        if (value == null || value.isEmpty() || progressPermille <= 0) {
            return Boundary.EMPTY;
        }
        int codePointCount = value.codePointCount(0, value.length());
        int clampedProgress = Math.min(1000, progressPermille);
        long scaledProgress = (long) codePointCount * clampedProgress;
        int completedCodePoints = (int) (scaledProgress / 1000L);
        if (completedCodePoints >= codePointCount) {
            return new Boundary(value.length(), value.length(), 0f);
        }
        int completeEnd = value.offsetByCodePoints(0, completedCodePoints);
        int partialEnd = value.offsetByCodePoints(completeEnd, 1);
        return new Boundary(completeEnd, partialEnd,
                (scaledProgress % 1000L) / 1000f);
    }

    /**
     * 没有逐字时间轴时，按本句时长估算的进度（issue #21）。
     *
     * <p>普通 `.lrc` 只有行时间轴，代码原本整句一次性点亮——同一首歌里"有逐字时间轴的逐字变、
     * 只有行时间轴的整句变"，观感不统一。这里给出 0..1 的线性进度，让调用方按它推进高亮宽度。
     *
     * <p>返回 {@code -1} 表示没有可用信息（本句没有时长、或位置还没到本句），调用方保持原来的
     * 整句高亮。长音、拖腔与行内停顿会让估算提前或滞后，只影响观感，不影响歌词同步。
     */
    static float estimatedFraction(long positionMs, long lineStartMs, long lineDurationMs) {
        if (lineDurationMs <= 0L || lineStartMs < 0L || positionMs < lineStartMs) return -1f;
        float elapsed = (positionMs - lineStartMs) / (float) lineDurationMs;
        return elapsed <= 0f ? 0f : Math.min(1f, elapsed);
    }

    static final class Boundary {
        static final Boundary EMPTY = new Boundary(0, 0, 0f);

        final int completeEnd;
        final int partialEnd;
        final float partialFraction;

        Boundary(int completeEnd, int partialEnd, float partialFraction) {
            this.completeEnd = completeEnd;
            this.partialEnd = partialEnd;
            this.partialFraction = partialFraction;
        }
    }
}
