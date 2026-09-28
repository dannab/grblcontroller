# GRBL Machining
### Android controller for GRBL CNC machines — Machining Edition

📖 **User guides:** [English manual](https://dannab.github.io/grblcontroller/manual.html) · [Manuale italiano](https://dannab.github.io/grblcontroller/manuale.html).

Actively maintained fork of [Grbl Controller by zeevy](https://github.com/zeevy/grblcontroller) (now archived), continued by a CNC user with improvements driven by real-world machining. License: GPL-3.0.

> A Play Store release under the name **GRBL Machining** is in preparation.

#### What's new in this fork
- **FluidNC support and Telnet connection** — works with [FluidNC](https://github.com/bdring/FluidNC) over Wi-Fi/Telnet as well as Bluetooth and USB. The host and port (normally 23) are configurable, the FluidNC machine name is shown in the app and browser interface, and Telnet can coexist with the FluidNC WebUI.
- **Run from line** — resume a job from any line. The app reads the file up to that point and rebuilds the full modal state (work position, units, plane, work coordinate system, distance mode, feed, spindle and coolant), then sends a preamble to restore the controller before streaming continues. You can let it reposition the machine automatically at a chosen safe Z height or place the tool by hand, and it warns you before the spindle/coolant restart. Line numbers match the built-in G-code editor.
- **G-code visualizer** — OpenGL toolpath preview with pinch zoom and pan; while streaming, the already-executed path is grayed out in real time.
- **Guided 3-point auto-leveling** — the Leveling button records three manual or probed points in its own `levelingpoints.txt`, shows a summary, computes the stock plane and writes a `_leveled` copy. Existing points can be reused or cleared; arcs must be linearized to G1 by the CAM first.
- **Z plunge analyzer** — scans the G-code before the job and flags suspicious deep/steep Z plunges (wrong zero, missing safe height), so you catch them before the bit does.
- **G-code editor** — built-in editor with G-code syntax highlighting and search.
- **HTTP file server with remote control** — send and fetch G-code files from your PC browser over WiFi; password protected, runs as a foreground service with the URL shown in the notification (user: `grbl`). The web page shows the FluidNC machine name, live job status, cached phone battery level with a low-charge warning, **pause/stop** controls and real-time **feed and spindle overrides**.
- **"Make it ring"** — a button on the web page makes the host phone ring at max volume (even in silent mode) with vibration and a 30 s auto-stop, so you can find it on a noisy shop floor.
- **Color schemes** — pick one of four schemes (slate, red, blue, teal) in Settings; the choice is applied to both the app and the web interface.
- **CAM tab** — generate lines, circles and rectangles with multiple Z passes, or engrave an ordered 2D polyline read from `points.txt`. Polyline mode uses the first point as the Z reference, ignores Z on later points and alternates direction between passes. Jobs are saved directly into File Sender.
- **Reworked jogging** — 4 axis separate step and continuous modes, step size cycling via the central joypad button.
- **Point capture without a fixed limit** — Get point appends plain X/Y/Z rows to `points.txt`, with no leveling header and no three-point limit, for surveys and CAM paths.
- **Priority volume-button control** — while a job is in Run the volume keys pause it, while the controller is in Hold they resume it; in every other machine state they send jog cancel immediately. This is a convenient software stop, not a replacement for a physical emergency stop.
- **FluidNC SD jobs** — browse a controller SD card from File Sender and start a file directly with FluidNC `$SD/Run`, without streaming it through the phone.
- **State-aware spindle control** — from File Sender, start an idle spindle with `M3 S…`, stop it with `M5`, and use FluidNC real-time spindle stop/restore while a job is paused. The manual S value is configurable.
- **Restore work coordinates on connect** — optional: on reconnect, if the jog fields still hold X/Y/Z/A values, the app offers to reapply them to the machine with `G10 L20 P0` — handy to recover your zero after an accidental disconnect mid-job (only correct if the tool hasn't moved).
- **Quality of life** — flashlight button, UI rearrangement, safety checks before starting a job, and a fresh adaptive (vector) app icon with a coordinated splash screen.
- **Modern Android** — target SDK 35; files are picked via the system picker (SAF) and stored in the app media folder, so **no storage permissions** are required; runtime Bluetooth permissions (Android 12+); tested on Android 16.
- **No telemetry** — Firebase and push notifications removed: the app talks to your machine and nothing else.

#### Core features (from the original project)
- Bluetooth and USB (OTG) serial connections, plus FluidNC Telnet over Wi-Fi.
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
The debug variant has its own application ID, the label **GRBL Machining DEBUG**, an orange icon and HTTP port 8889 by default, so it can be installed beside the normal app (default HTTP port 8888). It also includes a hardware-free controller simulator for UI tests and documentation screenshots. The simulator is intentionally absent from release builds. Launch it on a connected emulator/device with:

```text
adb shell am start -a io.github.dannab.grblmachining.action.DEMO -c android.intent.category.DEFAULT
```
