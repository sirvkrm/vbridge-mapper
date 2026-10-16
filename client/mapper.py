import math
import time
from typing import Dict, Set, Optional, Tuple
from common.protocol import TouchAction, pack_event, pack_sync
from client.config import Profile
from client.connection import TouchConnection
from client.raw_input import MouseLockEngine, is_key_down, get_vk

class SlotManager:
    """
    Manages multi-touch slots (0 to 9) to prevent collisions.
    Slot 0: Reserved for Camera / Aim Look (360 Pivot)
    Slot 1: Reserved for WASD Movement Joystick
    Slot 2..9: Dynamically allocated for hotkeys and mouse buttons
    """
    CAMERA_SLOT = 0
    JOYSTICK_SLOT = 1
    HOTKEY_START_SLOT = 2
    MAX_SLOTS = 10

    def __init__(self):
        self.active_slots: Dict[str, int] = {} # key_name -> slot_id
        self.free_slots: Set[int] = set(range(self.HOTKEY_START_SLOT, self.MAX_SLOTS))

    def acquire(self, identifier: str) -> Optional[int]:
        if identifier in self.active_slots:
            return self.active_slots[identifier]
        if not self.free_slots:
            return None # Out of multi-touch slots
        slot = min(self.free_slots)
        self.free_slots.remove(slot)
        self.active_slots[identifier] = slot
        return slot

    def release(self, identifier: str) -> Optional[int]:
        if identifier in self.active_slots:
            slot = self.active_slots.pop(identifier)
            self.free_slots.add(slot)
            return slot
        return None

class CameraPivotEngine:
    """
    360-Degree Endless Mouse Look Engine:
    Maintains a virtual finger at the on-screen pivot point.
    When mouse deltas occur, moves the virtual touch coordinate.
    When reaching the bounding radius, instantly lifts (UP), snaps back to center,
    and touches down (DOWN) inside a single tick to allow infinite panning.
    """
    def __init__(self, profile: Profile, conn: TouchConnection):
        self.profile = profile
        self.conn = conn
        self.camera_cfg = profile.camera
        self.enabled = True # Always active in Aim Mode
        self.pivot_center = tuple(self.camera_cfg.get("pivot_center", [1600, 540]))
        self.reset_radius = float(self.camera_cfg.get("reset_radius", 250))
        self.sens_x = float(self.camera_cfg.get("sensitivity_x", 1.0))
        self.sens_y = float(self.camera_cfg.get("sensitivity_y", 1.0))

        self.curr_x = float(self.pivot_center[0])
        self.curr_y = float(self.pivot_center[1])
        self.is_touching = False
        self.recenter_pending = False

    def start(self):
        if not self.is_touching:
            self.curr_x = float(self.pivot_center[0])
            self.curr_y = float(self.pivot_center[1])
            self.recenter_pending = False
            dx, dy = self.profile.display.to_digitizer_coords(self.curr_x, self.curr_y)
            self.conn.send_event(TouchAction.DOWN, SlotManager.CAMERA_SLOT, dx, dy, auto_sync=True)
            self.is_touching = True

    def stop(self):
        if self.is_touching:
            self.conn.send_event(TouchAction.UP, SlotManager.CAMERA_SLOT, 0, 0, auto_sync=True)
            self.is_touching = False
            self.recenter_pending = False

    def update_pivot(self, pivot_x: int, pivot_y: int, radius: int = 260):
        self.pivot_center = (float(pivot_x), float(pivot_y))
        self.reset_radius = float(radius)
        if self.is_touching:
            self.conn.send_event(TouchAction.UP, SlotManager.CAMERA_SLOT, 0, 0, auto_sync=True)
            self.curr_x = float(pivot_x)
            self.curr_y = float(pivot_y)
            dx, dy = self.profile.display.to_digitizer_coords(self.curr_x, self.curr_y)
            self.conn.send_event(TouchAction.DOWN, SlotManager.CAMERA_SLOT, dx, dy, auto_sync=True)
            self.recenter_pending = False
        else:
            self.curr_x = float(pivot_x)
            self.curr_y = float(pivot_y)

    def update(self, delta_x: int, delta_y: int):
        if not self.is_touching:
            return

        # If previous frame reached boundary and lifted, now touch down cleanly at pivot center
        if self.recenter_pending:
            self.curr_x = float(self.pivot_center[0])
            self.curr_y = float(self.pivot_center[1])
            dx, dy = self.profile.display.to_digitizer_coords(self.curr_x, self.curr_y)
            self.conn.send_event(TouchAction.DOWN, SlotManager.CAMERA_SLOT, dx, dy, auto_sync=True)
            self.recenter_pending = False
            if delta_x == 0 and delta_y == 0:
                return

        if delta_x == 0 and delta_y == 0:
            return

        # Apply trajectory sensitivity:
        # Moving mouse RIGHT (delta_x > 0) -> drag touch RIGHT (+X) to rotate view right.
        # Moving mouse UP (delta_y < 0 in Windows) -> drag touch UP (-Y) to look up.
        self.curr_x += delta_x * self.sens_x
        self.curr_y += delta_y * self.sens_y

        dist = math.hypot(self.curr_x - self.pivot_center[0], self.curr_y - self.pivot_center[1])
        effective_radius = max(300.0, self.reset_radius)

        if dist >= effective_radius:
            # Reached boundary: cleanly lift (UP with SYN_REPORT)
            # Standalone UP ensures Linux evdev finishes previous stroke
            self.conn.send_event(TouchAction.UP, SlotManager.CAMERA_SLOT, 0, 0, auto_sync=True)
            self.recenter_pending = True
        else:
            # Smooth continuous drag
            dx, dy = self.profile.display.to_digitizer_coords(self.curr_x, self.curr_y)
            self.conn.send_event(TouchAction.MOVE, SlotManager.CAMERA_SLOT, dx, dy, auto_sync=True)

