package com.zuoqirun.lyricscompanion;

/** Pure layout calculations for the classic lyrics card. */
final class ClassicLayoutMath {
    /** Below this the card is unreadable anyway, so fitting never goes further. */
    static final float MIN_TEXT_SCALE = 0.45f;
    /** The previous-line size the style used to hard-code: 12dp against the current 22dp. */
    static final float LEGACY_PREVIOUS_SCALE = 12f / 22f;
    /** Approximate Android font metrics: ascent ≈ 82% of the text size, descent ≈ 25%. */
    static final float ASCENT_RATIO = 0.82f;
    static final float DESCENT_RATIO = 0.25f;

    private ClassicLayoutMath() { }

    static float contentScale(float width, float height, float density) {
        float safeDensity = Math.max(0.01f, density);
        float referenceArea = 390f * 226f * safeDensity * safeDensity;
        float areaScale = (float) Math.sqrt(Math.max(0.01f,
                width * height / referenceArea));
        float heightScale = Math.max(0.01f, height / (226f * safeDensity));
        return Math.min(areaScale, heightScale);
    }

    /**
     * The vertical geometry of one classic card frame, in pixels.
     *
     * <p>The layout decides which rows exist and where their baselines sit; the fit below only
     * has to say how far the text must shrink for those rows not to touch. A row that is not
     * drawn must not constrain anything — that is what the {@code has*} flags are for, because
     * 「歌词显示行数」 can drop 上一句 or 下一句 (issue #26).
     */
    static final class Card {
        final float statusBaseline;
        final float titleBaseline;
        final float previousBaseline;
        final float currentBaseline;
        final float translationBaseline;
        final float nextBaseline;
        final boolean hasStatusRow;
        final boolean hasPreviousRow;
        final boolean hasTranslation;
        final boolean hasNextRow;

        Card(float statusBaseline, float titleBaseline, float previousBaseline,
             float currentBaseline, float translationBaseline, float nextBaseline,
             boolean hasStatusRow, boolean hasPreviousRow, boolean hasTranslation,
             boolean hasNextRow) {
            this.statusBaseline = statusBaseline;
            this.titleBaseline = titleBaseline;
            this.previousBaseline = previousBaseline;
            this.currentBaseline = currentBaseline;
            this.translationBaseline = translationBaseline;
            this.nextBaseline = nextBaseline;
            this.hasStatusRow = hasStatusRow;
            this.hasPreviousRow = hasPreviousRow;
            this.hasTranslation = hasTranslation;
            this.hasNextRow = hasNextRow;
        }
    }

    static float constrainedTextScale(float requested, float density, float unit,
                                      float titleScale, float nextScale,
                                      float statusBaseline, float titleBaseline,
                                      float previousBaseline, float currentBaseline,
                                      float translationBaseline, float nextBaseline,
                                      boolean hasTranslation) {
        return constrainedTextScale(requested, density, unit, titleScale, nextScale,
                new Card(statusBaseline, titleBaseline, previousBaseline, currentBaseline,
                        translationBaseline, nextBaseline, true, true, hasTranslation, true));
    }

