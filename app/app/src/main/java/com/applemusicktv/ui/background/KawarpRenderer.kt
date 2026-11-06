package com.applemusicktv.ui.background

import android.graphics.Bitmap
import android.opengl.GLES20 as GL
import android.opengl.GLUtils
import com.applemusicktv.data.NetworkLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Kept by the view across EGL surface recreation, not advanced while the app is hidden. */
internal class FluidClock {
    var time = 0.0
    var paletteTime = 0.0
    var speed = FluidTuning.ANIMATION_SPEED
}

/** All methods run on the owning EGL thread. No readback, simulation or per-frame image decode. */
internal class KawarpRenderer(private val clock: FluidClock) {
    private class Program(vertex: String, fragment: String) {
        val id: Int
        private val uniforms = HashMap<String, Int>()
        init {
            val v = shader(GL.GL_VERTEX_SHADER, vertex)
            val f = try { shader(GL.GL_FRAGMENT_SHADER, fragment) }
                catch (e: Exception) { GL.glDeleteShader(v); throw e }
            id = GL.glCreateProgram()
            GL.glAttachShader(id, v)
            GL.glAttachShader(id, f)
            GL.glBindAttribLocation(id, 0, "a_position")
            GL.glBindAttribLocation(id, 1, "a_texCoord")
            GL.glLinkProgram(id)
            GL.glDeleteShader(v)
            GL.glDeleteShader(f)
            val status = IntArray(1)
            GL.glGetProgramiv(id, GL.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val reason = GL.glGetProgramInfoLog(id)
                GL.glDeleteProgram(id)
                error("Kawarp link: $reason")
            }
        }
        fun location(name: String): Int = uniforms.getOrPut(name) { GL.glGetUniformLocation(id, name) }
        fun float(name: String, value: Float) = GL.glUniform1f(location(name), value)
        fun pair(name: String, x: Float, y: Float) = GL.glUniform2f(location(name), x, y)
        fun texture(name: String, unit: Int, texture: Int) {
            GL.glActiveTexture(GL.GL_TEXTURE0 + unit)
            GL.glBindTexture(GL.GL_TEXTURE_2D, texture)
            GL.glUniform1i(location(name), unit)
        }
        companion object {
            private fun shader(kind: Int, source: String): Int {
                val shader = GL.glCreateShader(kind)
                GL.glShaderSource(shader, source)
                GL.glCompileShader(shader)
                val status = IntArray(1)
                GL.glGetShaderiv(shader, GL.GL_COMPILE_STATUS, status, 0)
                if (status[0] == 0) {
                    val reason = GL.glGetShaderInfoLog(shader)
                    GL.glDeleteShader(shader)
                    error("Kawarp shader: $reason")
                }
                return shader
            }
        }
    }

    private data class Target(val texture: Int, val framebuffer: Int, val width: Int, val height: Int)
    private val programs = ArrayList<Program>()
    private val targets = ArrayList<Target>()
    private lateinit var blur: Program
    private lateinit var tint: Program
    private lateinit var blend: Program
    private lateinit var palette: Program
    private lateinit var paletteMap: Target
    private lateinit var warp: Program
    private lateinit var output: Program
    private lateinit var blurA: Target
    private lateinit var blurB: Target
    private lateinit var current: Target
    private lateinit var next: Target
    private lateinit var snapshot: Target
    private var warped: Target? = null
    private var sourceTexture = 0
    private var maxTextureSize = 2048
    private var hasImage = false
    private var transitioning = false
    private var transitionStart = 0L
    private var lastFrame = 0L
    private val positions = floats(-1f, -1f, 1f, -1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f, 1f)
    private val texCoords = floats(0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f, 1f)