class JoystickEngine:
    """
    Translates WASD keyboard states into virtual analog joystick touch movements.
    """
    def __init__(self, profile: Profile, conn: TouchConnection):
        self.profile = profile
        self.conn = conn
        self.cfg = profile.joystick
        self.enabled = self.cfg.get("enabled", True)
        self.center = tuple(self.cfg.get("center", [400, 800]))
        self.radius = float(self.cfg.get("radius", 120))
        self.key_defs = self.cfg.get("keys", {"w": "UP", "s": "DOWN", "a": "LEFT", "d": "RIGHT"})
        self.sprint_key = self.cfg.get("sprint_key", "shift")

        self.vk_w = get_vk("w")
        self.vk_s = get_vk("s")
        self.vk_a = get_vk("a")
        self.vk_d = get_vk("d")
        self.vk_sprint = get_vk(self.sprint_key)

        self.is_active = False

    def update(self):
        if not self.enabled:
            return

        w = is_key_down(self.vk_w)
        s = is_key_down(self.vk_s)
        a = is_key_down(self.vk_a)
        d = is_key_down(self.vk_d)
        sprint = is_key_down(self.vk_sprint)

        vx = 0.0
        vy = 0.0

        if w: vy -= 1.0
        if s: vy += 1.0
        if a: vx -= 1.0
        if d: vx += 1.0

        if vx == 0.0 and vy == 0.0:
            if self.is_active:
                self.conn.send_event(TouchAction.UP, SlotManager.JOYSTICK_SLOT, 0, 0)
                self.is_active = False
            return

        # Normalize direction
        length = math.hypot(vx, vy)
        nx = vx / length
        ny = vy / length

        # Boost radius on sprint for games with sprint zone
        r = self.radius * (1.3 if sprint else 1.0)
        target_x = self.center[0] + nx * r
        target_y = self.center[1] + ny * r

        dx, dy = self.profile.display.to_digitizer_coords(target_x, target_y)

        if not self.is_active:
            # Send initial center touch, then drag out for proper joystick detection
            cx, cy = self.profile.display.to_digitizer_coords(self.center[0], self.center[1])
            self.conn.send_event(TouchAction.DOWN, SlotManager.JOYSTICK_SLOT, cx, cy)
            self.conn.send_event(TouchAction.MOVE, SlotManager.JOYSTICK_SLOT, dx, dy)
            self.is_active = True
        else:
            self.conn.send_event(TouchAction.MOVE, SlotManager.JOYSTICK_SLOT, dx, dy)

    def update_center(self, center_x: int, center_y: int, radius: float = 140.0):
        self.center = (float(center_x), float(center_y))
        self.radius = float(radius)
        if self.is_active:
            self.conn.send_event(TouchAction.UP, SlotManager.JOYSTICK_SLOT, 0, 0)
            self.is_active = False

