package ru.sodovaya.volty.video

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES
import android.opengl.GLES20.*
import android.opengl.GLES30
import android.os.SystemClock
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The capture context owns the display. Its shared context draws to the encoder on another thread. */
internal class GlCameraSurface(private val shared: GlCameraSurface? = null) : AutoCloseable {
    data class Target(val texture: Int, val framebuffer: Int)
    data class Window(val surface: EGLSurface, val width: Int, val height: Int)

    private val display: EGLDisplay = shared?.display ?: EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val config: EGLConfig
    private val context: EGLContext
    private val idle: EGLSurface
    private val program: TextureProgram
    private val quad = ByteBuffer.allocateDirect(16 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)); flip() }
    private val identity = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }

    init {
        if (shared == null) check(EGL14.eglInitialize(display, null, 0, null, 0))
        var newContext = EGL14.EGL_NO_CONTEXT
        var newIdle = EGL14.EGL_NO_SURFACE
        try {
            config = shared?.config ?: chooseConfig()
            newContext = EGL14.eglCreateContext(display, config, shared?.context ?: EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            check(newContext != EGL14.EGL_NO_CONTEXT) { "Cannot create ride video GLES3 context" }
            newIdle = EGL14.eglCreatePbufferSurface(display, config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            check(newIdle != EGL14.EGL_NO_SURFACE)
            context = newContext
            idle = newIdle
            makeIdle()
            program = TextureProgram(external = shared == null)
        } catch (error: RuntimeException) {
            if (newContext != EGL14.EGL_NO_CONTEXT && EGL14.eglGetCurrentContext() == newContext) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            }
            if (newIdle != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, newIdle)
            if (newContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, newContext)
            if (shared == null) EGL14.eglTerminate(display)
            throw error
        }
    }

    private fun chooseConfig(): EGLConfig {
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE,
        ), 0, configs, 0, 1, count, 0) && count[0] == 1) { "No recordable GLES3 EGL config" }
        return checkNotNull(configs[0])
    }

    fun makeIdle() = makeCurrent(idle)

    fun ensureCurrent() {
        if (EGL14.eglGetCurrentContext() != context) makeIdle()
    }

    private fun makeCurrent(surface: EGLSurface) {
        if (EGL14.eglGetCurrentContext() == context && EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW) == surface) return
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "Cannot make ride video EGL surface current" }
    }

    fun window(surface: Surface): Window {
        val eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "Cannot open encoder EGL surface" }
        val size = IntArray(2)
        EGL14.eglQuerySurface(display, eglSurface, EGL14.EGL_WIDTH, size, 0)
        EGL14.eglQuerySurface(display, eglSurface, EGL14.EGL_HEIGHT, size, 1)
        return Window(eglSurface, size[0], size[1])
    }

    fun destroy(window: Window) {
        makeIdle()
        EGL14.eglDestroySurface(display, window.surface)
    }

    fun inputTexture(): Int = texture(GL_TEXTURE_EXTERNAL_OES)

    private fun texture(kind: Int): Int {
        val id = IntArray(1)
        glGenTextures(1, id, 0)
        glBindTexture(kind, id[0])
        glTexParameteri(kind, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(kind, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(kind, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(kind, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        return id[0]
    }

    fun deleteTexture(id: Int) = glDeleteTextures(1, intArrayOf(id), 0)

    fun target(width: Int, height: Int): Target {
        val texture = texture(GL_TEXTURE_2D)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
        val id = IntArray(1)
        glGenFramebuffers(1, id, 0)
        glBindFramebuffer(GL_FRAMEBUFFER, id[0])
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
        check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) { "Cannot allocate camera FIFO texture" }
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        return Target(texture, id[0])
    }

    fun destroy(target: Target) {
        glDeleteFramebuffers(1, intArrayOf(target.framebuffer), 0)
        deleteTexture(target.texture)
    }

    fun copy(input: Int, target: Target, width: Int, height: Int) {
        glBindFramebuffer(GL_FRAMEBUFFER, target.framebuffer)
        glViewport(0, 0, width, height)
        glDisable(GL_BLEND)
        draw(program, GL_TEXTURE_EXTERNAL_OES, input, identity)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
    }

    fun begin(window: Window, texture: Int, transform: FloatArray) {
        makeCurrent(window.surface)
        glViewport(0, 0, window.width, window.height)
        glDisable(GL_BLEND)
        draw(program, GL_TEXTURE_2D, texture, transform)
        glEnable(GL_BLEND)
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
    }

    fun present(window: Window, timestampNanos: Long, measureSwap: Boolean): Long {
        check(EGLExt.eglPresentationTimeANDROID(display, window.surface, timestampNanos)) { "Cannot stamp camera frame" }
        val started = if (measureSwap) SystemClock.elapsedRealtimeNanos() else 0L
        check(EGL14.eglSwapBuffers(display, window.surface)) { "Cannot submit camera frame" }
        return if (measureSwap) SystemClock.elapsedRealtimeNanos() - started else 0L
    }

    fun fence(): Long = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0).also {
        check(it != 0L) { "Cannot fence ride video texture" }
    }

    fun flush() = glFlush()

    fun awaitFence(fence: Long) {
        GLES30.glWaitSync(fence, 0, GLES30.GL_TIMEOUT_IGNORED)
        deleteFence(fence)
    }

    fun deleteFence(fence: Long) = GLES30.glDeleteSync(fence)

    private fun draw(program: TextureProgram, kind: Int, texture: Int, transform: FloatArray) {
        glUseProgram(program.id)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(kind, texture)
        glUniform1i(program.image, 0)
        glUniformMatrix4fv(program.matrix, 1, false, transform, 0)
        quad.position(0)
        glVertexAttribPointer(program.position, 2, GL_FLOAT, false, 16, quad)
        quad.position(2)
        glVertexAttribPointer(program.uv, 2, GL_FLOAT, false, 16, quad)
        glEnableVertexAttribArray(program.position)
        glEnableVertexAttribArray(program.uv)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        glDisableVertexAttribArray(program.position)
        glDisableVertexAttribArray(program.uv)
    }

    override fun close() {
        makeIdle()
        glDeleteProgram(program.id)
        detach()
        EGL14.eglDestroySurface(display, idle)
        EGL14.eglDestroyContext(display, context)
        if (shared == null) EGL14.eglTerminate(display)
    }

    fun detach() {
        check(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT))
    }

    private class TextureProgram(external: Boolean) {
        val id = glCreateProgram()
        val position: Int
        val uv: Int
        val matrix: Int
        val image: Int

        init {
            val vertex = compile(GL_VERTEX_SHADER, """
                attribute vec2 aPosition;
                attribute vec2 aUv;
                uniform mat4 uMatrix;
                varying vec2 vUv;
                void main() {
                    gl_Position = vec4(aPosition, 0.0, 1.0);
                    vUv = (uMatrix * vec4(aUv, 0.0, 1.0)).xy;
                }
            """.trimIndent())
            val extension = if (external) "#extension GL_OES_EGL_image_external : require\n" else ""
            val sampler = if (external) "samplerExternalOES" else "sampler2D"
            val fragment = compile(GL_FRAGMENT_SHADER, extension + """
                precision mediump float;
                uniform $sampler uImage;
                varying vec2 vUv;
                void main() { gl_FragColor = texture2D(uImage, vUv); }
            """.trimIndent())
            glAttachShader(id, vertex)
            glAttachShader(id, fragment)
            glLinkProgram(id)
            val linked = IntArray(1)
            glGetProgramiv(id, GL_LINK_STATUS, linked, 0)
            glDeleteShader(vertex)
            glDeleteShader(fragment)
            check(linked[0] != 0) { glGetProgramInfoLog(id) }
            position = glGetAttribLocation(id, "aPosition")
            uv = glGetAttribLocation(id, "aUv")
            matrix = glGetUniformLocation(id, "uMatrix")
            image = glGetUniformLocation(id, "uImage")
        }

        private fun compile(kind: Int, source: String): Int {
            val shader = glCreateShader(kind)
            glShaderSource(shader, source)
            glCompileShader(shader)
            val compiled = IntArray(1)
            glGetShaderiv(shader, GL_COMPILE_STATUS, compiled, 0)
            check(compiled[0] != 0) { glGetShaderInfoLog(shader) }
            return shader
        }
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
