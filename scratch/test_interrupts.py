import subprocess
import os

adb = os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe")
import time
def get_drm_interrupts():
    out = subprocess.check_output([adb, "shell", "cat", "/proc/interrupts"], text=True)
    for line in out.splitlines():
        if "msm_drm" in line:
            parts = line.split()
            # sum all core counts
            counts = [int(p) for p in parts[1:9] if p.isdigit()]
            return sum(counts)
    return 0

print("Testing msm_drm interrupt frequency...")
c0 = get_drm_interrupts()
t0 = time.perf_counter()
for _ in range(3):
    time.sleep(1.0)
    c1 = get_drm_interrupts()
    t1 = time.perf_counter()
    hz = (c1 - c0) / (t1 - t0)
    print(f"msm_drm interrupts/sec: {hz:.1f} Hz (Delta: {c1 - c0})")
    c0 = c1
    t0 = t1
