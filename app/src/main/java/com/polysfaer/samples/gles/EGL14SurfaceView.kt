package com.polysfaer.samples.gles

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.opengl.*
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.AttributeSet
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceHolder.Callback2
import android.view.SurfaceView
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 *
 * An implementation of a SurfaceView that uses the dedicated surface for displaying OpenGL rendering
 *  with EGL14.
 *
 *  (Draft / Work in Progress)
 *
 * */
class EGL14SurfaceView(context: Context?, attrs: AttributeSet? = null) : SurfaceView(context, attrs), Callback2 {
    companion object {
        private const val TAG: String = "EGL14SurfaceView"

        fun checkGLError(msg : String) {
            val err = EGL14.eglGetError()
            if (err != EGL14.EGL_SUCCESS) {
                throw java.lang.RuntimeException(msg + ": EGL error: 0x" + Integer.toHexString(err))
            }
        }
    }

    // -------------------------------------------------------

    /** An interface for choosing an EGLConfig configuration from a list of potential configurations. */
    interface EGLConfigChooser {
        fun chooseConfig(eglDisplay: EGLDisplay): EGLConfig
    }

    /** An interface for customizing the eglCreateContext and eglDestroyContext calls. */
    interface EGLContextFactory {
        fun createContext(eglDisplay: EGLDisplay, eglConfig: EGLConfig): EGLContext
        fun destroyContext(eglDisplay: EGLDisplay, eglContext: EGLContext)
    }

    /** An interface for customizing the eglCreateWindowSurface and eglDestroySurface calls. */
    interface EGLWindowSurfaceFactory {
        fun createWindowSurface(eglDisplay: EGLDisplay, eglConfig: EGLConfig, nativeWindow: Any?): EGLSurface?
        fun destroySurface(eglDisplay: EGLDisplay, eglSurface: EGLSurface)
    }

    /** A generic renderer interface.
     *  The renderer is responsible for making OpenGL calls to render a frame. */
    interface Renderer {
        fun onSurfaceCreated()
        fun onSurfaceChanged(width: Int, height: Int)
        fun onDrawFrame()
    }

    // -------------------------------------------------------

    // Work In Progress
    class RenderContext(
        configChooser: EGLConfigChooser,
        private val contextFactory: EGLContextFactory,
        private val windowSurfaceFactory: EGLWindowSurfaceFactory,
    ) {
        private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val config: EGLConfig
        private val context: EGLContext

        init {
            val version = IntArray(2)
            EGL14.eglInitialize(display, version, 0, version, 1)
            config = configChooser.chooseConfig(display)
            context = contextFactory.createContext(display, config)
        }

        fun release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            contextFactory.destroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }

        fun createWindowSurface(surface: Any): EGLSurface {
            if ((surface !is Surface) && (surface !is SurfaceTexture)) {
                throw RuntimeException("Invalid surface: $surface")
            }
            val eglSurface = windowSurfaceFactory.createWindowSurface(display, config, surface)
            checkGLError("eglCreateWindowSurface")
            return eglSurface!!
        }

        fun releaseSurface(eglSurface: EGLSurface) {
            windowSurfaceFactory.destroySurface(display, eglSurface)
        }

