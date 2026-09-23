package com.zuoqirun.lyricscompanion;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;
import java.util.function.IntConsumer;

/** HSV palette: a saturation/value square picked with the finger plus a hue strip. */
final class ColorPickerDialog {

    private final LinearLayout root;
    private final SaturationValueSquare square;
    private final SeekBar hueBar;
    private final View preview;
    private final TextView hexLabel;
    private final int[] hsv = new int[3];

    private ColorPickerDialog(Context context, int initialColor) {
        float[] components = new float[3];
        Color.colorToHSV(initialColor, components);
        hsv[0] = Math.round(components[0]);
        hsv[1] = Math.round(components[1] * 100);
        hsv[2] = Math.round(components[2] * 100);

        int dp8 = dp(context, 8);
        int dp12 = dp(context, 12);
        int dp16 = dp(context, 16);

        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp16, dp12, dp16, dp12);

        square = new SaturationValueSquare(context);
        square.setHue(hsv[0]);
        square.setSaturationValue(hsv[1] / 100f, hsv[2] / 100f);
        square.onPickChanged = (saturation, value) -> {
            hsv[1] = Math.round(saturation * 100);
            hsv[2] = Math.round(value * 100);
            updateColor();
        };
        root.addView(square, new LinearLayout.LayoutParams(-1, dp(context, 200)));

        hueBar = new SeekBar(context);
        hueBar.setMax(359);
        hueBar.setProgress(hsv[0]);
        hueBar.setBackground(new HueStripDrawable(dp(context, 20)));
        hueBar.setPadding(dp(context, 10), dp8, dp(context, 10), dp8);
        LinearLayout.LayoutParams hueParams = new LinearLayout.LayoutParams(-1, dp(context, 40));
        hueParams.topMargin = dp12;
        root.addView(hueBar, hueParams);
        hueBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                hsv[0] = progress;
                square.setHue(progress);
                updateColor();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });

        LinearLayout footer = new LinearLayout(context);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(-1, -2);
        footerParams.topMargin = dp12;
        root.addView(footer, footerParams);

        preview = new View(context);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(dp(context, 44),
                dp(context, 32));
        previewParams.rightMargin = dp12;
        footer.addView(preview, previewParams);

        hexLabel = new TextView(context);
        hexLabel.setTextSize(16f);
        hexLabel.setTextColor(0xFFD7E1EE);
        hexLabel.setTypeface(Typeface.MONOSPACE);
        footer.addView(hexLabel, new LinearLayout.LayoutParams(-2, -2));

        updateColor();
    }

    static void show(Context context, int initialColor, IntConsumer onPicked) {
        ColorPickerDialog picker = new ColorPickerDialog(context,
                initialColor == 0 ? 0xFFFFFFFF : initialColor);
        new AlertDialog.Builder(context)
                .setTitle("选择歌词颜色")
                .setView(picker.root)
                .setPositiveButton("确定", (d, which) -> onPicked.accept(picker.color()))
                .setNegativeButton("取消", null)
                .show();
    }

    private void updateColor() {
        preview.setBackgroundColor(color());
        hexLabel.setText(String.format(Locale.US, "#%06X", color() & 0xFFFFFF));
    }

    private int color() {
        return Color.HSVToColor(new float[]{hsv[0], hsv[1] / 100f, hsv[2] / 100f});
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** The hue strip behind the slider: a full-spectrum rounded bar. */
    private static final class HueStripDrawable extends android.graphics.drawable.Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();
        private final int height;

        HueStripDrawable(int height) {
            this.height = height;
        }

        @Override protected void onBoundsChange(android.graphics.Rect bounds) {
            super.onBoundsChange(bounds);
            int[] stops = new int[13];
            float[] positions = new float[13];
            for (int i = 0; i < 13; i++) {
                stops[i] = Color.HSVToColor(new float[]{i * 30f, 1f, 1f});
                positions[i] = i / 12f;
            }
            paint.setShader(new LinearGradient(bounds.left, 0, bounds.right, 0, stops, positions,
                    Shader.TileMode.CLAMP));
        }

        @Override public void draw(Canvas canvas) {
            bounds.set(getBounds());
            canvas.drawRoundRect(bounds, height / 2f, height / 2f, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** Saturation on the x axis, brightness on the y axis. */
    private static final class SaturationValueSquare extends View {
        interface OnPickChanged {
            void onChanged(float saturation, float value);
        }

        OnPickChanged onPickChanged;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint marker = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF area = new RectF();
        private final Path clip = new Path();
        private float hue;
        private float saturation = 1f;
        private float value = 1f;
        private final float cornerRadius;

        SaturationValueSquare(Context context) {
            super(context);
            cornerRadius = dp(context, 12);
            marker.setStyle(Paint.Style.STROKE);
            marker.setStrokeWidth(dp(context, 2));
            marker.setColor(0xFFFFFFFF);
        }

        void setHue(int hueDegrees) {
            hue = hueDegrees;
            invalidate();
        }

        void setSaturationValue(float saturation, float value) {
            this.saturation = saturation;
            this.value = value;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            area.set(0, 0, getWidth(), getHeight());
            int pure = Color.HSVToColor(new float[]{hue, 1f, 1f});
            clip.reset();
            clip.addRoundRect(area, cornerRadius, cornerRadius, Path.Direction.CW);
            canvas.clipPath(clip);
            fill.setShader(new LinearGradient(area.left, 0, area.right, 0, Color.WHITE, pure,
                    Shader.TileMode.CLAMP));
            canvas.drawRect(area, fill);
            fill.setShader(new LinearGradient(0, area.top, 0, area.bottom, Color.TRANSPARENT,
                    Color.BLACK, Shader.TileMode.CLAMP));
            canvas.drawRect(area, fill);
            float x = area.left + saturation * area.width();
            float y = area.top + (1f - value) * area.height();
            canvas.drawCircle(x, y, dp(getContext(), 9), marker);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE) {
                return action == MotionEvent.ACTION_UP;
            }
            if (action == MotionEvent.ACTION_DOWN) {
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            saturation = Math.max(0f, Math.min(1f, event.getX() / Math.max(1f, getWidth())));
            value = 1f - Math.max(0f, Math.min(1f, event.getY() / Math.max(1f, getHeight())));
            if (onPickChanged != null) onPickChanged.onChanged(saturation, value);
            invalidate();
            return true;
        }
    }
}
