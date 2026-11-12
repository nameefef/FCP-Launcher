// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 FCP contributors
package fcp.launcher.launch

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import fcp.launcher.R
import org.lwjgl.glfw.CallbackBridge

/**
 * Transparent controls drawn over the game surface. Buttons send GLFW keys and
 * mouse buttons through [CallbackBridge], the same path Fold Craft Launcher
 * uses. A drag on the empty area looks around while the pointer is grabbed,
 * and clicks menus when it is not.
 *
 * Sprint and sneak stay held until tapped again, which is how a thumb uses
 * them on a phone. Movement, jump, attack, and use last only while the finger
 * is down.
 */
class TouchControlsOverlay(context: Context) : FrameLayout(context) {
    private val lookPad = View(context)
    private val editor = EditText(context)
    private val controls = mutableListOf<View>()
    private val showButton: Button
    private var laidFor = 0
    private var loggedLayout = false
    private var grabbing = false
    private var lookPointer = -1
    private var lastX = 0f
    private var lastY = 0f
    private var virtualX = 0f
    private var virtualY = 0f
    private var sprintHeld = false
    private var sneakHeld = false
    private var editing = false

    init {
        setBackgroundColor(Color.TRANSPARENT)
        lookPad.setBackgroundColor(Color.TRANSPARENT)
        lookPad.setOnTouchListener { _, event -> onLook(event) }
        addView(lookPad, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        editor.apply {
            layoutParams = LayoutParams(1, 1)
            setBackgroundColor(Color.TRANSPARENT)
            isSingleLine = false
            isFocusable = false
            isFocusableInTouchMode = false
            setOnKeyListener { _, keyCode, event -> onEditorKey(keyCode, event) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}

                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                    if (editing) return
                    repeat(before) { tap(GLFW_KEY_BACKSPACE) }
                    for (ch in s.subSequence(start, start + count)) {
                        if (ch == '\n') tap(GLFW_KEY_ENTER) else sendChar(ch)
                    }
                }

                override fun afterTextChanged(s: Editable) {
                    if (editing || s.length < 24) return
                    editing = true
                    s.clear()
                    editing = false
                }
            })
        }
        addView(editor)

        showButton = button(context.getString(R.string.control_show)).also { view ->
            view.visibility = GONE
            view.setOnClickListener { setControlsVisible(true) }
        }
        addView(showButton)

        CallbackBridge.setGrabListener { grabbing = it }
    }

    fun releaseHeldKeys() {
        if (sprintHeld) setKey(GLFW_KEY_LEFT_CONTROL, false)
        if (sneakHeld) setKey(GLFW_KEY_LEFT_SHIFT, false)
        sprintHeld = false
        sneakHeld = false
        controls.forEach { view ->
            if (view.isSelected) {
                view.isSelected = false
                view.alpha = 0.72f
            }
        }
        setKey(GLFW_KEY_W, false)
        setKey(GLFW_KEY_A, false)
        setKey(GLFW_KEY_S, false)
        setKey(GLFW_KEY_D, false)
        setKey(GLFW_KEY_SPACE, false)
        mouse(MOUSE_LEFT, false)
        mouse(MOUSE_RIGHT, false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val key = w * 100_000 + h
        if (key == laidFor) return
        laidFor = key
        virtualX = w / 2f
        virtualY = h / 2f
        placeControls(w, h)
    }

    private fun placeControls(w: Int, h: Int) {
        controls.forEach { removeView(it) }
        controls.clear()
        val b = dp(54)
        val small = dp(44)
        val gap = dp(6)
        val margin = dp(10)

        fun add(view: View, x: Int, y: Int, width: Int, height: Int) {
            addView(view, placed(x, y, width, height))
            controls += view
        }

        val forward = holdKey(R.string.control_forward, GLFW_KEY_W)
        val back = holdKey(R.string.control_back, GLFW_KEY_S)
        val left = holdKey(R.string.control_left, GLFW_KEY_A)
        val right = holdKey(R.string.control_right, GLFW_KEY_D)
        val sprint = toggleKey(R.string.control_sprint, GLFW_KEY_LEFT_CONTROL) { sprintHeld = it }
        val sneak = toggleKey(R.string.control_sneak, GLFW_KEY_LEFT_SHIFT) { sneakHeld = it }
        val jump = holdKey(R.string.control_jump, GLFW_KEY_SPACE)
        val attack = holdMouse(R.string.control_attack, MOUSE_LEFT)
        val use = holdMouse(R.string.control_use, MOUSE_RIGHT)
        val inventory = tapKey(R.string.control_inventory, GLFW_KEY_E)
        val pause = tapKey(R.string.control_pause, GLFW_KEY_ESCAPE)
        val chat = tapKey(R.string.control_chat, GLFW_KEY_T)
        val keyboard = button(context.getString(R.string.control_keyboard)).also {
            it.setOnClickListener { toggleKeyboard() }
        }
        val hide = button(context.getString(R.string.control_hide)).also {
            it.setOnClickListener {
                releaseHeldKeys()
                setControlsVisible(false)
            }
        }

        val cross = b + gap
        val dpadX = margin
        val dpadY = h - margin - cross * 2 - b
        add(forward, dpadX + cross, dpadY, b, b)
        add(left, dpadX, dpadY + cross, b, b)
        add(back, dpadX + cross, dpadY + cross, b, b)
        add(right, dpadX + cross * 2, dpadY + cross, b, b)
        add(sprint, dpadX + cross * 2, dpadY, b, b)
        add(sneak, dpadX, dpadY + cross * 2, b, b)

        val rightX = w - margin - b
        add(use, rightX, h - margin - b, b, b)
        add(attack, rightX - cross, h - margin - cross - b, b, b)
        add(jump, rightX, h - margin - cross * 2 - b, b, b)

        var topX = margin
        for (view in listOf(hide, pause, inventory, chat, keyboard)) {
            add(view, topX, margin, small + dp(8), small)
            topX += small + dp(8) + gap
        }

        val slot = dp(36)
        val row = 9 * slot + 8 * gap
        var slotX = (w - row) / 2
        val slotY = h - margin - slot
        for (index in 0 until 9) {
            val key = GLFW_KEY_1 + index
            val slotButton = tapKeyCode((index + 1).toString(), key)
            add(slotButton, slotX, slotY, slot, slot)
            slotX += slot + gap
        }

        place(showButton, w - margin - small, margin, small + dp(8), small)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (!loggedLayout && controls.isNotEmpty() && controls[0].width > 0) {
            loggedLayout = true
            logCenters()
        }
    }

    private fun place(view: View, x: Int, y: Int, width: Int, height: Int) {
        view.layoutParams = placed(x, y, width, height)
    }

    private fun logCenters() {
        for (view in controls + showButton) {
            val label = (view as? Button)?.text ?: continue
            val cx = view.left + view.width / 2
            val cy = view.top + view.height / 2
            Log.i(TAG, "control $label $cx,$cy ${view.width}x${view.height}")
        }
    }

    private fun setControlsVisible(visible: Boolean) {
        val visibility = if (visible) VISIBLE else GONE
        controls.forEach { it.visibility = visibility }
        showButton.visibility = if (visible) GONE else VISIBLE
        if (!visible) hideKeyboard()
    }

    private fun onLook(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (lookPointer != -1) return true
                val index = event.actionIndex
                lookPointer = event.getPointerId(index)
                lastX = event.getX(index)
                lastY = event.getY(index)
                if (!grabbing) {
                    sendCursor(lastX, lastY)
                    mouse(MOUSE_LEFT, true)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(lookPointer)
                if (index < 0) return true
                val x = event.getX(index)
                val y = event.getY(index)
                if (grabbing) {
                    virtualX += (x - lastX) * LOOK_SCALE
                    virtualY += (y - lastY) * LOOK_SCALE
                    sendCursor(virtualX, virtualY)
                } else {
                    sendCursor(x, y)
                }
                lastX = x
                lastY = y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val index = event.actionIndex
                if (event.getPointerId(index) != lookPointer) return true
                if (!grabbing) mouse(MOUSE_LEFT, false)
                lookPointer = -1
            }
        }
        return true
    }

    private fun toggleKeyboard() {
        editor.isFocusable = true
        editor.isFocusableInTouchMode = true
        editor.requestFocus()
        val imm = context.getSystemService(InputMethodManager::class.java)
        imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = context.getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(editor.windowToken, 0)
        editor.clearFocus()
    }

    private fun onEditorKey(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (event.action != android.view.KeyEvent.ACTION_DOWN) return false
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_DEL -> {
                tap(GLFW_KEY_BACKSPACE)
                return true
            }
            android.view.KeyEvent.KEYCODE_ENTER -> {
                tap(GLFW_KEY_ENTER)
                return true
            }
        }
        return false
    }

    private fun holdKey(label: Int, key: Int): Button {
        return button(context.getString(label)).also { view ->
            view.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> setKey(key, true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> setKey(key, false)
                }
                true
            }
        }
    }

    private fun tapKey(label: Int, key: Int): Button = tapKeyCode(context.getString(label), key)

    private fun tapKeyCode(label: String, key: Int): Button {
        return button(label).also { view ->
            view.setOnClickListener { tap(key) }
        }
    }

    private fun toggleKey(label: Int, key: Int, onChange: (Boolean) -> Unit): Button {
        return button(context.getString(label)).also { view ->
            view.alpha = 0.72f
            view.setOnClickListener {
                view.isSelected = !view.isSelected
                view.alpha = if (view.isSelected) 1f else 0.72f
                setKey(key, view.isSelected)
                onChange(view.isSelected)
            }
        }
    }

    private fun holdMouse(label: Int, buttonId: Int): Button {
        return button(context.getString(label)).also { view ->
            view.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> mouse(buttonId, true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mouse(buttonId, false)
                }
                true
            }
        }
    }

    private fun button(label: String): Button {
        return Button(context).apply {
            text = label
            contentDescription = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            minimumWidth = 0
            minimumHeight = 0
            minWidth = 0
            minHeight = 0
            stateListAnimator = null
            background = GradientDrawable().apply {
                setColor(0xE6181818.toInt())
                setStroke(dp(2), Color.WHITE)
                cornerRadius = dp(10).toFloat()
            }
        }
    }

    private fun placed(x: Int, y: Int, width: Int, height: Int): LayoutParams {
        return LayoutParams(width, height).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = x
            topMargin = y
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun setKey(key: Int, down: Boolean) {
        try {
            CallbackBridge.nativeSendKey(key, 0, if (down) 1 else 0, 0)
        } catch (_: Throwable) {
        }
    }

    private fun tap(key: Int) {
        setKey(key, true)
        setKey(key, false)
    }

    private fun mouse(buttonId: Int, down: Boolean) {
        try {
            CallbackBridge.nativeSendMouseButton(buttonId, if (down) 1 else 0, 0)
        } catch (_: Throwable) {
        }
    }

    private fun sendCursor(x: Float, y: Float) {
        try {
            CallbackBridge.nativeSendCursorPos(x, y)
        } catch (_: Throwable) {
        }
    }

    private fun sendChar(ch: Char) {
        try {
            CallbackBridge.nativeSendChar(ch)
        } catch (_: Throwable) {
        }
    }

    private companion object {
        const val TAG = "FCP"
        const val LOOK_SCALE = 1.8f
        const val MOUSE_LEFT = 0
        const val MOUSE_RIGHT = 1
        const val GLFW_KEY_SPACE = 32
        const val GLFW_KEY_1 = 49
        const val GLFW_KEY_A = 65
        const val GLFW_KEY_D = 68
        const val GLFW_KEY_E = 69
        const val GLFW_KEY_S = 83
        const val GLFW_KEY_T = 84
        const val GLFW_KEY_W = 87
        const val GLFW_KEY_ESCAPE = 256
        const val GLFW_KEY_ENTER = 257
        const val GLFW_KEY_BACKSPACE = 259
        const val GLFW_KEY_LEFT_SHIFT = 340
        const val GLFW_KEY_LEFT_CONTROL = 341
    }
}
