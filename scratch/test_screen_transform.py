import socket
import time
import subprocess
import os
import sys

sys.path.append(os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
from common.protocol import pack_event, TouchAction

adb_bin = r"C:\Users\asus\AppData\Local\Android\Sdk\platform-tools\adb.exe"

def test_screen_coords(sx, sy):
    # New transform:
    # dx = 1080 - sy
    # dy = sx
    dx = int(round(1080 - sy))
    dy = int(round(sx))

    subprocess.run([adb_bin, "-s", "295cc14", "forward", "tcp:8888", "tcp:8888"], capture_output=True)
    s = socket.socket()
    s.connect(("127.0.0.1", 8888))

    # Send DOWN
    s.sendall(pack_event(TouchAction.DOWN, 2, dx, dy, auto_sync=True))
    time.sleep(0.2)

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
            if len(result) > 2:
                break

    # Send UP
    s.sendall(pack_event(TouchAction.UP, 2, 0, 0, auto_sync=True))
    s.close()
    return f"Target ({sx}, {sy}) -> Injected ({dx}, {dy}) -> " + "\n".join(result)

points = [
    (1170, 540), # Center
    (1913, 790), # Fire button
    (1681, 760), # Aim Pivot
    (100, 100),   # Top Left
    (2200, 950)  # Bottom Right
]

for sx, sy in points:
    print(test_screen_coords(sx, sy))
    time.sleep(0.4)
