#!/bin/bash
# Helper script to inspect touch digitizer and toggle coordinate overlay on Redmi K20 Pro (raphael)

echo "=================================================="
echo " 📱 Redmi K20 Pro Touch Digitizer Inspection Tool"
echo "=================================================="

echo "[*] Querying input devices via ADB..."
adb shell "su -c 'getevent -lp'" | grep -B 2 -A 10 "ABS_MT_POSITION_X"

echo ""
echo "[*] Checking screen resolution and density..."
adb shell wm size
adb shell wm density

echo ""
read -p "Do you want to enable on-screen Pointer Location to see button coordinates? (y/n): " choice
if [ "$choice" = "y" ] || [ "$choice" = "Y" ]; then
    adb shell "settings put system pointer_location 1"
    echo "[+] Pointer Location ENABLED. Open BGMI and note the (X, Y) coordinates of buttons."
    echo "    Run 'adb shell settings put system pointer_location 0' when done to disable it."
fi
