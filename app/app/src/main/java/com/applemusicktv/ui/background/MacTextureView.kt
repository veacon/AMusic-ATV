package com.applemusicktv.ui.background

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import android.view.Choreographer
import android.view.TextureView
import android.view.View
import com.applemusicktv.data.NetworkLog
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/** A composited background texture: never owns touch, focus, an audio decoder or the player. */
internal class MacTextureView(context: Context, private val clock: MacClock, private val onFailure: () -> Unit) :
    TextureView(context), TextureView.SurfaceTextureListener {

    @Volatile private var artwork: Bitmap? = null
    @Volatile private var renderState = FluidRenderState()
    @Volatile private var active = false
    @Volatile private var closed = false
    @Volatile private var surfaceGeneration = 0
    @Volatile private var surfaceWidth = 1
    @Volatile private var surfaceHeight = 1
    // A reused SurfaceTexture can have its default size reset when its View layer returns.
    private var recheckAfterLayerUpdate = true
    private var worker: Worker? = null

    init {
        surfaceTextureListener = this
        isOpaque = true
        isFocusable = false
        isClickable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        alpha = 0f // The CPU fallback stays visible until a real GL frame is presented.
    }

    fun update(bitmap: Bitmap?, state: FluidRenderState, foreground: Boolean) {
        if (closed) return
        val changed = artwork !== bitmap || renderState != state || active != foreground
        if (foreground && !active) requestOutputRecovery()
        artwork = bitmap
        renderState = state
        active = foreground
        if (changed) worker?.wake()
    }

    private fun requestOutputRecovery() {
        recheckAfterLayerUpdate = true
        surfaceGeneration++
        alpha = 0f
        worker?.wake()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE && !closed) requestOutputRecovery()
    }

    fun close() {
        if (closed) return
        closed = true
        active = false
        artwork = null
        worker?.stop(false)
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        if (closed) return
        if (!isHardwareAccelerated) {
            NetworkLog.line("Mac", "hardware composition unavailable; static fallback")
            onFailure()
            return
        }
        surfaceWidth = width
        surfaceHeight = height
        recheckAfterLayerUpdate = true
        // The worker owns buffer sizing and EGL surface recreation; the View remains full size.
        alpha = 0f
        worker = Worker(texture).also { it.start() }
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        // TextureView itself resets its default buffer on a View resize, even at the same aspect.
        surfaceGeneration++
        worker?.wake()
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        val old = worker ?: return true
        worker = null
        // EGL must let go before the native window is released.
        old.stop(true)
        return false
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        // The first update confirms that TextureView's hardware layer has been acquired.
        // Reapply the small output buffer once after that point, even if dimensions did not change.
        if (!closed && active && recheckAfterLayerUpdate) {
            recheckAfterLayerUpdate = false
            surfaceGeneration++
            alpha = 0f
            worker?.wake()
            return
        }
        if (!closed) alpha = if (worker?.visibleFrame == true) 1f else 0f
    }

    private inner class Worker(private val texture: SurfaceTexture) {
        private val thread = HandlerThread("Mac-GLES2")
        private lateinit var handler: Handler
        private val stopping = AtomicBoolean(false)
        private val textureReleased = AtomicBoolean(false)
        @Volatile private var releaseTexture = false
        @Volatile private var cleanedUp = false
        @Volatile var visibleFrame = false
            private set
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var eglConfig: EGLConfig? = null
        private var outputSize: BackgroundOutputSize? = null
        private var outputGeneration = -1
        private var reportedQuality: FluidQuality? = null
        private var window: Surface? = null
        private var renderer: MacRenderer? = null
        private var uploaded: Bitmap? = null
        private var contextRestarts = 0

        private var choreographer: Choreographer? = null
        private val vsyncFrame = Choreographer.FrameCallback { timestamp -> onVsync(timestamp) }
        private fun onVsync(timestamp: Long) {
            if (!stopping.get() && !closed && active) {
                choreographer?.postFrameCallback(vsyncFrame)
                render()
            }
        }
        private val frame = Runnable {
            choreographer?.postFrameCallback(vsyncFrame)
            render()
        }

        fun start() {
            thread.start()
            handler = Handler(thread.looper)
            handler.post {
                try { choreographer = Choreographer.getInstance(); createEgl(); schedule() }
                catch (e: Exception) { fail(e) }
            }
        }

        fun wake() {
            if (!stopping.get()) handler.post { schedule() }
        }

        private fun schedule() {
            handler.removeCallbacks(frame)
            choreographer?.removeFrameCallback(vsyncFrame)
            if (!stopping.get() && !closed && active && renderer != null) {
                renderer?.resetFrameClock()
                handler.post(frame)
            }
        }

        private fun render() {
            if (stopping.get() || closed || !active) return
            val started = SystemClock.uptimeMillis()
            try {
                val renderer = renderer ?: return
                val now = System.nanoTime()
                val bitmap = artwork
                if (bitmap !== uploaded) {
                    renderer.setArtwork(bitmap, now)
                    uploaded = bitmap
                }
                val state = renderState
                val viewWidth = surfaceWidth
                val viewHeight = surfaceHeight
                val requested = backgroundOutputSize(viewWidth, viewHeight, state.quality)
                ensureOutputSurface(requested)
                // The scene receives the full View dimensions. Only the final viewport shrinks.
                visibleFrame = renderer.render(viewWidth, viewHeight, state, now,
                    requested.width, requested.height)
                if (reportedQuality != state.quality) {
                    NetworkLog.line("MacOutput", "quality=${state.quality.name}; pacing=display-vsync; view=${viewWidth}x$viewHeight; buffer=${requested.width}x${requested.height}")
                    reportedQuality = state.quality
                }
                if (!EGL14.eglSwapBuffers(display, eglSurface)) {
                    val eglError = EGL14.eglGetError()
                    // Re-upload retained artwork after a single recoverable context loss.
                    if (eglError == 0x300E && contextRestarts++ == 0) {
                        NetworkLog.line("Mac", "EGL context lost; rebuilding retained artwork")
                        destroyEgl()
                        createEgl()
                    } else error("EGL swap failed: 0x${eglError.toString(16)}")
                }
            } catch (e: Exception) { fail(e) }
        }

        private fun createEgl() {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL initialize failed" }
            val attributes = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 0, EGL14.EGL_STENCIL_SIZE, 0,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "No RGBA8 ES2 config" }
            eglConfig = checkNotNull(configs[0])
            eglContext = EGL14.eglCreateContext(display, eglConfig, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            check(eglContext != EGL14.EGL_NO_CONTEXT) { "EGL context creation failed" }
            window = Surface(texture)
            ensureOutputSurface(backgroundOutputSize(surfaceWidth, surfaceHeight, renderState.quality))
            renderer = MacRenderer(context, clock)
            renderer!!.initialize()
            uploaded = null
        }

        private fun ensureOutputSurface(size: BackgroundOutputSize) {
            val generation = surfaceGeneration
            if (outputSize == size && outputGeneration == generation && eglSurface != EGL14.EGL_NO_SURFACE) return
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                check(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)) { "EGL detach for resize failed" }
                check(EGL14.eglDestroySurface(display, eglSurface)) { "EGL resize destroy failed" }
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            // Android requires recreating the EGL window surface for a new default buffer size.
            // Keep the context, renderer, textures, artwork transition and scene clock alive.
            texture.setDefaultBufferSize(size.width, size.height)
            eglSurface = EGL14.eglCreateWindowSurface(display, checkNotNull(eglConfig), window,
                intArrayOf(EGL14.EGL_NONE), 0)
            check(eglSurface != EGL14.EGL_NO_SURFACE) { "EGL window creation failed" }
            check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)) { "EGL make-current failed" }
            EGL14.eglSwapInterval(display, 1)
            val actualWidth = IntArray(1)
            val actualHeight = IntArray(1)
            check(EGL14.eglQuerySurface(display, eglSurface, EGL14.EGL_WIDTH, actualWidth, 0))
            check(EGL14.eglQuerySurface(display, eglSurface, EGL14.EGL_HEIGHT, actualHeight, 0))
            check(actualWidth[0] == size.width && actualHeight[0] == size.height) {
                "Unexpected EGL buffer ${actualWidth[0]}x${actualHeight[0]}, expected $size"
            }
            outputSize = size
            outputGeneration = generation
            reportedQuality = null
            NetworkLog.line("MacOutput", "EGL=${actualWidth[0]}x${actualHeight[0]}; context preserved; view=${surfaceWidth}x$surfaceHeight")
        }

        private fun destroyEgl() {
            runCatching { renderer?.release() }
            renderer = null
            uploaded = null
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
                EGL14.eglReleaseThread()
                EGL14.eglTerminate(display)
            }
            window?.release()
            window = null
            eglSurface = EGL14.EGL_NO_SURFACE
            eglContext = EGL14.EGL_NO_CONTEXT
            eglConfig = null
            outputSize = null
            outputGeneration = -1
            reportedQuality = null
            display = EGL14.EGL_NO_DISPLAY
        }

        private fun fail(error: Exception) {
            visibleFrame = false
            NetworkLog.line("Mac", "static fallback: ${error.message?.take(160) ?: error.javaClass.simpleName}")
            this@MacTextureView.post {
                if (!closed && worker === this) {
                    alpha = 0f
                    onFailure()
                }
            }
            stop(false)
        }

        fun stop(release: Boolean) {
            if (release) releaseTexture = true
            if (stopping.compareAndSet(false, true)) {
                handler.post {
                    handler.removeCallbacks(frame)
                    choreographer?.removeFrameCallback(vsyncFrame)
                    destroyEgl()
                    cleanedUp = true
                    if (releaseTexture) releaseNativeTexture()
                    thread.quitSafely()
                }
            } else if (release && cleanedUp) releaseNativeTexture()
        }

        private fun releaseNativeTexture() {
            if (textureReleased.compareAndSet(false, true)) texture.release()
        }
    }
}
