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

## 2026-06-22 - Task: 修复图片选择器只显示视频

### What was done

- 将主界面的媒体选择拆成“选择图片”和“选择视频”两个入口，避免厂商文件选择器在混合 MIME 筛选时只显示视频。
- 原生文件选择器按入口分别使用 `image/*` 或 `video/*`，并在 MIME 类型缺失时使用入口类型兜底。
- 更新界面测试，确认两个选择入口都展示。

### Testing

- `flutter analyze && flutter test && flutter build apk --release`：通过。
- 真机文件选择器中图片目录是否正常展示，仍需在目标 RK3588/Android 设备上用同一目录复测。

### Notes

- `lib/main.dart`：媒体选择按钮拆分为图片和视频入口，并向原生层传入选择类型。
- `android/app/src/main/kotlin/com/example/second_screen_viewer/MainActivity.kt`：根据选择类型设置系统文件选择器 MIME 过滤，并增加类型兜底。
- `test/widget_test.dart`：更新界面断言，覆盖“选择图片”和“选择视频”按钮。
- `progress.md`：记录本轮排查、修复、验证和回滚方式。
- 回滚方式：回退本轮修改，恢复单个“选择图片或视频”按钮，以及原生 `ACTION_OPEN_DOCUMENT` 的 `image/*`、`video/*` 混合筛选。

## 2026-06-22 - Task: 图片选择器修复发版

### What was done

- 将版本更新到 `1.2.6+15`，用于发布图片选择器修复版本。
- 同步 Flutter 页面和后台服务状态中的版本展示为 `版本 1.2.6 (15)`。

### Testing

- `flutter analyze && flutter test && flutter build apk --release`：通过。

### Notes

- `pubspec.yaml`：发版号更新到 `1.2.6+15`。
- `lib/main.dart`：页面底部版本展示更新到 `版本 1.2.6 (15)`。
- `android/app/src/main/kotlin/com/example/second_screen_viewer/ControlHttpService.kt`：后台服务状态版本展示更新到 `版本 1.2.6 (15)`。
- `progress.md`：记录本轮发版准备、验证和回滚方式。
- 回滚方式：回退本轮版本号修改，或删除 `v1.2.6` tag 并回到上一版 `v1.2.5` 发布产物。
