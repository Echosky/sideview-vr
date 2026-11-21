// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sideview VR contributors
package app.sideviewvr

import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES30.*
import android.opengl.GLUtils

/** Room bounds in meters. The middle seat's eyes are at the origin (floor 1.2 m below). */
object RoomBounds {
    const val MIN_X = -15f; const val MAX_X = 15f
    const val MIN_Y = -1.2f; const val MAX_Y = 9f
    const val MIN_Z = -28f; const val MAX_Z = 10f
    /** Stadium seating: rows every ROW meters, each RISE higher than the one in front. */
    const val ROW = 1.1f
    const val RISE = 0.3f
    /** Floor height of seat row [row] (row 0 = the middle seat, negative = toward the screen). */
    fun tierFloor(row: Int) = MIN_Y + RISE * row
    /** First row drawn: rows closer than 2 m to the screen are left out. */
    fun firstRow(distance: Float) = maxOf(-20, kotlin.math.ceil((-distance + 2f) / ROW).toInt())
}

/** Where the virtual screen sits (before tilt). */
data class ScreenGeometry(val width: Float, val height: Float, val distance: Float, val centerY: Float, val curve: Float,
                          /** Floor in front of the screen (the lowest seat tier). */
                          val floorY: Float = RoomBounds.MIN_Y) {
    companion object {
        fun of(width: Float, aspect: Float, distance: Float, curve: Float): ScreenGeometry {
            val h = width / aspect
            val floor = RoomBounds.tierFloor(RoomBounds.firstRow(distance))
            // Like a stadium cinema: the bottom edge 0.6 m above the front floor, else centered at eye level.
            val cy = maxOf(0f, floor + 0.6f + h / 2)
            return ScreenGeometry(width, h, distance, cy, curve, floor)
        }
    }
}

private const val OES_EXT = "#extension GL_OES_EGL_image_external_essl3 : require\n"

/** A procedurally shaded screening room (no textures). Lit by the screen through a tiny mipmapped copy of the video. */
class CinemaRoom {
    private val prog = Gl.program(
        """#version 300 es
        uniform mat4 uMvp;
        uniform float uFloorY;
        layout(location=0) in vec3 aPos;
        out vec3 vPos;
        void main() {
            vec3 p = aPos;
            if (p.y < -1.0) p.y = uFloorY;   // the floor drops with the seat tiers
            vPos = p; gl_Position = uMvp * vec4(p, 1.0);
        }
        """,
        """#version 300 es
        precision highp float;
        uniform vec3 uMin; uniform vec3 uMax;
        uniform vec3 uScreen; uniform vec2 uHalf;
        uniform float uGlow; uniform float uLights;
        uniform sampler2D uGlowTex;
        in vec3 vPos;
        out vec4 fragColor;
        float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }
        void main() {
            vec3 p = vPos;
            vec3 dMin = abs(p - uMin), dMax = abs(p - uMax);
            const float e = 0.02;
            vec3 col;
            if (dMin.y < e) {
                float n = hash(floor(p.xz * 30.0));
                col = vec3(0.11, 0.07, 0.07) * (0.8 + 0.4 * n);
                col *= 0.85 + 0.15 * step(0.04, fract(p.z / 1.1));
            } else if (dMax.y < e) {
                vec2 g = fract(p.xz / 2.0) - 0.5;
                col = vec3(0.06, 0.055, 0.07) + vec3(1.0, 0.82, 0.55) * smoothstep(0.07, 0.0, length(g)) * 0.9 * uLights;
            } else {
                float u = (dMin.x < e || dMax.x < e) ? p.z : p.x;
                float f = fract(u / 1.3);
                float groove = smoothstep(0.0, 0.025, f) * smoothstep(1.0, 0.975, f);
                float shade = 0.85 + 0.15 * (p.y - uMin.y) / (uMax.y - uMin.y);
                col = mix(vec3(0.04, 0.035, 0.05), vec3(0.16, 0.13, 0.15), groove) * shade;
                if (p.y < uMin.y + 1.0) col *= 0.6;
                float strip = smoothstep(0.05, 0.0, abs(p.y - (uMin.y + 3.4)));
                if (dMin.z >= e) col += vec3(1.0, 0.65, 0.35) * 0.18 * strip * uLights;
            }
            col *= 0.35 + 0.65 * uLights;
            // Light spill from the screen, colored by the nearest part of the picture.
            vec3 d = p - uScreen;
            vec2 outside = max(abs(d.xy) - uHalf, 0.0);
            float dist = length(vec3(outside, d.z));
            vec2 guv = clamp(d.xy / (2.0 * uHalf) + 0.5, 0.0, 1.0);
            vec3 light = textureLod(uGlowTex, guv, 2.0).rgb * 0.6 + textureLod(uGlowTex, vec2(0.5), 5.0).rgb * 0.4;
            col += light * uGlow * 0.35 / (1.0 + dist * dist * 0.05);
            fragColor = vec4(col, 1.0);
        }
        """
    )
    private val uMvp = glGetUniformLocation(prog, "uMvp")
    private val uMin = glGetUniformLocation(prog, "uMin")
    private val uMax = glGetUniformLocation(prog, "uMax")
    private val uScreen = glGetUniformLocation(prog, "uScreen")
    private val uHalf = glGetUniformLocation(prog, "uHalf")
    private val uGlow = glGetUniformLocation(prog, "uGlow")
    private val uLights = glGetUniformLocation(prog, "uLights")
    private val uGlowTex = glGetUniformLocation(prog, "uGlowTex")
    private val uFloorY = glGetUniformLocation(prog, "uFloorY")
    private val vao: Int

