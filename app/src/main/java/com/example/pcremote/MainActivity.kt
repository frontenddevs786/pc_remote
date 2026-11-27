package com.example.pcremote

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

private const val KIND_CARD = 0      // rounded card with outline
private const val KIND_BG = 1        // plain page background
private const val KIND_ACCENT = 2    // accent-coloured shape
private const val KIND_SURFACE = 3   // plain surface colour (drawer)

@SuppressLint("MissingPermission")
class MainActivity : Activity() {

    private class Palette(
        val bg: Int,
        val surface: Int,
        val surface2: Int,
        val text: Int,
        val textDim: Int,
        val accent: Int,
        val accentDark: Int,
        val stroke: Int,
        val ripple: Int,
        val success: Int,
        val warn: Int,
        val light: Boolean
    )

    private val darkPalette = Palette(
        bg = 0xFF0E1014.toInt(), surface = 0xFF171A21.toInt(), surface2 = 0xFF222733.toInt(),
        text = 0xFFEDEFF5.toInt(), textDim = 0xFF8E96A8.toInt(),
        accent = 0xFF4C8DFF.toInt(), accentDark = 0xFF2B5BD7.toInt(),
        stroke = 0xFF2A3040.toInt(), ripple = 0x33FFFFFF,
        success = 0xFF3DDC84.toInt(), warn = 0xFFFFB020.toInt(), light = false
    )
    private val lightPalette = Palette(
        bg = 0xFFF2F4F8.toInt(), surface = 0xFFFFFFFF.toInt(), surface2 = 0xFFEBEEF4.toInt(),
        text = 0xFF14171F.toInt(), textDim = 0xFF5F6878.toInt(),
        accent = 0xFF2F6BFF.toInt(), accentDark = 0xFF1F4FD0.toInt(),
        stroke = 0xFFDDE2EC.toInt(), ripple = 0x22000000,
        success = 0xFF1FA463.toInt(), warn = 0xFFD98A00.toInt(), light = true
    )
    private var pal = darkPalette

    // main views
    // The Bluetooth backend lives in HidHost (kept alive by HidService), not in this Activity.
    private val hid: Hid get() = HidHost.backend ?: NoopHid
    private lateinit var frame: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var welcome: LinearLayout     // logo + pairing card, shown only while not connected
    private lateinit var pairCard: LinearLayout
    private lateinit var fullBtn: ImageView
    private lateinit var discoverBtn: Button
    private lateinit var connectBtn: Button
    private lateinit var fullDrawerItem: LinearLayout
    private lateinit var bottomPanel: LinearLayout
    private lateinit var pad: TouchpadView
    private lateinit var status: TextView        // message inside the pairing card
    private lateinit var statusDot: View
    private lateinit var statusText: TextView    // "Connected" / "Not connected" in the top bar
    private lateinit var input: EditText
    private lateinit var dragBtn: Button
    private lateinit var fab: ImageView

    // drawer
    private lateinit var scrim: View
    private lateinit var drawer: LinearLayout
    private lateinit var drawerHeader: LinearLayout
    private lateinit var drawerStatus: TextView
    private var drawerOpen = false

    // settings page
    private lateinit var settingsPage: LinearLayout

    private lateinit var speedValue: TextView
    private lateinit var speedSeek: SeekBar
    private lateinit var accelSwitch: Switch

    // state
    private var bleMode = true
    private var sensitivity = 1.8f
    private var accel = true
    private var themeMode = 0   // 0 = follow system, 1 = light, 2 = dark
    private var mods = 0
    private var dragging = false
    private var linked = false
    private var fullScreen = false
    private var resetting = false
    private var typed = ""      // text already sent to the PC
    private var discoverBusy = false   // Discoverable pressed, waiting for the PC
    private var connectBusy = false    // Connect pressed, waiting for the PC
    private val uiHandler = Handler(Looper.getMainLooper())

    // theming registries
    private class Surf(val view: View, val kind: Int, val radiusDp: Int)
    private class Txt(val view: TextView, val role: Int)

    private val surfs = mutableListOf<Surf>()
    private val txts = mutableListOf<Txt>()
    private val allButtons = mutableListOf<Button>()
    private val accentButtons = mutableSetOf<Button>()
    private val toggleState = mutableMapOf<Button, () -> Boolean>()
    private val modButtons = mutableMapOf<Int, Button>()

    private val prefs by lazy { getSharedPreferences("cfg", MODE_PRIVATE) }

