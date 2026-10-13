package wemew.lrc.sync;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.SystemClock;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Renders one audio-reactive fragment shader into an offscreen GLES buffer and publishes it
 * as a {@link Bitmap} for the lyric panel to draw on its canvas.
 *
 * <p>Why not a TextureView: overlay windows on some ROMs (HyperOS among them) never composite
 * a TextureView's surface content — the GL pipeline works, the pixels reach the
 * SurfaceTexture, and nothing ever reaches the screen. A pbuffer render plus glReadPixels
 * into a bitmap is deterministic: the panel's ordinary canvas drawing always shows.
 *
 * <p>The render runs at half the panel's resolution (the effects are glow-heavy and upscale
 * cleanly) and is shared by every lyrics surface through a reference-counted singleton.
 */
final class SpectrumGlRenderer {
    private static final String TAG = "SpectrumGl";
    private static final int AUDIO_WIDTH = 256;
    private static final int AUDIO_HEIGHT = 2;
    private static final long FRAME_MS = 33L;

    private static final String VERTEX_SOURCE =
            "attribute vec2 aPosition;\n"
            + "void main() {\n"
            + "    gl_Position = vec4(aPosition, 0.0, 1.0);\n"
            + "}\n";

    private static volatile Bitmap latestFrame;
    private static Thread worker;
    private static int references;
    private static String runningStyle;
    private static int renderWidth;
    private static int renderHeight;

    private SpectrumGlRenderer() { }

    /** Called by every panel that wants frames; the render thread stops when none remain. */
    static synchronized void acquire(Context context) {
        references++;
        String style = AppPreferences.spectrumStyle(context);
        float density = context.getResources().getDisplayMetrics().density;
        int panelWidthPx = Math.max(1, Math.round(AppPreferences.panelWidthDp(context) * density));
        int panelHeightPx = Math.max(1,
                Math.round(AppPreferences.panelHeightDp(context) * density));
        int width = Math.max(64, panelWidthPx / 2);
        int height = Math.max(64, panelHeightPx / 2);
        if (worker != null && style.equals(runningStyle)
                && Math.abs(width - renderWidth) < width / 4
                && Math.abs(height - renderHeight) < height / 4) return;
        stopWorker();
        runningStyle = style;
        renderWidth = width;
        renderHeight = height;
        worker = new Thread(() -> run(style, width, height), "spectrum-gl");
        worker.start();
    }

    static synchronized void release() {
        references = Math.max(0, references - 1);
        if (references == 0) stopWorker();
    }

    /** The most recent rendered frame; null while the render thread is warming up. */
    static Bitmap frame() {
        return latestFrame;
    }

