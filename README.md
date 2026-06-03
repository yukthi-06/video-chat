# VideoChatApp

A complete Android video chat application implemented in Java using WebRTC and Material Design 3.

## Features
- **Home Screen**: Start a new video call or join an existing call using a room ID.
- **WebRTC Integration**: Real-time video/audio streaming with local preview and remote stream display.
- **Navigation Drawer**: Access settings, help, and about screens using a Material Design 3 side navigation panel.
- **Settings Screen**: Control video quality, toggle notifications, and toggle dark mode (with state persisted via SharedPreferences).
- **Help Screen**: Instructions for starting/joining a call, permissions, and troubleshooting tips.
- **About Screen**: App information, version, and developer details.
- **Permissions**: Handles runtime camera and microphone permission requests at startup.

## Project Structure
The app adheres to clean architecture principles with separate packages:
- `activities`: Activities for main screen, call session, settings, help, and about pages.
- `fragments`: Navigation drawer landing fragments.
- `webrtc`: WebRTC client wrapper (`WebRtcClient`) and signaling layer (`SignalingClient`).

## Build Instructions
To build the application from the command line, run:
```bash
./gradlew assembleDebug
```
The generated APK will be available in `app/build/outputs/apk/debug/app-debug.apk`.

## Running Instructions
1. Open the project in Android Studio.
2. Ensure you have a physical device connected or a configured emulator with Camera & Microphone access.
3. Click **Run** or use the terminal to install:
   ```bash
   ./gradlew installDebug
   ```
4. On startup, the app will request Camera and Microphone permissions. Allow these to enable video call features.

## WebRTC and Signaling Notes
- **WebRTC Library**: Powered by Google's native WebRTC library (`org.webrtc:google-webrtc`).
- **Signaling Client**: Includes an abstraction layer (`SignalingClient`). A loopback mock-signaling system is implemented by default for demonstration and local testing.
- **Production Integration**: Look for `TODO` comments in `SignalingClient.java` to connect your own WebSocket, Socket.io, or Firebase Firestore database to broker connection negotiations (SDP offers/answers and ICE candidates).

## GitHub Actions CI
The project includes a GitHub Actions configuration at `.github/workflows/android-build.yml` which automatically compiles the codebase and uploads the build artifact (`app-debug.apk`) on every push or pull request.