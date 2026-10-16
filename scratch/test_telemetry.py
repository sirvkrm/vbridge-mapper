import sys
import os
import time

sys.path.append(os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
from client.connection import TouchConnection

def main():
    print("[*] Testing Kernel Telemetry over TCP Socket...")
    conn = TouchConnection(auto_adb_forward=False)
    if not conn.connect():
        print("[-] Connection failed!")
        return

    print("[*] Connected! Requesting 500ms telemetry interval...")
    conn.configure_telemetry(500)

    print("[*] Waiting for telemetry packets from root daemon...")
    for _ in range(6):
        time.sleep(0.5)
        telem = conn.poll_telemetry()
        if telem:
            print(f"  [HARDWARE TELEMETRY] "
                  f"FPS: {telem.fps:.1f} | "
                  f"CPU: {telem.cpu_usage_pct:.1f}% ({telem.cpu_temp_c:.1f}°C) | "
                  f"GPU: {telem.gpu_usage_pct:.1f}% ({telem.gpu_temp_c:.1f}°C) | "
                  f"RAM: {telem.mem_used_mb}/{telem.mem_total_mb} MB | "
                  f"Battery: {telem.battery_temp_c:.1f}°C")
        else:
            print("  Waiting for packet...")

    conn.close()

if __name__ == "__main__":
    main()
