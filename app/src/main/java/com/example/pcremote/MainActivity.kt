package com.example.pcremote

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView

@SuppressLint("MissingPermission")
class MainActivity : Activity() {

    private class Palette(
        val bg: Int,
        val text: Int,
        val button: Int,
        val buttonText: Int,
        val ripple: Int,
        val pad: Int,
        val padText: Int,
        val light: Boolean
    )

    private val darkPalette = Palette(
        0xFF121212.toInt(), 0xFFFFFFFF.toInt(), 0xFF3A3A3F.toInt(), 0xFFFFFFFF.toInt(),
        0x33FFFFFF, 0xFF2A2A2E.toInt(), 0xFF8A8A92.toInt(), false
    )
    private val lightPalette = Palette(
        0xFFF5F5F8.toInt(), 0xFF1B1B1F.toInt(), 0xFFD6D6E0.toInt(), 0xFF1B1B1F.toInt(),
        0x33000000, 0xFFE4E4EC.toInt(), 0xFF6A6A72.toInt(), true
    )
    private var pal = darkPalette

    private lateinit var hid: Hid
    private lateinit var root: LinearLayout
    private lateinit var pad: TouchpadView
    private lateinit var pairRow: LinearLayout
    private lateinit var modeBtn: Button
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var dragBtn: Button

    private var bleMode = true
    private var sensitivity = 1.8f
    private var accel = true
    private var themeMode = 0   // 0 = follow system, 1 = light, 2 = dark

    private var mods = 0
    private var dragging = false
    private var resetting = false
    private var typed = ""   // text already sent to the PC

    private val modButtons = mutableMapOf<Int, Button>()
    private val allButtons = mutableListOf<Button>()

    private val prefs by lazy { getSharedPreferences("cfg", MODE_PRIVATE) }

