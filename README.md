# PC Remote

Android app that turns your phone into a Bluetooth touchpad, keyboard and media remote for
Windows 10/11 (or any Bluetooth host). No software is needed on the PC: Windows sees the phone as a
normal Bluetooth keyboard + mouse.

Requires Android 9 (API 28) or newer. Some phones have broken Bluetooth HID support.

## Get the APK without installing anything (GitHub Actions)

1. Create a new empty repository on github.com.
2. Upload every file in this folder (keep the folder structure, including `.github/workflows/build.yml`).
3. Open the repository's **Actions** tab, then run **Build APK** (it also runs on every upload).
4. When it finishes, open the run and download the **PCRemote-apk** artifact. Unzip it to get `app-debug.apk`.
5. Copy `app-debug.apk` to the phone and open it (allow "install unknown apps" if asked).

## Or build with Android Studio

Open this folder in Android Studio, let it sync, then Build > Build APK(s).

## Using it

1. Open the app and allow the Bluetooth permissions.
2. Tap **Discoverable**.
3. On Windows: Settings > Bluetooth & devices > Add device > Bluetooth > choose your phone. Accept the pairing code on both.
4. After pairing, move your finger on the touchpad. Next time, open the app and tap **Connect** (or connect from Windows).

If Windows will not reconnect, remove the phone in Windows Bluetooth settings and pair again.

## Controls

- One finger: move cursor. Tap: left click. Two-finger tap: right click. Two-finger drag: scroll.
- Drag toggle: holds the left button so you can select or drag windows.
- Ctrl / Alt / Shift / Win: tap to arm, then press the next key (e.g. Win then `d`).
- Keyboard button: opens the phone keyboard and sends what you type. Also has Esc, Tab, Del, Backspace, Enter, Start, arrows, Home/End and media keys.

## Main screen

While not connected, only the logo and the "Connect to your PC" block are shown. The touchpad, buttons,
keyboard button and full-screen controls (top bar and left drawer) appear once a PC is connected.

## Background connection

The Bluetooth link is held by a foreground service, so it stays connected while the app is open or
minimized. When you close the app (swipe it out of recent apps or kill it), the service, the connection
and the notification are all closed too, and nothing restarts them until you open the app again. The app is
shown in recent apps like any other app.

The "PC Remote" notification shows the state; tap it to open the app, or tap its **Stop** button to
disconnect and quit completely.

## Reconnecting

Pair once; after that the phone behaves like a Bluetooth headset. In Classic mode the app keeps retrying
the last PC in the background (every 5 s, then every 30 s) and reconnects as soon as the PC's Bluetooth link
appears. In Bluetooth LE mode the phone keeps advertising and asks the Bluetooth stack to auto-connect to
the remembered PC. Tap **Connect** to retry immediately (Classic: long-press it to choose another PC).
Only if you remove the pairing on either side do you need to pair again.
