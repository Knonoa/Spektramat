package com.spektra.latent.gl

import android.content.Context
import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A linked GL program with its uniform locations looked up once. GL thread only. */
class Program(vertex: String, fragment: String) {
    val id: Int
    private val locations = HashMap<String, Int>()

    init {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertex)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragment)
        id = GLES30.glCreateProgram()
        GLES30.glAttachShader(id, vs)
        GLES30.glAttachShader(id, fs)
        GLES30.glLinkProgram(id)
        val status = IntArray(1)
        GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(id)
            GLES30.glDeleteProgram(id)
            throw IllegalStateException("program link failed: $log")
        }
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
    }

    fun use() = GLES30.glUseProgram(id)

    fun loc(name: String): Int = locations.getOrPut(name) { GLES30.glGetUniformLocation(id, name) }

    fun set(name: String, v: Float) = GLES30.glUniform1f(loc(name), v)
    fun set(name: String, a: Float, b: Float) = GLES30.glUniform2f(loc(name), a, b)
    fun set(name: String, a: Float, b: Float, c: Float) = GLES30.glUniform3f(loc(name), a, b, c)
    fun set(name: String, a: Float, b: Float, c: Float, d: Float) = GLES30.glUniform4f(loc(name), a, b, c, d)
    fun setInt(name: String, v: Int) = GLES30.glUniform1i(loc(name), v)

    /**
     * Sends a flat block of floats to a uniform struct: [fields] names each member
     * and how many floats it takes, in the order of [block].
     */
    fun setStruct(struct: String, fields: List<Pair<String, Int>>, block: FloatArray) {
        var at = 0
        for ((field, size) in fields) {
            val l = loc("$struct.$field")
            if (l >= 0) {
                when (size) {
                    1 -> GLES30.glUniform1f(l, block[at])
                    3 -> GLES30.glUniform3f(l, block[at], block[at + 1], block[at + 2])
                    else -> error("unsupported member size $size")
                }
            }
            at += size
        }
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            Log.e("latent-gl", "shader failed:\n$log")
            throw IllegalStateException("shader compile failed: $log")
        }
        return shader
    }
}

/** The shader texts. The film arithmetic itself is assets/shaders/latent.glsl, shared with the CPU. */
object ShaderText {
    /** The members of `struct Grade` / `struct Film` in latent.glsl, in order, with their float counts. */
    val GRADE = listOf(
        "wb" to 3, "exposure" to 1, "contrast" to 1, "highlights" to 1, "shadows" to 1,
        "whites" to 1, "blacks" to 1, "vibrance" to 1, "saturation" to 1,
    )
    val FILM = listOf(
        "xyz_x" to 3, "xyz_y" to 3, "xyz_z" to 3, "exposure" to 1, "dir0" to 3, "dir1" to 3, "dir2" to 3,
        "dmax" to 3, "positive" to 1, "le_min" to 1, "le_scale" to 1, "d_lo" to 3, "d_scale" to 3,
        "print_gain" to 1, "ple_min" to 1, "ple_scale" to 1, "s_lo" to 3, "s_scale" to 3,
        "scan_film" to 1, "grain" to 1, "grain_amount" to 1, "seed" to 1,
    )

    /** A quad placed by `u_rect` (x, y, w, h in clip space) showing the texture region `u_uv`. */
    const val QUAD_VERTEX = """#version 300 es
layout(location = 0) in vec2 a_pos;
uniform vec4 u_rect;
uniform vec4 u_uv;
out vec2 v_uv;
out vec2 v_pos;
void main() {
    v_pos = a_pos;
    v_uv = mix(u_uv.xy, u_uv.zw, a_pos);
    gl_Position = vec4(u_rect.xy + a_pos * u_rect.zw, 0.0, 1.0);
}
"""

    private const val VERSION = "#version 300 es\n"

    /** The camera's frames arrive as an external texture; GLSL ES 3 reads one through this extension. */
    private const val EXTERNAL = "#extension GL_OES_EGL_image_external_essl3 : require\n"

