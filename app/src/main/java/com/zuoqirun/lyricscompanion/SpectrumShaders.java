package com.zuoqirun.lyricscompanion;

/**
 * Audio-reactive GLSL effects ported from audio_flux's shader catalogue to GLSL ES 1.00 for
 * the overlay's OpenGL layer. Every effect follows the Shadertoy convention the originals
 * were written against: {@code mainImage(fragColor, fragCoord)} with the audio data bound as
 * iChannel0 — row 0 of the texture carries the spectrum, row 1 the waveform.
 *
 * <p>Shader credits: the originals live in audio_flux/shaders; neon_bars, bass_starfield and
 * spectrum_tunnel are that project's own effect implementations.
 */
final class SpectrumShaders {
    static final String[] STYLE_IDS = {"neon", "starfield", "tunnel"};
    static final String[] STYLE_LABELS = {"霓虹柱", "星尘脉冲", "频谱隧道"};

    private SpectrumShaders() { }

    static boolean isShaderStyle(String style) {
        for (String id : STYLE_IDS) {
            if (id.equals(style)) return true;
        }
        return false;
    }

    static String labelFor(String style) {
        for (int i = 0; i < STYLE_IDS.length; i++) {
            if (STYLE_IDS[i].equals(style)) return STYLE_LABELS[i];
        }
        return "";
    }

    /** Wraps one effect body into a complete fragment shader for the GLES renderer. */
    static String fragmentFor(String style) {
        String body;
        if ("starfield".equals(style)) {
            body = STARFIELD_BODY;
        } else if ("tunnel".equals(style)) {
            body = TUNNEL_BODY;
        } else {
            body = NEON_BODY;
        }
        return "precision mediump float;\n"
                + "uniform vec2 iResolution;\n"
                + "uniform float iTime;\n"
                + "uniform sampler2D iChannel0;\n"
                + body
                + "\nvoid main() {\n"
                + "    vec4 fragColor = vec4(0.0);\n"
                + "    mainImage(fragColor, gl_FragCoord.xy);\n"
                // Shadertoy output is alpha=1 everywhere; a solid rectangle would paste a
                // black board over the car UI. Key the alpha by brightness (audio_flux's
                // approach): dark pixels become transparent, premultiplied for the window.
                + "    vec3 rgb = clamp(fragColor.rgb, 0.0, 1.0);\n"
                + "    float a = clamp(max(rgb.r, max(rgb.g, rgb.b)) * fragColor.a, 0.0, 1.0);\n"
                + "    gl_FragColor = vec4(rgb * a, a);\n"
                + "}\n";
    }

    /** 霓虹柱: classic bar spectrum with per-bar glow, mirror reflection and white caps. */
    private static final String NEON_BODY =
            "const float PI2 = 6.28318530718;\n"
            + "const float BARS = 48.0;\n"
            + "vec3 hue(float h) {\n"
            + "    return 0.5 + 0.5 * cos(PI2 * (h + vec3(0.0, 0.33, 0.67)));\n"
            + "}\n"
            + "void mainImage(out vec4 fragColor, in vec2 fragCoord) {\n"
            + "    vec2 uv = fragCoord.xy / iResolution.xy;\n"
            + "    uv.y = 1.0 - uv.y;\n"
            + "    float cell = fract(uv.x * BARS);\n"
            + "    float band = (floor(uv.x * BARS) + 0.5) / BARS;\n"
            + "    float fft = texture2D(iChannel0, vec2(band, 0.05)).x;\n"
            + "    float h = pow(fft, 0.75) * 0.62;\n"
            + "    float slot = smoothstep(0.5, 0.36, abs(cell - 0.5));\n"
            + "    float base = 0.34;\n"
            + "    float d = uv.y - base;\n"
            + "    float body = step(0.0, d) * smoothstep(h + 0.006, h - 0.006, d);\n"
            + "    float mirror = step(d, 0.0) * smoothstep(h * 0.45 + 0.006, h * 0.45 - 0.006, -d) * 0.3;\n"
            + "    float glow = h * 0.035 / (abs(d) + 0.045);\n"
            + "    vec3 tint = hue(band * 0.65 + iTime * 0.03);\n"
            + "    vec3 col = tint * ((body + mirror) * (0.6 + fft) + glow * 0.55) * slot;\n"
            + "    col += vec3(1.0) * smoothstep(0.014, 0.0, abs(d - h)) * slot * 0.7;\n"
            + "    col += tint * smoothstep(0.004, 0.0, abs(d)) * 0.35;\n"
            + "    fragColor = vec4(col, 1.0);\n"
            + "}\n";

