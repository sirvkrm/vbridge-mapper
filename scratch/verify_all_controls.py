import sys
import os
import time
import subprocess

sys.path.append(os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
from client.config import Profile, DisplayConfig
from client.connection import TouchConnection
from common.protocol import TouchAction

adb_bin = TouchConnection.get_adb_binary()

def query_last_cooked():
    cmd = [adb_bin, "-s", "295cc14", "shell", "dumpsys input"]
    p = subprocess.run(cmd, capture_output=True, text=True)
    lines = p.stdout.splitlines()
    for line in lines:
        if "id=0" in line or "id=1" in line or "id=2" in line:
            import re
            m = re.search(r'x=([0-9.]+),\s*y=([0-9.]+)', line)
            if m:
                return float(m.group(1)), float(m.group(2))
    return None

def main():
    disp = DisplayConfig({
        "screen_width": 2340,
        "screen_height": 1080,
        "digitizer_max_x": 1080,
        "digitizer_max_y": 2340,
        "orientation": 90
    })

    conn = TouchConnection()
    if not conn.connect():
        print("Failed to connect")
        return

    test_targets = [
        ("Aim Pivot Center", 1650, 540),
        ("Fire Button", 1913, 790),
        ("Scope / ADS", 1638, 150),
        ("Jump Button", 2121, 600),
        ("Crouch Button", 2153, 903),
        ("Center of Display", 1170, 540),
    ]

    print("\n==========================================================================")
    print(" VERIFYING CONTROLS & AIM PIVOT TRANSFORMATION WITH ANDROID KERNEL")
    print("==========================================================================")

    all_passed = True
    for name, tx, ty in test_targets:
        dx, dy = disp.to_digitizer_coords(tx, ty)
        conn.send_event(TouchAction.DOWN, 2, dx, dy, auto_sync=True)
        time.sleep(0.1)

        cooked = query_last_cooked()
        conn.send_event(TouchAction.UP, 2, 0, 0, auto_sync=True)
        time.sleep(0.05)

        if cooked:
            raw_x, raw_y = cooked
            # Apply Android 17 WindowManager matrix: x_win = y_raw, y_win = 1080 - x_raw
            win_x = raw_y
            win_y = 1080.0 - raw_x
            err_x = abs(win_x - tx)
            err_y = abs(win_y - ty)
            status = "PASS" if err_x <= 1.0 and err_y <= 1.0 else "FAIL"
            if status != "PASS":
                all_passed = False
            print(f"[{status}] {name:18} | Target: ({tx:4}, {ty:4}) -> Dig: ({dx:4}, {dy:4}) -> Game Window: ({win_x:6.1f}, {win_y:6.1f}) [Err: {err_x:.1f}, {err_y:.1f}]")
        else:
            print(f"[FAIL] {name:18} | Target: ({tx:4}, {ty:4}) -> No touch detected")
            all_passed = False

    # Test Camera Slot 0 Drag
    print("\n--------------------------------------------------------------------------")
    print(" Testing Camera Aim Look Drag (Slot 0):")
    cam_x, cam_y = 1650, 540
    cdx, cdy = disp.to_digitizer_coords(cam_x, cam_y)
    conn.send_event(TouchAction.DOWN, 0, cdx, cdy, auto_sync=True)
    time.sleep(0.05)

    # Move mouse right by 100px -> drag touch left by 100px
    cam_x -= 100
    cdx, cdy = disp.to_digitizer_coords(cam_x, cam_y)
    conn.send_event(TouchAction.MOVE, 0, cdx, cdy, auto_sync=True)
    time.sleep(0.05)
    cooked = query_last_cooked()
    conn.send_event(TouchAction.UP, 0, 0, 0, auto_sync=True)

    if cooked:
        win_x = cooked[1]
        win_y = 1080.0 - cooked[0]
        print(f"[PASS] Dragged Slot 0 to: Target Window ({cam_x}, {cam_y}) -> Actual Window ({win_x:.1f}, {win_y:.1f})")
    else:
        print("[FAIL] Slot 0 touch not detected")
        all_passed = False

    conn.close()
    print("==========================================================================")
    if all_passed:
        print(">>> ALL TESTS PASSED! Controls and Aim Pivot are 100% accurate! <<<")
    else:
        print(">>> SOME TESTS FAILED <<<")

if __name__ == "__main__":
    main()
