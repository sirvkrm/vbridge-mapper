import json
import os
from typing import Dict, Any, Tuple, List

class DisplayConfig:
    def __init__(self, data: Dict[str, Any]):
        self.screen_width: int = data.get("screen_width", 2340)
        self.screen_height: int = data.get("screen_height", 1080)
        self.digitizer_max_x: int = data.get("digitizer_max_x", 1080)
        self.digitizer_max_y: int = data.get("digitizer_max_y", 2340)
        self.orientation: int = data.get("orientation", 90)

    def to_digitizer_coords(self, screen_x: float, screen_y: float) -> Tuple[int, int]:
        """
        Converts in-game landscape screen coordinates to physical digitizer panel coordinates.
        Supports 0, 90, and 270 degree orientations.
        Verified against Android WindowManager input transform matrix:
          x_win = y_raw
          y_win = screen_height - x_raw
        """
        # Clamp to screen boundaries
        sx = max(0.0, min(float(self.screen_width), screen_x))
        sy = max(0.0, min(float(self.screen_height), screen_y))

        if self.orientation == 90:
            # Device in standard landscape (ROTATION_90, ear speaker on left):
            # WindowManager: x_win = y_raw, y_win = screen_height - x_raw
            # Inverting to raw digitizer coords:
            #   dig_x = ((self.screen_height - sy) / self.screen_height) * self.digitizer_max_x
            #   dig_y = (sx / self.screen_width) * self.digitizer_max_y
            dig_x = ((self.screen_height - sy) / self.screen_height) * self.digitizer_max_x
            dig_y = (sx / self.screen_width) * self.digitizer_max_y
        elif self.orientation == 270:
            # Reverse landscape (ROTATION_270, ear speaker on right)
            dig_x = (sy / self.screen_height) * self.digitizer_max_x
            dig_y = ((self.screen_width - sx) / self.screen_width) * self.digitizer_max_y
        else:
            # Native / Portrait (0 degrees)
            dig_x = (sx / self.screen_width) * self.digitizer_max_x
            dig_y = (sy / self.screen_height) * self.digitizer_max_y

        # Clamp strictly within physical digitizer limits (0..1079, 0..2339)
        max_x = max(1, self.digitizer_max_x - 1)
        max_y = max(1, self.digitizer_max_y - 1)
        clamped_x = max(0, min(max_x, int(round(dig_x))))
        clamped_y = max(0, min(max_y, int(round(dig_y))))

        return clamped_x, clamped_y

class Profile:
    def __init__(self, source: Any = None):
        if isinstance(source, str):
            with open(source, 'r', encoding='utf-8') as f:
                data = json.load(f)
            self.filepath: str = source
        elif isinstance(source, dict):
            data = source
            self.filepath: str = "dynamic"
        else:
            data = {}
            self.filepath: str = "empty"

        self.app_name: str = data.get("app_name", "Waiting for Phone App...")
        self.package_name: str = data.get("package_name", "")
        self.display = DisplayConfig(data.get("display", {}))
        self.camera: Dict[str, Any] = data.get("camera", {})
        self.joystick: Dict[str, Any] = data.get("joystick", {})
        self.key_mappings: List[Dict[str, Any]] = data.get("key_mappings", [])

    @classmethod
    def from_dict(cls, data: Dict[str, Any], filepath: str = "dynamic") -> "Profile":
        p = cls.__new__(cls)
        p.filepath = filepath
        p.app_name = data.get("app_name", "Custom Preset")
        p.package_name = data.get("package_name", "")
        p.display = DisplayConfig(data.get("display", {}))
        p.camera = data.get("camera", {})
        p.joystick = data.get("joystick", {})
        p.key_mappings = data.get("key_mappings", [])
        return p

    def save(self):
        data = {
            "app_name": self.app_name,
            "package_name": self.package_name,
            "display": {
                "screen_width": self.display.screen_width,
                "screen_height": self.display.screen_height,
                "digitizer_max_x": self.display.digitizer_max_x,
                "digitizer_max_y": self.display.digitizer_max_y,
                "orientation": self.display.orientation
            },
            "camera": self.camera,
            "joystick": self.joystick,
            "key_mappings": self.key_mappings
        }
        with open(self.filepath, 'w', encoding='utf-8') as f:
            json.dump(data, f, indent=2)

def list_profiles(profiles_dir: str) -> List[str]:
    if not os.path.exists(profiles_dir):
        return []
    return [os.path.join(profiles_dir, f) for f in os.listdir(profiles_dir) if f.endswith(".json")]
