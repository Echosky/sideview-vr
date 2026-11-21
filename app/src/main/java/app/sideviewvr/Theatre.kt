// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sideview VR contributors
package app.sideviewvr

import android.opengl.GLES30.*
import kotlin.math.cos
import kotlin.math.sin

/** Lighting shared by the theatre props: warm house lights plus light spilled from the screen. */
private const val LIGHT_GLSL = """
uniform vec3 uScreen; uniform vec2 uHalf;
uniform float uGlow; uniform float uLights;
uniform sampler2D uGlowTex;
vec3 sceneLight(vec3 p, vec3 n) {
    vec3 house = vec3(1.0, 0.86, 0.7) * (0.35 + 0.9 * max(n.y, 0.0) + 0.35 * max(n.z, 0.0)) * uLights;
    vec3 toScreen = uScreen - p;
    vec2 outside = max(abs(p.xy - uScreen.xy) - uHalf, 0.0);
    float dist = length(vec3(outside, p.z - uScreen.z));
    vec2 guv = clamp((p.xy - uScreen.xy) / (2.0 * uHalf) + 0.5, 0.0, 1.0);
    vec3 spill = textureLod(uGlowTex, guv, 3.0).rgb * 0.5 + textureLod(uGlowTex, vec2(0.5), 5.0).rgb * 0.5;
    float facing = max(dot(n, normalize(toScreen)), 0.0) * 0.8 + 0.2;
    return house + spill * uGlow * facing * 1.6 / (1.0 + dist * dist * 0.05);
}
"""

private class LightUniforms(prog: Int) {
    val screen = glGetUniformLocation(prog, "uScreen")
    val half = glGetUniformLocation(prog, "uHalf")
    val glow = glGetUniformLocation(prog, "uGlow")
    val lights = glGetUniformLocation(prog, "uLights")
    val tex = glGetUniformLocation(prog, "uGlowTex")
    fun set(geo: ScreenGeometry, glowAmount: Float, lightLevel: Float, glowTex: Int) {
        glUniform3f(screen, 0f, geo.centerY, -geo.distance)
        glUniform2f(half, geo.width / 2, geo.height / 2)
        glUniform1f(glow, glowAmount)
        glUniform1f(lights, lightLevel)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, glowTex)
        glUniform1i(tex, 0)
    }
}

/**
 * Rows of padded theatre seats. One seat model built from rounded boxes (smooth normals),
 * drawn once per seat with instancing; the seat the viewer sits in is left out.
 */