        fun makeCurrent(drawSurface: EGLSurface, readSurface: EGLSurface) {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, drawSurface, readSurface, context)
            }
        }

        fun makeCurrent(surface: EGLSurface) {
            makeCurrent(surface, surface)
        }

        fun makeUnCurrent() {
            makeCurrent(EGL14.EGL_NO_SURFACE)
        }

        fun swapBuffers(eglSurface: EGLSurface?): Boolean {
            return EGL14.eglSwapBuffers(display, eglSurface)
        }
    }

    // -------------------------------------------------------

    private val renderThread = RenderThread(this.holder)

    /** Handle to synchronize renderThread frames with VSYNC. */
    private val choreographerCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frametimeNs: Long) {
            if (null != renderThread.handler) {
                requestNextFrame()
                renderThread.handler!!.sendSurfaceRedraw(frametimeNs) //
            }
        }

        fun requestNextFrame() {
            Choreographer.getInstance().postFrameCallback(this)
        }

        fun stop() {
            Choreographer.getInstance().removeFrameCallback(this)
        }
    }

    // -------------------------------------------------------

    // [WIP / test] properties to directly access some RenderThread fields.

    var configChooser: EGLConfigChooser?
        get() = renderThread.configChooser
        set(value) { renderThread.configChooser = value }

    var contextFactory: EGLContextFactory?
        get() = renderThread.contextFactory
        set(value) { renderThread.contextFactory = value }

    var windowSurfaceFactory: EGLWindowSurfaceFactory?
        get() = renderThread.windowSurfaceFactory
        set(value) { renderThread.windowSurfaceFactory = value }

    var renderer: Renderer?
        get() = renderThread.renderer
        set(value) { renderThread.renderer = value }


    // -------------------------------------------------------

    init {
        // Default window surface factory.
        windowSurfaceFactory = object : EGLWindowSurfaceFactory {
            override fun createWindowSurface(eglDisplay: EGLDisplay, eglConfig: EGLConfig, nativeWindow: Any?): EGLSurface? {
                if ((nativeWindow !is Surface) && (nativeWindow !is SurfaceTexture)) {
                    throw RuntimeException("Invalid surface: $nativeWindow")
                }
                val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
                return EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, nativeWindow, surfaceAttribs, 0)
            }

            override fun destroySurface(eglDisplay: EGLDisplay, eglSurface: EGLSurface) {
                if (!EGL14.eglDestroySurface(eglDisplay, eglSurface)) {
                    Log.d(TAG, "eglDestroySurface failed.")
                }
            }
        }

        setWillNotDraw(false)
        holder.setFormat(PixelFormat.RGB_888) //
        holder.addCallback(this) //
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        // Notes:
        // Some bugs might appears because of thread member initialization.
        // We might have to set some variables inside RenderThread::run directly.
        Log.d(TAG, ">> surfaceCreated")

        renderThread.start()
        renderThread.waitUntilReady()
        renderThread.handler?.sendSurfaceCreated()
        requestRender()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d(TAG, ">> surfaceChanged")

        renderThread.handler?.sendSurfaceChanged(width, height)
    }

    override fun surfaceRedrawNeeded(holder: SurfaceHolder) {
        Log.d(TAG, ">> surfaceRedrawNeeded")

        renderThread.handler?.sendSurfaceRedraw(frameTimeNanos = 0) //
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, ">> surfaceDestroyed")

        renderThread.handler?.sendShutdown()
        try {
            renderThread.join()
        } catch (ie: InterruptedException) {
            throw RuntimeException("RenderThread join was interrupted", ie)
        }
        choreographerCallback.stop()
    }

    // -------------------------------------------------------

    /** Request that the renderer render a frame. */
    private fun requestRender() {
        choreographerCallback.requestNextFrame()
    }

    /** Pause the rendering thread. */
    fun onPause() {
        choreographerCallback.stop()
    }

    /** Resume the rendering thread. */
    fun onResume() {
        requestRender()
    }

    /** Specify a simple EGL14 Config chooser given its buffer channels bits size. */
    fun setEGLConfigChooser(redSize: Int, greenSize: Int, blueSize: Int, alphaSize: Int, depthSize: Int, stencilSize: Int) {
        configChooser = object: EGLConfigChooser {
            override fun chooseConfig(eglDisplay: EGLDisplay): EGLConfig {
                val renderableType = EGL14.EGL_OPENGL_ES2_BIT or EGLExt.EGL_OPENGL_ES3_BIT_KHR
                val configAttribs = intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, renderableType,
                    EGL14.EGL_RED_SIZE, redSize,
                    EGL14.EGL_GREEN_SIZE, greenSize,
                    EGL14.EGL_BLUE_SIZE, blueSize,
                    EGL14.EGL_ALPHA_SIZE, alphaSize,
                    EGL14.EGL_DEPTH_SIZE, depthSize,
                    EGL14.EGL_STENCIL_SIZE, stencilSize,
                    EGL14.EGL_NONE
                )
                val configs = arrayOfNulls<EGLConfig>(1)
                val numConfigs = intArrayOf(1)
                EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, configs.size, numConfigs, 0)
                return configs[0]!!
            }
        }
    }

    /** Specify a simple EGL14 Context Factory given a glES version number. */
    fun setEGLContextClientVersion(version: Int) {
        contextFactory = object: EGLContextFactory {
            override fun createContext(eglDisplay: EGLDisplay, eglConfig: EGLConfig): EGLContext {
                val contextAttribs = intArrayOf(
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, version,
                    EGL14.EGL_NONE
                )
                val config = configChooser?.chooseConfig(eglDisplay)
                val ctx = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
                return ctx!!
            }

            override fun destroyContext(eglDisplay: EGLDisplay, eglContext: EGLContext) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
            }
        }
    }

    /** Post an event to the render thread. */
    fun queueEvent(r: Runnable) {
        // [ don't even know if it works ]
        renderThread.handler?.post(r)
    }

    // -------------------------------------------------------

    /** Thread managing surface rendering. */
    class RenderThread(private val surfaceHolder: SurfaceHolder) : Thread() {
        /** Handle communications between threads. */
        class RenderHandler(private val rt: RenderThread) : Handler() {
            enum class MSG {
                SURFACE_CREATED,
                SURFACE_CHANGED,
                SURFACE_REDRAW,
                SHUTDOWN
            }

            override fun handleMessage(msg: Message) {
                when(MSG.values()[msg.what]) {
                    MSG.SURFACE_CREATED -> rt.surfaceCreated()
                    MSG.SURFACE_CHANGED -> rt.surfaceChanged(msg.arg1, msg.arg2)
                    MSG.SURFACE_REDRAW -> rt.drawFrame()
                    MSG.SHUTDOWN -> rt.shutdown()
                }
            }

            fun sendSurfaceCreated() {
                sendMessage(obtainMessage(MSG.SURFACE_CREATED.ordinal))
            }

            fun sendSurfaceChanged(width: Int, height: Int) {
                sendMessage(obtainMessage(MSG.SURFACE_CHANGED.ordinal, width, height))
            }

            fun sendSurfaceRedraw(frameTimeNanos: Long) {
                sendMessage(obtainMessage(MSG.SURFACE_REDRAW.ordinal, (frameTimeNanos shr 32).toInt(), frameTimeNanos.toInt()))
            }

            fun sendShutdown() {
                sendMessage(obtainMessage(MSG.SHUTDOWN.ordinal))
            }
        }
        var handler: RenderHandler? = null

        // --------
        var configChooser: EGLConfigChooser? = null
        var contextFactory: EGLContextFactory? = null
        var windowSurfaceFactory: EGLWindowSurfaceFactory? = null
        var renderer: Renderer? = null
        // --------

        private val lock = ReentrantLock()
        private val condition = lock.newCondition()
        private var ready = false

        private lateinit var egl: RenderContext
        private lateinit var windowSurface: EGLSurfaceBase

        /** Launch the render thread. */
        override fun run() {
            Looper.prepare()
            setup()

            lock.withLock {
                ready = true
                condition.signal()
            }

            Looper.loop()
            release()

            lock.withLock {
                ready = false
            }
        }

        /** Thread data initialization. */
        private fun setup() {
            if (null == renderer) {
                throw Exception("Renderer was not set.")
            }

            // ? might be init outside the thread ?
            egl = RenderContext(configChooser!!, contextFactory!!, windowSurfaceFactory!!)

            // *must* be initialized inside the thread.
            handler = RenderHandler(this)
        }

        /** Thread data release. */
        private fun release() {
            egl.release()
        }

        /** Wait until the render thread is ready to receive messages.
         *  Called from the UI thread. */
        fun waitUntilReady() {
            lock.withLock {
                while (!ready) {
                    condition.await()
                }
            }
        }

        /* --- messages handling --- */

        private fun surfaceCreated() {
            windowSurface = EGLWindowSurface(egl, surfaceHolder.surface)
            windowSurface.makeCurrent()
            renderer?.onSurfaceCreated()

            checkGLError("surfaceCreated")
        }

        private fun surfaceChanged(width: Int, height: Int) {
            windowSurface.makeCurrent()
            renderer?.onSurfaceChanged(width, height)

            checkGLError("surfaceChanged")
        }

        private fun drawFrame() {
            renderer?.onDrawFrame()
            windowSurface.makeCurrent()
            windowSurface.swapBuffers()

            checkGLError("drawFrame")
        }

        private fun shutdown() {
            Looper.myLooper()?.quit()
        }
    }
}