    /** What latent.glsl expects from its host: the table lookups. */
    private const val FILM_HOST = """
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp sampler3D;
uniform sampler2D u_tc;
uniform sampler2D u_curves;
uniform sampler3D u_enlarger;
uniform sampler3D u_scan;
uniform float u_tc_n;
uniform float u_curve_w[5];
uniform float u_enlarger_n;
uniform float u_scan_n;

vec3 tex_tc(vec2 q) {
    return texture(u_tc, (q * (u_tc_n - 1.0) + 0.5) / u_tc_n).rgb;
}

// one row of the curve table, each channel at its own place, linear between samples
vec3 curve3(int row, vec3 u) {
    float w = u_curve_w[row] - 1.0;
    vec3 x = clamp(u, 0.0, 1.0) * w;
    vec3 i0 = min(floor(x), vec3(w - 1.0));
    vec3 f = x - i0;
    ivec3 i = ivec3(i0);
    float r = mix(texelFetch(u_curves, ivec2(i.x, row), 0).r, texelFetch(u_curves, ivec2(i.x + 1, row), 0).r, f.x);
    float g = mix(texelFetch(u_curves, ivec2(i.y, row), 0).g, texelFetch(u_curves, ivec2(i.y + 1, row), 0).g, f.y);
    float b = mix(texelFetch(u_curves, ivec2(i.z, row), 0).b, texelFetch(u_curves, ivec2(i.z + 1, row), 0).b, f.z);
    return vec3(r, g, b);
}

// Both colour tables are 32-bit floats interpolated here, by hand: the same
// arithmetic as the engine's own lookup, and no dependence on which float
// formats a GPU is willing to filter (the emulator's returns black for a
// filtered 3D half-float texture).
vec3 lut3(highp sampler3D table, float size, vec3 c) {
    float n = size - 1.0;
    vec3 x = clamp(c, 0.0, 1.0) * n;
    vec3 i0 = min(floor(x), vec3(n - 1.0));
    vec3 f = x - i0;
    ivec3 p = ivec3(i0.z, i0.y, i0.x);   // the table's last index runs along the texture's x
    vec3 c00 = mix(texelFetch(table, p, 0).rgb, texelFetch(table, p + ivec3(1, 0, 0), 0).rgb, f.z);
    vec3 c01 = mix(texelFetch(table, p + ivec3(0, 1, 0), 0).rgb, texelFetch(table, p + ivec3(1, 1, 0), 0).rgb, f.z);
    vec3 c10 = mix(texelFetch(table, p + ivec3(0, 0, 1), 0).rgb, texelFetch(table, p + ivec3(1, 0, 1), 0).rgb, f.z);
    vec3 c11 = mix(texelFetch(table, p + ivec3(0, 1, 1), 0).rgb, texelFetch(table, p + ivec3(1, 1, 1), 0).rgb, f.z);
    return mix(mix(c00, c01, f.y), mix(c10, c11, f.y), f.x);
}

vec3 tex_enlarger(vec3 c) {
    return lut3(u_enlarger, u_enlarger_n, c);
}

vec3 tex_scan(vec3 c) {
    return lut3(u_scan, u_scan_n, c);
}
"""

    /**
     * The darkroom's picture: mode 0 through the film, mode 1 as it came.
     * Halation is the film's exposure blurred (u_halo, made by the two passes
     * below) and added back per layer, as the engine does between exposing and
     * developing: raw = (raw + a * blurred) / (1 + a).
     */
    private const val PREVIEW_MAIN = """
uniform Grade u_grade;
uniform Film u_film;
uniform sampler2D u_src;
uniform sampler2D u_halo;
uniform vec3 u_halation;
uniform int u_mode;
uniform vec2 u_cells;
in vec2 v_uv;
out vec4 o_color;
void main() {
    vec3 rgb = texture(u_src, v_uv).rgb;
    vec3 c = rgb;
    if (u_mode == 0) {
        vec3 raw = film_expose(grade_scene(rgb, u_grade), u_film);
        if (u_halation.x + u_halation.y + u_halation.z > 0.0) {
            raw = (raw + u_halation * texture(u_halo, v_uv).rgb) / (vec3(1.0) + u_halation);
        }
        vec3 d = film_grain(film_develop(raw, u_film), floor(v_uv * u_cells), u_film);
        c = film_print(d, u_film);
    }
    o_color = vec4(lt_srgb3(c), 1.0);
}
"""