    private static void stopWorker() {
        Thread thread = worker;
        worker = null;
        if (thread == null) return;
        thread.interrupt();
        try {
            thread.join(600L);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private static void run(String style, int width, int height) {
        final String fragmentSource = SpectrumShaders.fragmentFor(style);
        EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        EGLSurface surface = EGL14.EGL_NO_SURFACE;
        EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                throw new IllegalStateException("eglInitialize failed");
            }
            int[] configAttribs = {
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE};
            EGLConfig[] configs = new EGLConfig[1];
            int[] matched = new int[1];
            EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, matched, 0);
            if (matched[0] < 1) throw new IllegalStateException("no EGL config");
            int[] contextAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
            eglContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    contextAttribs, 0);
            int[] pbufferAttribs = {
                    EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE};
            surface = EGL14.eglCreatePbufferSurface(display, configs[0], pbufferAttribs, 0);
            if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
                throw new IllegalStateException("eglCreatePbufferSurface failed");
            }
            EGL14.eglMakeCurrent(display, surface, surface, eglContext);

            int program = buildProgram(fragmentSource);
            int positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
            int resolutionHandle = GLES20.glGetUniformLocation(program, "iResolution");
            int timeHandle = GLES20.glGetUniformLocation(program, "iTime");
            int channelHandle = GLES20.glGetUniformLocation(program, "iChannel0");
            FloatBuffer quad = ByteBuffer.allocateDirect(4 * 2 * 4).order(ByteOrder.nativeOrder())
                    .asFloatBuffer()
                    .put(new float[]{-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f});
            quad.position(0);

            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            int audioTexture = textures[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, audioTexture);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                    GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                    GLES20.GL_CLAMP_TO_EDGE);
            ByteBuffer audioBuffer =
                    ByteBuffer.allocateDirect(AUDIO_WIDTH * AUDIO_HEIGHT)
                            .order(ByteOrder.nativeOrder());
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, AUDIO_WIDTH,
                    AUDIO_HEIGHT, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, audioBuffer);

            GLES20.glClearColor(0f, 0f, 0f, 0f);
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glViewport(0, 0, width, height);
            // BGRA matches Android's ARGB_8888 byte layout on little-endian, letting the
            // readback land straight in the bitmap; fall back to RGBA plus a manual swizzle.
            boolean bgra = isBgraReadable();
            ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4)
                    .order(ByteOrder.nativeOrder());
            // Double-buffered bitmaps: the panel draws one while the next frame is copied
            // into the other, so a frame never tears mid-publication.
            Bitmap front = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Bitmap back = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            float[] spectrumLevels = new float[SpectrumMath.BAND_COUNT];
            float[] waveform = new float[AudioSpectrumSource.WAVE_POINTS];
            long startedAtMs = SystemClock.elapsedRealtime();
            Log.i(TAG, "Render thread started " + width + "x" + height
                    + " style=" + style + " bgra=" + bgra);
            while (worker == Thread.currentThread()) {
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                GLES20.glUseProgram(program);
                GLES20.glUniform2f(resolutionHandle, width, height);
                GLES20.glUniform1f(timeHandle, (SystemClock.elapsedRealtime() - startedAtMs)
                        / 1000f);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, audioTexture);
                GLES20.glUniform1i(channelHandle, 0);
                uploadAudioTexture(audioBuffer, spectrumLevels, waveform);

                GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, quad);
                GLES20.glEnableVertexAttribArray(positionHandle);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

                GLES20.glReadPixels(0, 0, width, height,
                        bgra ? 0x80E1 : GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
                if (!bgra) swizzleRgbaToBgra(pixels, width * height * 4);
                pixels.rewind();
                back.copyPixelsFromBuffer(pixels);
                latestFrame = back;
                Bitmap swap = front;
                front = back;
                back = swap;
                try {
                    Thread.sleep(FRAME_MS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "Spectrum GL render thread failed", error);
        } finally {
            latestFrame = null;
            try {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT);
                if (surface != null && surface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(display, surface);
                }
                if (eglContext != null && eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(display, eglContext);
                }
            } catch (Throwable ignored) { }
            Log.i(TAG, "Render thread stopped");
        }
    }

    /** GL_BGRA_EXT probe: renderers that support it make the readback zero-conversion. */
    private static boolean isBgraReadable() {
        int[] readType = new int[1];
        int[] readFormat = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_IMPLEMENTATION_COLOR_READ_TYPE, readType, 0);
        GLES20.glGetIntegerv(GLES20.GL_IMPLEMENTATION_COLOR_READ_FORMAT, readFormat, 0);
        return readType[0] == GLES20.GL_UNSIGNED_BYTE && readFormat[0] == 0x80E1;
    }

    private static void swizzleRgbaToBgra(ByteBuffer buffer, int length) {
        for (int i = 0; i < length; i += 4) {
            byte r = buffer.get(i);
            buffer.put(i, buffer.get(i + 2));
            buffer.put(i + 2, r);
        }
    }

    /** Copies the latest captured frame into the audio texture, upsampled to 256 columns. */
    private static void uploadAudioTexture(ByteBuffer audioBuffer, float[] spectrumLevels,
                                           float[] waveform) {
        AudioSpectrumSource.Frame frame = AudioSpectrumSource.latestFrame();
        if (frame.live) {
            System.arraycopy(frame.levels, 0, spectrumLevels, 0,
                    Math.min(spectrumLevels.length, frame.levels.length));
            System.arraycopy(frame.wave, 0, waveform, 0,
                    Math.min(waveform.length, frame.wave.length));
        } else {
            // Silence decays the texture so the effects wind down instead of freezing.
            for (int i = 0; i < spectrumLevels.length; i++) spectrumLevels[i] *= 0.90f;
            for (int i = 0; i < waveform.length; i++) waveform[i] *= 0.90f;
        }
        audioBuffer.clear();
        for (int i = 0; i < AUDIO_WIDTH; i++) {
            float position = i * spectrumLevels.length / (float) AUDIO_WIDTH;
            int low = (int) position;
            int high = Math.min(spectrumLevels.length - 1, low + 1);
            float fraction = position - low;
            float level = spectrumLevels[low] + (spectrumLevels[high] - spectrumLevels[low])
                    * fraction;
            audioBuffer.put((byte) (int) (Math.max(0f, Math.min(1f, level)) * 255f));
        }
        for (int i = 0; i < AUDIO_WIDTH; i++) {
            float sample = waveform[i * waveform.length / AUDIO_WIDTH];
            audioBuffer.put((byte) (int) ((Math.max(-1f, Math.min(1f, sample)) * 0.5f + 0.5f)
                    * 255f));
        }
        audioBuffer.position(0);
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, AUDIO_WIDTH, AUDIO_HEIGHT,
                GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, audioBuffer);
    }

    private static int buildProgram(String fragmentSource) {
        int vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SOURCE);
        int fragment = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertex);
        GLES20.glAttachShader(program, fragment);
        GLES20.glLinkProgram(program);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            throw new IllegalStateException("Program link failed: " + log);
        }
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);
        return program;
    }

    private static int compile(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException("Shader compile failed: " + log);
        }
        return shader;
    }
}
