import sys
import os
import time
import math
import ctypes
from ctypes import wintypes
from typing import Tuple, List, Optional
from collections import deque

sys.path.append(os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
from client.config import DisplayConfig
from client.connection import TouchConnection
from common.protocol import TouchAction

user32 = ctypes.windll.user32

class POINT(ctypes.Structure):
    _fields_ = [("x", wintypes.LONG), ("y", wintypes.LONG)]

class MouseCapture:
    """
    Robust Windows mouse delta capture.
    Allows full unrestricted mouse flick range without restrictive clipping boxes.
    """
    def __init__(self):
        self.screen_w = user32.GetSystemMetrics(0)
        self.screen_h = user32.GetSystemMetrics(1)
        self.center_x = self.screen_w // 2
        self.center_y = self.screen_h // 2
        self.locked = False

    def lock(self):
        user32.SetCursorPos(self.center_x, self.center_y)
        user32.ShowCursor(False)
        self.locked = True

    def unlock(self):
        user32.ShowCursor(True)
        self.locked = False

    def toggle(self) -> bool:
        if self.locked:
            self.unlock()
        else:
            self.lock()
        return self.locked

    def get_delta(self) -> Tuple[int, int]:
        if not self.locked:
            return 0, 0
        pt = POINT()
        user32.GetCursorPos(ctypes.byref(pt))
        dx = pt.x - self.center_x
        dy = pt.y - self.center_y
        if dx != 0 or dy != 0:
            user32.SetCursorPos(self.center_x, self.center_y)
        return dx, dy

class VirtualTouchAim:
    """
    Simulates a human thumb held down on the touchscreen for camera aiming.
    """
    def __init__(
        self,
        screen_w: int = 2340,
        screen_h: int = 1080,
        pivot_x: int = 1650,
        pivot_y: int = 540,
        box_w: int = 800,
        box_h: int = 650,
        sens: float = 1.3,
        conn: Optional[TouchConnection] = None
    ):
        self.screen_w = screen_w
        self.screen_h = screen_h
        self.pivot_x = float(pivot_x)
        self.pivot_y = float(pivot_y)
        self.min_x = self.pivot_x - (box_w / 2.0)
        self.max_x = self.pivot_x + (box_w / 2.0)
        self.min_y = self.pivot_y - (box_h / 2.0)
        self.max_y = self.pivot_y + (box_h / 2.0)
        self.sens = sens
        self.conn = conn

        self.display_cfg = DisplayConfig({
            "screen_width": screen_w,
            "screen_height": screen_h,
            "digitizer_max_x": 1080,
            "digitizer_max_y": 2340,
            "orientation": 90
        })

        self.curr_x = self.pivot_x
        self.curr_y = self.pivot_y
        self.state = "IDLE" # IDLE, TOUCH_DOWN, DRAGGING, RECENTERING
        self.trail: deque = deque(maxlen=12) # Trail of (x, y) for drawing
        self.reset_count = 0
        self.total_swipes = 0
        self.recenter_pending = False

    def start_aim(self):
        self.curr_x = self.pivot_x
        self.curr_y = self.pivot_y
        self.state = "TOUCH_DOWN"
        self.trail.clear()
        self.trail.append((self.curr_x, self.curr_y))
        if self.conn:
            dx, dy = self.display_cfg.to_digitizer_coords(self.curr_x, self.curr_y)
            self.conn.send_event(TouchAction.DOWN, slot=0, x=dx, y=dy, auto_sync=True)

    def stop_aim(self):
        self.state = "IDLE"
        self.trail.clear()
        if self.conn:
            self.conn.send_event(TouchAction.UP, slot=0, x=0, y=0, auto_sync=True)

    def update(self, delta_x: int, delta_y: int):
        if self.state == "IDLE":
            return

        # If we previously lifted (UP), now touch down at center cleanly
        if self.recenter_pending:
            self.curr_x = self.pivot_x
            self.curr_y = self.pivot_y
            self.state = "DRAGGING"
            self.recenter_pending = False
            self.trail.clear()
            self.trail.append((self.curr_x, self.curr_y))
            if self.conn:
                dx, dy = self.display_cfg.to_digitizer_coords(self.curr_x, self.curr_y)
                self.conn.send_event(TouchAction.DOWN, slot=0, x=dx, y=dy, auto_sync=True)
            return

        if delta_x == 0 and delta_y == 0:
            return

        # Apply trajectory movement
        self.curr_x += delta_x * self.sens
        self.curr_y += delta_y * self.sens
        self.state = "DRAGGING"
        self.trail.append((self.curr_x, self.curr_y))

        # Check boundary collision
        hit_boundary = (
            self.curr_x <= self.min_x or self.curr_x >= self.max_x or
            self.curr_y <= self.min_y or self.curr_y >= self.max_y
        )

        if hit_boundary:
            # Lift finger cleanly with a standalone SYN_REPORT
            self.reset_count += 1
            self.state = "RECENTERING"
            self.recenter_pending = True
            if self.conn:
                self.conn.send_event(TouchAction.UP, slot=0, x=0, y=0, auto_sync=True)
        else:
            # Normal smooth drag
            if self.conn:
                dx, dy = self.display_cfg.to_digitizer_coords(self.curr_x, self.curr_y)
                self.conn.send_event(TouchAction.MOVE, slot=0, x=dx, y=dy, auto_sync=True)

def render_ascii_screen(aim: VirtualTouchAim, mouse_dx: int, mouse_dy: int, sens: float) -> str:
    """
    Renders a 2D simulated game screen in terminal with camera aim zone.
    """
    GRID_W = 54
    GRID_H = 18

    # Create empty canvas
    canvas = [[" " for _ in range(GRID_W)] for _ in range(GRID_H)]

    # Draw border
    for x in range(GRID_W):
        canvas[0][x] = "─"
        canvas[GRID_H - 1][x] = "─"
    for y in range(GRID_H):
        canvas[y][0] = "│"
        canvas[y][GRID_W - 1] = "│"
    canvas[0][0] = "┌"
    canvas[0][GRID_W - 1] = "┐"
    canvas[GRID_H - 1][0] = "└"
    canvas[GRID_H - 1][GRID_W - 1] = "┘"

    # Screen mid dividing line (Left: Movement/HUD | Right: Camera Aim Area)
    div_x = GRID_W // 2
    for y in range(1, GRID_H - 1):
        canvas[y][div_x] = "┊"

    # Draw Camera Aim Zone boundary inside right half
    box_x1 = int((aim.min_x / aim.screen_w) * GRID_W)
    box_x2 = int((aim.max_x / aim.screen_w) * GRID_W)
    box_y1 = int((aim.min_y / aim.screen_h) * GRID_H)
    box_y2 = int((aim.max_y / aim.screen_h) * GRID_H)

    for x in range(box_x1, box_x2 + 1):
        if 0 < box_y1 < GRID_H - 1 and 0 < x < GRID_W - 1: canvas[box_y1][x] = "."
        if 0 < box_y2 < GRID_H - 1 and 0 < x < GRID_W - 1: canvas[box_y2][x] = "."
    for y in range(box_y1, box_y2 + 1):
        if 0 < y < GRID_H - 1 and 0 < box_x1 < GRID_W - 1: canvas[y][box_x1] = ":"
        if 0 < y < GRID_H - 1 and 0 < box_x2 < GRID_W - 1: canvas[y][box_x2] = ":"

    # Center Pivot mark
    piv_gx = int((aim.pivot_x / aim.screen_w) * GRID_W)
    piv_gy = int((aim.pivot_y / aim.screen_h) * GRID_H)
    if 0 < piv_gy < GRID_H - 1 and 0 < piv_gx < GRID_W - 1:
        canvas[piv_gy][piv_gx] = "+"

    # Trail dots
    for tx, ty in aim.trail:
        gx = int((tx / aim.screen_w) * GRID_W)
        gy = int((ty / aim.screen_h) * GRID_H)
        if 0 < gy < GRID_H - 1 and 0 < gx < GRID_W - 1:
            canvas[gy][gx] = "·"

    # Finger position
    fg_x = int((aim.curr_x / aim.screen_w) * GRID_W)
    fg_y = int((aim.curr_y / aim.screen_h) * GRID_H)
    fg_x = max(1, min(GRID_W - 2, fg_x))
    fg_y = max(1, min(GRID_H - 2, fg_y))

    if aim.state == "DRAGGING":
        canvas[fg_y][fg_x] = "●"
    elif aim.state == "RECENTERING":
        canvas[fg_y][fg_x] = "↺"
    elif aim.state == "TOUCH_DOWN":
        canvas[fg_y][fg_x] = "◉"
    else:
        canvas[fg_y][fg_x] = "○"

    lines = ["".join(row) for row in canvas]

    # Labels inside canvas
    lines[1] = lines[1][:3] + " [JOYSTICK / HUD] " + lines[1][20:]
    lines[1] = lines[1][:32] + " [CAMERA AIM ZONE] " + lines[1][51:]

    return "\n".join(lines)

def main():
    print("\n" + "=" * 62)
    print(" 🎯 MOUSE TRAJECTORY & TOUCH AIM SIMULATOR")
    print("=" * 62)
    print("Connecting to device touch daemon (if available)...")

    conn = TouchConnection(auto_adb_forward=False)
    has_conn = conn.connect(retries=2, retry_delay=0.3)
    if has_conn:
        print("[+] CONNECTED! Touch events will live-stream to physical digitizer!")
    else:
        print("[-] Device daemon not connected. Running in LOCAL SIMULATION mode.")

    aim = VirtualTouchAim(
        screen_w=2340,
        screen_h=1080,
        pivot_x=1650,
        pivot_y=540,
        box_w=850,
        box_h=650,
        sens=1.3,
        conn=conn if has_conn else None
    )

    mouse = MouseCapture()
    print("\nControls:")
    print("  [ ` ] (Tilde) or [SPACE]: Lock / Unlock Mouse Aim")
    print("  [ + ] / [ - ]: Increase / Decrease Sensitivity")
    print("  [ Q ] / [Ctrl+C]: Exit")
    print("\nPress Enter to begin...")
    try:
        input()
    except EOFError:
        pass

    # Start loop
    aim_active = False
    last_render = time.perf_counter()
    render_interval = 1.0 / 30.0 # 30 FPS terminal render

    # Windows Virtual Key Codes
    VK_TILDE = 0xC0
    VK_SPACE = 0x20
    VK_PLUS = 0xBB
    VK_MINUS = 0xBD
    VK_Q = 0x51

    tilde_was_down = False
    space_was_down = False

    def is_down(vk):
        return (user32.GetAsyncKeyState(vk) & 0x8000) != 0

    try:
        while True:
            t0 = time.perf_counter()

            # 1. Check toggle keys
            t_down = is_down(VK_TILDE)
            sp_down = is_down(VK_SPACE)

            if (t_down and not tilde_was_down) or (sp_down and not space_was_down):
                aim_active = not aim_active
                if aim_active:
                    mouse.lock()
                    aim.start_aim()
                else:
                    mouse.unlock()
                    aim.stop_aim()

            tilde_was_down = t_down
            space_was_down = sp_down

            if is_down(VK_Q):
                break

            # 2. Get mouse delta
            dx, dy = mouse.get_delta()

            # 3. Update touch aim
            aim.update(dx, dy)

            # 4. Render terminal screen at 30 FPS
            now = time.perf_counter()
            if now - last_render >= render_interval:
                screen_str = render_ascii_screen(aim, dx, dy, aim.sens)
                digit_x, digit_y = aim.display_cfg.to_digitizer_coords(aim.curr_x, aim.curr_y)

                state_color = "\033[92m" if aim_active else "\033[93m"
                reset_color = "\033[0m"

                # Clear and reprint screen
                output = (
                    "\033[H\033[J" # Clear screen
                    f"==============================================================\n"
                    f" 🎮 [AIM MODE: {state_color}{'LOCKED (Endless Aim)' if aim_active else 'UNLOCKED (Desktop)'}{reset_color}]  "
                    f"Sens: {aim.sens:.2f}  [Toggle: ` or SPACE]\n"
                    f"==============================================================\n"
                    f"{screen_str}\n"
                    f" Live Trajectory Coordinates:\n"
                    f"   Mouse Delta:       dX = {dx:+4d}   dY = {dy:+4d}\n"
                    f"   In-Game Screen:    X  = {aim.curr_x:6.1f}   Y  = {aim.curr_y:6.1f} px\n"
                    f"   Physical Digitizer: X  = {digit_x:4d}     Y  = {digit_y:4d} (event3)\n"
                    f"   Aim Touch State:   {aim.state:<12} (Recenters: {aim.reset_count})\n"
                    f"   Device Connection: {'🟢 Active /dev/input/event3' if has_conn else '⚪ Local Simulation'}\n"
                    f"==============================================================\n"
                    f" Move your mouse! Press SPACE/Tilde to release cursor, Q to exit."
                )
                sys.stdout.write(output)
                sys.stdout.flush()
                last_render = now

            time.sleep(0.003) # 333 Hz loop

    except KeyboardInterrupt:
        pass
    finally:
        mouse.unlock()
        aim.stop_aim()
        if has_conn:
            conn.close()
        print("\n\n[+] Simulator closed cleanly.")

if __name__ == "__main__":
    main()