    init {
        val x0 = RoomBounds.MIN_X; val x1 = RoomBounds.MAX_X
        val y0 = RoomBounds.MIN_Y; val y1 = RoomBounds.MAX_Y
        val z0 = RoomBounds.MIN_Z; val z1 = RoomBounds.MAX_Z
        fun quad(a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray) = a + b + c + a + c + d
        val v = floatArrayOf(x0, y0, z0); val v1 = floatArrayOf(x1, y0, z0)
        val v2 = floatArrayOf(x1, y1, z0); val v3 = floatArrayOf(x0, y1, z0)
        val v4 = floatArrayOf(x0, y0, z1); val v5 = floatArrayOf(x1, y0, z1)
        val v6 = floatArrayOf(x1, y1, z1); val v7 = floatArrayOf(x0, y1, z1)
        val data = quad(v, v1, v2, v3) + quad(v5, v4, v7, v6) + quad(v4, v, v3, v7) +
            quad(v1, v5, v6, v2) + quad(v4, v5, v1, v) + quad(v3, v2, v6, v7)
        vao = Gl.vao(Gl.floats(*data), 3)
    }

    fun draw(mvp: FloatArray, screen: ScreenGeometry, glow: Float, lights: Float, glowTex: Int) {
        glUseProgram(prog)
        glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        glUniform3f(uMin, RoomBounds.MIN_X, screen.floorY, RoomBounds.MIN_Z)
        glUniform1f(uFloorY, screen.floorY)
        glUniform3f(uMax, RoomBounds.MAX_X, RoomBounds.MAX_Y, RoomBounds.MAX_Z)
        glUniform3f(uScreen, 0f, screen.centerY, -screen.distance)
        glUniform2f(uHalf, screen.width / 2, screen.height / 2)
        glUniform1f(uGlow, glow)
        glUniform1f(uLights, lights)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, glowTex)
        glUniform1i(uGlowTex, 0)
        glBindVertexArray(vao)
        glDrawArrays(GL_TRIANGLES, 0, 36)
    }
}

/** Keeps a 32x32 mipmapped copy of the video for room lighting. */
class GlowCapture {
    val tex: Int
    private val fbo: Int
    private val prog = Gl.program(
        """#version 300 es
        uniform mat4 uTexMatrix; uniform vec4 uRect;
        out vec2 vUv;
        void main() {
            vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
            vUv = (uTexMatrix * vec4(uRect.xy + p * 0.5 * uRect.zw * 2.0, 0.0, 1.0)).xy;
            gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
        }
        """,
        "#version 300 es\n" + OES_EXT + """
        precision mediump float;
        uniform samplerExternalOES uTex;
        in vec2 vUv; out vec4 fragColor;
        void main() { fragColor = vec4(texture(uTex, vUv).rgb, 1.0); }
        """
    )
    private val uTexMatrix = glGetUniformLocation(prog, "uTexMatrix")
    private val uRect = glGetUniformLocation(prog, "uRect")
    private val vao = IntArray(1).also { glGenVertexArrays(1, it, 0) }[0]