class TheatreSeats {
    private val prog = Gl.program(
        """#version 300 es
        uniform mat4 uVp;
        uniform vec3 uCam;
        uniform int uRow0;       // first row drawn (rows closer than 2 m to the screen are skipped)
        uniform float uFocusZ;   // rows are arcs around this point behind the screen
        layout(location=0) in vec3 aPos;
        layout(location=1) in vec3 aNormal;
        layout(location=2) in float aPart;   // 0 velvet, 1 dark shell / wood, 2 tier platform
        out vec3 vPos; out vec3 vNormal; out float vPart; out vec2 vSeat; out vec3 vLocal;
        const int COLS = 19;
        void main() {
            int row = uRow0 + gl_InstanceID / COLS;
            int col = gl_InstanceID % COLS - 9;
            // Stadium rows: each an arc facing the screen, each step higher than the one in front.
            float R = -uFocusZ + float(row) * ROW_M;
            float th = float(col) * 0.6 / R;
            float c = cos(th), s = sin(th);
            vec2 seat = vec2(R * s, uFocusZ + R * c);
            float floorY = FLOOR_Y + RISE_M * float(row);
            vec3 p = vec3(seat.x + c * aPos.x + s * aPos.z, floorY + aPos.y, seat.y - s * aPos.x + c * aPos.z);
            vPos = p; vNormal = vec3(c * aNormal.x + s * aNormal.z, aNormal.y, -s * aNormal.x + c * aNormal.z);
            vPart = aPart; vSeat = seat; vLocal = aPos;
            bool own = abs(seat.x - uCam.x) < 0.31 && abs(seat.y - uCam.z) < 0.6 && aPart < 1.5;
            bool aisle = (col == 7 || col == -7) && aPart < 1.5;
            gl_Position = (own || aisle) ? vec4(2.0, 2.0, 2.0, 1.0) : uVp * vec4(p, 1.0);
        }
        """.replace("FLOOR_Y", RoomBounds.MIN_Y.toString()).replace("ROW_M", RoomBounds.ROW.toString())
            .replace("RISE_M", RoomBounds.RISE.toString()),
        """#version 300 es
        precision highp float;
        uniform vec3 uCam;
        """ + LIGHT_GLSL + """
        in vec3 vPos; in vec3 vNormal; in float vPart; in vec2 vSeat; in vec3 vLocal;
        out vec4 fragColor;
        float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }
        void main() {
            vec3 n = normalize(vNormal);
            vec3 v = normalize(uCam - vPos);
            float ndv = abs(dot(n, v));
            vec3 col;
            if (vPart > 1.5) {
                // Carpeted tier with a warm step light along its front edge (stays on in the dark).
                float pile = 0.85 + 0.3 * hash(floor(vPos.xz * 90.0));
                col = vec3(0.10, 0.045, 0.05) * pile;
                if (vLocal.y > -0.05 && vLocal.z < -0.53) { fragColor = vec4(1.0, 0.62, 0.3, 1.0) * 0.55; return; }
                if (vLocal.y < -0.02) col *= 0.55;   // riser faces
                fragColor = vec4(col * sceneLight(vPos, n), 1.0);
                return;
            }
            if (vPart < 0.5) {
                // Velvet: deep red, a soft sheen at grazing angles, darker where the pile faces you.
                float pile = 0.9 + 0.2 * hash(floor(vPos.xy * 260.0) + floor(vPos.zz * 260.0));
                col = vec3(0.50, 0.035, 0.05) * pile * (0.75 + 0.25 * ndv) + vec3(0.85, 0.20, 0.22) * pow(1.0 - ndv, 3.0) * 0.55;
                col *= 0.92 + 0.16 * hash(vSeat);
            } else {
                vec3 h = normalize(v + vec3(0.0, 1.0, 0.0));
                col = vec3(0.07, 0.045, 0.035) + vec3(0.25) * pow(max(dot(n, h), 0.0), 40.0);
            }
            fragColor = vec4(col * sceneLight(vPos, n), 1.0);
        }
        """
    )
    private val uVp = glGetUniformLocation(prog, "uVp")
    private val uCam = glGetUniformLocation(prog, "uCam")
    private val uRow0 = glGetUniformLocation(prog, "uRow0")
    private val uFocusZ = glGetUniformLocation(prog, "uFocusZ")
    private val light = LightUniforms(prog)
    private val vao: Int
    private val count: Int

