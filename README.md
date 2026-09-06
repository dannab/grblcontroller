# GRBL Machining
### Android controller for GRBL CNC machines — Machining Edition

📖 **User guides:** [English manual](https://dannab.github.io/grblcontroller/manual.html) · [Manuale italiano](https://dannab.github.io/grblcontroller/manuale.html).

Actively maintained fork of [Grbl Controller by zeevy](https://github.com/zeevy/grblcontroller) (now archived), continued by a CNC user with improvements driven by real-world machining. License: GPL-3.0.

> A Play Store release under the name **GRBL Machining** is in preparation.

#### What's new in this fork
- **FluidNC support** — works out of the box with [FluidNC](https://github.com/bdring/FluidNC) controllers (tested on real hardware): both the GRBL 1.1 and the FluidNC banners are auto-detected at connection, no custom init string needed.
- **Run from line** — resume a job from any line. The app reads the file up to that point and rebuilds the full modal state (work position, units, plane, work coordinate system, distance mode, feed, spindle and coolant), then sends a preamble to restore the controller before streaming continues. You can let it reposition the machine automatically at a chosen safe Z height or place the tool by hand, and it warns you before the spindle/coolant restart. Line numbers match the built-in G-code editor.
- **G-code visualizer** — OpenGL toolpath preview with pinch zoom and pan; while streaming, the already-executed path is grayed out in real time.
- **3-point autolevel** — probe three points on the stock, the app computes the plane compensation and writes a `_leveled` copy of the G-code file.
- **Z plunge analyzer** — scans the G-code before the job and flags suspicious deep/steep Z plunges (wrong zero, missing safe height), so you catch them before the bit does.
- **G-code editor** — built-in editor with G-code syntax highlighting and search.
- **HTTP file server with remote control** — send and fetch G-code files from your PC browser over WiFi; password protected, runs as a foreground service with the URL shown in the notification (user: `grbl`). The web page is themed to match the app and shows a live header with the machine name and status, plus controls to **pause/stop the running job** and adjust **feed and spindle overrides** in real time from across the workshop.
- **"Make it ring"** — a button on the web page makes the host phone ring at max volume (even in silent mode) with vibration and a 30 s auto-stop, so you can find it on a noisy shop floor.
- **Color schemes** — pick one of four schemes (slate, red, blue, teal) in Settings; the choice is applied to both the app and the web interface.
- **CAM tab** — generate simple jobs directly on the device (lines, circles, rectangles) with multiple Z passes, saved straight into the file sender.
- **Reworked jogging** — 4 axis separate step and continuous modes, step size cycling via the central joypad button.
- **Restore work coordinates on connect** — optional: on reconnect, if the jog fields still hold X/Y/Z/A values, the app offers to reapply them to the machine with `G10 L20 P0` — handy to recover your zero after an accidental disconnect mid-job (only correct if the tool hasn't moved).
- **Quality of life** — flashlight button, UI rearrangement, safety checks before starting a job, and a fresh adaptive (vector) app icon with a coordinated splash screen.
- **Modern Android** — target SDK 35; files are picked via the system picker (SAF) and stored in the app media folder, so **no storage permissions** are required; runtime Bluetooth permissions (Android 12+); tested on Android 16.
- **No telemetry** — Firebase and push notifications removed: the app talks to your machine and nothing else.

#### Core features (from the original project)
- Bluetooth and USB (OTG) serial connections.
- GRBL 1.1 real-time feed, spindle and rapid overrides.
- Character-counting streaming protocol.
- Real-time machine status (position, feed, spindle speed, buffer state — enable buffer reports with `$10=2`).
- Streams G-code files (.gcode, .nc, .ngc, .tap and more).
- Short text commands console.
- Probing (G38.3) with auto Z-axis adjustment; manual tool change with G43.1.
- 4 configurable custom buttons with multi-line commands (short and long click).
- Works in background with low resource usage.

#### Notes
- If you are connecting a Bluetooth module to your machine for the first time, make sure its baud rate is set to 115200 (GRBL 1.1 default: 8 bits, no parity, 1 stop bit).
- HC-05 Bluetooth module setup: http://www.buildlog.net/blog/2017/10/using-the-hc-05-bluetooth-module/
- HC-06 Bluetooth module setup: https://github.com/zeevy/grblcontroller/wiki/Bluetooth-Setup-HC-06

#### Limitations
- No trimming of decimal places.
- Does not remove unsupported G-codes.
- No expansion of canned drill cycles or M06 tool change.

#### Credits
- [zeevy](https://github.com/zeevy/grblcontroller) — the original Grbl Controller this fork is built on
- Will Winder https://github.com/winder/Universal-G-Code-Sender
- Joan Zapata https://github.com/JoanZapata/android-iconify
- Markus Junginger https://github.com/greenrobot/EventBus
- Felipe Herranz https://github.com/felHR85/UsbSerial
- nbsp-team https://github.com/nbsp-team/MaterialFilePicker
- Chuang Guangquan https://github.com/warkiz/IndicatorSeekBar
- Ace editor https://ace.c9.io/ and NanoHTTPD https://github.com/NanoHttpd/nanohttpd

See [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) for full third-party license details.

#### Debug-only demo mode
The debug variant includes a hardware-free controller simulator for UI tests and documentation screenshots. It is intentionally absent from release builds. Launch it on a connected emulator/device with:

```text
adb shell am start -a io.github.dannab.grblmachining.action.DEMO -c android.intent.category.DEFAULT
```
