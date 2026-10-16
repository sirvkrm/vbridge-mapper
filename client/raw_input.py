import ctypes
from ctypes import wintypes
import time
from typing import Tuple, Dict, Callable

# Win32 Structures & API
user32 = ctypes.windll.user32

# Enable per-monitor DPI awareness to prevent Windows 125%/150% scaling drift
try:
    ctypes.windll.shcore.SetProcessDpiAwareness(2) # PROCESS_PER_MONITOR_DPI_AWARE
except Exception:
    try:
        user32.SetProcessDPIAware()
    except Exception:
        pass

class POINT(ctypes.Structure):
    _fields_ = [("x", wintypes.LONG), ("y", wintypes.LONG)]

class RECT(ctypes.Structure):
    _fields_ = [
        ("left", wintypes.LONG),
        ("top", wintypes.LONG),
        ("right", wintypes.LONG),
        ("bottom", wintypes.LONG)
    ]

user32.GetCursorPos.argtypes = [ctypes.POINTER(POINT)]
user32.GetCursorPos.restype = wintypes.BOOL
user32.SetCursorPos.argtypes = [wintypes.INT, wintypes.INT]
user32.SetCursorPos.restype = wintypes.BOOL

# Common Virtual Key Codes
VK_MAP: Dict[str, int] = {
    "mouse_left": 0x01,   # VK_LBUTTON
    "mouse_right": 0x02,  # VK_RBUTTON
    "mouse_middle": 0x04, # VK_MBUTTON
    "backspace": 0x08,
    "tab": 0x09,
    "enter": 0x0D,
    "shift": 0x10,
    "ctrl": 0x11,
    "alt": 0x12,
    "capslock": 0x14,
    "esc": 0x1B,
    "space": 0x20,
    "pageup": 0x21,
    "pagedown": 0x22,
    "end": 0x23,
    "home": 0x24,
    "left": 0x25,
    "up": 0x26,
    "right": 0x27,
    "down": 0x28,
    "`": 0xC0,            # VK_OEM_3 (Tilde / Backquote)
    "tilde": 0xC0,
    "f1": 0x70, "f2": 0x71, "f3": 0x72, "f4": 0x73,
    "f5": 0x74, "f6": 0x75, "f7": 0x76, "f8": 0x77
}

# Add standard alphanumeric keys (0-9, A-Z)
for i in range(10):
    VK_MAP[str(i)] = 0x30 + i
for c in range(ord('a'), ord('z') + 1):
    char = chr(c)
    VK_MAP[char] = ord(char.upper())

def get_vk(key_name: str) -> int:
    return VK_MAP.get(key_name.lower().strip(), 0)

def is_key_down(vk: int) -> bool:
    if vk <= 0:
        return False
    # Most significant bit indicates key is currently pressed
    return (user32.GetAsyncKeyState(vk) & 0x8000) != 0

class MouseLockEngine:
    """
    Locks the Windows mouse cursor to the center of the primary display,
    computes relative movement deltas (dx, dy), and prevents cursor escape.
    Fully high-DPI aware to prevent phantom drift.
    """
    def __init__(self):
        self.screen_width = user32.GetSystemMetrics(0)
        self.screen_height = user32.GetSystemMetrics(1)
        self.center_x = self.screen_width // 2
        self.center_y = self.screen_height // 2
        self.locked = False

    def set_locked(self, locked: bool):
        self.locked = locked
        if locked:
            # Refresh coordinates upon locking in case of display changes
            self.screen_width = user32.GetSystemMetrics(0)
            self.screen_height = user32.GetSystemMetrics(1)
            self.center_x = self.screen_width // 2
            self.center_y = self.screen_height // 2
            user32.SetCursorPos(self.center_x, self.center_y)
            user32.ShowCursor(False)
        else:
            user32.ShowCursor(True)

    def toggle(self) -> bool:
        self.set_locked(not self.locked)
        return self.locked

    def poll_delta(self) -> Tuple[int, int]:
        """
        Calculates cursor deviation from center and resets position.
        Returns: (dx, dy)
        """
        if not self.locked:
            return 0, 0

        pt = POINT()
        if not user32.GetCursorPos(ctypes.byref(pt)):
            return 0, 0

        dx = pt.x - self.center_x
        dy = pt.y - self.center_y

        if dx != 0 or dy != 0:
            user32.SetCursorPos(self.center_x, self.center_y)

        return dx, dy

    def cleanup(self):
        self.set_locked(False)
