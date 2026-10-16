import asyncio
import json
import logging
import websockets

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")

CLIENTS = set()
ACTIVE_PROFILE = None

async def handler(websocket):
    global ACTIVE_PROFILE
    CLIENTS.add(websocket)
    remote = websocket.remote_address
    logging.info(f"🔗 Android client connected: {remote}")
    
    # Send current state ACK
    await websocket.send(json.dumps({
        "event": "CONNECTED",
        "status": "ready",
        "active_profile": ACTIVE_PROFILE
    }))

    try:
        async for message in websocket:
            try:
                data = json.loads(message)
                event = data.get("event", "UNKNOWN")
                
                if event == "PING":
                    await websocket.send(json.dumps({"event": "PONG", "time": asyncio.get_event_loop().time()}))
                
                elif event == "PROFILE_ACTIVATED":
                    ACTIVE_PROFILE = data
                    pkg = data.get("package", "Unknown")
                    preset_id = data.get("preset_id", 1)
                    binding_id = data.get("keybinding_id", 1)
                    preset_name = data.get("preset_name", "")
                    binding_name = data.get("binding_name", "")
                    controller = data.get("controller_type", "Python")
                    via = data.get("via", "ADB+Root")
                    
                    logging.info(f"⚡ [PROFILE ACTIVATED] {preset_name} (#{preset_id}) -> Binding: {binding_name} (#{binding_id}) | App: {pkg} | Controller: {controller} via {via}")
                    
                    try:
                        sig_file = os.path.join(os.path.dirname(__file__), "active_signal.json")
                        with open(sig_file, "w") as f:
                            json.dump(data, f)
                    except Exception:
                        pass

                    await websocket.send(json.dumps({
                        "event": "PROFILE_LOADED",
                        "status": "success",
                        "preset_id": preset_id,
                        "keybinding_id": binding_id
                    }))
                
                elif event == "LIVE_KEY_UPDATE":
                    key = data.get("key")
                    x = data.get("x")
                    y = data.get("y")
                    logging.info(f"📍 [LIVE KEY UPDATE] Key='{key}' -> ({x}, {y})")

                    try:
                        keys_file = os.path.join(os.path.dirname(__file__), "live_keys.json")
                        with open(keys_file, "w") as f:
                            json.dump(data, f)
                    except Exception:
                        pass

                    await websocket.send(json.dumps({"event": "ACK_KEY", "key": key}))
                    
                else:
                    logging.info(f"📩 Received unknown event '{event}': {data}")
                    await websocket.send(json.dumps({"event": "ACK", "received": event}))
                    
            except json.JSONDecodeError:
                logging.warning(f"Malformed JSON: {message}")
    except websockets.exceptions.ConnectionClosed:
        logging.info(f"🔌 Android client disconnected: {remote}")
    except Exception as e:
        logging.warning(f"Handler error: {e}")
    finally:
        CLIENTS.discard(websocket)

async def main():
    port = 8889
    logging.info(f"🚀 Windows WebSocket Trajectory Bridge listening on ws://127.0.0.1:{port}...")
    async with websockets.serve(handler, "127.0.0.1", port):
        await asyncio.Future()  # run forever

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        logging.info("Bridge stopped.")
