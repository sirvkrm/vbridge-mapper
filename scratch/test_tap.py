import sys
import os
import time

sys.path.append(os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
from client.connection import TouchConnection
from common.protocol import TouchAction

def main():
    print("[*] Testing Touch Mapper Connection...")
    conn = TouchConnection(host="127.0.0.1", port=8888, auto_adb_forward=False)
    if not conn.connect():
        print("[-] Connection failed!")
        return

    print("[+] Connected! Injecting a test tap at center (540, 1170)...")
    # DOWN on slot 2
    conn.send_event(TouchAction.DOWN, slot=2, x=540, y=1170)
    time.sleep(0.05)
    # UP on slot 2
    conn.send_event(TouchAction.UP, slot=2, x=0, y=0)
    time.sleep(0.1)

    conn.close()
    print("[+] Test tap complete!")

if __name__ == "__main__":
    main()
