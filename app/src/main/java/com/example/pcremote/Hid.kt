package com.example.pcremote

import android.bluetooth.BluetoothDevice

/** Common interface for the two ways the phone can act as a Bluetooth keyboard/mouse. */
interface Hid {
    /** Called with true when a PC connects and false when it disconnects. */
    var connectionListener: ((Boolean) -> Unit)?

    fun start()
    fun stop()

    /** BLE mode: start advertising so the PC can find the phone. */
    fun advertise() {}

    /** Classic mode: connect to an already-paired PC. */
    fun connectTo(device: BluetoothDevice) {}

    fun mouse(dx: Int, dy: Int, buttons: Int = 0, wheel: Int = 0)
    fun press(usage: Int, modifiers: Int = 0)
    fun typeChar(c: Char, extraMods: Int = 0): Boolean
    fun consumer(usage: Int)
}
