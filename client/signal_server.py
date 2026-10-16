import asyncio
import json
import threading
import subprocess
import os
import sys
import time
from typing import Callable, Optional, Set
import websockets

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

from client.config import Profile, DisplayConfig
from client.connection import TouchConnection

class SignalServer:
    """
    WebSocket bridge server running on port 8889.
    Receives profile activation events and live key updates from the VBridgeMapper Android app.
    Automatically hot-reloads profile JSON from /sdcard/VbridgeBind/ or updates key coordinates in real time.
    """
    def __init__(
        self,
        port: int = 8889,
        on_profile_activated: Optional[Callable[[Profile], None]] = None,
        on_live_key_update: Optional[Callable[[str, int, int], None]] = None,
        on_live_camera_update: Optional[Callable[[int, int, int], None]] = None,
        on_live_joystick_update: Optional[Callable[[int, int, int], None]] = None
    ):
        self.port = port
        self.on_profile_activated = on_profile_activated
        self.on_live_key_update = on_live_key_update
        self.on_live_camera_update = on_live_camera_update
        self.on_live_joystick_update = on_live_joystick_update
        self.running = False
        self.clients: Set = set()
        self.loop: Optional[asyncio.AbstractEventLoop] = None
        self.thread: Optional[threading.Thread] = None
        self.adb_bin = TouchConnection.get_adb_binary()
        self.last_profile_name: Optional[str] = None

    def fetch_profile_from_device(self, remote_path: str) -> Optional[Profile]:
        try:
            p_dir = remote_path.rstrip('/')
            # Try unified profile.json first
            res = subprocess.run([self.adb_bin, "exec-out", "cat", f"{p_dir}/profile.json"],
                                 capture_output=True, text=True, timeout=2.0)
            if res.returncode == 0 and res.stdout.strip():
                data = json.loads(res.stdout)
                return Profile.from_dict(data)

            # Fallback: assemble from click.json, joystick.json, mouse.json
            click_res = subprocess.run([self.adb_bin, "exec-out", "cat", f"{p_dir}/click.json"],
                                       capture_output=True, text=True, timeout=2.0)
            joy_res = subprocess.run([self.adb_bin, "exec-out", "cat", f"{p_dir}/joystick.json"],
                                     capture_output=True, text=True, timeout=2.0)
            mouse_res = subprocess.run([self.adb_bin, "exec-out", "cat", f"{p_dir}/mouse.json"],
                                       capture_output=True, text=True, timeout=2.0)

            data = {
                "app_name": "Synced Preset",
                "display": {"screen_width": 2340, "screen_height": 1080, "digitizer_max_x": 1080, "digitizer_max_y": 2340, "orientation": 90},
                "camera": json.loads(mouse_res.stdout) if mouse_res.returncode == 0 and mouse_res.stdout.strip() else {},
                "joystick": json.loads(joy_res.stdout) if joy_res.returncode == 0 and joy_res.stdout.strip() else {},
                "key_mappings": json.loads(click_res.stdout) if click_res.returncode == 0 and click_res.stdout.strip() else []
            }
            return Profile.from_dict(data)
        except Exception as e:
            print(f"[!] Error reading profile from device: {e}")
        return None

    async def _handle_client(self, websocket):
        self.clients.add(websocket)
        remote = getattr(websocket, "remote_address", "client")
        print(f"\n[+] Android VBridgeMapper connected via WebSocket ({remote})")

        # Welcome packet - proactively request full active profile from phone
        try:
            await websocket.send(json.dumps({
                "event": "CONNECTED",
                "status": "ready",
                "request": "SYNC_ACTIVE_PROFILE",
                "active_profile": self.last_profile_name
            }))
        except Exception:
            pass

        try:
            async for message in websocket:
                try:
                    data = json.loads(message)
                    event = data.get("event", "UNKNOWN")

                    if event == "PING":
                        await websocket.send(json.dumps({
                            "event": "PONG",
                            "time": time.time()
                        }))

                    elif event == "PROFILE_ACTIVATED":
                        path = data.get("path")
                        pkg = data.get("package", "Unknown")
                        preset_name = data.get("preset_name", "Custom Preset")
                        preset_id = data.get("preset_id", 1)
                        binding_id = data.get("keybinding_id", 1)
                        profile_dict = data.get("profile")

                        if not path:
                            path = f"/sdcard/VbridgeBind/preset${preset_id}/keybinding${binding_id}/"

                        print(f"\n[⚡ PROFILE ACTIVATED] {preset_name} (#{preset_id}) -> Slot #{binding_id} for {pkg}")
                        self.last_profile_name = preset_name

                        new_profile = None
                        if profile_dict and isinstance(profile_dict, dict):
                            new_profile = Profile.from_dict(profile_dict)
                            print(f"    [+] Transferred dynamically from Android App over WebSocket! ({len(new_profile.key_mappings)} keybindings)")
                        elif path:
                            new_profile = self.fetch_profile_from_device(path)

                        if new_profile and self.on_profile_activated:
                            self.on_profile_activated(new_profile)
                            print(f"    [+] Successfully activated profile: {new_profile.app_name}")

                        await websocket.send(json.dumps({
                            "event": "PROFILE_LOADED",
                            "status": "success",
                            "preset_id": preset_id,
                            "keybinding_id": binding_id
                        }))

                    elif event == "LIVE_KEY_UPDATE":
                        key = data.get("key")
                        x = data.get("x")
                        y = data.get("y")
                        if key is not None and x is not None and y is not None:
                            if self.on_live_key_update:
                                self.on_live_key_update(str(key), int(x), int(y))
                        await websocket.send(json.dumps({
                            "event": "ACK_KEY",
                            "key": key
                        }))

                    elif event == "LIVE_CAMERA_UPDATE":
                        px = data.get("pivot_x")
                        py = data.get("pivot_y")
                        radius = data.get("reset_radius", 260)
                        if px is not None and py is not None:
                            if self.on_live_camera_update:
                                self.on_live_camera_update(int(px), int(py), int(radius))
                        await websocket.send(json.dumps({"event": "ACK_CAMERA"}))

                    elif event == "LIVE_JOYSTICK_UPDATE":
                        cx = data.get("center_x")
                        cy = data.get("center_y")
                        radius = data.get("radius", 140)
                        if cx is not None and cy is not None:
                            if self.on_live_joystick_update:
                                self.on_live_joystick_update(int(cx), int(cy), int(radius))
                        await websocket.send(json.dumps({"event": "ACK_JOYSTICK"}))

                    else:
                        await websocket.send(json.dumps({"event": "ACK", "received": event}))

                except json.JSONDecodeError:
                    pass
                except Exception as e:
                    print(f"[!] WebSocket message handling error: {e}")

        except websockets.exceptions.ConnectionClosed:
            pass
        finally:
            self.clients.discard(websocket)
            print(f"\n[-] Android VBridgeMapper disconnected")

    async def _main_ws(self):
        self.stop_event = asyncio.Event()
        async with websockets.serve(self._handle_client, "127.0.0.1", self.port) as server:
            self.server = server
            self.running = True
            await self.stop_event.wait()

    def _run_server(self):
        self.loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self.loop)

        try:
            self.loop.run_until_complete(self._main_ws())
        except OSError:
            # Port already in use by bridge_server.py, fall back to file watcher
            self.running = True
            self._run_file_watcher()
        except Exception:
            pass
        finally:
            try:
                pending = asyncio.all_tasks(self.loop)
                for t in pending:
                    t.cancel()
                self.loop.run_until_complete(asyncio.gather(*pending, return_exceptions=True))
                self.loop.close()
            except Exception:
                pass

    def _run_file_watcher(self):
        scratch_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "scratch"))
        signal_file = os.path.join(scratch_dir, "active_signal.json")
        keys_file = os.path.join(scratch_dir, "live_keys.json")
        last_sig_mtime = os.path.getmtime(signal_file) if os.path.exists(signal_file) else 0
        last_keys_mtime = os.path.getmtime(keys_file) if os.path.exists(keys_file) else 0

        while self.running:
            try:
                if os.path.exists(signal_file):
                    mtime = os.path.getmtime(signal_file)
                    if mtime > last_sig_mtime:
                        last_sig_mtime = mtime
                        with open(signal_file, "r") as f:
                            data = json.load(f)
                        path = data.get("path")
                        if not path:
                            preset_id = data.get("preset_id", 1)
                            binding_id = data.get("keybinding_id", 1)
                            path = f"/sdcard/VbridgeBind/preset{preset_id}/keybinding{binding_id}/"
                        if path and self.on_profile_activated:
                            new_profile = self.fetch_profile_from_device(path)
                            if new_profile:
                                self.on_profile_activated(new_profile)

                if os.path.exists(keys_file):
                    mtime = os.path.getmtime(keys_file)
                    if mtime > last_keys_mtime:
                        last_keys_mtime = mtime
                        with open(keys_file, "r") as f:
                            data = json.load(f)
                        k = data.get("key")
                        x = data.get("x")
                        y = data.get("y")
                        if k is not None and x is not None and y is not None and self.on_live_key_update:
                            self.on_live_key_update(str(k), int(x), int(y))
            except Exception:
                pass
            time.sleep(0.2)

    def start(self):
        if self.running:
            return
        self.thread = threading.Thread(target=self._run_server, daemon=True)
        self.thread.start()

    def stop(self):
        self.running = False
        if self.loop and self.loop.is_running() and hasattr(self, "stop_event"):
            self.loop.call_soon_threadsafe(self.stop_event.set)
        if self.thread and self.thread.is_alive():
            self.thread.join(timeout=1.0)