    init {
        val v = ArrayList<Float>(40000)
        /** Rounded box: center c, half sizes h, corner radius r, tilt (degrees about x), n = grid per face. */
        fun rbox(cx: Float, cy: Float, cz: Float, hx: Float, hy: Float, hz: Float, r: Float, tiltDeg: Float, part: Float, n: Int) {
            val ct = cos(Math.toRadians(tiltDeg.toDouble())).toFloat(); val st = sin(Math.toRadians(tiltDeg.toDouble())).toFloat()
            val hs = floatArrayOf(hx, hy, hz)
            fun vert(a: Float, b: Float, face: Int) {
                // Point on the unit cube for this face.
                val u = floatArrayOf(0f, 0f, 0f)
                val ax = face / 2; val sign = if (face % 2 == 0) 1f else -1f
                u[ax] = sign
                u[(ax + 1) % 3] = a * sign
                u[(ax + 2) % 3] = b
                val p = FloatArray(3) { u[it] * hs[it] }
                val q = FloatArray(3) { p[it].coerceIn(-(hs[it] - r), hs[it] - r) }
                var dx = p[0] - q[0]; var dy = p[1] - q[1]; var dz = p[2] - q[2]
                var len = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
                if (len < 1e-6f) { dx = 0f; dy = 0f; dz = 0f; when (ax) { 0 -> dx = sign; 1 -> dy = sign; else -> dz = sign }; len = 1f }
                val nx = dx / len; val ny = dy / len; val nz = dz / len
                val px = q[0] + nx * r; val py = q[1] + ny * r; val pz = q[2] + nz * r
                // Tilt about x (backrest leans back), then move to the center.
                v += cx + px; v += cy + py * ct - pz * st; v += cz + py * st + pz * ct
                v += nx; v += ny * ct - nz * st; v += ny * st + nz * ct
                v += part
            }
            for (face in 0 until 6) for (i in 0 until n) for (j in 0 until n) {
                val a0 = -1f + 2f * i / n; val a1 = -1f + 2f * (i + 1) / n
                val b0 = -1f + 2f * j / n; val b1 = -1f + 2f * (j + 1) / n
                vert(a0, b0, face); vert(a1, b0, face); vert(a1, b1, face)
                vert(a0, b0, face); vert(a1, b1, face); vert(a0, b1, face)
            }
        }
        // Local seat space: floor at y = 0, the occupant faces -z, backrest toward +z.
        rbox(0f, 0.43f, -0.04f, 0.25f, 0.075f, 0.24f, 0.06f, 0f, 0f, 4)        // seat cushion
        rbox(0f, 0.76f, 0.25f, 0.245f, 0.33f, 0.075f, 0.07f, 12f, 0f, 4)      // padded backrest
        rbox(0f, 0.74f, 0.33f, 0.26f, 0.35f, 0.03f, 0.025f, 12f, 1f, 3)       // back shell
        rbox(0.3f, 0.64f, 0.02f, 0.04f, 0.025f, 0.27f, 0.02f, 0f, 1f, 3)       // armrest top
        rbox(0.3f, 0.32f, 0.06f, 0.025f, 0.32f, 0.2f, 0.015f, 0f, 1f, 2)       // armrest side panel
        rbox(0f, 0.17f, 0.12f, 0.2f, 0.17f, 0.04f, 0.02f, 0f, 1f, 2)          // base
        rbox(0f, -2.0f, -0.05f, 0.3f, 2.0f, 0.55f, 0.01f, 0f, 2f, 1)         // tier platform (top = row floor)
        count = v.size / 7
        vao = Gl.vao(Gl.floats(*v.toFloatArray()), 3, 3, 1)
    }

    fun draw(vp: FloatArray, cam: FloatArray, geo: ScreenGeometry, glow: Float, lights: Float, glowTex: Int) {
        // Rows -20..7 (z = row * ROW on the center line); skip rows within 2 m of the screen.
        val first = RoomBounds.firstRow(geo.distance)
        val rows = 7 - first + 1
        if (rows <= 0) return
        glUseProgram(prog)
        glUniformMatrix4fv(uVp, 1, false, vp, 0)
        glUniform3f(uCam, cam[0], cam[1], cam[2])
        glUniform1i(uRow0, first)
        glUniform1f(uFocusZ, -geo.distance - 6f)
        light.set(geo, glow, lights, glowTex)
        glBindVertexArray(vao)
        glDrawArraysInstanced(GL_TRIANGLES, 0, count, rows * 19)
    }
}

