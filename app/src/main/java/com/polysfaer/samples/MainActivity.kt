package com.polysfaer.samples

import android.os.Bundle
import android.annotation.SuppressLint
import android.opengl.GLES31
import android.support.v7.app.AppCompatActivity
import android.view.MotionEvent.ACTION_DOWN
import kotlin.random.Random
import com.polysfaer.samples.gles.EGL14SurfaceView

class MainActivity : AppCompatActivity() {
    private lateinit var glView: EGL14SurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        glView = findViewById(R.id.gl_surface_view)
        setupGLSurface(glView)
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
    }

    private var clearColor = floatArrayOf(1.0f, 0.5f, 0.25f)

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGLSurface(view: EGL14SurfaceView) {
        view.setEGLConfigChooser(8, 8, 8, 8, 24, 8)
        view.setEGLContextClientVersion(3)

        view.renderer = object : EGL14SurfaceView.Renderer {
            override fun onSurfaceCreated() {
                GLES31.glClearColor(1.0f, 0.5f, 0.25f, 1.0f)
                GLES31.glDisable(GLES31.GL_DEPTH_TEST)
                GLES31.glDisable(GLES31.GL_CULL_FACE)
            }

            override fun onSurfaceChanged(width: Int, height: Int) {
                GLES31.glViewport(0, 0, width, height)
            }

            override fun onDrawFrame() {
                GLES31.glClearColor(clearColor[0], clearColor[1], clearColor[2], 1.0f)
                GLES31.glClear(GLES31.GL_COLOR_BUFFER_BIT)
            }
        }

        view.setOnTouchListener { v, motionEvent ->
            when (motionEvent.action) {
                ACTION_DOWN -> onActionDown(view)
                else -> false
            }
        }
    }

    private fun onActionDown(view: EGL14SurfaceView): Boolean {
        view.queueEvent {
            randomClearColor()
        }
        return true
    }

    private fun randomClearColor() {
        clearColor = clearColor.map { Random.nextFloat() }.toFloatArray()
    }
}