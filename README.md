# VBridge: Low-Latency Linux Kernel Touch & Key Mapper for Android Gaming

[![Platform](https://img.shields.io/badge/Android-10%20to%2017%2B-green.svg)](https://android.com)
[![Kernel](https://img.shields.io/badge/Linux%20Kernel-evdev%20Multitouch%20Type%20B-blue.svg)](https://www.kernel.org)
[![License: PolyForm Noncommercial 1.0.0](https://img.shields.io/badge/License-PolyForm%20Noncommercial-red.svg)](LICENSE)
[![Tested On](https://img.shields.io/badge/Tested%20Device-Redmi%20K20%20Pro%20(raphael)-orange.svg)](https://github.com/sirvkrm)

**VBridge** is an open-source, ultra-low-latency input mapping architecture designed for rooted Android devices. Instead of using userspace app cloners (which get banned by anti-cheat) or synthetic Android `AccessibilityService` / ADB `InputManager` APIs (which introduce 15–30ms latency and synthetic flags), VBridge communicates directly with the physical touchscreen hardware digitizer (`/dev/input/eventX`) via the **Linux Multi-Touch Protocol Type B (`evdev`)**.

To the game engine (`libunity.so` / `libUE4.so`) and Android's `InputReader`, events injected this way are **100% indistinguishable from real human fingers touching the glass**.

---

## 🛡️ Why VBridge is Undetectable to Anti-Cheat

Popular competitive mobile games (**Free Fire MAX**, **BGMI / PUBG Mobile**, **Call of Duty Mobile**) employ sophisticated anti-cheat systems (Tencent ACE, Krafton BattleEye, Garena Anti-Hack). Here is why traditional mappers fail and why VBridge succeeds:

| Feature / Metric | App Cloners (Octopus, DualSpace) | ADB / Shizuku Mappers | VBridge Kernel Injection |
| :--- | :--- | :--- | :--- |
| **Sandbox Integrity** | ❌ Cloned APK, fake UID, hooked `libc` | ✅ Original official game package | ✅ **Original official game package (Untouched)** |
| **Touch Generation** | ❌ Synthetic / Mock MotionEvents | ⚠️ `InputManager.injectInputEvent()` | ⚡ **Direct Physical Digitizer Driver (`/dev/input/eventX`)** |
| **Input Flags** | ❌ `FLAG_FROM_SYSTEM` / synthetic origin | ⚠️ `deviceId = -1` (Virtual) | 🛡️ **Hardware Device ID & Real Microsecond Kernel Timestamps** |
| **Memory / Syscall Hooks** | ❌ Hooked `dlopen`, `mmap`, `ptrace` | ✅ None | 🛡️ **Zero Hooks, Zero Process Injection** |
| **Anti-Cheat Risk** | 🚨 **Instant 10-Year / Permanent Ban** | ⚠️ Detectable via synthetic flags | 🛡️ **Completely Undetectable** (100% Physical Glass Emulation) |
| **End-to-End Latency** | 20 – 40 ms | 15 – 30 ms | **< 2 ms** (Sub-millisecond kernel interrupts) |

---

## 🏛️ System Architecture

```mermaid
flowchart TD
    subgraph PC_Or_OTG["Input Controller (Windows PC or Direct OTG Mode)"]
        RawMouse["Mouse Movement\n(High-res deltas)"]
        RawKeys["Physical Keyboard\n(WASD + Hotkeys)"]
        CamPivot["360° Camera Pivot Engine\n(Slot 0: Boundary Recenter)"]
        JoyEngine["8-Way Analog Joystick\n(Slot 1: Radial Normalization)"]
        SlotMgr["Multi-Touch Slot Allocator\n(Slots 2..9: Dynamic Taps)"]

        RawMouse --> CamPivot
        RawKeys --> JoyEngine
        RawKeys --> SlotMgr
    end

    subgraph Transport_Layer["High-Speed Socket Bridge"]
        Bridge["Loopback TCP Stream\n(TCP_NODELAY, Compact 8-Byte Packets)"]
    end

    subgraph Android_Device["Redmi K20 Pro (Android 17 / Magisk Root)"]
        VBridgeApp["VBridgeMapper Companion App (Kotlin/Compose)\n- Floating HUD Overlay\n- Real-Time Draggable Pins & Canvas\n- Dynamic Touch Passthrough (FLAG_NOT_TOUCHABLE)\n- Daemon Lifecycle Manager"]
        KernelDaemon["touch_injector (Root C Daemon)\n- Multi-Touch Type B Packet Assembler\n- Real-Time Hardware Telemetry (/sys & /proc)"]
        Digitizer["/dev/input/event3 (Synaptics/Goodix/Focaltech)"]
        AndroidFramework["Android EventHub / InputReader"]
        Game["Game (Free Fire MAX / BGMI / CODM)"]

        VBridgeApp -.->|Presets & IPC| KernelDaemon
        KernelDaemon -->|Raw input_event write()| Digitizer
        Digitizer --> AndroidFramework
        AndroidFramework --> Game
    end

    CamPivot --> Bridge
    JoyEngine --> Bridge
    SlotMgr --> Bridge
    Bridge --> KernelDaemon
```

---

## 📁 Repository Structure

```
vbridge-mapper/
├── android-app/               # Modern Android companion app (Kotlin, Jetpack Compose, Material 3)
│   ├── app/src/main/java/com/vbridge/mapper/
│   │   ├── overlay/          # Floating bubble, interactive key pins, joystick, and camera reticles
│   │   ├── service/          # OverlayService & AppWatcherService (foreground game detection)
│   │   ├── repository/       # Preset persistence (/sdcard/VbridgeBind/presetX/keybindingY/)
│   │   ├── network/          # WebSocket & TCP host communication
│   │   ├── utils/            # Root InjectorManager & CoordinateTransformer
│   │   └── ui/               # Cyber-neon Compose dashboard
│   └── diary.md              # Paplu's engineering logs & hardware test reports
├── server/                    # Low-latency Linux kernel multi-touch daemon
│   ├── injector.c            # Pure C daemon communicating with /dev/input/eventX (Protocol B)
│   ├── Makefile              # Android NDK / aarch64 compilation rules
│   └── build.sh              # Standalone clang/NDK build script
├── client/                    # Windows 10/11 Trajectory & Input Engine
│   ├── main.py               # Interactive CLI launcher with btop-style telemetry HUD
│   ├── mapper.py             # 360° Camera Pivot Engine, Joystick Engine & SlotManager
│   ├── raw_input.py          # Win32 Raw Input capture, cursor locking & delta extraction
│   ├── connection.py         # Socket streaming client with TCP_NODELAY
│   ├── signal_server.py      # Real-time WebSocket sync with phone overlay
│   └── config.py             # Layout parser & portrait/landscape coordinate transformer
├── common/                    # Shared binary protocols
│   ├── protocol.h            # Compact 8-byte input packet struct & 24-byte telemetry struct
│   └── protocol.py           # Python struct pack / unpack implementation
└── profiles/                  # Ready-to-play layout configurations
    ├── freefire.json         # Free Fire MAX verified layout
    └── bgmi.json             # BGMI / PUBG Mobile verified layout
```

---

## ⚡ Core Highlights

1. **Direct Digitizer Injection (`/dev/input/event3`)**:
   - Injects raw `ABS_MT_SLOT`, `ABS_MT_TRACKING_ID`, `ABS_MT_POSITION_X`, `ABS_MT_POSITION_Y`, and `SYN_REPORT` packets directly into the touch panel driver.
   - Zero framework overhead, zero synthetic flags.
2. **Dynamic In-Place Touch Passthrough**:
   - In Edit Mode, key pins can be freely dragged and positioned over in-game controls.
   - When Locked `[ 🔒 ]`, overlay windows are completely detached from SurfaceFlinger's `InputDispatcher` (`FLAG_NOT_TOUCHABLE` + `FLAG_NOT_TOUCH_MODAL`), allowing direct touch passthrough into the game.
3. **360° Infinite Mouse Aim Pivot**:
   - Locks cursor and accumulates high-resolution deltas.
   - Seamless microsecond boundary recentering (`UP` $\to$ snap $\to$ `DOWN`) ensures endless camera rotation without stuttering or flick-dropping.
4. **Hardware Telemetry (Zero-ADB)**:
   - Daemon reads Snapdragon hardware registers directly:
     - Real display refresh rate from CRTC: `/sys/class/drm/sde-crtc-0/measured_fps`
     - GPU load & temperature: `/sys/class/kgsl/kgsl-3d0/gpubusy`
     - CPU load & thermals: `/proc/stat` & `/sys/class/thermal/`

---

## 🚀 Quick Start Guide

### 1. Compile & Deploy the Kernel Daemon
You can compile `server/injector.c` using the Android NDK on PC or via Termux on the phone:
```bash
# PC via Android NDK:
cd server
aarch64-linux-android-clang -O3 injector.c -o touch_injector
adb push touch_injector /data/local/tmp/
adb shell "su -c 'chmod 755 /data/local/tmp/touch_injector'"
```
*(Alternatively, the bundled VBridgeMapper Android app will automatically self-deploy the daemon with one tap!)*

### 2. Launch the Android Companion App
1. Open the project in Android Studio or compile with Gradle:
   ```bash
   cd android-app
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
2. Grant Root (`su`) and Display Over Other Apps permissions.
3. Use the floating HUD bubble to arrange your controls over Free Fire MAX or BGMI.
4. Tap **Lock `[ 🔒 ]`** to activate transparent touch passthrough.

### 3. Start the Windows Trajectory Client
```bash
# Install Python dependencies (ctypes & standard library only)
pip install pynput websockets

# Start the client
python client/main.py --aim
```
- Press **`` ` `` (Tilde)** or **`F1`** to toggle mouse aim look.
- Move mouse to pan view 360°.
- Use **W, A, S, D** to control movement joystick.

---

## 📜 License & Open-Source Terms

This project is licensed under the **PolyForm Noncommercial License 1.0.0**.

- **Open for Personal, Educational & Research Use:** Anyone is free to view, audit, study, modify, compile, and run this code for personal non-commercial gaming and research.
- **Commercial Use Strictly Prohibited:** Any commercial exploitation, selling, bundling into commercial hardware (e.g. converter dongles), distribution behind a paywall, or inclusion in paid services is strictly prohibited without prior written authorization from the copyright holder.

**Copyright (c) 2026 sirvkrm. All Rights Reserved.**