/** Red velvet curtains framing the screen: two side drapes and a scalloped valance. */
class Curtains {
    private val prog = Gl.program(
        """#version 300 es
        uniform mat4 uVp;
        uniform vec4 uRect;   // x0, x1, y0, y1
        uniform vec2 uZ;      // z, scallop depth
        uniform float uFolds; // > 0: folds follow the cloth (gather when drawn open)
        layout(location=0) in vec2 aUv;
        out vec3 vPos; out vec3 vNormal; out float vFold;
        const float K = 6.2831853 / 0.34;   // one fold every 34 cm
        void main() {
            float x = mix(uRect.x, uRect.y, aUv.x);
            float y = mix(uRect.z, uRect.w, aUv.y);
            float k = uFolds > 0.0 ? 6.2831853 * uFolds / max(uRect.y - uRect.x, 0.05) : K;
            float ph = uFolds > 0.0 ? aUv.x * 6.2831853 * uFolds : x * K;
            y += uZ.y * (1.0 - aUv.y) * (1.0 - abs(sin(ph * 0.25)));
            float z = uZ.x + 0.07 * sin(ph);
            vFold = sin(ph);
            vNormal = normalize(vec3(-0.07 * k * cos(ph), 0.0, 1.0));
            vPos = vec3(x, y, z);
            gl_Position = uVp * vec4(vPos, 1.0);
        }
        """,
        """#version 300 es
        precision highp float;
        uniform vec3 uCam;
        """ + LIGHT_GLSL + """
        in vec3 vPos; in vec3 vNormal; in float vFold;
        out vec4 fragColor;
        void main() {
            vec3 n = normalize(vNormal);
            vec3 v = normalize(uCam - vPos);
            float rim = pow(1.0 - abs(dot(n, v)), 2.0);
            vec3 col = vec3(0.36, 0.02, 0.035) * (0.7 + 0.3 * vFold) + vec3(0.5, 0.06, 0.08) * rim * 0.5;
            fragColor = vec4(col * sceneLight(vPos, n), 1.0);
        }
        """
    )
    private val uVp = glGetUniformLocation(prog, "uVp")
    private val uRect = glGetUniformLocation(prog, "uRect")
    private val uZ = glGetUniformLocation(prog, "uZ")
    private val uCam = glGetUniformLocation(prog, "uCam")
    private val uFolds = glGetUniformLocation(prog, "uFolds")
    private val light = LightUniforms(prog)
    private val vao: Int
    private val count: Int

    init {
        val nx = 96; val ny = 6
        val v = ArrayList<Float>()
        fun pt(i: Int, j: Int) { v += i.toFloat() / nx; v += j.toFloat() / ny }
        for (i in 0 until nx) for (j in 0 until ny) {
            pt(i, j); pt(i + 1, j); pt(i + 1, j + 1)
            pt(i, j); pt(i + 1, j + 1); pt(i, j + 1)
        }
        count = v.size / 2
        vao = Gl.vao(Gl.floats(*v.toFloatArray()), 2)
    }

    /** [open]: 0 = drawn closed across the screen, 1 = pulled back to the sides. */
    fun draw(vp: FloatArray, cam: FloatArray, geo: ScreenGeometry, glow: Float, lights: Float, glowTex: Int, open: Float = 1f) {
        // Screen edge (the screen may be curved toward the viewer).
        var ex = geo.width / 2; var ez = -geo.distance
        if (geo.curve > 0.001f) {
            val r = geo.distance / geo.curve; val a = (geo.width / 2) / r
            ex = r * sin(a); ez = -geo.distance + r - r * cos(a)
        }
        val top = geo.centerY + geo.height / 2
        val floor = geo.floorY
        val outer = ex + 2.4f
        glUseProgram(prog)
        glUniformMatrix4fv(uVp, 1, false, vp, 0)
        glUniform3f(uCam, cam[0], cam[1], cam[2])
        light.set(geo, glow, lights, glowTex)
        glBindVertexArray(vao)
        // Each side curtain keeps its cloth (fold count); its inner edge travels to the middle to close.
        val o = open * open * (3 - 2 * open)          // ease in and out
        val inner = 0.04f + (ex - 0.08f - 0.04f) * o
        val folds = (outer + 0.04f) / 0.34f
        glUniform1f(uFolds, folds)
        glUniform2f(uZ, ez + 0.15f, 0f)
        glUniform4f(uRect, -outer, -inner, floor, top + 1.0f)
        glDrawArrays(GL_TRIANGLES, 0, count)
        glUniform4f(uRect, inner, outer, floor, top + 1.0f)
        glDrawArrays(GL_TRIANGLES, 0, count)
        glUniform1f(uFolds, 0f)
        glUniform2f(uZ, ez + 0.3f, 0.18f)
        glUniform4f(uRect, -outer, outer, top - 0.05f, top + 1.0f)
        glDrawArrays(GL_TRIANGLES, 0, count)
    }
}