class InputMapper:
    """
    Main orchestration loop mapping raw inputs to touch injector events.
    Supports starting in standby mode waiting for the Android app to upload controls.
    """
    def __init__(self, profile: Optional[Profile], conn: TouchConnection):
        self.profile = profile
        self.conn = conn
        self.slot_mgr = SlotManager()
        self.mouse_lock = MouseLockEngine()
        self.aim_mode = False
        self.toggle_was_down = False
        self.hotkeys = []
        self.camera: Optional[CameraPivotEngine] = None
        self.joystick: Optional[JoystickEngine] = None
        self.toggle_vk = 0

        if profile is not None:
            self._apply_profile(profile)

    def _apply_profile(self, profile: Profile):
        self.profile = profile
        self.camera = CameraPivotEngine(profile, self.conn)
        self.joystick = JoystickEngine(profile, self.conn)
        self.hotkeys = []
        for mapping in profile.key_mappings:
            key_name = mapping.get("key", "").lower()
            vk = get_vk(key_name)
            if vk > 0:
                self.hotkeys.append({
                    "name": key_name,
                    "label": mapping.get("label", key_name.upper()),
                    "vk": vk,
                    "x": mapping.get("x", 0),
                    "y": mapping.get("y", 0),
                    "pressed": False
                })
        toggle_key = profile.camera.get("toggle_key", "`")
        self.toggle_vk_name = toggle_key
        self.toggle_vk = get_vk(toggle_key)

    def update_profile(self, new_profile: Profile):
        was_aiming = self.aim_mode
        if self.camera and was_aiming:
            self.camera.stop()
        self._apply_profile(new_profile)
        if was_aiming and self.camera:
            self.aim_mode = True
            self.mouse_lock.set_locked(True)
            self.camera.start()
            print("\n[+] Aim Mode: Re-anchored to new profile pivot seamlessly.")

    def update_camera_pivot(self, pivot_x: int, pivot_y: int, radius: int = 260):
        if self.camera:
            self.camera.update_pivot(pivot_x, pivot_y, radius)

    def update_joystick(self, center_x: int, center_y: int, radius: int = 140):
        if self.joystick:
            self.joystick.update_center(center_x, center_y, radius)

    def update_key_coord(self, key_name: str, x: int, y: int) -> bool:
        key_name = key_name.lower()
        for item in self.hotkeys:
            if item["name"] == key_name:
                item["x"] = x
                item["y"] = y
                return True
        vk = get_vk(key_name)
        if vk > 0:
            self.hotkeys.append({
                "name": key_name,
                "label": key_name.upper(),
                "vk": vk,
                "x": x,
                "y": y,
                "pressed": False
            })
            return True
        return False

    def toggle_aim_mode(self):
        if not self.profile or not self.camera:
            return
        self.aim_mode = not self.aim_mode
        self.mouse_lock.set_locked(self.aim_mode)
        if self.aim_mode:
            print(f"\n🎯 [AIM MODE: LOCKED & ACTIVE] Moving mouse rotates camera at ({self.camera.pivot_center[0]}, {self.camera.pivot_center[1]}). Press '{self.toggle_vk_name}' or F1 to unlock cursor.")
            self.camera.start()
        else:
            print("\n🔓 [AIM MODE: UNLOCKED] Cursor released on Windows desktop.")
            self.camera.stop()

    def tick(self):
        if not self.profile:
            return

        # 1. Check toggle aim mode key (configured key, standard tilde 0xC0, UK tilde 0xDF, or F1 0x70)
        toggle_down = (self.toggle_vk > 0 and is_key_down(self.toggle_vk)) or is_key_down(0x70) or is_key_down(0xC0) or is_key_down(0xDF)
        if toggle_down and not self.toggle_was_down:
            self.toggle_aim_mode()
        self.toggle_was_down = toggle_down

        # 2. Camera look update
        if self.aim_mode and self.camera:
            dx, dy = self.mouse_lock.poll_delta()
            self.camera.update(dx, dy)

        # 3. WASD Movement update
        if self.joystick:
            self.joystick.update()

        # 4. Hotkey clicks
        for item in self.hotkeys:
            is_down = is_key_down(item["vk"])
            if is_down and not item["pressed"]:
                # Key Down
                slot = self.slot_mgr.acquire(item["name"])
                if slot is not None:
                    dx, dy = self.profile.display.to_digitizer_coords(item["x"], item["y"])
                    self.conn.send_event(TouchAction.DOWN, slot, dx, dy)
                    item["pressed"] = True
            elif not is_down and item["pressed"]:
                # Key Up
                slot = self.slot_mgr.release(item["name"])
                if slot is not None:
                    self.conn.send_event(TouchAction.UP, slot, 0, 0)
                    item["pressed"] = False

    def cleanup(self):
        self.mouse_lock.cleanup()
        if self.camera:
            self.camera.stop()
        self.conn.reset_all()
