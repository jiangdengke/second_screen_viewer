# Background control service

## Goal

The app must be able to boot without showing the Flutter UI and still keep the HTTP control API available on port `9999`.

## Android startup flow

1. `BootCompletedReceiver` receives `BOOT_COMPLETED` or `MY_PACKAGE_REPLACED`.
2. The receiver starts `ControlHttpService`.
3. `ControlHttpService` enters foreground-service mode and shows a persistent notification.
4. The service binds `0.0.0.0:9999` and handles HTTP requests directly in Kotlin.
5. HTTP media commands call `SecondScreenDisplayController`, which owns Android `Presentation` playback on the secondary display.

## UI flow

`MainActivity` is now optional. Opening the app starts or refreshes the service status through the MethodChannel, but the Flutter page does not bind port `9999` anymore. This avoids a port conflict between the UI and the background service.

## Supported HTTP endpoints

- `GET /api/status`
- `GET /api/displays`
- `POST /robot_task/screen_control`
- `POST /robot_task/screen_control_Img_display`
- `POST /api/hide`
- `POST /robot_task/screen_stop`

## Operational notes

- A persistent Android notification is expected and required for reliable long-running background listening.
- The service still requires the device network to allow callers to reach port `9999`.
- Media URLs must be directly reachable from the Android device.
- Some vendor ROMs may still require enabling app auto-start or disabling battery optimization for maximum reliability.
