# Python WebRTC Signaling Server

A simple, room-based WebSocket signaling server for WebRTC using Python's `asyncio` and `websockets` library.

## Prerequisites
- Python 3.7 or higher installed on your computer.

## Setup Instructions

1. **Install Dependencies**:
   Open a terminal in this folder and install the required `websockets` library:
   ```bash
   pip install -r requirements.txt
   ```

2. **Run the Server**:
   Execute the python script to spin up the server:
   ```bash
   python server.py
   ```
   The server will start listening on port `8765` for connections from all IP addresses (`0.0.0.0`).

## Connecting your Android App

1. **Find your Local IP**:
   Find the local IP address of the computer running this server:
   - On Windows, run `ipconfig` in cmd (look for `IPv4 Address` under your Wi-Fi/Ethernet adapter, e.g., `192.168.1.5`).
   - On macOS/Linux, run `ifconfig` or `ip a`.

2. **Update Android App Settings**:
   In the Android source code, open `SignalingClient.java` and change the base WebSocket URL to point to your computer's local IP address:
   ```java
   // Replace WS_BASE_URL with your local server IP
   private static final String WS_BASE_URL = "ws://<YOUR_LOCAL_IP>:8765/videochatapp-";
   ```

3. **Verify Network Connectivity**:
   Ensure both the computer running this server and your Android devices are connected to the same Wi-Fi network.