    private val pairHint = "Not connected. Tap Discoverable, then pair from your PC (Add device > Bluetooth)."

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        bleMode = prefs.getBoolean("ble", true)
        sensitivity = prefs.getFloat("speed", 1.8f)
        accel = prefs.getBoolean("accel", true)
        themeMode = prefs.getInt("theme", 0)
        pal = currentPalette()
        buildUi()
        hid = createBackend()
        startWhenPermitted()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (themeMode == 0) applyTheme()
    }

    override fun onDestroy() {
        hid.stop()
        super.onDestroy()
    }

    // ---------- backend ----------

    private fun createBackend(): Hid {
        val h: Hid =
            if (bleMode) BleHidController(this) { appendLog(it) } else HidController(this) { appendLog(it) }
        h.connectionListener = { connected -> runOnUiThread { showConnected(connected) } }
        return h
    }

    private fun switchMode() {
        hid.stop()
        bleMode = !bleMode
        prefs.edit().putBoolean("ble", bleMode).apply()
        modeBtn.text = if (bleMode) "Mode: BLE" else "Mode: Classic"
        status.text = pairHint
        hid = createBackend()
        startWhenPermitted()
    }

    /** Hide the pairing buttons and status line while connected; show them again when disconnected. */
    private fun showConnected(connected: Boolean) {
        val v = if (connected) View.GONE else View.VISIBLE
        pairRow.visibility = v
        status.visibility = v
        if (!connected) status.text = pairHint
    }

    // Routine events are not shown; only real problems appear in the status line.
    private fun appendLog(msg: String) {
        val problem = listOf("cannot", "could not", "failed", "turn bluetooth", "not ready", "not support", "not registered")
            .any { msg.contains(it, ignoreCase = true) }
        if (problem) runOnUiThread { status.text = msg }
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

    // ---------- theme ----------

    private fun isDark(): Boolean = when (themeMode) {
        1 -> false
        2 -> true
        else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun currentPalette() = if (isDark()) darkPalette else lightPalette

    private fun styleButton(b: Button) {
        val shape = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(pal.button)
        }
        b.background = RippleDrawable(ColorStateList.valueOf(pal.ripple), shape, null)
        b.setTextColor(pal.buttonText)
        b.stateListAnimator = null
    }

    @Suppress("DEPRECATION")
    private fun applyTheme() {
        pal = currentPalette()
        root.setBackgroundColor(pal.bg)
        status.setTextColor(pal.text)
        allButtons.forEach { styleButton(it) }
        pad.setColors(pal.pad, pal.padText)
        window.statusBarColor = pal.bg
        window.navigationBarColor = pal.bg
        val decor = window.decorView
        val bits = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        decor.systemUiVisibility = if (pal.light) decor.systemUiVisibility or bits else decor.systemUiVisibility and bits.inv()
        refreshMods()
    }

    private fun dialogBuilder() = AlertDialog.Builder(
        this,
        if (pal.light) android.R.style.Theme_Material_Light_Dialog_Alert else android.R.style.Theme_Material_Dialog_Alert
    )

    // ---------- UI ----------

    private fun btn(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(44)
        minimumHeight = dp(44)
        setPadding(dp(2), 0, dp(2), 0)
        setOnClickListener { onClick() }
        allButtons.add(this)
        styleButton(this)
    }

    private fun row(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach {
            addView(
                it,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                }
            )
        }
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

    // Ctrl/Alt/Shift/Win stay on until tapped again.
    private fun sendKey(usage: Int) {
        hid.press(usage, mods)
    }

    private fun typeChar(c: Char) {
        hid.typeChar(c, mods)
    }

    private fun resetInput() {
        resetting = true
        input.text.clear()
        typed = ""
        resetting = false
    }

    private fun click(button: Int) {
        hid.mouse(0, 0, button, 0)
        hid.mouse(0, 0, 0, 0)
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        status = TextView(this).apply {
            text = pairHint
            textSize = 12f
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }
        root.addView(status)

        pairRow = row(
            btn("Discoverable") { if (bleMode) hid.advertise() else makeDiscoverable() },
            btn("Connect") { if (bleMode) hid.advertise() else pickDevice() },
            btn(if (bleMode) "Mode: BLE" else "Mode: Classic") { switchMode() }.also { modeBtn = it }
        )
        root.addView(pairRow)
        root.addView(row(btn("Keyboard") { showKeyboard() }, btn("Settings") { showSettings() }))

        pad = TouchpadView(this)
        pad.sensitivity = sensitivity
        pad.accel = accel
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

        // Invisible field that captures the phone's on-screen keyboard (with its suggestion strip).
        input = EditText(this).apply {
            alpha = 0f
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_FULLSCREEN
        }
        // Mirror the field on the PC: whatever changes in the field (typing, autocorrect,
        // tapping a suggestion) is sent as backspaces plus the new characters.
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(e: Editable?) {
                if (resetting || e == null) return
                val now = e.toString()
                var common = 0
                val max = minOf(typed.length, now.length)
                while (common < max && typed[common] == now[common]) common++
                repeat(typed.length - common) { hid.press(0x2A, 0) }
                for (i in common until now.length) typeChar(now[i])
                typed = now
                if (now.length > 400) resetInput()
            }
        })
        input.setOnKeyListener { _, keyCode, ev ->
            when (keyCode) {
                KeyEvent.KEYCODE_DEL -> {
                    // With text in the field, the edit itself is mirrored above; only handle the empty case.
                    if (input.text.isEmpty()) {
                        if (ev.action == KeyEvent.ACTION_DOWN) sendKey(0x2A)
                        true
                    } else {
                        false
                    }
                }
                KeyEvent.KEYCODE_ENTER -> {
                    if (ev.action == KeyEvent.ACTION_DOWN) {
                        sendKey(0x28)
                        resetInput()
                    }
                    true
                }
                else -> false
            }
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NONE) {
                false
            } else {
                sendKey(0x28)
                resetInput()
                true
            }
        }
        root.addView(input, LinearLayout.LayoutParams(dp(1), dp(1)))

        setContentView(root)
        applyTheme()
    }

    // ---------- actions ----------

    private fun showKeyboard() {
        input.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        resetInput()
        imm.showSoftInput(input, InputMethodManager.SHOW_FORCED)
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), 0)
        }

        val speedLabel = TextView(this).apply { textSize = 15f }
        fun updateLabel() {
            speedLabel.text = "Cursor speed: %.1f".format(sensitivity)
        }
        updateLabel()
        val seek = SeekBar(this).apply {
            max = 100
            progress = ((sensitivity - 0.5f) / 0.045f).toInt().coerceIn(0, 100)
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                sensitivity = 0.5f + p * 0.045f
                pad.sensitivity = sensitivity
                updateLabel()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {
                prefs.edit().putFloat("speed", sensitivity).apply()
            }
        })

        val accelBox = CheckBox(this).apply {
            text = "Pointer acceleration (faster swipes move further)"
            isChecked = accel
            setOnCheckedChangeListener { _, checked ->
                accel = checked
                pad.accel = checked
                prefs.edit().putBoolean("accel", checked).apply()
            }
        }

        val themeLabel = TextView(this).apply {
            text = "Theme"
            textSize = 15f
            setPadding(0, dp(14), 0, 0)
        }
        val group = RadioGroup(this)
        listOf("Follow system", "Light", "Dark").forEachIndexed { i, label ->
            group.addView(RadioButton(this).apply {
                text = label
                id = 100 + i
                isChecked = themeMode == i
            })
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            themeMode = checkedId - 100
            prefs.edit().putInt("theme", themeMode).apply()
            applyTheme()
        }

        box.addView(speedLabel)
        box.addView(seek)
        box.addView(accelBox)
        box.addView(themeLabel)
        box.addView(group)

        dialogBuilder().setTitle("Settings").setView(box).setPositiveButton("Done", null).show()
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
        dialogBuilder()
            .setTitle("Connect to")
            .setItems(devices.map { it.name ?: it.address }.toTypedArray()) { _, i ->
                hid.connectTo(devices[i])
            }
            .show()
    }
}