    init {
        val ids = IntArray(1)
        glGenTextures(1, ids, 0); tex = ids[0]
        glBindTexture(GL_TEXTURE_2D, tex)
        glTexStorage2D(GL_TEXTURE_2D, 6, GL_RGBA8, 32, 32)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glGenFramebuffers(1, ids, 0); fbo = ids[0]
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0)
        glClearColor(0f, 0f, 0f, 1f); glClear(GL_COLOR_BUFFER_BIT)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glGenerateMipmap(GL_TEXTURE_2D)
    }

    /** rect = (u, v, w, h) of the left-eye image in video texture space. */
    fun update(videoTex: Int, texMatrix: FloatArray, rect: FloatArray) {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glViewport(0, 0, 32, 32)
        glDisable(GL_DEPTH_TEST)
        glUseProgram(prog)
        glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        glUniform4fv(uRect, 1, rect, 0)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTex)
        glBindVertexArray(vao)
        glDrawArrays(GL_TRIANGLES, 0, 3)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glBindTexture(GL_TEXTURE_2D, tex)
        glGenerateMipmap(GL_TEXTURE_2D)
    }
}

/** The video screen: a flat or cylindrically curved grid sampling the video texture. */
class VideoScreen {
    private val prog = Gl.program(
        """#version 300 es
        uniform mat4 uVp; uniform mat4 uTilt; uniform mat4 uTexMatrix;
        uniform vec4 uGeo;   // width, height, distance, centerY
        uniform float uCurve;
        uniform vec4 uRect;  // eye sub-image in video texture space
        uniform float uShift;
        layout(location=0) in vec2 aPos;
        out vec2 vUv;
        void main() {
            float x = aPos.x * uGeo.x, y = aPos.y * uGeo.y + uGeo.w;
            vec3 p;
            if (uCurve < 0.001) {
                p = vec3(x, y, -uGeo.z);
            } else {
                float r = uGeo.z / uCurve;
                float a = x / r;
                p = vec3(r * sin(a), y, -uGeo.z + r - r * cos(a));
            }
            vec2 uv = aPos + 0.5;
            uv.x += uShift;
            vUv = (uTexMatrix * vec4(uRect.xy + uv * uRect.zw, 0.0, 1.0)).xy;
            gl_Position = uVp * uTilt * vec4(p, 1.0);
        }
        """,
        "#version 300 es\n" + OES_EXT + """
        precision mediump float;
        uniform samplerExternalOES uTex;
        uniform float uHasVideo;
        uniform vec4 uRect;
        uniform mat4 uTexMatrix;
        in vec2 vUv;
        out vec4 fragColor;
        void main() {
            vec3 c = uHasVideo > 0.5 ? texture(uTex, vUv).rgb : vec3(0.06, 0.06, 0.08);
            fragColor = vec4(c, 1.0);
        }
        """
    )
    private val uVp = glGetUniformLocation(prog, "uVp")
    private val uTilt = glGetUniformLocation(prog, "uTilt")
    private val uTexMatrix = glGetUniformLocation(prog, "uTexMatrix")
    private val uGeo = glGetUniformLocation(prog, "uGeo")
    private val uCurve = glGetUniformLocation(prog, "uCurve")
    private val uRect = glGetUniformLocation(prog, "uRect")
    private val uShift = glGetUniformLocation(prog, "uShift")
    private val uHasVideo = glGetUniformLocation(prog, "uHasVideo")
    private val vao: Int
    private val count: Int

    init {
        val nx = 48; val ny = 4
        val v = ArrayList<Float>()
        fun pt(i: Int, j: Int) { v += i.toFloat() / nx - 0.5f; v += j.toFloat() / ny - 0.5f }
        for (i in 0 until nx) for (j in 0 until ny) {
            pt(i, j); pt(i + 1, j); pt(i + 1, j + 1)
            pt(i, j); pt(i + 1, j + 1); pt(i, j + 1)
        }
        count = v.size / 2
        vao = Gl.vao(Gl.floats(*v.toFloatArray()), 2)
    }

    fun draw(vp: FloatArray, tilt: FloatArray, geo: ScreenGeometry, texMatrix: FloatArray, tex: Int,
             rect: FloatArray, shift: Float, hasVideo: Boolean) {
        glUseProgram(prog)
        glUniformMatrix4fv(uVp, 1, false, vp, 0)
        glUniformMatrix4fv(uTilt, 1, false, tilt, 0)
        glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        glUniform4f(uGeo, geo.width, geo.height, geo.distance, geo.centerY)
        glUniform1f(uCurve, geo.curve)
        glUniform4fv(uRect, 1, rect, 0)
        glUniform1f(uShift, shift)
        glUniform1f(uHasVideo, if (hasVideo) 1f else 0f)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        glBindVertexArray(vao)
        glDrawArrays(GL_TRIANGLES, 0, count)
    }
}

