import socket
import time
import subprocess
import os
import sys

sys.path.append(os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
from common.protocol import pack_event, pack_sync, TouchAction

def main():
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.connect(("127.0.0.1", 8888))

    adb_bin = r"C:\Users\asus\AppData\Local\Android\Sdk\platform-tools\adb.exe"

    test_points = [
        ("Center (540, 1170)", 540, 1170),
        ("Corner A (100, 100)", 100, 100),
        ("Corner B (900, 100)", 900, 100),
        ("Corner C (100, 2000)", 100, 2000),
        ("Corner D (900, 2000)", 900, 2000),
    ]

    for label, dx, dy in test_points:
        print(f"\n=======================================================")
        print(f"Testing raw digitizer ({dx}, {dy}) [{label}]")
        print(f"=======================================================")

        # Send DOWN on slot 2 with auto_sync
        sock.sendall(pack_event(TouchAction.DOWN, 2, dx, dy, auto_sync=True))
        time.sleep(0.4)

        res = subprocess.run([adb_bin, "-s", "295cc14", "shell", "dumpsys input | grep -A 10 'Last Cooked Touch'"],
                             capture_output=True, text=True)
        print("DUMPSYS INPUT COOKED TOUCH:")
        print(res.stdout.strip())

        # Send UP
        sock.sendall(pack_event(TouchAction.UP, 2, 0, 0, auto_sync=True))
        time.sleep(0.3)

    sock.close()

if __name__ == "__main__":
    main()
