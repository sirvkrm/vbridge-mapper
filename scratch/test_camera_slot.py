import socket
import time
import subprocess
import os
import sys

sys.path.append(os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
from common.protocol import pack_event, TouchAction

adb_bin = r"C:\Users\asus\AppData\Local\Android\Sdk\platform-tools\adb.exe"

def test_camera_drag():
    subprocess.run([adb_bin, "-s", "295cc14", "forward", "tcp:8888", "tcp:8888"], capture_output=True)
    s = socket.socket()
    s.connect(("127.0.0.1", 8888))

    # Center (1650, 540) in landscape -> raw (540, 1650)
    center_dx = 1080 - 540
    center_dy = 1650

    print(f"[*] Starting Slot 0 at raw ({center_dx}, {center_dy}) [Screen: (1650, 540)]")
    s.sendall(pack_event(TouchAction.DOWN, 0, center_dx, center_dy, auto_sync=True))
    time.sleep(0.05)

    # Drag left in screen space (1600, 540) -> raw (540, 1600)
    for offset in range(10, 200, 20):
        move_sx = 1650 - offset
        move_sy = 540
        move_dx = 1080 - move_sy
        move_dy = move_sx
        s.sendall(pack_event(TouchAction.MOVE, 0, move_dx, move_dy, auto_sync=True))
        time.sleep(0.02)

    cmd = [adb_bin, "-s", "295cc14", "shell", "dumpsys input"]
    p = subprocess.run(cmd, capture_output=True, text=True)

    lines = p.stdout.splitlines()
    found = False
    result = []
    for line in lines:
        if "Last Cooked Touch:" in line:
            found = True
        if found:
            result.append(line)
            if len(result) > 5:
                break

    # Lift
    s.sendall(pack_event(TouchAction.UP, 0, 0, 0, auto_sync=True))
    s.close()
    return "\n".join(result)

print(test_camera_drag())
