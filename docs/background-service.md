# Background control service

## Goal

The app must be able to boot without showing the Flutter UI and still keep the HTTP control API available on port `9999`.

## Android startup flow

1. `BootCompletedReceiver` receives `BOOT_COMPLETED` or `MY_PACKAGE_REPLACED`.
2. The receiver starts `ControlHttpService`.
3. `ControlHttpService` enters foreground-service mode and shows a persistent notification.
4. The service binds `0.0.0.0:9999` and handles HTTP requests directly in Kotlin.
5. Only a genuine `BOOT_COMPLETED` carries its captured elapsed-realtime timestamp. A serial storage worker reads `Settings.Global.BOOT_COUNT` and the atomic boot ledger after listener startup; an ordinary Activity/package-update/sticky start can only resume an existing record for the same boot.
6. An armed deadline is 120 seconds after that broadcast, not kernel boot or service creation. When due, the service durably claims one attempt before reading the local cached image. `Handler` delivery is best effort: sleep or main-queue contention can make it late, and no exact alarm or periodic retry is added.
7. HTTP media commands call `SecondScreenDisplayController`, which owns `Presentation` or permission-checked overlay playback. Prepared HTTP/boot images are copied and sampled/decoded off main; main only validates their lease and attaches the window.

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
- Initial boot startup performs no media reads, downloads, decode, or window creation. After the grace period, at most one local image may be restored; never video, a slideshow, Flutter UI, permission settings, or a remote URI.
- The service still requires the device network to allow callers to reach port `9999`.
- Media URLs must be directly reachable from the Android device.
- Some vendor ROMs may still require enabling app auto-start or disabling battery optimization for maximum reliability.

## Command precedence and restart behavior

The normalized recognized POST media/stop routes invalidate automatic restore immediately after the request line, before headers, body parsing, required-field checks, downloads or decode. Even malformed/incomplete media requests cancel. HTTP workers wait for the terminal ledger write before proceeding or reporting a parsing error; Activity show/hide and long-press close continue asynchronously after that write without blocking main. GET status/displays, OPTIONS, unknown routes, selected-screen changes and simply opening the UI do not cancel.

The per-boot ledger records `ARMED`, `CANCELLED` or `CONSUMED` with `BOOT_COUNT` and an elapsed deadline. Duplicates never postpone a deadline or rearm a terminal state. Service destruction only revokes that instance's lease; it does not record a user cancellation. Missing/unreadable boot identity or ledger makes restoration a logged no-op rather than using a sentinel boot ID. A cancellation write failure still suppresses restore in memory and logs that restart safety could not be guaranteed.

A process-wide generation gate orders admission against the final main-thread attachment/hide. Every prepared restore, request and slideshow tick carries its generation and service lease. Late downloads, queued main runnables and old future registration cannot overrule newer local/video/hide intentions. No worker holds this gate while waiting for main, and the storage worker posts continuations instead of waiting for the main handler. Normal slideshow scheduling and its minimum 500 ms interval remain unchanged; the most recent successfully attached slide supplies the single-image fallback.

## Durable last-good image

`filesDir/last_good_image/` owns immutable `image-*.img` generations and an `AtomicFile` manifest containing the actual display ID, normalized scale/rotation, name and diagnostic source URI. Restoration opens only the owned local image, never the diagnostic source URI. A unique candidate is copied/synced and actually pixel-decoded at sampled target dimensions before replacing the current window. The candidate attaches before the previous window is dismissed. Invalid pixels, network/read/decode failures, missing targets and attachment/permission failures leave the previous window and manifest untouched.

Only successful attachment enqueues manifest publication, in successful display order. The complete manifest is atomically published and read back before orphan cleanup. Active candidates/windows pin their files; cleanup retains the referenced image and live pins, then removes unreferenced owned generations, including interrupted leftovers on the next store initialization. Temporary download cleanup and service shutdown do not delete the durable fallback. If the manifest cannot be read, cleanup is conservatively deferred and logged.

If copying or publishing durable storage fails, a usable new image can remain visible while the old durable pointer remains the reboot fallback. An adopted bitmap belongs to the image view until detach; failed/stale work releases its own resources and a later publication failure must never recycle a bitmap still on screen. Local Activity file selection is not saved as the HTTP fallback.

Once an attempt is consumed, a crash before display intentionally skips fallback for the rest of that boot. A crash after a successful display but before publication may retain the previous fallback. This is best-effort display continuity, not a fix or diagnosis for the previously unconfirmed idle process exit. The service type is unchanged. The feature is first published in prerelease `1.3.0-beta.1+18`.

## Verification and target-device checklist

Host checks: `cd android && ./gradlew :app:testDebugUnitTest`, `flutter analyze`, `flutter test`, `flutter build apk --release`, and `git diff --check`. JVM tests exercise the actual pure boot ledger, command gate and store with injected copy/publication failures; they do not execute Android `BitmapFactory`, `AtomicFile`, overlays or `Presentation`.

On RK3588, save a known image/settings, reboot without opening the app, confirm port `9999` is available during the grace period and no media/UI appears before the deadline, then confirm one local-only image appears. Repeat with a malformed recognized request, new image/video, local show/hide and long-press dismissal before/during restore; none may be overwritten. Kill/recreate the process before and after cancellation/consumption and verify the original deadline and terminal state survive. Check multiple slides, invalid image and attachment failures, removed/reassigned displays, absent overlay permission, corrupt/missing cache, BOOT_COUNT availability and cold/power-loss durability. Android display IDs may be reassigned across boots; missing IDs are skipped, not remapped to another screen.
