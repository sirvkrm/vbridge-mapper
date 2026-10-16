import os
import sys
import time
import argparse
from collections import deque

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

sys.path.append(os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
from client.config import Profile, list_profiles
from client.connection import TouchConnection
from client.mapper import InputMapper
from client.ui import create_dashboard_layout, render_dashboard
from client.raw_input import is_key_down, get_vk

try:
    from rich.live import Live
    RICH_AVAILABLE = True
except ImportError:
    RICH_AVAILABLE = False

def select_profile(profiles_dir: str) -> Profile:
    profile_paths = list_profiles(profiles_dir)
    if not profile_paths:
        print(f"[-] No profiles found in {profiles_dir}")
        sys.exit(1)

    print("\n==========================================")
    print("   🎮 Kernel Touch Mapper - Profiles")
    print("==========================================")
    for idx, path in enumerate(profile_paths, 1):
        try:
            p = Profile(path)
            print(f"  [{idx}] {p.app_name} ({os.path.basename(path)})")
        except Exception:
            print(f"  [{idx}] {os.path.basename(path)}")

    while True:
        try:
            choice = input("\nSelect profile number (default 1): ").strip()
            if not choice:
                choice = "1"
            idx = int(choice) - 1
            if 0 <= idx < len(profile_paths):
                selected_path = profile_paths[idx]
                return Profile(selected_path)
        except ValueError:
            pass
        print("Invalid choice, please try again.")

def main():
    parser = argparse.ArgumentParser(description="Kernel Touch Mapper & Hardware Telemetry Monitor for Rooted Android")
    parser.add_argument("--profile", "-p", help="Path to profile JSON file")
    parser.add_argument("--host", default="127.0.0.1", help="Device server host (default: 127.0.0.1)")
    parser.add_argument("--port", type=int, default=8888, help="Device server port (default: 8888)")
    parser.add_argument("--no-forward", action="store_true", help="Skip automatic 'adb forward' command")
    parser.add_argument("--rate", type=int, default=250, help="Input polling rate in Hz (default: 250)")
    parser.add_argument("--telemetry-delay", type=int, default=1000, help="Telemetry update interval in ms (default: 1000, 0=disable)")
    parser.add_argument("--aim", action="store_true", help="Start with Mouse Aim Mode locked immediately")
    parser.add_argument("--no-ui", action="store_true", help="Disable btop-like Rich TUI dashboard")
    args = parser.parse_args()

    profiles_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "profiles"))

    if args.profile:
        profile_path = os.path.abspath(args.profile)
        if not os.path.exists(profile_path):
            print(f"[-] Profile file not found: {profile_path}")
            return
        profile = Profile(profile_path)
        print(f"\n[+] Loaded CLI profile: {profile.app_name}")
        print(f"    Target Screen: {profile.display.screen_width}x{profile.display.screen_height} (Orientation: {profile.display.orientation}°)")
        print(f"    Digitizer Max: {profile.display.digitizer_max_x}x{profile.display.digitizer_max_y}")
        print(f"    Toggle Aim Key: '{profile.camera.get('toggle_key', '`')}' or F1")
    else:
        profile = None
        print("\n=======================================================")
        print(" 🎮 Kernel Touch Mapper - Dynamic Phone Bridge Mode")
        print(" ⏳ WAITING FOR CONTROLS FROM ANDROID APP...")
        print(" 📱 Please open VBridgeMapper on your Redmi K20 Pro.")
        print("    Controls and keybindings will be transferred dynamically.")
        print("=======================================================\n")

    # Connect to device
    conn = TouchConnection(host=args.host, port=args.port, auto_adb_forward=not args.no_forward)
    if not conn.connect():
        return

    # Configure hardware telemetry directly on the C root daemon
    current_telemetry_delay = max(0, args.telemetry_delay)
    conn.configure_telemetry(current_telemetry_delay)

    mapper = InputMapper(profile, conn)
    toggle_key = profile.camera.get("toggle_key", "`") if profile else "`"

    if profile and args.aim:
        mapper.toggle_aim_mode()

    def on_remote_profile_activated(new_prof: Profile):
        nonlocal toggle_key
        print(f"\n[⚡ CONTROLS RECEIVED FROM APP] App: {new_prof.app_name} ({len(new_prof.key_mappings)} keybindings)")
        mapper.update_profile(new_prof)
        toggle_key = new_prof.camera.get("toggle_key", "`")
        if args.aim and not mapper.aim_mode:
            mapper.toggle_aim_mode()

    def on_live_key_update(key: str, x: int, y: int):
        mapper.update_key_coord(key, x, y)

    def on_live_camera_update(px: int, py: int, radius: int):
        mapper.update_camera_pivot(px, py, radius)

    def on_live_joystick_update(cx: int, cy: int, radius: int):
        mapper.update_joystick(cx, cy, radius)

    from client.signal_server import SignalServer
    sig_server = SignalServer(
        port=8889,
        on_profile_activated=on_remote_profile_activated,
        on_live_key_update=on_live_key_update,
        on_live_camera_update=on_live_camera_update,
        on_live_joystick_update=on_live_joystick_update
    )
    sig_server.start()

    interval = 1.0 / max(10, min(1000, args.rate))

    use_tui = RICH_AVAILABLE and not args.no_ui

    # Telemetry state
    fps_val = 60.0
    cpu_pct = 0.0
    cpu_temp = 0.0
    gpu_pct = 0.0
    gpu_temp = 0.0
    mem_used = 0
    mem_total = 7474
    battery_temp = 0.0
    fps_history = deque([60.0] * 20, maxlen=30)

    # Hotkey for toggling telemetry on/off
    vk_t = get_vk("t")
    t_was_down = False

    # Timing metrics
    last_ui_update = time.perf_counter()
    ui_interval = 1.0 / 20.0 # 20 FPS UI refresh
    ticks_count = 0
    fps_meas_start = time.perf_counter()
    current_polling_hz = float(args.rate)
    last_loop_latency = 0.0

    if not use_tui:
        print("\n=======================================================")
        print(f" [+] Mapper Running at {args.rate}Hz (Latency < {1000/args.rate:.1f}ms)!")
        print(f" [+] Press '{toggle_key}' (Tilde) or [F1] to Lock/Unlock Mouse Aim")
        print(" [+] Press Ctrl+C in this console to exit cleanly")
        print("=======================================================\n")

    layout = create_dashboard_layout() if use_tui else None
    live = Live(layout, screen=True, refresh_per_second=20) if use_tui else None

    try:
        if live:
            live.start()

        while True:
            t0 = time.perf_counter()
            mapper.tick()
            t1 = time.perf_counter()

            last_loop_latency = (t1 - t0) * 1000.0
            ticks_count += 1

            # Read incoming 24-byte kernel telemetry packet (non-blocking)
            telem = conn.poll_telemetry()
            if telem:
                fps_val = telem.fps
                cpu_pct = telem.cpu_usage_pct
                cpu_temp = telem.cpu_temp_c
                gpu_pct = telem.gpu_usage_pct
                gpu_temp = telem.gpu_temp_c
                mem_used = telem.mem_used_mb
                mem_total = telem.mem_total_mb
                battery_temp = telem.battery_temp_c
                if fps_val > 0:
                    fps_history.append(fps_val)

            # Toggle telemetry with 'T' key when aim mode is off
            if not mapper.aim_mode:
                t_down = is_key_down(vk_t)
                if t_down and not t_was_down:
                    if current_telemetry_delay > 0:
                        current_telemetry_delay = 0
                    else:
                        current_telemetry_delay = 1000
                    conn.configure_telemetry(current_telemetry_delay)
                t_was_down = t_down

            now = time.perf_counter()
            if (now - fps_meas_start) >= 0.5:
                current_polling_hz = ticks_count / (now - fps_meas_start)
                ticks_count = 0
                fps_meas_start = now

            if use_tui and (now - last_ui_update) >= ui_interval:
                curr_app = mapper.profile.app_name if mapper.profile else "📱 WAITING FOR PHONE CONTROLS..."
                curr_pkg = mapper.profile.package_name if mapper.profile else "Open VBridgeMapper on phone"
                curr_cam = (mapper.camera.curr_x, mapper.camera.curr_y) if mapper.camera else (1600.0, 540.0)
                curr_joy = mapper.joystick.is_active if mapper.joystick else False

                render_dashboard(
                    layout=layout,
                    app_name=curr_app,
                    active_pkg=curr_pkg,
                    fps=fps_val,
                    fps_history=list(fps_history),
                    cpu_pct=cpu_pct,
                    cpu_temp=cpu_temp,
                    gpu_pct=gpu_pct,
                    gpu_temp=gpu_temp,
                    mem_used=mem_used,
                    mem_total=mem_total,
                    battery_temp=battery_temp,
                    telemetry_delay_ms=current_telemetry_delay,
                    polling_hz=current_polling_hz,
                    target_hz=args.rate,
                    loop_latency_ms=last_loop_latency,
                    aim_mode=mapper.aim_mode,
                    toggle_key=toggle_key,
                    cam_pos=curr_cam,
                    joy_active=curr_joy,
                    active_slots=mapper.slot_mgr.active_slots,
                    hotkeys=mapper.hotkeys,
                    total_packets=conn.total_packets_sent
                )
                last_ui_update = now
            elif not use_tui and (now - last_ui_update) >= 1.0:
                profile_status = mapper.profile.app_name if mapper.profile else "⏳ WAITING FOR PHONE CONTROLS"
                print(f"[DEVICE] FPS: {fps_val:.1f} | CPU: {cpu_pct:.1f}% ({cpu_temp:.1f}°C) | GPU: {gpu_pct:.1f}% ({gpu_temp:.1f}°C) | RAM: {mem_used}/{mem_total}MB | Bat: {battery_temp:.1f}°C | Status: {profile_status} | Aim: {'LOCKED' if mapper.aim_mode else 'UNLOCKED'}", flush=True)
                last_ui_update = now

            elapsed = time.perf_counter() - t0
            sleep_time = interval - elapsed
            if sleep_time > 0:
                time.sleep(sleep_time)

    except KeyboardInterrupt:
        pass
    finally:
        if live:
            live.stop()
        sig_server.stop()
        mapper.cleanup()
        conn.close()
        print("\n[+] Clean shutdown complete.")

if __name__ == "__main__":
    main()
