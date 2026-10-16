import subprocess
import os

adb = os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe")
cmd = """
for z in /sys/class/thermal/thermal_zone*; do
    if [ -f "$z/type" ]; then
        echo "$(cat $z/type)=$(cat $z/temp 2>/dev/null)"
    fi
done
"""
out = subprocess.check_output([adb, "shell", "su", "-c", cmd], text=True)
for line in out.splitlines():
    if any(k in line.lower() for k in ["cpu", "gpu", "battery", "bms", "skin", "soc", "tsens"]):
        print(line)
