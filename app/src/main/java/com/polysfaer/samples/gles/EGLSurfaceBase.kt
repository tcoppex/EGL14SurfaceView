package com.polysfaer.samples.gles

import android.opengl.EGL14
import android.view.Surface

/** */
open class EGLSurfaceBase(private val egl: EGL14SurfaceView.RenderContext, surface: Any) {
    private var eglSurface = egl.createWindowSurface(surface)

    open fun release() {
        egl.releaseSurface(eglSurface)
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    fun makeCurrent() {
        egl.makeCurrent(eglSurface)
    }

    fun swapBuffers(): Boolean {
        return egl.swapBuffers(eglSurface)
    }

//    fun setPresentationTime(nsecs: Long) {
//        egl.setPresentationTime(eglSurface, nsecs)
//    }
}

/** */
class EGLWindowSurface(egl: EGL14SurfaceView.RenderContext, private val surface: Surface) : EGLSurfaceBase(egl, surface) {
    override fun release() {
        super.release()
        surface.release()
    }
}
