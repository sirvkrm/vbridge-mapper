import subprocess
import os

adb = r"C:\Users\asus\AppData\Local\Android\Sdk\platform-tools\adb.exe"

def run_adb_su(cmd):
    res = subprocess.run([adb, "shell", f"su -c '{cmd}'"], capture_output=True, text=True)
    return res.stdout.strip()

print("--- 1. Measured FPS ---")
print(run_adb_su("cat /sys/class/drm/sde-crtc-0/measured_fps 2>/dev/null || cat /sys/devices/platform/soc/ae00000.qcom,mdss_mdp/drm/card0/sde-crtc-0/measured_fps 2>/dev/null"))

print("--- 2. GPU Busy ---")
print(run_adb_su("cat /sys/class/kgsl/kgsl-3d0/gpubusy 2>/dev/null"))

print("--- 3. CPU Stat ---")
print(run_adb_su("head -n 1 /proc/stat"))

print("--- 4. Meminfo ---")
print(run_adb_su("grep -E 'MemTotal|MemAvailable' /proc/meminfo"))

print("--- 5. Thermal Zones ---")
script = 'for z in /sys/class/thermal/thermal_zone*; do echo "$(cat $z/type): $(cat $z/temp 2>/dev/null)"; done'
print(run_adb_su(script))

print("--- 6. Touch Injector binary ---")
print(run_adb_su("ls -la /data/local/tmp/touch_injector; ps -A | grep touch_injector"))
