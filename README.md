# BlockBridge

Android app. Lets a console (Xbox, PlayStation, Switch) join any Minecraft
Bedrock server through the **LAN Games** tab.

## How it works

1. Phone opens UDP port 19132 on your Wi-Fi.
2. Console looks for LAN games. Phone answers with the real server's name.
3. Console joins. Phone passes every packet to the real server and back.

Phone must stay on, on the same Wi-Fi as the console, while you play.

## Use

1. Open app. Add a server (address + port, default 19132).
2. Tap the server. A notification shows it is sharing.
3. On console: Play → Friends → LAN Games → pick it.

## Build

Needs JDK 17 and the Android SDK (Android Studio has both).

- Android Studio: open this folder, press Run.
- Command line: `gradle assembleRelease` → `app/build/outputs/apk/release/`.
- GitHub: push this folder; the `build` workflow uploads the APK.

## Code

- `core/RakNet.kt` – ping / pong packets for LAN discovery.
- `core/LanRelay.kt` – UDP relay, one upstream socket per console.
- `RelayService.kt` – foreground service, Wi-Fi and wake locks.
- `MainActivity.kt` – server list UI (Jetpack Compose).
