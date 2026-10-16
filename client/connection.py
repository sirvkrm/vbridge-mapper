import socket
import subprocess
import time
import sys
import os
import struct
import select

sys.path.append(os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
from common.protocol import (
    pack_event, pack_reset, pack_sync, TouchAction,
    TELEMETRY_MAGIC, TELEMETRY_SIZE, unpack_telemetry
)

class TouchConnection:
    def __init__(self, host: str = "127.0.0.1", port: int = 8888, auto_adb_forward: bool = True):
        self.host = host
        self.port = port
        self.auto_adb_forward = auto_adb_forward
        self.sock: socket.socket | None = None
        self.total_packets_sent: int = 0
        self._telem_buf = bytearray()
        self.last_telemetry = None

    @staticmethod
    def get_adb_binary() -> str:
        import shutil
        adb = shutil.which("adb")
        if adb:
            return adb
        fallback = os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe")
        if os.path.exists(fallback):
            return fallback
        return "adb"

    def ensure_daemon_running(self) -> bool:
        """
        Verifies that touch_injector is running as root on Android.
        If not, automatically launches it in the background via su.
        """
        adb_bin = self.get_adb_binary()
        try:
            ps_res = subprocess.run([adb_bin, "shell", "su", "-c", "ps -A | grep touch_injector"],
                                    capture_output=True, text=True, timeout=2.5)
            if ps_res.returncode == 0 and "touch_injector" in ps_res.stdout:
                return True

            print("[*] 'touch_injector' daemon is not active on device. Auto-launching via root...")
            # Ensure binary exists on device
            local_bin = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "server", "touch_injector"))
            if os.path.exists(local_bin):
                subprocess.run([adb_bin, "push", local_bin, "/data/local/tmp/touch_injector"],
                               capture_output=True, text=True, timeout=3.0)

            launch_cmd = "chmod 755 /data/local/tmp/touch_injector && nohup /data/local/tmp/touch_injector -d /dev/input/event3 </dev/null >/dev/null 2>&1 &"
            subprocess.run([adb_bin, "shell", "su", "-c", launch_cmd],
                           capture_output=True, text=True, timeout=3.0)
            time.sleep(0.5)

            # Re-verify
            chk = subprocess.run([adb_bin, "shell", "su", "-c", "ps -A | grep touch_injector"],
                                 capture_output=True, text=True, timeout=2.5)
            if "touch_injector" in chk.stdout:
                print("[+] 'touch_injector' daemon successfully launched on phone!")
                return True
        except Exception as e:
            print(f"[!] Note: Could not auto-verify touch_injector daemon: {e}")
        return False

    def setup_adb_forward(self) -> bool:
        adb_bin = self.get_adb_binary()
        try:
            print(f"[*] Setting up ADB port forwarding using: {adb_bin}...")
            res = subprocess.run([adb_bin, "forward", f"tcp:{self.port}", f"tcp:{self.port}"],
                                 capture_output=True, text=True, check=True)
            print(f"[+] ADB forward succeeded: {res.stdout.strip()}")
            return True
        except Exception as e:
            print(f"[!] Warning: ADB forward command failed: {e}")
            print("    Make sure your Redmi K20 Pro is connected with USB Debugging enabled.")
            return False

    def setup_adb_reverse(self, reverse_port: int = 8889) -> bool:
        adb_bin = self.get_adb_binary()
        try:
            subprocess.run([adb_bin, "reverse", f"tcp:{reverse_port}", f"tcp:{reverse_port}"],
                           capture_output=True, text=True, check=True)
            print(f"[+] ADB reverse port {reverse_port} configured for Android app signals.")
            return True
        except Exception as e:
            return False

    def connect(self, retries: int = 5, retry_delay: float = 1.0) -> bool:
        if self.auto_adb_forward:
            self.ensure_daemon_running()
            self.setup_adb_forward()
            self.setup_adb_reverse(8889)

        for attempt in range(1, retries + 1):
            try:
                print(f"[*] Connecting to Touch Injector Daemon at {self.host}:{self.port} (Attempt {attempt}/{retries})...")
                s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                # Sub-millisecond latency: disable Nagle's algorithm
                s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                s.connect((self.host, self.port))
                self.sock = s
                self._telem_buf.clear()
                print("[+] Successfully connected to kernel touch daemon!")
                return True
            except ConnectionRefusedError:
                time.sleep(retry_delay)
            except Exception as e:
                print(f"[-] Connection error: {e}")
                time.sleep(retry_delay)

        print("[-] Failed to connect to device. Ensure 'touch_injector' is running as root on your device.")
        return False

    def send_event(self, action: TouchAction, slot: int, x: int, y: int, auto_sync: bool = True):
        if not self.sock:
            return
        try:
            payload = pack_event(action, slot, x, y, auto_sync)
            self.sock.sendall(payload)
            self.total_packets_sent += 1
        except Exception as e:
            print(f"[!] Socket send error: {e}")
            self.close()

    def send_batch(self, raw_bytes: bytes):
        if not self.sock:
            return
        try:
            self.sock.sendall(raw_bytes)
            self.total_packets_sent += len(raw_bytes) // 8
        except Exception as e:
            print(f"[!] Socket send error: {e}")
            self.close()

    def configure_telemetry(self, interval_ms: int):
        if not self.sock:
            return
        try:
            from common.protocol import pack_config_telemetry
            self.sock.sendall(pack_config_telemetry(interval_ms))
        except Exception as e:
            print(f"[!] Failed to configure telemetry: {e}")

    def poll_telemetry(self):
        """
        Non-blocking read of 24-byte kernel telemetry packet from device socket.
        Uses sliding buffer & magic byte scanning for resilient stream decoding.
        Zero ADB overhead!
        """
        if not self.sock:
            return None
        MAGIC_BYTES = struct.pack("<I", TELEMETRY_MAGIC)

        try:
            while True:
                r, _, _ = select.select([self.sock], [], [], 0)
                if not r:
                    break
                chunk = self.sock.recv(4096)
                if not chunk:
                    break
                self._telem_buf.extend(chunk)

            # Slide window to find and parse complete telemetry packets
            while len(self._telem_buf) >= TELEMETRY_SIZE:
                idx = self._telem_buf.find(MAGIC_BYTES)
                if idx < 0:
                    # Keep tail in case magic is split across recv boundaries
                    self._telem_buf = self._telem_buf[-3:]
                    break
                if idx > 0:
                    del self._telem_buf[:idx]

                if len(self._telem_buf) >= TELEMETRY_SIZE:
                    packet_bytes = bytes(self._telem_buf[:TELEMETRY_SIZE])
                    del self._telem_buf[:TELEMETRY_SIZE]
                    telem = unpack_telemetry(packet_bytes)
                    if telem:
                        self.last_telemetry = telem
        except Exception:
            pass

        return self.last_telemetry

    def reset_all(self):
        if not self.sock:
            return
        try:
            self.sock.sendall(pack_reset())
        except Exception:
            pass

    def close(self):
        if self.sock:
            try:
                self.reset_all()
                self.sock.close()
            except Exception:
                pass
            self.sock = None
            print("[+] Connection closed cleanly.")