    static float constrainedTextScale(float requested, float density, float unit,
                                      float titleScale, float nextScale, Card card) {
        float safeDensityUnit = Math.max(0.01f, density * unit);
        float limit = Math.max(MIN_TEXT_SCALE, requested);

        // Keeping those extents apart prevents glyphs from colliding.
        if (card.hasPreviousRow) {
            limit = Math.min(limit, adjacentLimit(card.previousBaseline - card.titleBaseline,
                    15f * titleScale, 12f, safeDensityUnit, true));
            limit = Math.min(limit, adjacentLimit(card.currentBaseline - card.previousBaseline,
                    12f, 22f, safeDensityUnit, false));
        } else {
            // 只有本句（或本句 + 下一句）时，歌名下面就是当前句，约束要对着它算。
            limit = Math.min(limit, adjacentLimit(card.currentBaseline - card.titleBaseline,
                    15f * titleScale, 22f, safeDensityUnit, true));
        }
        if (card.hasTranslation) {
            limit = Math.min(limit, adjacentLimit(card.translationBaseline - card.currentBaseline,
                    22f, 12f, safeDensityUnit, false));
            if (card.hasNextRow) {
                limit = Math.min(limit, adjacentLimit(card.nextBaseline - card.translationBaseline,
                        12f, 22f * nextScale, safeDensityUnit, false));
            }
        } else if (card.hasNextRow) {
            limit = Math.min(limit, adjacentLimit(card.nextBaseline - card.currentBaseline,
                    22f, 22f * nextScale, safeDensityUnit, false));
        }

        if (card.hasStatusRow) {
            float titleAscent = ASCENT_RATIO * 15f * Math.max(0.5f, titleScale) * safeDensityUnit;
            float statusRoom = card.titleBaseline - card.statusBaseline - titleAscent;
            if (statusRoom > 0f) {
                limit = Math.min(limit, statusRoom / (DESCENT_RATIO * 11f * safeDensityUnit));
            }
        }
        return Math.max(MIN_TEXT_SCALE, Math.min(requested, limit));
    }

    /**
     * Baseline gap that keeps two stacked rows apart.
     *
     * <p>The minimum keeps the historical spacing for the sizes the style was designed around;
     * past that the gap grows with the rows it separates, so raising 歌名与歌手字号 moves the
     * rows below it down instead of squeezing every other line (issue #38).
     */
    static float stackedGapDp(float upperSizeDp, float lowerSizeDp, float minimumDp) {
        return Math.max(minimumDp,
                DESCENT_RATIO * upperSizeDp + ASCENT_RATIO * lowerSizeDp + 2f);
    }

    /** Ascent of a row drawn at {@code sizePx}, as a positive number of pixels. */
    static float ascent(float sizePx) {
        return ASCENT_RATIO * sizePx;
    }

    /** Descent of a row drawn at {@code sizePx}. */
    static float descent(float sizePx) {
        return DESCENT_RATIO * sizePx;
    }

    /**
     * How far a block of rows has to move so it sits at the requested vertical alignment.
     *
     * <p>{@code blockTop} is the top of the block (the first row's ascent above its baseline) and
     * {@code blockHeight} its full extent. {@code ""} means "the style decides" and returns 0, so
     * the historical placement is untouched; "top" / "center" / "bottom" place the block inside
     * {@code [areaTop, areaBottom]}, which is how a panel dragged to the screen edge loses the
     * strip of empty space above its text (issue #41).
     */
    static float alignedRowShift(String align, float blockTop, float blockHeight,
                                 float areaTop, float areaBottom) {
        float room = Math.max(0f, areaBottom - areaTop);
        float slack = Math.max(0f, room - Math.max(0f, blockHeight));
        if ("top".equals(align)) return areaTop - blockTop;
        if ("bottom".equals(align)) return areaTop + slack - blockTop;
        if ("center".equals(align)) return areaTop + slack * 0.5f - blockTop;
        return 0f;
    }

    /** Lyric rows the classic style can place: 上一句 / 本句 / 下一句. */
    static int visibleRowCount(int requested) {
        return Math.max(1, Math.min(3, requested));
    }

    private static float adjacentLimit(float baselineGap, float upperSize,
                                       float lowerSize, float densityUnit,
                                       boolean upperIsFixed) {
        float safeGap = Math.max(0f, baselineGap - 2f * densityUnit);
        if (upperIsFixed) {
            float fixedDescent = DESCENT_RATIO * upperSize * densityUnit;
            return Math.max(0f, safeGap - fixedDescent)
                    / Math.max(0.01f, ASCENT_RATIO * lowerSize * densityUnit);
        }
        float extent = DESCENT_RATIO * upperSize + ASCENT_RATIO * lowerSize;
        return safeGap / Math.max(0.01f, extent * densityUnit);
    }
}