    fun initialize() {
        // ES2 fragment highp is optional. An unsupported GPU gets the static fallback,
        // rather than overflowing the original simplex noise in mediump.
        val range = IntArray(2)
        val precision = IntArray(1)
        GL.glGetShaderPrecisionFormat(GL.GL_FRAGMENT_SHADER, GL.GL_HIGH_FLOAT, range, 0, precision, 0)
        check(precision[0] > 0) { "Fragment highp unavailable" }
        val limit = IntArray(1)
        GL.glGetIntegerv(GL.GL_MAX_TEXTURE_SIZE, limit, 0)
        maxTextureSize = limit[0].coerceAtLeast(128)
        GL.glDisable(GL.GL_DEPTH_TEST)
        GL.glDisable(GL.GL_BLEND)
        GL.glClearColor(0f, 0f, 0f, 1f)
        fun program(fragment: String) = Program(KawarpShaders.VERTEX, fragment).also { programs += it }
        blur = program(KawarpShaders.BLUR)
        tint = program(KawarpShaders.TINT)
        blend = program(KawarpShaders.BLEND)
        palette = program(KawarpShaders.PALETTE)
        warp = program(KawarpShaders.WARP)
        output = program(KawarpShaders.OUTPUT)
        blurA = target(128, 128)
        blurB = target(128, 128)
        current = target(128, 128)
        next = target(128, 128)
        snapshot = target(128, 128)
        paletteMap = target(128, 128)
        sourceTexture = texture()
        NetworkLog.line("Kawarp", "GLES2 ready; ${GL.glGetString(GL.GL_RENDERER)}; blur=128/${FluidTuning.BLUR_PASSES}; tuned demo parameters; scale=${FluidTuning.SCALE} speed=${FluidTuning.ANIMATION_SPEED}; palette cycle=${FluidTuning.PALETTE_CYCLE_SECONDS}s")
        checkGl()
    }

    /** Snapshot the currently blended colour map before replacing an in-flight destination. */
    fun setArtwork(bitmap: Bitmap?, now: Long) {
        if (bitmap == null) {
            hasImage = false
            transitioning = false
            return
        }
        if (hasImage) {
            val amount = blendAmount(now)
            if (transitioning && amount < 1f) mix(current.texture, next.texture, amount, snapshot)
            else copy(next.texture, snapshot)
            val old = current
            current = snapshot
            snapshot = old
        }
        GL.glActiveTexture(GL.GL_TEXTURE0)
        GL.glBindTexture(GL.GL_TEXTURE_2D, sourceTexture)
        GLUtils.texImage2D(GL.GL_TEXTURE_2D, 0, bitmap, 0)
        use(tint, blurA)
        tint.texture("u_texture", 0, sourceTexture)
        GL.glUniform3f(tint.location("u_tintColor"), 0.157f, 0.157f, 0.235f)
        tint.float("u_tintIntensity", FluidTuning.TINT_INTENSITY)
        draw()
        var read = blurA
        var write = blurB
        repeat(FluidTuning.BLUR_PASSES) { pass ->
            use(blur, write)
            blur.texture("u_texture", 0, read.texture)
            blur.pair("u_resolution", 128f, 128f)
            blur.float("u_offset", pass + 0.5f)
            draw()
            val old = read
            read = write
            write = old
        }
        copy(read.texture, next)
        transitioning = hasImage
        transitionStart = now
        hasImage = true
        checkGl()
    }

    fun render(width: Int, height: Int, state: FluidRenderState, now: Long): Boolean {
        if (width <= 0 || height <= 0) return false
        val dt = if (lastFrame == 0L) 0.0 else ((now - lastFrame) / 1_000_000_000.0).coerceIn(0.0, 0.1)
        lastFrame = now
        val targetSpeed = if (state.playing) FluidTuning.ANIMATION_SPEED else 0.12f
        clock.speed += (targetSpeed - clock.speed) * (1.0 - exp(-dt / 0.325)).toFloat()
        clock.time += dt * clock.speed
        // Colour tempo is independent of the warp speed setting. Hidden time is never advanced.
        clock.paletteTime += dt * if (state.playing) 1.0 else 0.12
        if (!hasImage) {
            GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
            GL.glViewport(0, 0, width, height)
            GL.glClear(GL.GL_COLOR_BUFFER_BIT)
            return false
        }
        val cap = min(state.quality.maxEdge, maxTextureSize).coerceAtLeast(128)
        val scale = min(1f, cap.toFloat() / max(width, height))
        val w = (width * scale).roundToInt().coerceAtLeast(1)
        val h = (height * scale).roundToInt().coerceAtLeast(1)
        val previous = warped
        if (previous == null || previous.width != w || previous.height != h) {
            previous?.let(::delete)
            warped = target(w, h)
            NetworkLog.line("Kawarp", "warp=${w}x$h; ${state.quality.name}; ${state.quality.fps}fps limit")
        }
        val amount = blendAmount(now)
        var source = next.texture
        if (transitioning && amount < 1f) {
            mix(current.texture, next.texture, amount, blurA)
            source = blurA.texture
        } else transitioning = false
        // Evolve colours on the small map, then apply the unchanged spatial warp.
        use(palette, paletteMap)
        palette.texture("u_texture", 0, source)
        palette.float("u_phase", ((clock.paletteTime / FluidTuning.PALETTE_CYCLE_SECONDS) % 1.0).toFloat())
        draw()
        use(warp, warped!!)
        warp.texture("u_texture", 0, paletteMap.texture)
        warp.float("u_time", clock.time.toFloat())
        warp.float("u_intensity", FluidTuning.WARP_INTENSITY)
        draw()
        use(output, null, width, height)
        output.texture("u_texture", 0, warped!!.texture)
        output.float("u_saturation", FluidTuning.SATURATION)
        output.float("u_dithering", FluidTuning.DITHERING)
        output.float("u_time", clock.time.toFloat())
        output.float("u_scale", FluidTuning.SCALE)
        output.pair("u_resolution", width.toFloat(), height.toFloat())
        draw()
        checkGl()
        return true
    }