/** 180/360 video: a box around the eye; the fragment shader maps view direction to the video. */
class ImmersiveView {
    private val prog = Gl.program(
        """#version 300 es
        uniform mat4 uVp;
        layout(location=0) in vec3 aPos;
        out vec3 vDir;
        void main() { vDir = aPos; gl_Position = (uVp * vec4(aPos, 1.0)).xyww; }
        """,
        "#version 300 es\n" + OES_EXT + """
        precision highp float;
        uniform samplerExternalOES uTex;
        uniform mat4 uTexMatrix;
        uniform vec4 uRect;
        uniform int uProj;
        in vec3 vDir;
        out vec4 fragColor;
        const float PI = 3.14159265;
        vec2 eac(vec3 d) {
            vec3 a = abs(d);
            vec2 st; int col; int row; bool rot = false;
            if (a.x >= a.y && a.x >= a.z) {
                if (d.x > 0.0) { st = vec2(d.z, d.y) / a.x; col = 2; row = 1; }   // right
                else { st = vec2(-d.z, d.y) / a.x; col = 0; row = 1; }           // left
            } else if (a.y >= a.z) {
                if (d.y > 0.0) { st = vec2(d.x, d.z) / a.y; col = 2; row = 0; rot = true; } // up
                else { st = vec2(d.x, -d.z) / a.y; col = 0; row = 0; rot = true; }        // down
            } else {
                if (d.z < 0.0) { st = vec2(d.x, d.y) / a.z; col = 1; row = 1; }  // front
                else { st = vec2(-d.x, d.y) / a.z; col = 1; row = 0; rot = true; } // back
            }
            if (rot) st = vec2(st.y, -st.x);
            st = atan(st) * (4.0 / PI);   // equi-angular
            return vec2((float(col) + st.x * 0.5 + 0.5) / 3.0, (float(row) + st.y * 0.5 + 0.5) / 2.0);
        }
        void main() {
            vec3 d = normalize(vDir);
            float lon = atan(d.x, -d.z);
            float lat = asin(clamp(d.y, -1.0, 1.0));
            vec2 uv;
            bool inside = true;
            if (uProj == 2) {
                uv = vec2(0.5 + lon / (2.0 * PI), 0.5 + lat / PI);
            } else if (uProj == 1) {
                inside = abs(lon) <= PI * 0.5;
                uv = vec2(0.5 + lon / PI, 0.5 + lat / PI);
            } else if (uProj == 3) {
                float th = acos(clamp(-d.z, -1.0, 1.0));
                inside = th <= PI * 0.5;
                float r = th / PI;
                float ph = atan(d.y, d.x);
                uv = vec2(0.5 + r * cos(ph), 0.5 + r * sin(ph));
            } else {
                uv = eac(d);
            }
            if (!inside) { fragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
            vec2 t = (uTexMatrix * vec4(uRect.xy + clamp(uv, 0.0, 1.0) * uRect.zw, 0.0, 1.0)).xy;
            fragColor = vec4(texture(uTex, t).rgb, 1.0);
        }
        """
    )
    private val uVp = glGetUniformLocation(prog, "uVp")
    private val uTexMatrix = glGetUniformLocation(prog, "uTexMatrix")
    private val uRect = glGetUniformLocation(prog, "uRect")
    private val uProj = glGetUniformLocation(prog, "uProj")
    private val vao: Int

    init {
        val s = 10f
        val c = arrayOf(
            floatArrayOf(-s, -s, -s), floatArrayOf(s, -s, -s), floatArrayOf(s, s, -s), floatArrayOf(-s, s, -s),
            floatArrayOf(-s, -s, s), floatArrayOf(s, -s, s), floatArrayOf(s, s, s), floatArrayOf(-s, s, s))
        val faces = intArrayOf(0, 1, 2, 3, 5, 4, 7, 6, 4, 0, 3, 7, 1, 5, 6, 2, 4, 5, 1, 0, 3, 2, 6, 7)
        val v = ArrayList<Float>()
        for (f in 0 until 6) {
            val q = IntArray(4) { faces[f * 4 + it] }
            for (i in intArrayOf(0, 1, 2, 0, 2, 3)) c[q[i]].forEach { v += it }
        }
        vao = Gl.vao(Gl.floats(*v.toFloatArray()), 3)
    }