    /** 星尘脉冲: layered parallax starfield, each star bound to a frequency band. */
    private static final String STARFIELD_BODY =
            "const float PI2 = 6.28318530718;\n"
            + "vec3 hue(float h) {\n"
            + "    return 0.5 + 0.5 * cos(PI2 * (h + vec3(0.0, 0.33, 0.67)));\n"
            + "}\n"
            + "float hash21(vec2 p) {\n"
            + "    p = fract(p * vec2(123.34, 456.21));\n"
            + "    p += dot(p, p + 45.32);\n"
            + "    return fract(p.x * p.y);\n"
            + "}\n"
            + "void mainImage(out vec4 fragColor, in vec2 fragCoord) {\n"
            + "    vec2 uv = (fragCoord.xy * 2.0 - iResolution.xy) / min(iResolution.x, iResolution.y);\n"
            + "    float bass = texture2D(iChannel0, vec2(0.03, 0.05)).x;\n"
            + "    vec3 col = vec3(0.0);\n"
            + "    for (int i = 0; i < 6; i++) {\n"
            + "        float fi = float(i);\n"
            + "        float depth = fract(fi * 0.1667 + iTime * 0.09);\n"
            + "        float scale = mix(9.0, 1.2, depth);\n"
            + "        vec2 gp = uv * scale + fi * 31.7;\n"
            + "        vec2 id = floor(gp);\n"
            + "        vec2 f = fract(gp) - 0.5;\n"
            + "        float rnd = hash21(id + fi * 5.1);\n"
            + "        vec2 star = (vec2(hash21(id + 1.3), hash21(id + 7.7)) - 0.5) * 0.7;\n"
            + "        float band = texture2D(iChannel0, vec2(fract(rnd * 1.7), 0.05)).x;\n"
            + "        float d = length(f - star);\n"
            + "        float size = 0.035 + band * 0.06 + bass * 0.02;\n"
            + "        float bright = smoothstep(size, 0.0, d) * (0.2 + band * 1.8);\n"
            + "        bright *= depth * (1.0 - depth) * 4.0;\n"
            + "        col += hue(rnd + iTime * 0.03) * bright;\n"
            + "    }\n"
            + "    col += hue(0.6) * bass * bass * 0.25 / (length(uv) + 0.35);\n"
            + "    fragColor = vec4(col, 1.0);\n"
            + "}\n";

    /** 频谱隧道: perspective rings flying outward, brightness mapped around the circle. */
    private static final String TUNNEL_BODY =
            "const float PI2 = 6.28318530718;\n"
            + "vec3 hue(float h) {\n"
            + "    return 0.5 + 0.5 * cos(PI2 * (h + vec3(0.0, 0.33, 0.67)));\n"
            + "}\n"
            + "void mainImage(out vec4 fragColor, in vec2 fragCoord) {\n"
            + "    vec2 uv = (fragCoord.xy * 2.0 - iResolution.xy) / min(iResolution.x, iResolution.y);\n"
            + "    float r = max(length(uv), 0.001);\n"
            + "    float a = fract(atan(uv.y, uv.x) / PI2 + 0.5);\n"
            + "    float bass = texture2D(iChannel0, vec2(0.03, 0.05)).x;\n"
            + "    float z = 1.0 / (r + 0.12);\n"
            + "    float depth = z * 0.55 - iTime * (0.45 + bass * 0.8);\n"
            + "    vec3 col = vec3(0.0);\n"
            + "    for (int i = 0; i < 3; i++) {\n"
            + "        float fi = float(i);\n"
            + "        float ring = fract(depth + fi * 0.333);\n"
            + "        float band = texture2D(iChannel0, vec2(abs(a * 2.0 - 1.0), 0.05)).x;\n"
            + "        float shell = pow(ring, 6.0) * (0.25 + band * 2.2);\n"
            + "        col += hue(fract(depth * 0.15 + fi * 0.2 + a * 0.35)) * shell;\n"
            + "    }\n"
            + "    col *= smoothstep(1.7, 0.15, r);\n"
            + "    col += vec3(0.6, 0.8, 1.0) * bass * 0.12 / (r * 6.0 + 0.25);\n"
            + "    fragColor = vec4(col, 1.0);\n"
            + "}\n";
}
