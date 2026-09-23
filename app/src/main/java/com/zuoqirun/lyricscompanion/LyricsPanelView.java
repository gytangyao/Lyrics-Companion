package com.zuoqirun.lyricscompanion;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.LruCache;
import android.view.MotionEvent;
import android.view.View;

/** Canvas renderer shared by the preview, the phone overlay and the secondary display. */
final class LyricsPanelView extends View {
    private static final Typeface SANS_NORMAL = Typeface.create("sans", Typeface.NORMAL);
    private static final Typeface SANS_BOLD = Typeface.create("sans", Typeface.BOLD);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG
            | Paint.FILTER_BITMAP_FLAG);
    private final RectF panelRect = new RectF();
    private final Path clipPath = new Path();
    private final LruCache<TextLayoutKey, String> ellipsizedTextCache =
            new LruCache<>(96);
    private float textScale = 1f;
    private int lyricOffsetMs;
    private int lyricColor;
    private int lyricLightColor;
    private int lyricDarkColor;
    private int currentLyricColor;
    private int inactiveLyricColor;
    private int currentLyricLightColor;
    private int currentLyricDarkColor;
    private int inactiveLyricLightColor;
    private int inactiveLyricDarkColor;
    private boolean currentLyricOutline;
    private int currentLyricOutlineColor;
    private int currentLyricOutlineAlphaPercent = 88;
    private int currentLyricOutlineWidthPercent = 8;
    private boolean inactiveLyricOutline;
    private int inactiveLyricOutlineColor;
    private int inactiveLyricOutlineAlphaPercent = 88;
    private int inactiveLyricOutlineWidthPercent = 8;
    private float nextLyricScale = 0.70f;
    /** Panel corner radius as a percentage of the shorter side; -1 keeps the style default. */
    private int cornerRadiusPercent = -1;
    /** Estimate word-by-word karaoke progress from line duration when no word timings exist. */
    private boolean estimatedWordKaraoke;
    /** Last time the scheduled theme was re-checked; see {@link #refreshScheduledTheme(long)}. */
    private long lastThemeCheckMs;
    private String overlayStyle;
    private String themeMode;
    private boolean lyricsFollowTheme;
    private String refinedColorScheme;
    private String refinedTextEffect;
    private boolean refinedLyricGlow;
    private int refinedLyricFontSize;
    private Typeface customTypeface;
    /** Avoid repeatedly entering vendor Typeface code when the requested face has not changed. */
    private Typeface appliedTextTypeface;
    private String compactMarqueeText = "";
    private long compactMarqueeElapsedMs;
    private long compactMarqueeLastFrameMs;
    private boolean compactMarqueeActive;
    private final boolean fullscreen;
    private final boolean compactTextOnly;
    private final boolean secondary;

    LyricsPanelView(Context context) { this(context, false, false, false); }

    LyricsPanelView(Context context, boolean secondary) {
        this(context, secondary, false, false);
    }

    LyricsPanelView(Context context, boolean secondary, boolean fullscreen) {
        this(context, secondary, fullscreen, false);
    }

    /** Compact text-only mode backs the transparent top lyric strip. */
    LyricsPanelView(Context context, boolean secondary, boolean fullscreen, boolean compactTextOnly) {
        super(context);
        this.secondary = secondary;
        this.fullscreen = fullscreen;
        this.compactTextOnly = compactTextOnly;
        reloadStyle();
    }

    void reloadStyle() {
        textScale = compactTextOnly ? AppPreferences.topLyricFontScale(getContext()) / 100f
                : AppPreferences.textScale(getContext(), secondary);
        lyricOffsetMs = AppPreferences.lyricOffsetMs(getContext(), secondary);
        lyricColor = compactTextOnly ? AppPreferences.statusLyricColor(getContext())
                : AppPreferences.lyricColor(getContext(), secondary);
        lyricLightColor = compactTextOnly ? AppPreferences.statusLyricLightColor(getContext())
                : AppPreferences.lyricLightColor(getContext(), secondary);
        lyricDarkColor = compactTextOnly ? AppPreferences.statusLyricDarkColor(getContext())
                : AppPreferences.lyricDarkColor(getContext(), secondary);
        currentLyricColor = compactTextOnly ? 0
                : AppPreferences.currentLyricColor(getContext(), secondary);
        inactiveLyricColor = compactTextOnly ? 0
                : AppPreferences.inactiveLyricColor(getContext(), secondary);
        currentLyricLightColor = compactTextOnly ? 0
                : AppPreferences.currentLyricLightColor(getContext(), secondary);
        currentLyricDarkColor = compactTextOnly ? 0
                : AppPreferences.currentLyricDarkColor(getContext(), secondary);
        inactiveLyricLightColor = compactTextOnly ? 0
                : AppPreferences.inactiveLyricLightColor(getContext(), secondary);
        inactiveLyricDarkColor = compactTextOnly ? 0
                : AppPreferences.inactiveLyricDarkColor(getContext(), secondary);
        currentLyricOutline = !compactTextOnly
                && AppPreferences.lyricOutline(getContext(), secondary, true);
        currentLyricOutlineColor = compactTextOnly ? 0
                : AppPreferences.lyricOutlineColor(getContext(), secondary, true);
        currentLyricOutlineAlphaPercent = AppPreferences.lyricOutlineAlphaPercent(getContext(),
                secondary, true);
        currentLyricOutlineWidthPercent = AppPreferences.lyricOutlineWidthPercent(getContext(),
                secondary, true);
        inactiveLyricOutline = !compactTextOnly
                && AppPreferences.lyricOutline(getContext(), secondary, false);
        inactiveLyricOutlineColor = compactTextOnly ? 0
                : AppPreferences.lyricOutlineColor(getContext(), secondary, false);
        inactiveLyricOutlineAlphaPercent = AppPreferences.lyricOutlineAlphaPercent(getContext(),
                secondary, false);
        inactiveLyricOutlineWidthPercent = AppPreferences.lyricOutlineWidthPercent(getContext(),
                secondary, false);
        nextLyricScale = (compactTextOnly
                ? AppPreferences.topLyricNextFontScale(getContext())
                : AppPreferences.nextLyricScale(getContext(), secondary)) / 100f;
        cornerRadiusPercent = AppPreferences.cornerRadiusPercent(getContext(), secondary);
        estimatedWordKaraoke = AppPreferences.estimatedWordKaraoke(getContext(), secondary);
        overlayStyle = compactTextOnly ? "compact" : AppPreferences.overlayStyle(getContext(), secondary);
        themeMode = AppPreferences.resolvedThemeMode(getContext());
        lyricsFollowTheme = compactTextOnly ? AppPreferences.statusLyricFollowTheme(getContext())
                : AppPreferences.lyricsFollowTheme(getContext());
        refinedColorScheme = AppPreferences.refinedColorScheme(getContext(), secondary);
        refinedTextEffect = AppPreferences.refinedTextEffect(getContext(), secondary);
        refinedLyricFontSize = AppPreferences.refinedLyricFontSize(getContext(), secondary);
        refinedLyricGlow = AppPreferences.refinedLyricGlow(getContext(), secondary);
        customTypeface = CustomFontStore.load(getContext());
        clearTextCaches();
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float width = getWidth();
        float height = getHeight();
        if (width <= 2f || height <= 2f) return;
        panelRect.set(fullscreen ? 0f : 1f, fullscreen ? 0f : 1f,
                fullscreen ? width : width - 1f, fullscreen ? height : height - 1f);
        long now = SystemClock.elapsedRealtime();
        lyricOffsetMs = AppPreferences.lyricOffsetMs(getContext(), secondary,
                MusicStateStore.activeSourceId());
        MusicSnapshot snapshot = MusicStateStore.snapshot(lyricOffsetMs);
        refreshScheduledTheme(now);
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
        drawCompact(canvas, snapshot, density);
        scheduleNextFrame(nextFrameDelay(snapshot, now));
    }

    private void scheduleNextFrame(long delayMs) {
        if (delayMs <= 16L) {
            postInvalidateOnAnimation();
        } else {
            postInvalidateDelayed(delayMs);
        }
    }

    private long nextFrameDelay(MusicSnapshot snapshot, long nowElapsedMs) {
        if (compactMarqueeActive && snapshot.playing) return 16L;
        if (!snapshot.active) return 750L;
        if (!snapshot.playing) return 400L;
        if (snapshot.lyrics.wordTimed && snapshot.lyrics.wordDurationMs > 0L
                && !snapshot.lyrics.currentWord.isEmpty()) return 16L;
        return 100L;
    }

    boolean isLyricGestureRegion(float x, float y) {
        return false;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        return super.onTouchEvent(event);
    }

    void cancelLyricBrowseForOverlayLock() {
        // No-op: browse gesture removed.
    }

    void setTopWindowBlurActive(boolean active) {
        // No-op: blur system removed.
    }


    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    private void drawCompact(Canvas canvas, MusicSnapshot snapshot, float density) {
        float width = getWidth();
        float height = getHeight();
        boolean light = lyricUsesLightColors();
        int primaryText = light ? 0xFF000000 : 0xFFFFFFFF;

        int save = canvas.save();
        float radius = Math.min(width, height) * panelCornerRadiusRatio(0.18f);
        clipPath.reset();
        clipPath.addRoundRect(panelRect, radius, radius, Path.Direction.CW);
        canvas.clipPath(clipPath);
        float referenceArea = 320f * 104f * density * density;
        float responsiveScale = compactTextOnly
                ? (float) Math.sqrt(Math.max(0.01f, height / (104f * density)))
                : (float) Math.sqrt(Math.max(0.01f, width * height / referenceArea));
        float pad = Math.max(2f * density, 7f * density * responsiveScale);

        float lyricLeft = pad;
        float lyricWidth = Math.max(1f, width - pad * 2f);
        float lyricTop = pad;
        float lyricBottom = height - pad;
        float lyricStageHeight = Math.max(1f, lyricBottom - lyricTop);
        boolean preferTranslation = compactTextOnly
                ? AppPreferences.topLyricShowTranslation(getContext())
                : AppPreferences.refinedShowTranslation(getContext(), secondary);
        boolean showTranslation = preferTranslation && !snapshot.lyrics.translatedLyric.isEmpty();
        boolean showNextLine = (!preferTranslation || !showTranslation)
                && (compactTextOnly || AppPreferences.compactShowNextLine(getContext(), secondary))
                && !snapshot.lyrics.nextLyric.isEmpty();
        float requestedLyricSize = refinedLyricFontSize * density * textScale
                * 1.50f * responsiveScale;
        float lyricSize = (showNextLine || showTranslation)
                ? requestedLyricSize * 0.78f : requestedLyricSize;
        float secondaryLineSize = lyricSize * nextLyricScale;
        setTextPaint(lyricSize, Typeface.BOLD);
        float originalAscent = paint.ascent();
        float originalDescent = paint.descent();
        setTextPaint(secondaryLineSize, Typeface.NORMAL);
        float secondaryAscent = paint.ascent();
        float secondaryDescent = paint.descent();
        float lineGap = Math.max(3f * density, lyricSize * 0.12f);
        boolean showSecondaryLine = showNextLine || showTranslation;
        float groupHeight = showSecondaryLine
                ? originalDescent - originalAscent + lineGap
                + secondaryDescent - secondaryAscent
                : originalDescent - originalAscent;
        float groupTop = lyricTop + Math.max(0f, (lyricStageHeight - groupHeight) * 0.5f);
        float baseline = groupTop - originalAscent;
        float secondaryBaseline = baseline + originalDescent + lineGap - secondaryAscent;
        String secondaryText = showSecondaryLine ? (showNextLine
                ? snapshot.lyrics.nextLyric : snapshot.lyrics.translatedLyric) : "";
        Paint.Align lyricAlign = lyricTextAlign(Paint.Align.CENTER);
        float lyricAnchor = lyricAnchorX(lyricLeft, lyricWidth, lyricAlign);
        if (showNextLine) {
            setTextPaint(secondaryLineSize, Typeface.NORMAL);
            paint.setTextAlign(lyricAlign);
            paint.setColor(lyricColor(primaryText));
            canvas.drawText(ellipsize(secondaryText.replace('\n', ' '), lyricWidth),
                    lyricAnchor, secondaryBaseline, paint);
        }
        drawCompactCurrentLine(canvas, snapshot, density, lyricLeft, lyricWidth, baseline,
                lyricSize, secondaryBaseline, secondaryLineSize, secondaryText,
                showTranslation, lyricColor(primaryText), currentLyricColor(primaryText));
        canvas.restoreToCount(save);
    }

    private void drawCompactCurrentLine(Canvas canvas, MusicSnapshot snapshot, float density,
                                        float lyricLeft, float lyricWidth, float baseline,
                                        float lyricSize, float secondaryBaseline,
                                        float secondaryLineSize, String secondaryText,
                                        boolean showTranslation, int baseColor, int activeColor) {
        if (snapshot.lyrics.interlude) {
            compactMarqueeActive = false;
            compactMarqueeText = "";
            compactMarqueeElapsedMs = 0L;
            return;
        }
        float marqueeOffset = drawCompactMarqueeKaraoke(canvas, snapshot,
                currentText(snapshot), lyricLeft, baseline, lyricSize, lyricWidth, density,
                baseColor, activeColor);
        if (showTranslation) {
            drawCompactFollowingTranslation(canvas, secondaryText, lyricLeft, secondaryBaseline,
                    secondaryLineSize, lyricWidth, marqueeOffset, baseColor);
        }
    }

    private float drawCompactMarqueeKaraoke(Canvas canvas, MusicSnapshot snapshot, String value,
                                           float x, float y, float requestedSize, float maxWidth,
                                           float density, int baseColor, int activeColor) {
        if (value == null || value.isEmpty()) {
            compactMarqueeActive = false;
            compactMarqueeText = "";
            compactMarqueeElapsedMs = 0L;
            return 0f;
        }
        String text = value.replace('\n', ' ');
        setTextPaint(requestedSize, Typeface.BOLD);
        paint.setTextAlign(Paint.Align.LEFT);
        float textWidth = paint.measureText(text);
        if (textWidth <= maxWidth) {
            compactMarqueeActive = false;
            compactMarqueeText = "";
            compactMarqueeElapsedMs = 0L;
            Paint.Align align = lyricTextAlign(Paint.Align.CENTER);
            drawKaraoke(canvas, snapshot, text, lyricAnchorX(x, maxWidth, align), y, requestedSize,
                    maxWidth, align,
                    baseColor, activeColor);
            return 0f;
        }

        long now = SystemClock.elapsedRealtime();
        if (!text.equals(compactMarqueeText)) {
            compactMarqueeText = text;
            compactMarqueeElapsedMs = 0L;
            compactMarqueeLastFrameMs = now;
        } else if (snapshot.playing) {
            compactMarqueeElapsedMs += Math.max(0L, now - compactMarqueeLastFrameMs);
            compactMarqueeLastFrameMs = now;
        } else {
            compactMarqueeLastFrameMs = now;
        }
        compactMarqueeActive = snapshot.playing;
        float overflow = textWidth - maxWidth;
        float offset;
        if (snapshot.lyrics.wordTimed) {
            LrcTimeline.At at = snapshot.lyrics;
            float highlightedWidth = karaokeHighlightWidth(text, at);
            float anchor = Math.max(12f * density, maxWidth * 0.64f);
            offset = Math.max(0f, Math.min(overflow, highlightedWidth - anchor));
        } else if (snapshot.lyrics.lineStartMs >= 0L
                && snapshot.lyrics.lineDurationMs > 0L) {
            float lineProgress = clamp((snapshot.positionMs + lyricOffsetMs
                    - snapshot.lyrics.lineStartMs) / (float) snapshot.lyrics.lineDurationMs);
            float scrollProgress = clamp((lineProgress - 0.06f) / 0.82f);
            offset = overflow * scrollProgress;
        } else {
            long travelMs = Math.max(240L, Math.round(overflow / (72f * density) * 1_000f));
            long cycleMs = 400L + travelMs + 700L;
            long cyclePosition = compactMarqueeElapsedMs % cycleMs;
            offset = cyclePosition <= 400L ? 0f
                    : cyclePosition >= 400L + travelMs ? overflow
                    : overflow * (cyclePosition - 400L) / (float) travelMs;
        }

        int save = canvas.save();
        canvas.clipRect(x, y - requestedSize * 1.25f, x + maxWidth, y + requestedSize * 0.35f);
        float drawX = x - offset;
        drawLyricText(canvas, text, drawX, y, requestedSize, baseColor);
        if (!snapshot.lyricAvailable || snapshot.lyrics.lyric.isEmpty()) {
            drawLyricOutline(canvas, text, drawX, y, requestedSize, activeColor, true);
            paint.setColor(activeColor);
            canvas.drawText(text, drawX, y, paint);
        } else if (!snapshot.lyrics.wordTimed) {
            drawLyricOutline(canvas, text, drawX, y, requestedSize, activeColor, true);
            paint.setColor(activeColor);
            applyRefinedTextEffect(requestedSize, activeColor, 255);
            canvas.drawText(text, drawX, y, paint);
            paint.clearShadowLayer();
        } else {
            LrcTimeline.At at = snapshot.lyrics;
            float highlightedWidth = karaokeHighlightWidth(text, at);
            int highlightSave = canvas.save();
            canvas.clipRect(drawX, y - requestedSize * 1.25f,
                    drawX + Math.min(textWidth, highlightedWidth), y + requestedSize * 0.35f);
            drawLyricOutline(canvas, text, drawX, y, requestedSize, activeColor, true);
            paint.setColor(activeColor);
            if (refinedLyricGlow) {
                paint.setShadowLayer(Math.max(3f, requestedSize * 0.24f), 0f, 0f,
                        withAlpha(activeColor, 90));
            }
            canvas.drawText(text, drawX, y, paint);
            paint.clearShadowLayer();
            canvas.restoreToCount(highlightSave);
        }
        canvas.restoreToCount(save);
        return offset;
    }

    private void drawCompactFollowingTranslation(Canvas canvas, String value, float x, float y,
                                                  float size, float maxWidth, float sourceOffset,
                                                  int color) {
        if (value == null || value.isEmpty()) return;
        String text = value.replace('\n', ' ');
        setTextPaint(size, Typeface.NORMAL);
        paint.setTextAlign(Paint.Align.LEFT);
        float textWidth = paint.measureText(text);
        if (textWidth <= maxWidth) {
            Paint.Align align = lyricTextAlign(Paint.Align.CENTER);
            paint.setTextAlign(align);
            paint.setColor(color);
            canvas.drawText(text, lyricAnchorX(x, maxWidth, align), y, paint);
            return;
        }
        float offset = Math.min(Math.max(0f, textWidth - maxWidth), Math.max(0f, sourceOffset));
        int save = canvas.save();
        canvas.clipRect(x, y - size * 1.25f, x + maxWidth, y + size * 0.35f);
        paint.setColor(color);
        canvas.drawText(text, x - offset, y, paint);
        canvas.restoreToCount(save);
    }

    private void refreshScheduledTheme(long nowElapsedMs) {
        if (nowElapsedMs - lastThemeCheckMs < 30_000L) return;
        lastThemeCheckMs = nowElapsedMs;
        if (!AppPreferences.themeScheduleEnabled(getContext())) return;
        String resolved = AppPreferences.resolvedThemeMode(getContext());
        if (resolved.equals(themeMode)) return;
        reloadStyle();
    }

    private Paint.Align lyricTextAlign(Paint.Align fallback) {
        String value = AppPreferences.lyricAlign(getContext(), secondary);
        if (value.isEmpty()) return fallback;
        return "left".equals(value) ? Paint.Align.LEFT : Paint.Align.CENTER;
    }

    private static float lyricAnchorX(float left, float maxWidth, Paint.Align align) {
        return align == Paint.Align.CENTER ? left + maxWidth * 0.5f : left;
    }

    private int lyricColor(int fallback) {
        int selected = lyricColor;
        if (lyricsFollowTheme) {
            selected = lyricEnvironmentUsesLightColors() ? lyricLightColor : lyricDarkColor;
        }
        return selected == 0 ? fallback : withAlpha(selected, Color.alpha(fallback));
    }

    private int currentLyricColor(int fallback) {
        return slotLyricColor(currentLyricColor, currentLyricLightColor,
                currentLyricDarkColor, fallback);
    }

    private int inactiveLyricColor(int fallback) {
        return slotLyricColor(inactiveLyricColor, inactiveLyricLightColor,
                inactiveLyricDarkColor, fallback);
    }

    private int slotLyricColor(int flat, int light, int dark, int fallback) {
        int selected = AppPreferences.resolveThemedSlotColor(lyricsFollowTheme,
                lyricEnvironmentUsesLightColors(), flat, light, dark);
        return selected == 0 ? lyricColor(fallback) : withAlpha(selected, Color.alpha(fallback));
    }

    private boolean refinedUsesLightColors() {
        if ("light".equals(refinedColorScheme)) return true;
        if ("dark".equals(refinedColorScheme)) return false;
        if ("light".equals(themeMode)) return true;
        if ("dark".equals(themeMode)) return false;
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode != Configuration.UI_MODE_NIGHT_YES;
    }

    private boolean lyricUsesLightColors() {
        return lyricsFollowTheme && lyricEnvironmentUsesLightColors();
    }

    private boolean lyricEnvironmentUsesLightColors() {
        if (!compactTextOnly) return refinedUsesLightColors();
        if ("light".equals(themeMode)) return true;
        if ("dark".equals(themeMode)) return false;
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode != Configuration.UI_MODE_NIGHT_YES;
    }

    private float panelCornerRadiusRatio(float styleRatio) {
        return cornerRadiusPercent < 0 ? styleRatio : cornerRadiusPercent / 100f;
    }

    private void applyRefinedTextEffect(float size, int color, int alpha) {
        if ("shadow".equals(refinedTextEffect)) {
            paint.setShadowLayer(Math.max(2f, size * 0.16f), 0f, size * 0.10f,
                    Color.argb(Math.min(115, alpha), 0, 0, 0));
        } else if ("glow".equals(refinedTextEffect)) {
            paint.setShadowLayer(Math.max(3f, size * 0.24f), 0f, 0f,
                    withAlpha(color, Math.min(95, alpha)));
        }
    }

    private void drawKaraoke(Canvas canvas, MusicSnapshot snapshot, String value, float anchorX,
                              float y, float requestedSize, float maxWidth, Paint.Align align,
                              int baseColor, int activeColor) {
        if (value == null || value.isEmpty()) return;
        float size = fitSize(value, requestedSize, maxWidth, Typeface.BOLD);
        setTextPaintForValue(size, Typeface.BOLD, value);
        paint.setTextAlign(align);
        String text = ellipsize(value.replace('\n', ' '), maxWidth);
        float textWidth = paint.measureText(text);
        float left = align == Paint.Align.CENTER ? anchorX - textWidth / 2f : anchorX;
        drawLyricText(canvas, text, anchorX, y, size, baseColor);
        if (!snapshot.lyricAvailable || snapshot.lyrics.lyric.isEmpty()) return;

        LrcTimeline.At at = snapshot.lyrics;
        float estimated = at.wordTimed ? -1f : estimatedKaraokeWidth(text, snapshot);
        if (!at.wordTimed && estimated < 0f) {
            drawLyricOutline(canvas, text, anchorX, y, size, activeColor, true);
            paint.setColor(activeColor);
            applyRefinedTextEffect(size, activeColor, 255);
            canvas.drawText(text, anchorX, y, paint);
            paint.clearShadowLayer();
            return;
        }
        float highlightedWidth = estimated >= 0f ? estimated : karaokeHighlightWidth(text, at);
        int save = canvas.save();
        canvas.clipRect(left, y - size * 1.25f,
                left + Math.min(textWidth, highlightedWidth), y + size * 0.35f);
        drawLyricOutline(canvas, text, anchorX, y, size, activeColor, true);
        paint.setColor(activeColor);
        paint.setShadowLayer(Math.max(4f, size * 0.35f), 0f, 0f,
                Color.argb(100, Color.red(activeColor), Color.green(activeColor),
                        Color.blue(activeColor)));
        canvas.drawText(text, anchorX, y, paint);
        paint.clearShadowLayer();
        canvas.restoreToCount(save);
    }

    private float estimatedKaraokeWidth(String text, MusicSnapshot snapshot) {
        if (!estimatedWordKaraoke || text == null || text.isEmpty()) return -1f;
        LrcTimeline.At at = snapshot.lyrics;
        float fraction = KaraokeProgress.estimatedFraction(snapshot.positionMs + lyricOffsetMs,
                at.lineStartMs, at.lineDurationMs);
        if (fraction < 0f) return -1f;
        return paint.measureText(text) * fraction;
    }

    private float karaokeHighlightWidth(String text, LrcTimeline.At at) {
        if (text == null || text.isEmpty()) return 0f;
        KaraokeProgress.Boundary boundary = KaraokeProgress.boundary(
                at.currentWord, at.wordProgressPermille);
        int completedEnd = Math.min(text.length(), at.completedLyric.length());
        int completeEnd = Math.min(text.length(), completedEnd + boundary.completeEnd);
        int partialEnd = Math.min(text.length(), completedEnd + boundary.partialEnd);
        float width = completeEnd <= 0 ? 0f : paint.measureText(text, 0, completeEnd);
        if (boundary.partialFraction > 0f && partialEnd > completeEnd) {
            width += paint.measureText(text, completeEnd, partialEnd)
                    * boundary.partialFraction;
        }
        return width;
    }

    private boolean shouldOutlineLyric(boolean current) {
        return current ? currentLyricOutline : inactiveLyricOutline;
    }

    static float outlineStrokeWidth(float sizePx, int percentWidth) {
        float scaled = sizePx * Math.max(1, Math.min(40, percentWidth)) / 100f;
        return Math.max(1f, scaled);
    }

    private int outlineStrokeColor(int textColor, boolean current) {
        int color = current ? currentLyricOutlineColor : inactiveLyricOutlineColor;
        if (color == 0) {
            double luminance = (0.299 * Color.red(textColor)
                    + 0.587 * Color.green(textColor) + 0.114 * Color.blue(textColor))
                    * (Color.alpha(textColor) / 255.0);
            color = luminance >= 128.0 ? 0xFF000000 : 0xFFFFFFFF;
        }
        int percent = current ? currentLyricOutlineAlphaPercent : inactiveLyricOutlineAlphaPercent;
        int alpha = Math.round(255f * Math.max(0, Math.min(100, percent)) / 100f);
        return withAlpha(color | 0xFF000000, alpha);
    }

    private void drawLyricText(Canvas canvas, String text, float x, float y, float size,
                               int resolvedColor) {
        drawLyricOutline(canvas, text, x, y, size, resolvedColor, false);
        paint.setColor(resolvedColor);
        canvas.drawText(text, x, y, paint);
    }

    private void drawLyricOutline(Canvas canvas, String text, float x, float y, float size,
                                  int resolvedColor, boolean current) {
        if (!shouldOutlineLyric(current)) return;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(outlineStrokeWidth(size, current
                ? currentLyricOutlineWidthPercent : inactiveLyricOutlineWidthPercent));
        paint.setColor(outlineStrokeColor(resolvedColor, current));
        canvas.drawText(text, x, y, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private float fitSize(String value, float requested, float maxWidth, int style) {
        setTextPaintForValue(requested, style, value);
        float measured = paint.measureText(value == null ? "" : value);
        if (measured <= maxWidth || measured <= 0f) return requested;
        return Math.max(requested * 0.62f, requested * maxWidth / measured);
    }

    private String currentText(MusicSnapshot snapshot) {
        if (!snapshot.active) return "\u7b49\u5f85\u64ad\u653e";
        if (!snapshot.lyricLoaded && !snapshot.lyricAvailable) {
            return "\u6b63\u5728\u5339\u914d\u6b4c\u8bcd\u2026";
        }
        if (!snapshot.lyricAvailable) return "\u6682\u65e0\u5339\u914d\u6b4c\u8bcd";
        if (snapshot.lyrics.interlude) return "\u266a  \u00b7  \u00b7  \u00b7";
        if (snapshot.lyrics.lyric.isEmpty()) return "\u5373\u5c06\u5f00\u59cb";
        return snapshot.lyrics.lyric;
    }

    private String ellipsize(String value, float maxWidth) {
        if (paint.measureText(value) <= maxWidth) return value;
        TextLayoutKey key = TextLayoutKey.fromPaint(value, paint, maxWidth, -1);
        String cached = ellipsizedTextCache.get(key);
        if (cached != null) return cached;
        String suffix = "\u2026";
        int low = 0;
        int high = value.length();
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (paint.measureText(value.substring(0, mid) + suffix) <= maxWidth) low = mid;
            else high = mid - 1;
        }
        if (low > 0 && Character.isHighSurrogate(value.charAt(low - 1))) low--;
        String result = value.substring(0, low) + suffix;
        ellipsizedTextCache.put(key, result);
        return result;
    }

    private void setTextPaint(float size, int style) {
        paint.setShader(null);
        paint.setAlpha(255);
        paint.setStyle(Paint.Style.FILL);
        paint.setMaskFilter(null);
        paint.clearShadowLayer();
        paint.setTextSize(size);
        Typeface target = customTypeface == null
                ? (style == Typeface.BOLD ? SANS_BOLD : SANS_NORMAL)
                : Typeface.create(customTypeface, style);
        if (paint.getTypeface() != target && appliedTextTypeface != target) {
            paint.setTypeface(target);
        }
        appliedTextTypeface = target;
    }

    private void setTextPaintForValue(float size, int style, String value) {
        setTextPaint(size, style);
        if (customTypeface != null && !CustomFontStore.canRender(value, paint.getTypeface())) {
            Typeface fallback = style == Typeface.BOLD ? SANS_BOLD : SANS_NORMAL;
            if (paint.getTypeface() != fallback) paint.setTypeface(fallback);
            appliedTextTypeface = fallback;
        }
    }

    private void clearTextCaches() {
        ellipsizedTextCache.evictAll();
    }

    @Override protected void onDetachedFromWindow() {
        clearTextCaches();
        super.onDetachedFromWindow();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        if (width != oldWidth || height != oldHeight) clearTextCaches();
        super.onSizeChanged(width, height, oldWidth, oldHeight);
    }

    private static final class TextLayoutKey {
        final String value;
        final int textSizeBits;
        final int widthBits;
        final int maxLines;
        final int typefaceStyle;

        private TextLayoutKey(String value, int textSizeBits, int widthBits,
                              int maxLines, int typefaceStyle) {
            this.value = value;
            this.textSizeBits = textSizeBits;
            this.widthBits = widthBits;
            this.maxLines = maxLines;
            this.typefaceStyle = typefaceStyle;
        }

        static TextLayoutKey fromPaint(String value, Paint paint, float maxWidth,
                                       int maxLines) {
            Typeface typeface = paint.getTypeface();
            return new TextLayoutKey(value, Float.floatToIntBits(paint.getTextSize()),
                    Float.floatToIntBits(maxWidth), maxLines,
                    typeface == null ? Typeface.NORMAL : typeface.getStyle());
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof TextLayoutKey)) return false;
            TextLayoutKey key = (TextLayoutKey) other;
            return textSizeBits == key.textSizeBits && widthBits == key.widthBits
                    && maxLines == key.maxLines && typefaceStyle == key.typefaceStyle
                    && value.equals(key.value);
        }

        @Override public int hashCode() {
            int result = value.hashCode();
            result = 31 * result + textSizeBits;
            result = 31 * result + widthBits;
            result = 31 * result + maxLines;
            return 31 * result + typefaceStyle;
        }
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(Math.max(0, Math.min(255, alpha)), Color.red(color),
                Color.green(color), Color.blue(color));
    }

    private static float clamp(float value) { return Math.max(0f, Math.min(1f, value)); }
}