    /** [vp] must contain rotation only (no translation). */
    fun draw(vp: FloatArray, texMatrix: FloatArray, tex: Int, rect: FloatArray, projection: Int) {
        glDepthMask(false)
        glUseProgram(prog)
        glUniformMatrix4fv(uVp, 1, false, vp, 0)
        glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        glUniform4fv(uRect, 1, rect, 0)
        glUniform1i(uProj, projection)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        glBindVertexArray(vao)
        glDrawArrays(GL_TRIANGLES, 0, 36)
        glDepthMask(true)
    }
}

/** The controller's laser: a thin camera-facing beam from the controller to the pointer, fading out. */
class LaserBeam {
    private val prog = Gl.program(
        """#version 300 es
        uniform mat4 uVp; uniform vec3 uP[4];
        out float vT; out float vS;
        void main() {
            int i = gl_VertexID;
            int c = (i == 0 || i == 3) ? 0 : (i == 1) ? 1 : (i == 2 || i == 4) ? 2 : 3;
            vT = (c < 2) ? 0.0 : 1.0;          // 0 at the controller, 1 at the pointer
            vS = (c == 0 || c == 3) ? -1.0 : 1.0;
            gl_Position = uVp * vec4(uP[c], 1.0);
        }
        """,
        """#version 300 es
        precision mediump float;
        uniform vec4 uColor;
        in float vT; in float vS; out vec4 fragColor;
        void main() {
            float core = 1.0 - smoothstep(0.0, 1.0, abs(vS));
            float a = uColor.a * mix(0.85, 0.15, vT) * core;
            fragColor = vec4(mix(uColor.rgb, vec3(1.0), core * 0.5), a);
        }
        """
    )
    private val uVp = glGetUniformLocation(prog, "uVp")
    private val uP = glGetUniformLocation(prog, "uP")
    private val uColor = glGetUniformLocation(prog, "uColor")
    private val vao = IntArray(1).also { glGenVertexArrays(1, it, 0) }[0]
    private val pts = FloatArray(12)

    /** [from], [to] and [eye] are world positions; draw with blending on and depth test off. */
    fun draw(vp: FloatArray, from: FloatArray, to: FloatArray, eye: FloatArray, r: Float, g: Float, b: Float, alpha: Float) {
        val ax = to[0] - from[0]; val ay = to[1] - from[1]; val az = to[2] - from[2]
        val vx = eye[0] - from[0]; val vy = eye[1] - from[1]; val vz = eye[2] - from[2]
        var sx = ay * vz - az * vy; var sy = az * vx - ax * vz; var sz = ax * vy - ay * vx
        val len = kotlin.math.sqrt(sx * sx + sy * sy + sz * sz)
        if (len < 1e-6f) return
        sx /= len; sy /= len; sz /= len
        val w0 = 0.006f; val w1 = 0.012f    // half widths: thin at the hand, slightly wider far away
        pts[0] = from[0] - sx * w0; pts[1] = from[1] - sy * w0; pts[2] = from[2] - sz * w0
        pts[3] = from[0] + sx * w0; pts[4] = from[1] + sy * w0; pts[5] = from[2] + sz * w0
        pts[6] = to[0] + sx * w1; pts[7] = to[1] + sy * w1; pts[8] = to[2] + sz * w1
        pts[9] = to[0] - sx * w1; pts[10] = to[1] - sy * w1; pts[11] = to[2] - sz * w1
        glUseProgram(prog)
        glUniformMatrix4fv(uVp, 1, false, vp, 0)
        glUniform3fv(uP, 4, pts, 0)
        glUniform4f(uColor, r, g, b, alpha)
        glBindVertexArray(vao)
        glDrawArrays(GL_TRIANGLES, 0, 6)
    }
}

