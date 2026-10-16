import subprocess
import time
import os

adb = os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe")

print("Testing direct kernel DRM measured_fps sampling...")
for i in range(5):
    t0 = time.perf_counter()
    out = subprocess.check_output([adb, "shell", "su", "-c", "cat /sys/class/drm/sde-crtc-0/measured_fps"], text=True)
    dt = (time.perf_counter() - t0) * 1000.0
    print(f"[{i+1}] {out.strip()} (Read time: {dt:.1f}ms)")
    time.sleep(0.5)
