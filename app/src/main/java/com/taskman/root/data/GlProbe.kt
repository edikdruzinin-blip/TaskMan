package com.taskman.root.data

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31

/** What the GPU driver reports through OpenGL ES (no root needed). */
data class GlInfo(
    val renderer: String,
    val vendor: String,
    val version: String,
    /** max threads (invocations) in one compute work-group; OpenGL ES 3.1+ only */
    val maxInvocations: Int?,
    /** max work-group size in x, y, z */
    val maxGroupSize: List<Int>?,
    /** shared memory one work-group may use, bytes */
    val sharedMemBytes: Int?,
    /** shader cores when the renderer name says so ("MP3", "MC12") */
    val cores: Int?
)

/**
 * Creates a tiny off-screen OpenGL ES context, asks the driver for its limits and throws the context
 * away. Android has no API for "how many GPU cores / threads", but the compute work-group limits are
 * what the hardware really supports. Call from a background thread; never terminates the EGL display
 * (the app's own UI rendering shares it).
 */
object GlProbe {
    private val VERSION_RE = Regex("OpenGL ES (\\d+)\\.(\\d+)")
    private val CORES_RE = Regex("\\bM[PC](\\d+)\\b")

    private class Ctx(val display: EGLDisplay, val context: EGLContext, val surface: EGLSurface)

    fun query(): GlInfo? {
        return try {
            queryInternal()
        } catch (e: Throwable) {
            null
        }
    }

    private fun queryInternal(): GlInfo? {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == null || display == EGL14.EGL_NO_DISPLAY) return null
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) return null

        var ctx = open(display, true)
        val es3 = ctx != null
        if (ctx == null) ctx = open(display, false)
        if (ctx == null) return null
        try {
            return read(es3)
        } finally {
            close(ctx)
        }
    }

    private fun open(display: EGLDisplay, es3: Boolean): Ctx? {
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, if (es3) EGLExt.EGL_OPENGL_ES3_BIT_KHR else EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] <= 0) return null
        val config = configs[0] ?: return null

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, if (es3) 3 else 2, EGL14.EGL_NONE)
        val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        if (context == null || context == EGL14.EGL_NO_CONTEXT) return null

        val surfAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        val surface = EGL14.eglCreatePbufferSurface(display, config, surfAttribs, 0)
        if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroyContext(display, context)
            return null
        }
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            return null
        }
        return Ctx(display, context, surface)
    }

    private fun close(c: Ctx) {
        EGL14.eglMakeCurrent(c.display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(c.display, c.surface)
        EGL14.eglDestroyContext(c.display, c.context)
    }

    private fun read(es3: Boolean): GlInfo {
        GLES20.glGetError() // clear anything left over from context creation
        val renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: ""
        val vendor = GLES20.glGetString(GLES20.GL_VENDOR) ?: ""
        val version = GLES20.glGetString(GLES20.GL_VERSION) ?: ""

        var invocations: Int? = null
        var size: List<Int>? = null
        var shared: Int? = null

        val m = VERSION_RE.find(version)
        val major = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val minor = m?.groupValues?.get(2)?.toIntOrNull() ?: 0
        if (es3 && major * 10 + minor >= 31) {
            val one = IntArray(1)
            GLES20.glGetIntegerv(GLES31.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS, one, 0)
            if (GLES20.glGetError() == GLES20.GL_NO_ERROR) invocations = one[0]

            val dims = IntArray(3)
            var ok = true
            for (i in 0..2) {
                GLES30.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_SIZE, i, one, 0)
                if (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
                    ok = false
                    break
                }
                dims[i] = one[0]
            }
            if (ok) size = dims.toList()

            GLES20.glGetIntegerv(GLES31.GL_MAX_COMPUTE_SHARED_MEMORY_SIZE, one, 0)
            if (GLES20.glGetError() == GLES20.GL_NO_ERROR) shared = one[0]
        }
        val cores = CORES_RE.find(renderer)?.groupValues?.get(1)?.toIntOrNull()
        return GlInfo(renderer, vendor, version, invocations, size, shared, cores)
    }
}
