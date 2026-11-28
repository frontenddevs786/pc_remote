package com.example.pcremote

/** Message codes between the screen (process ":ui") and the connection service (main process). */
object Ipc {
    // screen -> service
    const val REGISTER = 1      // replyTo = the screen's inbox
    const val UNREGISTER = 2
    const val CONFIG = 3        // arg1 = BLE mode (0/1), arg2 = keep awake (0/1)
    const val MOUSE = 4         // arg1 = dx | dy << 16, arg2 = buttons | wheel << 8
    const val PRESS = 5         // arg1 = usage, arg2 = modifiers
    const val TYPE = 6          // arg1 = char code, arg2 = extra modifiers
    const val CONSUMER = 7      // arg1 = usage
    const val ADVERTISE = 8
    const val RECONNECT = 9
    const val CONNECT_TO = 10   // data "d" = BluetoothDevice
    const val SWITCH = 11       // arg1 = BLE mode (0/1)
    const val ENSURE = 12       // arg1 = BLE mode (0/1)

    // service -> screen
    const val STATE = 20        // arg1 = connected (0/1); data: running, name, hasHost
    const val STATUS = 21       // data "m" = message
}
