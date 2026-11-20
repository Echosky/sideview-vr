// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sideview VR contributors
package app.sideviewvr

import android.opengl.GLES30.*
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Small OpenGL ES helpers. */
object Gl {
    fun program(vs: String, fs: String): Int {
        val p = glCreateProgram()
        glAttachShader(p, shader(GL_VERTEX_SHADER, vs))
        glAttachShader(p, shader(GL_FRAGMENT_SHADER, fs))
        glLinkProgram(p)
        val ok = IntArray(1)
        glGetProgramiv(p, GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) error("link: " + glGetProgramInfoLog(p))
        return p
    }

    private fun shader(type: Int, src: String): Int {
        val s = glCreateShader(type)
        glShaderSource(s, src)
        glCompileShader(s)
        val ok = IntArray(1)
        glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = glGetShaderInfoLog(s)
            Log.e("SideviewGl", "shader error: $log\n$src")
            error("compile: $log")
        }
        return s
    }

    fun floats(vararg v: Float): FloatBuffer =
        ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(v); position(0)
        }

    /** Uploads a static vertex buffer and returns a VAO with float attributes of the given sizes. */
    fun vao(data: FloatBuffer, vararg sizes: Int): Int {
        val ids = IntArray(1)
        glGenVertexArrays(1, ids, 0)
        val vao = ids[0]
        glBindVertexArray(vao)
        glGenBuffers(1, ids, 0)
        glBindBuffer(GL_ARRAY_BUFFER, ids[0])
        glBufferData(GL_ARRAY_BUFFER, data.capacity() * 4, data, GL_STATIC_DRAW)
        val stride = sizes.sum() * 4
        var offset = 0
        sizes.forEachIndexed { i, n ->
            glEnableVertexAttribArray(i)
            glVertexAttribPointer(i, n, GL_FLOAT, false, stride, offset)
            offset += n * 4
        }
        glBindVertexArray(0)
        return vao
    }
}

/** Offscreen color+depth target for one eye. */
class EyeTarget(val size: Int) {
    val fbo: Int
    val tex: Int

    init {
        val ids = IntArray(1)
        glGenTextures(1, ids, 0); tex = ids[0]
        glBindTexture(GL_TEXTURE_2D, tex)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, size, size, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glGenRenderbuffers(1, ids, 0)
        val depth = ids[0]
        glBindRenderbuffer(GL_RENDERBUFFER, depth)
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, size, size)
        glGenFramebuffers(1, ids, 0); fbo = ids[0]
        glBindFramebuffer(GL_FRAMEBUFFER, fbo)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0)
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
    }
}
