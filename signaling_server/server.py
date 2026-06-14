import asyncio
import json
import logging
import websockets
import uuid

# Setup Logging
logging.basicConfig(
    format="%(asctime)s - %(levelname)s - %(message)s",
    level=logging.INFO
)

# Map roomId (str) -> set of WebSockets
rooms = {}

# Map WebSocket -> roomId (str) for quick unregistration on close
client_rooms = {}

# Map WebSocket -> unique client ID
client_ids = {}

async def register(websocket, room_id):
    if room_id not in rooms:
        rooms[room_id] = set()
    rooms[room_id].add(websocket)
    client_rooms[websocket] = room_id
    
    # Assign a unique short ID for this peer
    peer_id = f"peer-{uuid.uuid4().hex[:6]}"
    client_ids[websocket] = peer_id
    
    ip = websocket.remote_address[0]
    port = websocket.remote_address[1]
    logging.info(f"[{room_id}] Client {peer_id} ({ip}:{port}) registered. Total peers in room: {len(rooms[room_id])}")
    
    # Notify existing peers when a new peer joins the room
    for peer in rooms[room_id]:
        if peer != websocket:
            await peer.send(json.dumps({"type": "peer_joined"}))
            other_id = client_ids.get(peer, "unknown")
            logging.info(f"[{room_id}] Notified existing client {other_id} of guest {peer_id} joining.")

async def unregister(websocket):
    room_id = client_rooms.pop(websocket, None)
    peer_id = client_ids.pop(websocket, None)
    ip = websocket.remote_address[0] if websocket.remote_address else "unknown"
    
    if room_id and room_id in rooms:
        rooms[room_id].discard(websocket)
        logging.info(f"[{room_id}] Client {peer_id} ({ip}) disconnected. Remaining peers: {len(rooms[room_id])}")
        
        # Clean up empty room
        if not rooms[room_id]:
            del rooms[room_id]
            logging.info(f"[{room_id}] Room is empty and has been deleted.")

async def handler(websocket):
    # Extract the room ID from the connection URL path
    # E.g., for ws://ip:8765/videochatapp-123456, path is '/videochatapp-123456'
    path = websocket.path.strip("/")
    room_id = path if path else "default_room"
    
    await register(websocket, room_id)
    peer_id = client_ids.get(websocket, "unknown")
    ip = websocket.remote_address[0]

    try:
        async for message in websocket:
            # Parse message content to print readable, non-flooding log traces
            try:
                msg_data = json.loads(message)
                msg_type = msg_data.get("type", "unknown")
                # Truncate large Base64 binary packets (like video chunks) for cleaner logs
                if "data" in msg_data and isinstance(msg_data["data"], str):
                    msg_data["data"] = msg_data["data"][:30] + "... (truncated)"
                logging.info(f"[{room_id}] {peer_id} ({ip}) -> {msg_type}: {json.dumps(msg_data)}")
            except Exception:
                truncated_raw = message[:100] + "..." if len(message) > 100 else message
                logging.info(f"[{room_id}] {peer_id} ({ip}) -> raw: {truncated_raw}")

            # Broadcast the raw message to all other peers in the same room
            if room_id in rooms:
                other_peers = [ws for ws in rooms[room_id] if ws != websocket]
                if other_peers:
                    # Send message to all other clients concurrently
                    await asyncio.gather(*[ws.send(message) for ws in other_peers])
    except websockets.exceptions.ConnectionClosedOK:
        logging.info(f"[{room_id}] Connection closed cleanly for {peer_id} ({ip})")
    except websockets.exceptions.ConnectionClosedError:
        logging.warning(f"[{room_id}] Connection closed with error for {peer_id} ({ip})")
    finally:
        await unregister(websocket)

async def main():
    # Bind to 0.0.0.0 so external devices on the same local network can connect
    PORT = 8765
    async with websockets.serve(handler, "0.0.0.0", PORT):
        logging.info(f"WebRTC Signaling Server running on ws://localhost:{PORT}")
        logging.info("Ready for Android device connections (bind address: 0.0.0.0)")
        await asyncio.Future()  # run forever

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        logging.info("Signaling Server stopped.")
