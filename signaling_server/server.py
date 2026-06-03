import asyncio
import json
import logging
import websockets

# Setup Logging
logging.basicConfig(
    format="%(asctime)s - %(levelname)s - %(message)s",
    level=logging.INFO
)

# Map roomId (str) -> set of WebSockets
rooms = {}

# Map WebSocket -> roomId (str) for quick unregistration on close
client_rooms = {}

async def register(websocket, room_id):
    if room_id not in rooms:
        rooms[room_id] = set()
    rooms[room_id].add(websocket)
    client_rooms[websocket] = room_id
    logging.info(f"New client joined room [{room_id}]. Total active peers: {len(rooms[room_id])}")
    
    # Notify host peer when a second peer joins the room
    if len(rooms[room_id]) == 2:
        for peer in rooms[room_id]:
            if peer != websocket:
                await peer.send(json.dumps({"type": "peer_joined"}))
                logging.info(f"Notified existing peer in room [{room_id}] of new guest joining.")

async def unregister(websocket):
    room_id = client_rooms.pop(websocket, None)
    if room_id and room_id in rooms:
        rooms[room_id].discard(websocket)
        logging.info(f"Client left room [{room_id}]. Remaining peers: {len(rooms[room_id])}")
        
        # Clean up empty room
        if not rooms[room_id]:
            del rooms[room_id]
            logging.info(f"Room [{room_id}] is empty and has been deleted.")

async def handler(websocket):
    # Extract the room ID from the connection URL path
    # E.g., for ws://ip:8765/videochatapp-123456, path is '/videochatapp-123456'
    path = websocket.path.strip("/")
    room_id = path if path else "default_room"
    
    await register(websocket, room_id)

    try:
        async for message in websocket:
            # Broadcast the raw message to all other peers in the same room
            if room_id in rooms:
                other_peers = [ws for ws in rooms[room_id] if ws != websocket]
                if other_peers:
                    # Send message to all other clients concurrently
                    await asyncio.gather(*[ws.send(message) for ws in other_peers])
                    logging.info(f"Broadcasted message to {len(other_peers)} peer(s) in room [{room_id}]")
    except websockets.exceptions.ConnectionClosedOK:
        logging.info(f"Connection closed cleanly in room [{room_id}]")
    except websockets.exceptions.ConnectionClosedError:
        logging.warning(f"Connection closed with error in room [{room_id}]")
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
