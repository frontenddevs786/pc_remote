package com.example.pcremote

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

@SuppressLint("MissingPermission")
class MainActivity : Activity() {

    private lateinit var hid: HidController
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var dragBtn: Button

    private var mods = 0
    private var dragging = false
    private var clearing = false
    private val modButtons = mutableMapOf<Int, Button>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val logLines = ArrayDeque<String>()

    private fun appendLog(msg: String) {
        runOnUiThread {
            logLines.addLast(msg)
            while (logLines.size > 8) logLines.removeFirst()
            status.text = logLines.joinToString("\n")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        hid = HidController(this) { msg -> appendLog(msg) }
        startWhenPermitted()
    }

    override fun onDestroy() {
        hid.stop()
        super.onDestroy()
    }

    // ---------- permissions ----------

    private fun startWhenPermitted() {
        if (Build.VERSION.SDK_INT >= 31) {
            val needed = listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (needed.isNotEmpty()) {
                requestPermissions(needed.toTypedArray(), 1)
                return
            }
        }
        hid.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            hid.start()
        } else {
            status.text = "Bluetooth permission is required. Please allow it in app settings."
        }
    }

    // ---------- UI ----------

    private fun btn(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(2), 0, dp(2), 0)
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    private fun modBtn(label: String, bit: Int): Button {
        val b = btn(label) {
            mods = mods xor bit
            refreshMods()
        }
        modButtons[bit] = b
        return b
    }

    private fun refreshMods() {
        modButtons.forEach { (bit, b) ->
            val on = (mods and bit) != 0
            b.alpha = if (on) 1f else 0.5f
            b.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun keyBtn(label: String, usage: Int) = btn(label) { sendKey(usage) }
    private fun mediaBtn(label: String, usage: Int) = btn(label) { hid.consumer(usage) }

    private fun sendKey(usage: Int) {
        hid.press(usage, mods)
        mods = 0
        refreshMods()
    }

    private fun typeChar(c: Char) {
        hid.typeChar(c, mods)
        mods = 0
        refreshMods()
    }

    private fun click(button: Int) {
        hid.mouse(0, 0, button, 0)
        hid.mouse(0, 0, 0, 0)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(0xFF121212.toInt())
        }

        status = TextView(this).apply {
            text = "Starting..."
            setTextColor(Color.WHITE)
            textSize = 11f
            minLines = 8
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }
        root.addView(status)

        root.addView(
            row(
                btn("Discoverable") { makeDiscoverable() },
                btn("Connect") { pickDevice() },
                btn("Keyboard") { showKeyboard() }
            )
        )

        val pad = TouchpadView(this)
        pad.onMove = { dx, dy -> hid.mouse(dx, dy, if (dragging) 1 else 0, 0) }
        pad.onScroll = { steps -> hid.mouse(0, 0, if (dragging) 1 else 0, steps) }
        pad.onTap = { if (!dragging) click(1) }
        pad.onTwoFingerTap = { click(2) }
        root.addView(
            pad,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(6)
                bottomMargin = dp(6)
            }
        )

        dragBtn = btn("Drag: off") {
            dragging = !dragging
            dragBtn.text = if (dragging) "Drag: ON" else "Drag: off"
            hid.mouse(0, 0, if (dragging) 1 else 0, 0)
        }
        root.addView(
            row(
                btn("Left click") { click(1) },
                dragBtn,
                btn("Right click") { click(2) }
            )
        )

        root.addView(
            row(
                modBtn("Ctrl", HidController.MOD_CTRL),
                modBtn("Alt", HidController.MOD_ALT),
                modBtn("Shift", HidController.MOD_SHIFT),
                modBtn("Win", HidController.MOD_WIN)
            )
        )
        root.addView(
            row(
                keyBtn("Esc", 0x29), keyBtn("Tab", 0x2B), keyBtn("Del", 0x4C),
                keyBtn("Bksp", 0x2A), keyBtn("Enter", 0x28),
                btn("Start") { hid.press(0, HidController.MOD_WIN) }
            )
        )
        root.addView(
            row(
                keyBtn("←", 0x50), keyBtn("↑", 0x52), keyBtn("↓", 0x51), keyBtn("→", 0x4F),
                keyBtn("Home", 0x4A), keyBtn("End", 0x4D)
            )
        )
        root.addView(
            row(
                mediaBtn("Vol-", 0xEA), mediaBtn("Vol+", 0xE9), mediaBtn("Mute", 0xE2),
                mediaBtn("Play", 0xCD), mediaBtn("Prev", 0xB6), mediaBtn("Next", 0xB5)
            )
        )

        // Invisible field that captures the phone's on-screen keyboard.
        input = EditText(this).apply {
            alpha = 0f
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_FULLSCREEN
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (clearing || s == null) return
                for (i in start until start + count) typeChar(s[i])
            }
            override fun afterTextChanged(e: Editable?) {
                if (!clearing && e != null && e.isNotEmpty()) {
                    clearing = true
                    e.clear()
                    clearing = false
                }
            }
        })
        input.setOnKeyListener { _, keyCode, ev ->
            val usage = when (keyCode) {
                KeyEvent.KEYCODE_DEL -> 0x2A
                KeyEvent.KEYCODE_ENTER -> 0x28
                else -> -1
            }
            if (usage < 0) {
                false
            } else {
                if (ev.action == KeyEvent.ACTION_DOWN) sendKey(usage)
                true
            }
        }
        root.addView(input, LinearLayout.LayoutParams(dp(1), dp(1)))

        setContentView(root)
        refreshMods()
    }

    // ---------- actions ----------

    private fun showKeyboard() {
        input.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(input, InputMethodManager.SHOW_FORCED)
    }

    private fun makeDiscoverable() {
        val i = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        startActivity(i)
    }

    private fun pickDevice() {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val devices = adapter?.bondedDevices?.toList().orEmpty()
        if (devices.isEmpty()) {
            status.text = "No paired devices yet. Pair from your PC first (tap Discoverable)."
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Connect to")
            .setItems(devices.map { it.name ?: it.address }.toTypedArray()) { _, i ->
                hid.connectTo(devices[i])
            }
            .show()
    }
}
