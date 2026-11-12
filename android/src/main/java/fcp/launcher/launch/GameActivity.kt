// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.launcher.launch

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.widget.FrameLayout
import android.widget.PopupWindow
import com.tungsten.fclauncher.bridge.FCLBridge
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import net.kdt.pojavlaunch.utils.JREUtils
import org.lwjgl.glfw.CallbackBridge

/**
 * Fullscreen surface for the in-process JVM. Runs in `:game` with
 * [GameSessionService]. `glfwInit` acquires the `ANativeWindow` stored by
 * [CallbackBridge.setupBridgeWindow], so the JVM thread starts only after
 * that call.
 */
class GameActivity : Activity(), SurfaceHolder.Callback {
    private val jvmStarted = AtomicBoolean(false)
    private val windowInstalled = AtomicBoolean(false)
    private var boundSurface: Surface? = null
    private lateinit var surfaceView: SurfaceView
    private var controls: TouchControlsOverlay? = null
    private var controlsWindow: PopupWindow? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        surfaceView = SurfaceView(this)
        // Keep the game surface behind this window. A sibling view is still
        // covered on this emulator, so the controls live in a popup above it.
        surfaceView.setZOrderOnTop(false)
        surfaceView.setZOrderMediaOverlay(false)
        surfaceView.holder.addCallback(this)
        val root = FrameLayout(this)
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        val overlay = TouchControlsOverlay(this)
        controls = overlay
        setContentView(root)
        root.post { showControls(root, overlay) }
        val version = intent.getStringExtra(GameSessionService.EXTRA_VERSION) ?: "Minecraft"
        startForegroundService(
            Intent(this, GameSessionService::class.java)
                .setAction(GameSessionService.ACTION_FOREGROUND)
                .putExtra(GameSessionService.EXTRA_VERSION, version),
        )
    }

    private fun showControls(anchor: FrameLayout, overlay: TouchControlsOverlay) {
        if (isFinishing || controlsWindow != null) return
        val screen = anchor.resources.displayMetrics
        val width = screen.widthPixels
        val height = screen.heightPixels
        val popup = PopupWindow(overlay, width, height, true)
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.isClippingEnabled = false
        popup.isTouchable = true
        popup.inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
        popup.showAtLocation(anchor, Gravity.TOP or Gravity.START, 0, 0)
        popup.update(0, 0, width, height)
        controlsWindow = popup
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        publishSurface(holder.surface, surfaceView.width, surfaceView.height)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        publishSurface(holder.surface, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        boundSurface = null
        // The GL thread still holds the window while the JVM is inside
        // glfwInit. Releasing it here faults ANativeWindow_acquire. Drop it
        // only when the JVM never started.
        if (!jvmStarted.get()) {
            releaseWindow()
        }
    }

    private fun publishSurface(surface: Surface?, width: Int, height: Int) {
        if (surface == null || !surface.isValid || width <= 0 || height <= 0) return
        try {
            // Loads libpojavexec.so so setupBridgeWindow can bind.
            Class.forName(FCLBridge::class.java.name)
            if (boundSurface !== surface) {
                CallbackBridge.setupBridgeWindow(surface)
                boundSurface = surface
                windowInstalled.set(true)
                CallbackBridge.nativeSetInputReady(true)
            }
            CallbackBridge.nativeSendScreenSize(width, height)
        } catch (e: Throwable) {
            return
        }
        startJvm()
    }

    private fun startJvm() {
        if (!jvmStarted.compareAndSet(false, true)) return
        val specPath = intent.getStringExtra(GameSessionService.EXTRA_SPEC)
        val spec = specPath?.let { File(it) }
        if (spec == null || !spec.isFile) {
            jvmStarted.set(false)
            return
        }
        Thread({
            try {
                InProcessJvm.run(spec)
            } finally {
                Process.killProcess(Process.myPid())
            }
        }, "fcp-jli").start()
    }

    private fun releaseWindow() {
        if (!windowInstalled.compareAndSet(true, false)) return
        try {
            JREUtils.releaseBridgeWindow()
        } catch (_: Throwable) {
        }
        boundSurface = null
    }

    override fun onDestroy() {
        controls?.releaseHeldKeys()
        controlsWindow?.dismiss()
        controlsWindow = null
        if (!jvmStarted.get()) releaseWindow()
        super.onDestroy()
    }
}