    fun resetFrameClock() { lastFrame = 0L }
    fun release() {
        targets.toList().forEach(::delete)
        programs.forEach { GL.glDeleteProgram(it.id) }
        programs.clear()
        if (sourceTexture != 0) GL.glDeleteTextures(1, intArrayOf(sourceTexture), 0)
    }
    private fun blendAmount(now: Long): Float {
        if (!transitioning) return 1f
        val t = ((now - transitionStart) / (FluidTuning.TRANSITION_SECONDS * 1_000_000_000.0)).coerceIn(0.0, 1.0)
        return (0.5 - 0.5 * cos(t * Math.PI)).toFloat()
    }
    private fun mix(a: Int, b: Int, amount: Float, target: Target) {
        use(blend, target)
        blend.texture("u_texture1", 0, a)
        blend.texture("u_texture2", 1, b)
        blend.float("u_blend", amount)
        draw()
    }
    private fun copy(source: Int, target: Target) {
        use(blur, target)
        blur.texture("u_texture", 0, source)
        blur.pair("u_resolution", 128f, 128f)
        blur.float("u_offset", 0f)
        draw()
    }
    private fun use(program: Program, target: Target?, width: Int = 128, height: Int = 128) {
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, target?.framebuffer ?: 0)
        GL.glViewport(0, 0, target?.width ?: width, target?.height ?: height)
        GL.glUseProgram(program.id)
        positions.position(0)
        texCoords.position(0)
        GL.glEnableVertexAttribArray(0)
        GL.glEnableVertexAttribArray(1)
        GL.glVertexAttribPointer(0, 2, GL.GL_FLOAT, false, 0, positions)
        GL.glVertexAttribPointer(1, 2, GL.GL_FLOAT, false, 0, texCoords)
    }
    private fun draw() = GL.glDrawArrays(GL.GL_TRIANGLES, 0, 6)
    private fun texture(): Int {
        val id = IntArray(1)
        GL.glGenTextures(1, id, 0)
        GL.glBindTexture(GL.GL_TEXTURE_2D, id[0])
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
        return id[0]
    }
    private fun target(width: Int, height: Int): Target {
        val texture = texture()
        GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA, width, height, 0, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, null)
        val id = IntArray(1)
        GL.glGenFramebuffers(1, id, 0)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, id[0])
        GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, texture, 0)
        if (GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER) != GL.GL_FRAMEBUFFER_COMPLETE) {
            GL.glDeleteFramebuffers(1, id, 0)
            GL.glDeleteTextures(1, intArrayOf(texture), 0)
            error("Incomplete RGBA8 framebuffer")
        }
        return Target(texture, id[0], width, height).also { targets += it }
    }
    private fun delete(target: Target) {
        GL.glDeleteTextures(1, intArrayOf(target.texture), 0)
        GL.glDeleteFramebuffers(1, intArrayOf(target.framebuffer), 0)
        targets.remove(target)
    }
    private fun checkGl() {
        val error = GL.glGetError()
        check(error == GL.GL_NO_ERROR) { "GL error 0x${error.toString(16)}" }
    }
    companion object {
        private fun floats(vararg values: Float): FloatBuffer = ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
    }
}
