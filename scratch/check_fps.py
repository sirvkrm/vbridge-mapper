import subprocess
import time
import os

adb = os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe")

import re

def test_surfaceflinger_layers():
    out = subprocess.check_output([adb, "shell", "dumpsys", "SurfaceFlinger", "--list"], text=True)
    layers = [l.strip() for l in out.splitlines() if l.strip()]
    for l in layers:
        m = re.search(r"\{(.+?)\}", l)
        inner = m.group(1) if m else l
        # Clean up addresses if present
        for candidate in [inner, inner.split()[-1], re.sub(r'^[0-9a-fA-F]+\s+', '', inner)]:
            if "freefire" in candidate.lower():
                try:
                    lat = subprocess.check_output([adb, "shell", "dumpsys", "SurfaceFlinger", "--latency", f"'{candidate}'"], text=True)
                    lines = [x for x in lat.strip().splitlines() if x.strip()]
                    if len(lines) > 2:
                        print(f"SUCCESS on candidate '{candidate}': {len(lines)} frame lines!")
                        print(f"Sample: {lines[1]}")
                        return candidate
                except Exception:
                    pass
    print("Testing dumpsys display...")
    try:
        disp = subprocess.check_output([adb, "shell", "dumpsys", "display"], text=True)
        for line in disp.splitlines():
            if any(k in line.lower() for k in ["refresh", "activemode", "fps", "hz"]):
                print(f"  {line.strip()}")
    except Exception as e:
        print(f"display error: {e}")

def get_game_frame_count():
    sf = subprocess.check_output([adb, "shell", "dumpsys", "SurfaceFlinger"], text=True)
    # Search for SurfaceView.*BLAST or MainActivity with frame=
    m = re.search(r"SurfaceView\[.+?\]\(BLAST\).+?frame=(\d+)", sf, re.DOTALL)
    if not m:
        m = re.search(r"Layer \[.+?\]\s+.+?(?:com\.\w+\.\w+).+?frame=(\d+)", sf, re.DOTALL)
    if m:
        return int(m.group(1))
    return None

def test_fps():
    print("Sampling Game FPS over 3 seconds...")
    last_f = get_game_frame_count()
    last_t = time.perf_counter()
    for _ in range(5):
        time.sleep(0.5)
        now_f = get_game_frame_count()
        now_t = time.perf_counter()
        if now_f is not None and last_f is not None:
            fps = (now_f - last_f) / (now_t - last_t)
            print(f"[{time.strftime('%H:%M:%S')}] Frame: {now_f} | Live Game FPS: {fps:.1f}")
        last_f = now_f
        last_t = now_t

if __name__ == "__main__":
    test_fps()