    private val pairHint = "Tap Discoverable, then on Windows open Settings > Bluetooth > Add device > Bluetooth and pick this phone."

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HidHost.attach(applicationContext)
        HidHost.log("Activity onCreate")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        bleMode = prefs.getBoolean("ble", true)
        sensitivity = prefs.getFloat("speed", 1.8f)
        accel = prefs.getBoolean("accel", true)
        themeMode = prefs.getInt("theme", 0)
        pal = currentPalette()
        buildUi()
        showInRecents()
        startWhenPermitted()
    }

    /**
     * The app must show up in the recent-apps list like any normal app. Older versions hid it, and
     * that flag is remembered by the task, so clear it explicitly.
     */
    private fun showInRecents() {
        try {
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            am.appTasks.forEach { it.setExcludeFromRecents(false) }
        } catch (e: Exception) {
            HidHost.log("setExcludeFromRecents failed: $e")
        }
        prefs.edit().remove("hide_recents").apply()
    }

    override fun onStart() {
        super.onStart()
        HidHost.log("Activity onStart (backend running: ${HidHost.backend != null}, connected: ${HidHost.connected})")
        // Re-attach to the backend that kept running in the background.
        HidHost.uiConnection = { connected -> showConnected(connected) }
        HidHost.uiStatus = { appendLog(it) }
        if (HidHost.backend == null) {
            if (hasBluetoothPermission()) launchBackend()
        } else if (!HidHost.connected) {
            hid.reconnect()
        }
        showConnected(HidHost.connected)
        showAutoReconnect()
    }

    /** If a PC was connected before, say so and show that the app is reconnecting by itself. */
    private fun showAutoReconnect() {
        if (HidHost.connected || connectBusy || discoverBusy) return
        val name = prefs.getString(if (bleMode) "ble_last_host_name" else "last_host_name", null) ?: return
        connectBusy = true
        discoverBusy = false
        refreshPairButtons()
        status.text = if (bleMode) {
            "Waiting for $name to reconnect. Windows can take up to a minute. If it does not, open Settings > Bluetooth & devices on Windows, click this phone and choose Connect."
        } else {
            "Reconnecting to $name... Make sure Bluetooth is on at your PC."
        }
        uiHandler.removeCallbacksAndMessages(null)
        uiHandler.postDelayed({ clearPairBusy() }, 90_000)
    }

    override fun onDestroy() {
        HidHost.log("Activity onDestroy (finishing=$isFinishing)")
        super.onDestroy()
    }

    override fun onStop() {
        HidHost.log("Activity onStop")
        HidHost.uiConnection = null
        HidHost.uiStatus = null
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (themeMode == 0) applyTheme()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            settingsPage.visibility == View.VISIBLE -> closeSettings()
            drawerOpen -> closeDrawer()
            fullScreen -> setFullScreen(false)
            else -> super.onBackPressed()
        }
    }

    // The connection is deliberately NOT closed here: HidService keeps it alive in the background.
    // Use the "Stop" button in the notification to disconnect and quit completely.

    // ---------- backend ----------

    private fun switchMode() {
        bleMode = !bleMode
        prefs.edit().putBoolean("ble", bleMode).apply()
        clearPairBusy()
        status.text = pairHint
        showConnected(false)
        HidHost.switchMode(applicationContext, bleMode)
    }

    private fun launchBackend() {
        HidService.start(this)
        HidHost.ensure(applicationContext, bleMode)
    }

    private fun showConnected(connected: Boolean) {
        val changed = linked != connected
        linked = connected
        if (connected) clearPairBusy()
        if (!connected) {
            fullScreen = false     // leave full-screen if the link drops
            if (changed) status.text = pairHint
        }
        refreshLayout()
        updateStatusUi()
        if (changed && connected) maybeAskBackgroundAccess()
        if (changed && !connected) {
            welcome.alpha = 0f
            welcome.animate().alpha(1f).setDuration(250).start()
        }
    }

    /**
     * Not connected: only the logo and the pairing block are shown.
     * Connected: touchpad, buttons and the full-screen controls appear.
     */
    private fun refreshLayout() {
        welcome.visibility = if (linked) View.GONE else View.VISIBLE
        pad.visibility = if (linked) View.VISIBLE else View.GONE
        bottomPanel.visibility = if (linked && !fullScreen) View.VISIBLE else View.GONE
        topBar.visibility = if (fullScreen) View.GONE else View.VISIBLE
        fullBtn.visibility = if (linked) View.VISIBLE else View.GONE
        fullDrawerItem.visibility = if (linked) View.VISIBLE else View.GONE
        fab.visibility = if (fullScreen) View.VISIBLE else View.GONE
    }

    private fun updateStatusUi() {
        val label = if (linked) "Connected" else "Not connected"
        statusText.text = label
        drawerStatus.text = label
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (linked) pal.success else pal.warn)
        }
        statusDot.background = dot
    }

    // Routine events are hidden; problems and the progress of Discoverable / Connect are shown.
    private fun appendLog(msg: String) {
        val problem = listOf("cannot", "could not", "failed", "turn bluetooth", "not ready", "not support", "not registered")
            .any { msg.contains(it, ignoreCase = true) }
        val progress = listOf("Visible to the PC", "Connecting to", "Reconnecting to", "Waiting for")
            .any { msg.startsWith(it) }
        if (problem) {
            runOnUiThread {
                clearPairBusy()
                status.text = msg
            }
        } else if (progress) {
            runOnUiThread { status.text = msg }
        }
    }

    // ---------- Discoverable / Connect feedback ----------

    private fun refreshPairButtons() {
        discoverBtn.text = if (discoverBusy) "Discoverable..." else "Discoverable"
        connectBtn.text = if (connectBusy) "Connecting..." else "Connect"
        discoverBtn.isEnabled = !discoverBusy
        connectBtn.isEnabled = !connectBusy
        discoverBtn.alpha = if (discoverBusy) 0.65f else 1f
        connectBtn.alpha = if (connectBusy) 0.65f else 1f
    }

    private fun clearPairBusy() {
        uiHandler.removeCallbacksAndMessages(null)
        discoverBusy = false
        connectBusy = false
        refreshPairButtons()
    }

    private fun onDiscoverable() {
        ensureBackend()
        if (bleMode) {
            discoverBusy = true
            connectBusy = false
            refreshPairButtons()
            status.text = "Making this phone visible..."
            hid.advertise()
            uiHandler.removeCallbacksAndMessages(null)
            uiHandler.postDelayed({ clearPairBusy() }, 120_000)
        } else {
            makeDiscoverable()
        }
    }

    private fun onConnect() {
        ensureBackend()
        if (bleMode) {
            connectBusy = true
            discoverBusy = false
            refreshPairButtons()
            status.text = "Waiting for your PC to connect. It must be paired in Windows Bluetooth settings."
            hid.advertise()
            uiHandler.removeCallbacksAndMessages(null)
            uiHandler.postDelayed({ clearPairBusy() }, 60_000)
        } else if (prefs.getString("last_host", null) != null) {
            // Same as a Bluetooth headset: reuse the pairing that already exists. Long-press to pick another PC.
            connectBusy = true
            discoverBusy = false
            refreshPairButtons()
            status.text = "Connecting to ${prefs.getString("last_host_name", "your PC")}... Long-press Connect to pick another PC."
            hid.reconnect()
            uiHandler.removeCallbacksAndMessages(null)
            uiHandler.postDelayed({ clearPairBusy() }, 30_000)
        } else {
            pickDevice()
        }
    }

    // ---------- keep running in the background ----------

    private fun isIgnoringBatteryOpt(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun requestBackgroundAccess() {
        try {
            if (!isIgnoringBatteryOpt()) {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName"))
                )
            } else {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$packageName"))
                )
            }
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    /** One-time prompt after the first successful connection. */
    private fun maybeAskBackgroundAccess() {
        if (prefs.getBoolean("bg_asked", false) || isIgnoringBatteryOpt()) return
        prefs.edit().putBoolean("bg_asked", true).apply()
        dialogBuilder()
            .setTitle("Keep connected in the background")
            .setMessage(
                "Some phones stop apps when you close them, which disconnects your PC. " +
                    "Allow PC Remote to run without battery restrictions so the connection stays alive. " +
                    "On vivo, Xiaomi, Oppo and similar phones also enable Auto-start / Background activity " +
                    "for this app in the phone's settings."
            )
            .setPositiveButton("Allow") { _, _ -> requestBackgroundAccess() }
            .setNegativeButton("Not now", null)
            .show()
    }

    // ---------- permissions ----------

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
                .all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun startWhenPermitted() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
                .filterTo(needed) { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        }
        // Lets the "PC Remote is running" notification show; the app works without it.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), 1)
            return
        }
        launchBackend()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasBluetoothPermission()) {
            launchBackend()
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

    /** role: 0 = main text, 1 = dim text, 2 = white, 3 = accent */
    private fun colorFor(role: Int): Int = when (role) {
        0 -> pal.text
        1 -> pal.textDim
        2 -> Color.WHITE
        else -> pal.accent
    }

    private fun styleSurface(s: Surf) {
        val d = GradientDrawable()
        d.cornerRadius = dp(s.radiusDp).toFloat()
        when (s.kind) {
            KIND_CARD -> {
                d.setColor(pal.surface)
                d.setStroke(dp(1), pal.stroke)
            }
            KIND_BG -> d.setColor(pal.bg)
            KIND_ACCENT -> d.setColor(pal.accent)
            else -> d.setColor(pal.surface)
        }
        s.view.background = d
    }

    private fun surface(v: View, kind: Int, radiusDp: Int = 16) {
        val s = Surf(v, kind, radiusDp)
        surfs.add(s)
        styleSurface(s)
    }

    private fun txt(text: String, sp: Float, role: Int = 0, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = sp
        if (bold) t.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        t.setTextColor(colorFor(role))
        txts.add(Txt(t, role))
        return t
    }

    private fun icon(type: Int, sizeDp: Int, role: Int) = IconDrawable(type, dp(sizeDp)) { colorFor(role) }

    private fun iconBtn(type: Int, onClick: () -> Unit): ImageView {
        val v = ImageView(this)
        v.setImageDrawable(icon(type, 24, 0))
        v.scaleType = ImageView.ScaleType.CENTER
        val mask = GradientDrawable()
        mask.shape = GradientDrawable.OVAL
        mask.setColor(Color.WHITE)
        v.background = RippleDrawable(ColorStateList.valueOf(0x33888888), null, mask)
        v.setOnClickListener { onClick() }
        return v
    }

    private fun styleButton(b: Button) {
        val on = toggleState[b]?.invoke() ?: false
        val accent = on || accentButtons.contains(b)
        val shape = GradientDrawable()
        shape.cornerRadius = dp(12).toFloat()
        shape.setColor(if (accent) pal.accent else pal.surface2)
        b.background = RippleDrawable(ColorStateList.valueOf(if (accent) 0x44FFFFFF else pal.ripple), shape, null)
        b.setTextColor(if (accent) Color.WHITE else pal.text)
        b.stateListAnimator = null
    }

    @Suppress("DEPRECATION")
    private fun applyTheme() {
        pal = currentPalette()
        frame.setBackgroundColor(pal.bg)
        surfs.forEach { styleSurface(it) }
        txts.forEach { it.view.setTextColor(colorFor(it.role)) }
        allButtons.forEach { styleButton(it) }
        drawerHeader.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR, intArrayOf(pal.accent, pal.accentDark)
        )
        pad.setColors(pal.surface, pal.stroke, pal.textDim)

        speedSeek.progressTintList = ColorStateList.valueOf(pal.accent)
        speedSeek.thumbTintList = ColorStateList.valueOf(pal.accent)
        speedSeek.progressBackgroundTintList = ColorStateList.valueOf(pal.stroke)
        val checked = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        accelSwitch.thumbTintList = ColorStateList(checked, intArrayOf(pal.accent, pal.textDim))
        val trackColors = intArrayOf((pal.accent and 0x00FFFFFF) or 0x66000000, (pal.textDim and 0x00FFFFFF) or 0x55000000)
        accelSwitch.trackTintList = ColorStateList(checked, trackColors)

        window.statusBarColor = pal.bg
        window.navigationBarColor = pal.bg
        val decor = window.decorView
        val bits = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        decor.systemUiVisibility =
            if (pal.light) decor.systemUiVisibility or bits else decor.systemUiVisibility and bits.inv()

        updateStatusUi()
        invalidateTree(frame)
    }

    private fun invalidateTree(v: View) {
        v.invalidate()
        if (v is ViewGroup) for (i in 0 until v.childCount) invalidateTree(v.getChildAt(i))
    }

    private fun dialogBuilder() = AlertDialog.Builder(
        this,
        if (pal.light) android.R.style.Theme_Material_Light_Dialog_Alert else android.R.style.Theme_Material_Dialog_Alert
    )

    // ---------- reusable widgets ----------

    private fun btn(label: String, accent: Boolean = false, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.textSize = 12f
        b.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        b.minWidth = 0
        b.minimumWidth = 0
        b.minHeight = dp(44)
        b.minimumHeight = dp(44)
        b.setPadding(dp(2), 0, dp(2), 0)
        b.setOnClickListener { onClick() }
        allButtons.add(b)
        if (accent) accentButtons.add(b)
        styleButton(b)
        return b
    }

    private fun row(vararg views: View): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        for (v in views) {
            val params = lp(0, WRAP, 1f)
            params.setMargins(dp(3), dp(3), dp(3), dp(3))
            r.addView(v, params)
        }
        return r
    }

    private fun modBtn(label: String, bit: Int): Button {
        val b = btn(label) {
            mods = mods xor bit
            refreshMods()
        }
        modButtons[bit] = b
        toggleState[b] = { (mods and bit) != 0 }
        styleButton(b)
        return b
    }

    private fun refreshMods() {
        modButtons.values.forEach { styleButton(it) }
    }

    private fun keyBtn(label: String, usage: Int) = btn(label) { sendKey(usage) }
    private fun mediaBtn(label: String, usage: Int) = btn(label) { hid.consumer(usage) }

    private fun segmented(labels: List<String>, selected: () -> Int, onSelect: (Int) -> Unit): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        labels.forEachIndexed { i, label ->
            val b = btn(label) {
                onSelect(i)
                allButtons.forEach { styleButton(it) }
            }
            toggleState[b] = { selected() == i }
            styleButton(b)
            val params = lp(0, dp(44), 1f)
            params.setMargins(dp(2), dp(2), dp(2), dp(2))
            box.addView(b, params)
        }
        return box
    }

    private fun card(vararg children: View): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(16), dp(14), dp(16), dp(14))
        surface(c, KIND_CARD, 16)
        children.forEach { c.addView(it) }
        return c
    }

    // ---------- sending ----------

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

    // ---------- screen building ----------

    private fun buildUi() {
        frame = FrameLayout(this)
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(12), dp(4), dp(12), dp(10))

        buildTopBar()
        buildPairCard()

        pad = TouchpadView(this)
        pad.sensitivity = sensitivity
        pad.accel = accel
        pad.onMove = { dx, dy -> hid.mouse(dx, dy, if (dragging) 1 else 0, 0) }
        pad.onScroll = { steps -> hid.mouse(0, 0, if (dragging) 1 else 0, steps) }
        pad.onTap = { if (!dragging) click(1) }
        pad.onTwoFingerTap = { click(2) }
        val padParams = lp(MATCH, 0, 1f)
        padParams.topMargin = dp(4)
        padParams.bottomMargin = dp(8)
        content.addView(pad, padParams)

        buildBottomPanel()
        buildKeyboardInput()
        frame.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))

        // button that brings the controls back from full-screen mode
        fab = ImageView(this)
        fab.setImageDrawable(icon(Ic.SHRINK, 24, 2))
        fab.scaleType = ImageView.ScaleType.CENTER
        fab.alpha = 0.9f
        fab.elevation = dp(6).toFloat()
        fab.visibility = View.GONE
        surface(fab, KIND_ACCENT, 26)
        fab.setOnClickListener { setFullScreen(false) }
        val fabParams = FrameLayout.LayoutParams(dp(52), dp(52), Gravity.BOTTOM or Gravity.END)
        fabParams.setMargins(0, 0, dp(16), dp(16))
        frame.addView(fab, fabParams)

        buildSettingsPage()
        buildDrawer()

        setContentView(frame)
        applyTheme()
        refreshLayout()
    }

    private fun buildTopBar() {
        topBar = LinearLayout(this)
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.gravity = Gravity.CENTER_VERTICAL

        val menu = iconBtn(Ic.MENU) { openDrawer() }

        statusDot = View(this)
        statusText = txt("Not connected", 12f, 1)
        val statusRow = LinearLayout(this)
        statusRow.orientation = LinearLayout.HORIZONTAL
        statusRow.gravity = Gravity.CENTER_VERTICAL
        val dotParams = lp(dp(8), dp(8))
        dotParams.rightMargin = dp(6)
        statusRow.addView(statusDot, dotParams)
        statusRow.addView(statusText)

        val titleCol = LinearLayout(this)
        titleCol.orientation = LinearLayout.VERTICAL
        titleCol.addView(txt("PC Remote", 18f, 0, true))
        titleCol.addView(statusRow)

        val full = iconBtn(Ic.EXPAND) { setFullScreen(true) }
        fullBtn = full

        topBar.addView(menu, lp(dp(44), dp(44)))
        val titleParams = lp(0, WRAP, 1f)
        titleParams.leftMargin = dp(8)
        topBar.addView(titleCol, titleParams)
        topBar.addView(full, lp(dp(44), dp(44)))
        content.addView(topBar, lp(MATCH, dp(56)))
    }

    /** Centered logo followed by the "Connect to your PC" block. Hidden once a PC is connected. */
    private fun buildPairCard() {
        welcome = LinearLayout(this)
        welcome.orientation = LinearLayout.VERTICAL
        welcome.gravity = Gravity.CENTER
        welcome.setPadding(dp(16), 0, dp(16), dp(48))

        val logo = ImageView(this)
        logo.setImageResource(R.drawable.logo_mark)
        logo.scaleType = ImageView.ScaleType.FIT_CENTER
        welcome.addView(logo, lp(dp(96), dp(96)))

        val title = txt("Connect to your PC", 20f, 0, true)
        title.gravity = Gravity.CENTER
        val titleParams = lp(MATCH, WRAP)
        titleParams.topMargin = dp(28)
        welcome.addView(title, titleParams)

        status = txt(pairHint, 13f, 1)
        status.gravity = Gravity.CENTER
        status.setLineSpacing(0f, 1.15f)
        val statusParams = lp(MATCH, WRAP)
        statusParams.topMargin = dp(8)
        statusParams.bottomMargin = dp(20)
        statusParams.leftMargin = dp(12)
        statusParams.rightMargin = dp(12)
        welcome.addView(status, statusParams)

        discoverBtn = btn("Discoverable", accent = true) { onDiscoverable() }
        connectBtn = btn("Connect") { onConnect() }
        connectBtn.setOnLongClickListener {
            // Classic mode: choose a different paired PC instead of the remembered one.
            if (!bleMode) pickDevice()
            !bleMode
        }
        pairCard = row(discoverBtn, connectBtn)
        welcome.addView(pairCard, lp(MATCH, WRAP))

        content.addView(welcome, lp(MATCH, 0, 1f))
    }

    private fun buildBottomPanel() {
        bottomPanel = LinearLayout(this)
        bottomPanel.orientation = LinearLayout.VERTICAL
        bottomPanel.setPadding(dp(6), dp(6), dp(6), dp(6))
        surface(bottomPanel, KIND_CARD, 18)

        dragBtn = btn("Drag: off") {
            dragging = !dragging
            dragBtn.text = if (dragging) "Drag: ON" else "Drag: off"
            styleButton(dragBtn)
            hid.mouse(0, 0, if (dragging) 1 else 0, 0)
        }
        toggleState[dragBtn] = { dragging }
        styleButton(dragBtn)

        // Keyboard button sits at the bottom-right corner, after the media keys.
        val keyboard = ImageView(this)
        keyboard.setImageDrawable(icon(Ic.KEYBOARD, 24, 2))
        keyboard.scaleType = ImageView.ScaleType.CENTER
        keyboard.minimumHeight = dp(44)
        surface(keyboard, KIND_ACCENT, 12)
        keyboard.foreground = RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), null, ColorDrawable(Color.BLACK))
        keyboard.setOnClickListener { showKeyboard() }

        bottomPanel.addView(row(btn("Left click") { click(1) }, dragBtn, btn("Right click") { click(2) }))
        bottomPanel.addView(
            row(
                modBtn("Ctrl", HidController.MOD_CTRL),
                modBtn("Alt", HidController.MOD_ALT),
                modBtn("Shift", HidController.MOD_SHIFT),
                modBtn("Win", HidController.MOD_WIN)
            )
        )
        bottomPanel.addView(
            row(
                keyBtn("Esc", 0x29), keyBtn("Tab", 0x2B), keyBtn("Del", 0x4C),
                keyBtn("Bksp", 0x2A), keyBtn("Enter", 0x28),
                btn("Start") { hid.press(0, HidController.MOD_WIN) }
            )
        )
        bottomPanel.addView(
            row(
                keyBtn("←", 0x50), keyBtn("↑", 0x52), keyBtn("↓", 0x51), keyBtn("→", 0x4F),
                keyBtn("Home", 0x4A), keyBtn("End", 0x4D)
            )
        )
        val mediaRow = row(
            mediaBtn("Vol-", 0xEA), mediaBtn("Vol+", 0xE9), mediaBtn("Mute", 0xE2),
            mediaBtn("Play", 0xCD), mediaBtn("Prev", 0xB6), mediaBtn("Next", 0xB5),
            keyboard
        )
        (keyboard.layoutParams as LinearLayout.LayoutParams).weight = 1.5f
        bottomPanel.addView(mediaRow)

        content.addView(bottomPanel, lp(MATCH, WRAP))
    }

    /** Invisible field that captures the phone's on-screen keyboard (with its suggestion strip). */
    private fun buildKeyboardInput() {
        input = EditText(this)
        input.alpha = 0f
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN

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
        content.addView(input, lp(dp(1), dp(1)))
    }

    private fun buildDrawer() {
        val width = dp(288)

        scrim = View(this)
        scrim.setBackgroundColor(0x99000000.toInt())
        scrim.visibility = View.GONE
        scrim.setOnClickListener { closeDrawer() }
        frame.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))

        drawer = LinearLayout(this)
        drawer.orientation = LinearLayout.VERTICAL
        drawer.isClickable = true
        drawer.elevation = dp(16).toFloat()
        surface(drawer, KIND_SURFACE, 0)

        drawerHeader = LinearLayout(this)
        drawerHeader.orientation = LinearLayout.VERTICAL
        drawerHeader.setPadding(dp(20), dp(32), dp(20), dp(20))
        val logo = ImageView(this)
        logo.setImageDrawable(icon(Ic.TOUCHPAD, 30, 2))
        logo.scaleType = ImageView.ScaleType.CENTER
        val logoBg = GradientDrawable()
        logoBg.cornerRadius = dp(14).toFloat()
        logoBg.setColor(0x33FFFFFF)
        logo.background = logoBg
        drawerHeader.addView(logo, lp(dp(52), dp(52)))
        val nameParams = lp(WRAP, WRAP)
        nameParams.topMargin = dp(14)
        drawerHeader.addView(txt("PC Remote", 20f, 2, true), nameParams)
        drawerStatus = txt("Not connected", 12f, 2)
        val statusParams = lp(WRAP, WRAP)
        statusParams.topMargin = dp(2)
        drawerHeader.addView(drawerStatus, statusParams)
        drawer.addView(drawerHeader, lp(MATCH, WRAP))

        drawer.addView(drawerItem(Ic.TUNE, "Settings") {
            closeDrawer()
            openSettings()
        })
        fullDrawerItem = drawerItem(Ic.EXPAND, "Full-screen touchpad") {
            closeDrawer()
            setFullScreen(true)
        }
        drawer.addView(fullDrawerItem)
        drawer.addView(drawerItem(Ic.TUNE, "Keep running in background") {
            closeDrawer()
            requestBackgroundAccess()
        })
        drawer.addView(drawerItem(Ic.TUNE, "Connection log") {
            closeDrawer()
            showLog()
        })
        drawer.addView(drawerItem(Ic.HELP, "How to connect") {
            closeDrawer()
            showHelp()
        })

        drawer.addView(View(this), lp(MATCH, 0, 1f))
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            ""
        }
        val footer = txt("Version $versionName", 11f, 1)
        footer.setPadding(dp(20), 0, dp(20), dp(18))
        drawer.addView(footer)

        frame.addView(drawer, FrameLayout.LayoutParams(width, MATCH))
        drawer.translationX = -width.toFloat()
    }

    private fun drawerItem(type: Int, label: String, onClick: () -> Unit): LinearLayout {
        val item = LinearLayout(this)
        item.orientation = LinearLayout.HORIZONTAL
        item.gravity = Gravity.CENTER_VERTICAL
        item.setPadding(dp(20), 0, dp(20), 0)
        item.isClickable = true
        item.background = RippleDrawable(ColorStateList.valueOf(0x33888888), null, ColorDrawable(Color.BLACK))
        item.setOnClickListener { onClick() }
        val iv = ImageView(this)
        iv.setImageDrawable(icon(type, 24, 1))
        item.addView(iv, lp(dp(24), dp(24)))
        val labelParams = lp(WRAP, WRAP)
        labelParams.leftMargin = dp(18)
        item.addView(txt(label, 15f, 0), labelParams)
        item.layoutParams = lp(MATCH, dp(52))
        return item
    }

    private fun buildSettingsPage() {
        settingsPage = LinearLayout(this)
        settingsPage.orientation = LinearLayout.VERTICAL
        settingsPage.isClickable = true
        settingsPage.visibility = View.GONE
        settingsPage.setPadding(dp(12), dp(4), dp(12), dp(12))
        surface(settingsPage, KIND_BG, 0)

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.addView(iconBtn(Ic.BACK) { closeSettings() }, lp(dp(44), dp(44)))
        val titleParams = lp(WRAP, WRAP)
        titleParams.leftMargin = dp(8)
        bar.addView(txt("Settings", 18f, 0, true), titleParams)
        settingsPage.addView(bar, lp(MATCH, dp(56)))

        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(list)
        settingsPage.addView(scroll, lp(MATCH, 0, 1f))

        fun addCard(c: View) {
            val p = lp(MATCH, WRAP)
            p.topMargin = dp(12)
            list.addView(c, p)
        }

        // --- Cursor ---
        speedValue = txt("", 13f, 1)
        updateSpeedLabel()
        val speedRow = LinearLayout(this)
        speedRow.orientation = LinearLayout.HORIZONTAL
        speedRow.addView(txt("Speed", 15f, 0), lp(0, WRAP, 1f))
        speedRow.addView(speedValue)

        speedSeek = SeekBar(this)
        speedSeek.max = 100
        speedSeek.progress = ((sensitivity - 0.5f) / 0.045f).toInt().coerceIn(0, 100)
        speedSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                sensitivity = 0.5f + p * 0.045f
                pad.sensitivity = sensitivity
                updateSpeedLabel()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {
                prefs.edit().putFloat("speed", sensitivity).apply()
            }
        })

        accelSwitch = Switch(this)
        accelSwitch.isChecked = accel
        accelSwitch.setOnCheckedChangeListener { _, checked ->
            accel = checked
            pad.accel = checked
            prefs.edit().putBoolean("accel", checked).apply()
        }
        val accelText = LinearLayout(this)
        accelText.orientation = LinearLayout.VERTICAL
        accelText.addView(txt("Pointer acceleration", 15f, 0))
        accelText.addView(txt("Faster swipes move the cursor further", 12f, 1))
        val accelRow = LinearLayout(this)
        accelRow.orientation = LinearLayout.HORIZONTAL
        accelRow.gravity = Gravity.CENTER_VERTICAL
        accelRow.addView(accelText, lp(0, WRAP, 1f))
        accelRow.addView(accelSwitch)

        val cursorTitle = txt("CURSOR", 12f, 3, true)
        val seekParams = lp(MATCH, WRAP)
        seekParams.topMargin = dp(6)
        val accelParams = lp(MATCH, WRAP)
        accelParams.topMargin = dp(12)
        val cursorCard = card(cursorTitle)
        cursorCard.addView(speedRow, lp(MATCH, WRAP).apply { topMargin = dp(10) })
        cursorCard.addView(speedSeek, seekParams)
        cursorCard.addView(accelRow, accelParams)
        addCard(cursorCard)

        // --- Appearance ---
        val themeCard = card(txt("APPEARANCE", 12f, 3, true))
        themeCard.addView(
            segmented(
                listOf("System", "Light", "Dark"),
                { themeMode },
                {
                    themeMode = it
                    prefs.edit().putInt("theme", it).apply()
                    applyTheme()
                }
            ),
            lp(MATCH, WRAP).apply { topMargin = dp(10) }
        )
        addCard(themeCard)

        // --- Connection ---
        val connCard = card(txt("CONNECTION", 12f, 3, true))
        connCard.addView(
            segmented(
                listOf("Bluetooth LE", "Classic"),
                { if (bleMode) 0 else 1 },
                { if ((it == 0) != bleMode) switchMode() }
            ),
            lp(MATCH, WRAP).apply { topMargin = dp(10) }
        )
        connCard.addView(
            txt("Use Bluetooth LE unless your phone supports classic Bluetooth keyboards. Changing this restarts pairing.", 12f, 1),
            lp(MATCH, WRAP).apply { topMargin = dp(8) }
        )
        addCard(connCard)

        frame.addView(settingsPage, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun updateSpeedLabel() {
        speedValue.text = "%.1fx".format(sensitivity)
    }

    // ---------- actions ----------

    private fun openDrawer() {
        drawerOpen = true
        scrim.visibility = View.VISIBLE
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(180).start()
        drawer.animate().translationX(0f).setDuration(220).start()
    }

    private fun closeDrawer() {
        drawerOpen = false
        scrim.animate().alpha(0f).setDuration(180).withEndAction {
            if (!drawerOpen) scrim.visibility = View.GONE
        }.start()
        drawer.animate().translationX(-dp(288).toFloat()).setDuration(220).start()
    }

    private fun openSettings() {
        settingsPage.alpha = 0f
        settingsPage.visibility = View.VISIBLE
        settingsPage.animate().alpha(1f).setDuration(160).start()
    }

    private fun closeSettings() {
        settingsPage.visibility = View.GONE
    }

    /** Full-screen mode hides every on-screen button so the touchpad fills the screen. */
    private fun setFullScreen(on: Boolean) {
        fullScreen = on && linked
        refreshLayout()
    }

    private fun showKeyboard() {
        input.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        resetInput()
        imm.showSoftInput(input, InputMethodManager.SHOW_FORCED)
    }

    private fun showLog() {
        val tv = TextView(this)
        tv.typeface = Typeface.MONOSPACE
        tv.textSize = 10f
        tv.setTextColor(colorFor(0))
        tv.setPadding(dp(16), dp(8), dp(16), dp(8))
        tv.setTextIsSelectable(true)
        val text = HidHost.readLog().takeLast(20_000)
        tv.text = if (text.isBlank()) "(empty)" else text
        val sv = ScrollView(this)
        sv.addView(tv)
        dialogBuilder()
            .setTitle("Connection log")
            .setView(sv)
            .setPositiveButton("Copy") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("PC Remote log", text))
            }
            .setNeutralButton("Clear") { _, _ -> HidHost.clearLog() }
            .setNegativeButton("Close", null)
            .show()
        sv.post { sv.fullScroll(View.FOCUS_DOWN) }
    }

    private fun showHelp() {
        dialogBuilder()
            .setTitle("How to connect")
            .setMessage(
                "1. Tap Discoverable on this screen.\n" +
                    "2. On Windows open Settings > Bluetooth & devices > Add device > Bluetooth.\n" +
                    "3. Pick this phone and accept the pairing request on both devices.\n\n" +
                    "Once connected, the pairing card disappears. If Windows will not reconnect, " +
                    "remove the phone in Windows Bluetooth settings and pair again."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun ensureBackend() {
        if (HidHost.backend == null && hasBluetoothPermission()) launchBackend()
    }

    private fun makeDiscoverable() {
        val i = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        @Suppress("DEPRECATION")
        startActivityForResult(i, 2)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 2) return
        if (resultCode == RESULT_CANCELED) {
            clearPairBusy()
            status.text = "Discoverable was cancelled. Tap Discoverable to try again."
        } else {
            discoverBusy = true
            connectBusy = false
            refreshPairButtons()
            status.text = "Discoverable for 5 minutes. On Windows: Settings > Bluetooth > Add device > Bluetooth, then pick this phone."
            uiHandler.removeCallbacksAndMessages(null)
            uiHandler.postDelayed({ clearPairBusy() }, 300_000)
        }
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
                connectBusy = true
                refreshPairButtons()
                status.text = "Connecting to ${devices[i].name ?: devices[i].address}..."
                hid.connectTo(devices[i])
                uiHandler.removeCallbacksAndMessages(null)
                uiHandler.postDelayed({ clearPairBusy() }, 20_000)
            }
            .show()
    }
}
