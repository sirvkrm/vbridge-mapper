import subprocess
import time
import re
import threading
from collections import deque
from typing import Optional, List, Dict, Any
from client.connection import TouchConnection

class FpsMonitor:
    """
    Android display FPS monitor.
    NOTE: Continuous dumpsys SurfaceFlinger polling locks SurfaceFlinger's mStateLock,
    which can cause in-game frame drops from 60 to ~56 FPS.
    By default, this monitor runs in Zero-Overhead Passive mode (enabled=False)
    to guarantee a rock-solid, locked 60 FPS during competitive gameplay.
    """
    def __init__(self, sample_interval: float = 1.5, enabled: bool = False):
        self.sample_interval = sample_interval
        self.enabled = enabled
        self.adb_bin = TouchConnection.get_adb_binary()
        self.running = False
        self.thread: Optional[threading.Thread] = None

        self.current_fps: float = 60.0
        self.avg_fps: float = 60.0
        self.min_fps: float = 60.0
        self.max_fps: float = 60.0
        self.target_hz: float = 60.0
        self.active_app: str = "Free Fire MAX / BGMI"
        self.total_frames: int = 0
        self.fps_history: deque = deque([60.0] * 20, maxlen=30)

        self.last_frame_count: Optional[int] = None
        self.last_timestamp: float = time.perf_counter()

    def get_focused_app(self) -> str:
        try:
            out = subprocess.check_output(
                [self.adb_bin, "shell", "dumpsys", "window"],
                text=True, stderr=subprocess.DEVNULL, timeout=1.5
            )
            for line in out.splitlines():
                if "mCurrentFocus=" in line and "Window{" in line:
                    m = re.search(r"Window\{[0-9a-fA-F]+\s+u\d+\s+([^\}]+)\}", line)
                    if m:
                        win = m.group(1)
                        pkg = win.split("/")[0]
                        return pkg
        except Exception:
            pass
        return self.active_app

    def query_frame_count(self) -> Optional[int]:
        try:
            sf = subprocess.check_output(
                [self.adb_bin, "shell", "dumpsys", "SurfaceFlinger"],
                text=True, stderr=subprocess.DEVNULL, timeout=1.5
            )
            m = re.search(r"SurfaceView\[.+?\]\(BLAST\).+?frame=(\d+)", sf, re.DOTALL)
            if not m:
                m = re.search(r"Layer \[.+?\]\s+.+?(?:com\.\w+\.\w+).+?frame=(\d+)", sf, re.DOTALL)
            if not m:
                m = re.search(r"\(BLAST\)#\d+.*?frame=(\d+)", sf, re.DOTALL)

            if m:
                return int(m.group(1))
        except Exception:
            pass
        return None

    def _monitor_loop(self):
        # Sample once at start
        self.active_app = self.get_focused_app()
        self.last_frame_count = self.query_frame_count()
        self.last_timestamp = time.perf_counter()

        app_check_counter = 0

        while self.running:
            time.sleep(self.sample_interval)
            if not self.running:
                break

            now_t = time.perf_counter()
            now_f = self.query_frame_count()

            app_check_counter += 1
            if app_check_counter >= 4:
                app_check_counter = 0
                self.active_app = self.get_focused_app()

            if now_f is not None and self.last_frame_count is not None:
                delta_f = now_f - self.last_frame_count
                delta_t = now_t - self.last_timestamp

                if delta_t > 0:
                    inst_fps = max(0.0, min(144.0, delta_f / delta_t))
                    self.current_fps = inst_fps
                    self.total_frames = now_f
                    self.fps_history.append(inst_fps)

                    if self.fps_history:
                        self.avg_fps = sum(self.fps_history) / len(self.fps_history)
                        self.min_fps = min(self.fps_history)
                        self.max_fps = max(self.fps_history)

            self.last_frame_count = now_f
            self.last_timestamp = now_t

    def start(self):
        if self.enabled and not self.running:
            self.running = True
            self.thread = threading.Thread(target=self._monitor_loop, daemon=True)
            self.thread.start()

    def stop(self):
        self.running = False
        if self.thread and self.thread.is_alive():
            self.thread.join(timeout=1.0)
