# Progress

## 2026-06-22 - Background HTTP control service

### Goal

- Boot the Android app without showing the Flutter UI.
- Keep the HTTP control API listening on port `9999` in the background.

### Changes

- Added `ControlHttpService` as an Android foreground service that binds `0.0.0.0:9999`.
- Changed `BootCompletedReceiver` to start the service on `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` instead of launching `MainActivity`.
- Moved secondary-display presentation control into `SecondScreenDisplayController` for reuse by both the service and the Flutter activity.
- Updated `MainActivity` so opening the UI starts/queries the service but does not own port `9999`.
- Removed the Dart-side HTTP server path from the Flutter page to avoid port conflicts.
- Added foreground-service permissions and service declaration in `AndroidManifest.xml`.
- Updated README and added `docs/background-service.md` with the new runtime flow.

### Verification plan

- Run `flutter analyze`.
- Run `flutter test`.
- Run `flutter build apk --release`.
- On target Android/RK3588 hardware, install the APK and reboot.
- Confirm the main UI does not auto-open after boot.
- Confirm the foreground-service notification is present.
- Confirm `GET http://<device-ip>:9999/api/status` works after boot.
- Confirm video, image slideshow, and stop endpoints still work.

### Rollback notes

- Revert `BootCompletedReceiver` to launch `MainActivity` if UI-on-boot behavior is needed again.
- Re-enable the previous Dart `HttpServer` only if the foreground service is removed, otherwise port `9999` will conflict.

## 2026-06-22 - Task: 开机后台监听 9999 并发版

### What was done

- 将开机自启从拉起主界面改为启动 Android 前台服务，后台长期监听 `9999` 端口。
- 将 HTTP 控制接口迁移到原生后台服务，并复用副屏显示控制器处理视频、图片轮播和停止显示。
- 调整 Flutter 页面为服务状态入口，避免页面与后台服务抢占 `9999` 端口。
- 更新版本到 `1.2.5+14`，用于本次发版。

### Testing

- `flutter pub get`：通过。
- `flutter analyze`：通过，No issues found。
- `flutter test`：通过，All tests passed。
- `flutter build apk --release`：通过。
- 真机开机后不显示界面和 `9999` 端口可访问仍需在目标 RK3588/Android 设备上验证。

### Notes

- `README.md`：补充开机后台运行和前台服务通知说明。
- `docs/background-service.md`：新增后台控制服务的启动流程、接口和运维注意事项。
- `progress.md`：记录本轮实现、验证和回滚方式。
- `pubspec.yaml`：发版号更新到 `1.2.5+14`。
- `pubspec.lock`：同步 `flutter pub get` 后的锁定依赖版本。
- `lib/main.dart`：页面改为启动/查询后台服务状态，并更新展示版本号。
- `android/app/src/main/AndroidManifest.xml`：增加前台服务权限和服务声明。
- `android/app/src/main/kotlin/com/example/second_screen_viewer/BootCompletedReceiver.kt`：开机后启动后台服务，不再启动主界面。
- `android/app/src/main/kotlin/com/example/second_screen_viewer/ControlHttpService.kt`：新增原生 HTTP 前台服务并监听 `9999`。
- `android/app/src/main/kotlin/com/example/second_screen_viewer/SecondScreenDisplayController.kt`：新增副屏显示控制器，集中处理 `Presentation` 显示和状态持久化。
- `android/app/src/main/kotlin/com/example/second_screen_viewer/MainActivity.kt`：简化为 Flutter 与原生服务/副屏控制的入口。
- `android/gradle.properties`：保留 Flutter 构建过程中自动补充的 Gradle 兼容标记。
- 回滚方式：回退本次提交，或将 `BootCompletedReceiver` 恢复为启动 `MainActivity`、移除 `ControlHttpService` 和 Manifest 服务声明，并恢复 `lib/main.dart` 中的 Dart `HttpServer` 监听逻辑。
