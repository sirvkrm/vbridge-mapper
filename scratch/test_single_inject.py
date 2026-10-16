import socket
import time
import subprocess
import os
import sys

sys.path.append(os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
from common.protocol import pack_event, TouchAction

adb_bin = r"C:\Users\asus\AppData\Local\Android\Sdk\platform-tools\adb.exe"

def inject_and_query(dx, dy):
    subprocess.run([adb_bin, "-s", "295cc14", "forward", "tcp:8888", "tcp:8888"], capture_output=True)
    s = socket.socket()
    s.connect(("127.0.0.1", 8888))
    # Send DOWN
    s.sendall(pack_event(TouchAction.DOWN, 2, dx, dy, auto_sync=True))
    time.sleep(0.2)
    # Query dumpsys input
    cmd = [adb_bin, "-s", "295cc14", "shell", "dumpsys input"]
    p = subprocess.run(cmd, capture_output=True, text=True)
    # Find Last Cooked Touch
    lines = p.stdout.splitlines()
    found = False
    result = []
    for line in lines:
        if "Last Cooked Touch:" in line:
            found = True
        if found:
            result.append(line)
            if len(result) > 6:
                break
    # Send UP
    s.sendall(pack_event(TouchAction.UP, 2, 0, 0, auto_sync=True))
    s.close()
    return "\n".join(result)

for pt in [(100, 200), (500, 1000), (900, 2100)]:
    print(f"=== Injecting raw dx={pt[0]}, dy={pt[1]} ===")
    print(inject_and_query(pt[0], pt[1]))
    time.sleep(0.5)
