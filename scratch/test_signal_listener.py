import socket
import json
import sys

def main():
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", 8889))
    server.listen(5)
    print("[+] Windows Trajectory Signal Server listening on 127.0.0.1:8889...", flush=True)

    try:
        while True:
            client, addr = server.accept()
            print(f"[+] Connection accepted from {addr}", flush=True)
            data = client.recv(4096)
            if data:
                text = data.decode("utf-8").strip()
                print(f"[RECV SIGNAL]: {text}", flush=True)
                try:
                    obj = json.loads(text)
                    event = obj.get("event")
                    pkg = obj.get("package")
                    preset_id = obj.get("preset_id")
                    preset_name = obj.get("preset_name")
                    print(f"[*] AUTO-SWITCH TRIGGER: Game={pkg}, Preset #{preset_id} ({preset_name})", flush=True)
                except Exception as e:
                    print(f"[-] Parse error: {e}", flush=True)
                client.sendall(b'{"status":"ok","msg":"profile_loaded"}\n')
            client.close()
    except KeyboardInterrupt:
        print("\nExiting signal listener.")
    finally:
        server.close()

if __name__ == "__main__":
    main()