    /** Halation, first pass: the film's exposure of the graded picture, small, into a float target. */
    private const val EXPOSURE_MAIN = """
uniform Grade u_grade;
uniform Film u_film;
uniform sampler2D u_src;
in vec2 v_uv;
out vec4 o_color;
void main() {
    o_color = vec4(film_expose(grade_scene(texture(u_src, v_uv).rgb, u_grade), u_film), 1.0);
}
"""

    /** Halation, second pass (run twice): a Gaussian of `u_sigma` texels along `u_step`. */
    const val BLUR_FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D u_src;
uniform vec2 u_step;
uniform float u_sigma;
in vec2 v_uv;
out vec4 o_color;
void main() {
    vec3 sum = vec3(0.0);
    float weights = 0.0;
    for (int i = -8; i <= 8; i++) {
        float x = float(i);
        float w = exp(-x * x / (2.0 * u_sigma * u_sigma));
        sum += w * texture(u_src, v_uv + u_step * x).rgb;
        weights += w;
    }
    o_color = vec4(sum / weights, 1.0);
}
"""

    @Volatile
    private var shared: String? = null

    private fun shared(context: Context): String =
        shared ?: context.assets.open("shaders/latent.glsl").bufferedReader().use { it.readText() }.also { shared = it }

    /** A full-surface quad whose texture coordinates go through the camera's transform. */
    const val CAMERA_VERTEX = """#version 300 es
layout(location = 0) in vec2 a_pos;
uniform mat4 u_tex;
out vec2 v_uv;
out vec2 v_cam;
void main() {
    v_uv = a_pos;
    v_cam = (u_tex * vec4(a_pos, 0.0, 1.0)).xy;
    gl_Position = vec4(a_pos * 2.0 - 1.0, 0.0, 1.0);
}
"""

    /**
     * The viewfinder and the video frame: the camera's picture (display-referred
     * sRGB, as a JPEG from the same camera would be) made linear, then through
     * the same grade and film as everything else. No halation here: it needs
     * extra passes, and a moving picture has to be drawn in one.
     */
    private const val CAMERA_MAIN = """
uniform Grade u_grade;
uniform Film u_film;
uniform samplerExternalOES u_camera;
uniform vec2 u_cells;
in vec2 v_uv;
in vec2 v_cam;
out vec4 o_color;
void main() {
    vec3 c = clamp(texture(u_camera, v_cam).rgb, 0.0, 1.0);
    vec3 lin = mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c));
    vec3 raw = film_expose(grade_scene(lin, u_grade), u_film);
    vec3 d = film_grain(film_develop(raw, u_film), floor(v_uv * u_cells), u_film);
    o_color = vec4(lt_srgb3(film_print(d, u_film)), 1.0);
}
"""

    fun previewFragment(context: Context): String = VERSION + FILM_HOST + shared(context) + PREVIEW_MAIN
    fun exposureFragment(context: Context): String = VERSION + FILM_HOST + shared(context) + EXPOSURE_MAIN
    fun cameraFragment(context: Context): String = VERSION + EXTERNAL + FILM_HOST + shared(context) + CAMERA_MAIN
}

/** A unit quad in attribute 0. GL thread only. */
object Quad {
    private var vao = 0

    fun draw() {
        if (vao == 0) {
            val ids = IntArray(1)
            GLES30.glGenVertexArrays(1, ids, 0)
            vao = ids[0]
            GLES30.glBindVertexArray(vao)
            GLES30.glGenBuffers(1, ids, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[0])
            val data = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            data.put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)).position(0)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 8 * 4, data, GLES30.GL_STATIC_DRAW)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        }
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }
}