/** A textured quad for the in-VR control panel and the pointer dot. */
class PanelQuad {
    private val prog = Gl.program(
        """#version 300 es
        uniform mat4 uMvp;
        layout(location=0) in vec2 aPos;
        out vec2 vUv;
        void main() { vUv = vec2(aPos.x + 0.5, 0.5 - aPos.y); gl_Position = uMvp * vec4(aPos, 0.0, 1.0); }
        """,
        """#version 300 es
        precision mediump float;
        uniform sampler2D uTex; uniform vec4 uColor; uniform int uDot;
        in vec2 vUv; out vec4 fragColor;
        void main() {
            if (uDot == 1) {
                float r = length(vUv - 0.5);
                float a = smoothstep(0.5, 0.35, r);
                fragColor = vec4(mix(vec3(0.0), uColor.rgb, smoothstep(0.42, 0.3, r)), a * uColor.a);
            } else {
                fragColor = texture(uTex, vUv) * uColor;
            }
        }
        """
    )
    private val uMvp = glGetUniformLocation(prog, "uMvp")
    private val uTex = glGetUniformLocation(prog, "uTex")
    private val uColor = glGetUniformLocation(prog, "uColor")
    private val uDot = glGetUniformLocation(prog, "uDot")
    private val vao = Gl.vao(Gl.floats(-.5f, -.5f, .5f, -.5f, .5f, .5f, -.5f, -.5f, .5f, .5f, -.5f, .5f), 2)
    private var tex = 0
    var texWidth = 0; private set
    var texHeight = 0; private set

    fun upload(bmp: Bitmap) {
        if (tex == 0) {
            val ids = IntArray(1); glGenTextures(1, ids, 0); tex = ids[0]
        }
        glBindTexture(GL_TEXTURE_2D, tex)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GL_TEXTURE_2D, 0, bmp, 0)
        texWidth = bmp.width; texHeight = bmp.height
    }

    val hasTexture get() = tex != 0

    fun drawPanel(mvp: FloatArray) {
        glUseProgram(prog)
        glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        glUniform1i(uDot, 0)
        glUniform4f(uColor, 1f, 1f, 1f, 1f)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, tex)
        glUniform1i(uTex, 0)
        glBindVertexArray(vao)
        glDrawArrays(GL_TRIANGLES, 0, 6)
    }

    fun drawDot(mvp: FloatArray, r: Float, g: Float, b: Float, alpha: Float = 1f) {
        glUseProgram(prog)
        glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        glUniform1i(uDot, 1)
        glUniform4f(uColor, r, g, b, alpha)
        glBindVertexArray(vao)
        glDrawArrays(GL_TRIANGLES, 0, 6)
    }
}

/** Draws one eye's image onto its half of the display through the inverse lens distortion. */
class LensDistortion {
    private val prog = Gl.program(
        """#version 300 es
        void main() {
            vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
            gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
        }
        """,
        """#version 300 es
        precision highp float;
        uniform sampler2D uEye;
        uniform vec2 uLensCenter;   // window pixels
        uniform float uPxPerTan;    // pixels per unit tan-angle (px/m * screen-to-lens m)
        uniform vec2 uK;            // radial distortion k1, k2
        uniform float uTanMax;      // half-extent of the eye image in tan-angle
        out vec4 fragColor;
        void main() {
            vec2 t = (gl_FragCoord.xy - uLensCenter) / uPxPerTan;
            float r2 = dot(t, t);
            t *= 1.0 + uK.x * r2 + uK.y * r2 * r2;   // where the lens makes this pixel appear
            vec2 uv = t / uTanMax * 0.5 + 0.5;
            if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) {
                fragColor = vec4(0.0, 0.0, 0.0, 1.0);
            } else {
                fragColor = texture(uEye, uv);
            }
        }
        """
    )
    private val uEye = glGetUniformLocation(prog, "uEye")
    private val uLensCenter = glGetUniformLocation(prog, "uLensCenter")
    private val uPxPerTan = glGetUniformLocation(prog, "uPxPerTan")
    private val uK = glGetUniformLocation(prog, "uK")
    private val uTanMax = glGetUniformLocation(prog, "uTanMax")
    private val emptyVao = IntArray(1).also { glGenVertexArrays(1, it, 0) }[0]

    fun draw(eyeTex: Int, lensX: Float, lensY: Float, pxPerTan: Float, k1: Float, k2: Float, tanMax: Float) {
        glUseProgram(prog)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, eyeTex)
        glUniform1i(uEye, 0)
        glUniform2f(uLensCenter, lensX, lensY)
        glUniform1f(uPxPerTan, pxPerTan)
        glUniform2f(uK, k1, k2)
        glUniform1f(uTanMax, tanMax)
        glBindVertexArray(emptyVao)
        glDrawArrays(GL_TRIANGLES, 0, 3)
    }
}
