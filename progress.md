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

## 2026-09-04 - Task: 开机仅自启后台服务

### What was done

- 移除开机启动时恢复上一次图片或视频的逻辑，避免启动阶段读取本地媒体或下载网络媒体导致卡死。
- 移除图片、视频及显示状态的持久化保存，显示状态改为仅保留在当前进程内。
- 保留开机启动前台服务和监听 `9999` 端口的能力。
- 更新后台服务文档，明确开机不会自动加载上一次媒体。

### Testing

- `flutter analyze`：通过，No issues found。
- `flutter test`：通过，All tests passed。
- `flutter build apk --release`：通过，生成 `build/app/outputs/flutter-apk/app-release.apk`。
- 真机重启后确认不显示主界面、不加载旧媒体，并确认 `9999` 端口仍可访问。

### Notes

- `android/app/src/main/kotlin/com/example/second_screen_viewer/ControlHttpService.kt`：移除开机恢复媒体逻辑。
- `android/app/src/main/kotlin/com/example/second_screen_viewer/SecondScreenDisplayController.kt`：移除媒体持久化，改为进程内保存当前显示状态。
- `android/app/src/main/kotlin/com/example/second_screen_viewer/MainActivity.kt`：移除本地媒体配置保存和旧配置兜底读取。
- `README.md`：说明开机不会恢复上一次媒体。
- `docs/background-service.md`：补充后台服务不恢复旧媒体的运行说明。
- `progress.md`：记录本轮改动、验证计划和回滚方式。
- 回滚方式：恢复 `ControlHttpService` 的启动恢复调用和 `SecondScreenDisplayController` 的 `SharedPreferences` 媒体持久化逻辑；如需完整回滚，可回退本轮提交。

## 2026-09-04 - Task: 开机仅自启服务发版

### What was done

- 将版本更新到 `1.2.7+16`，用于发布开机启动稳定性修复版本。
- 保留开机启动前台服务和监听 `9999` 端口，不再恢复或加载上一次显示的媒体。

### Testing

- `flutter analyze`：通过，No issues found。
- `flutter test`：通过，All tests passed。
- `flutter build apk --release`：通过，生成 `build/app/outputs/flutter-apk/app-release.apk`。
- 真机重启后确认不显示主界面、不加载旧媒体，并确认 `9999` 端口仍可访问。

### Notes

- `pubspec.yaml`：发版号更新到 `1.2.7+16`。
- `lib/main.dart`：页面底部版本展示更新到 `版本 1.2.7 (16)`。
- `android/app/src/main/kotlin/com/example/second_screen_viewer/ControlHttpService.kt`：后台服务状态版本展示更新到 `版本 1.2.7 (16)`。
- `README.md`：说明开机不会恢复上一次媒体。
- `docs/background-service.md`：补充后台服务不恢复旧媒体的运行说明。
- `progress.md`：记录本轮发版准备、验证和回滚方式。
- 回滚方式：回退本轮提交，或恢复 `v1.2.6` 发布产物。

## 2026-10-19 - Task: 检测并选择所有屏幕（1.2.8-beta.1）

### What was done

- 页面列出检测到的所有屏幕，包括系统默认屏（display 0），每块屏都可以选择。
- 页面上选择的屏幕保存到 `SharedPreferences`，HTTP 请求不传 `displayId` 时使用它；从未选择时使用第一块非系统默认屏。
- 带 `FLAG_PRESENTATION` 的屏继续用 `Presentation`；display 0 等其他屏改用悬浮窗显示，需要“显示在其他应用上层”权限。
- 悬浮窗按住 3 秒可以关闭；悬浮窗或 Presentation 被外部关闭时，后台服务停止图片轮播。
- 版本更新到 `1.2.8-beta.1+17`，`-` 后缀的标签在 GitHub Release 中标为预发布版本。

### Testing

- `flutter analyze`：通过，No issues found。
- `flutter test`：通过，All tests passed。
- `flutter build apk --release`：通过。
- 真机待验证：选择 display 0 播放视频和图片、按住 3 秒关闭、不传 `displayId` 的 HTTP 请求使用页面选择的屏幕。

### Notes

- `android/app/src/main/kotlin/com/example/second_screen_viewer/SecondScreenDisplayController.kt`：保存屏幕选择，新增悬浮窗显示方式。
- `android/app/src/main/kotlin/com/example/second_screen_viewer/MainActivity.kt`：新增 `setSelectedDisplay`，缺少悬浮窗权限时打开设置页。
- `android/app/src/main/kotlin/com/example/second_screen_viewer/ControlHttpService.kt`：显示窗口被外部关闭时停止图片轮播。
- `android/app/src/main/AndroidManifest.xml`：声明 `SYSTEM_ALERT_WINDOW`。
- `lib/main.dart`：屏幕列表显示全部屏幕，选择后保存。
- `.github/workflows/android-release.yml`：带 `-` 后缀的标签发布为预发布版本。
- `README.md`：说明屏幕选择规则和悬浮窗授权方法。
- 回滚方式：回退本轮提交，或恢复 `v1.2.7` 发布产物。

## 2026-11-18 - Task: 开机两分钟后使用最后成功图片兜底

### What was done

- 保留开机不打开主界面、立即启动前台服务监听 `9999` 的路径；真正收到开机完成广播后等待 120 秒，再按条件尝试一次本地图片兜底。
- 使用开机标识和持久状态记录原截止时间、取消及已尝试状态，避免打开页面、重复启动服务或进程重建重新计时。
- 图片、视频、本地显示和停止指令优先；识别到图片请求即取消本次开机兜底，即使参数或下载失败也不让旧图随后覆盖新指令。
- 成功显示的 HTTP 图片独立保存在私有目录；候选图片在工作线程复制和采样解码，新窗口可用后再替换旧画面并原子更新持久缓存。
- 保留多图轮播，最后成功显示的一张可供下次开机单图兜底；不恢复视频，不在恢复时联网。
- 增加开机状态、指令竞争和缓存事务的原生 JVM 测试；同步 README、后台服务说明和本地决策记录。
- 本轮不改版本号、不提交、不推送、不发布；该功能不代表已定位或修复此前空闲异常退出的原因。

### Testing

- `android/` 下 `./gradlew :app:testDebugUnitTest :app:assembleRelease`：首轮通过；复核修正后 `./gradlew :app:testDebugUnitTest :app:compileReleaseKotlin` 再次通过，最终 30 项原生测试无失败。
- `flutter analyze`：通过，No issues found。
- `flutter test`：通过，3 项测试。
- `flutter build apk --release`：通过，生成 `build/app/outputs/flutter-apk/app-release.apk`。
- 决策记录结构、格式和归档校验：通过；`git diff --check`：通过。
- 独立代码复核已完成：修正旧窗口关闭/通知与新指令的竞争、主线程投递失败的资源清理、客户端断开时错误响应写入和关闭 socket 的 I/O 异常边界。
- 图片解码的边界/像素分配失败转换为普通准备错误，保留原始原因；不全局吞掉其他严重错误，也不承诺极端内存耗尽下必然恢复。
- 所有复核修改后重跑 `flutter analyze && flutter test && flutter build apk --release && git diff --check`：通过，最终 APK 已重新打包，不是复核前的旧产物。
- `adb devices -l` 无连接设备。真实开机两分钟、显示权限、屏幕 ID 稳定性、Android 解码/原子文件和突然断电恢复仍需 RK3588 真机验证。

### Notes

- `BootCompletedReceiver.kt`、`ControlHttpService.kt`：区分真正开机事件，后台计时并识别新指令。
- `BootFallbackLedger.kt`、`MediaCommandGate.kt`、`MediaFallbackCoordinator.kt`：持久开机状态、最终显示指令顺序及串行存储工作。
- `LastGoodImageStore.kt`、`AtomicRecordFile.kt`、`PreparedDisplayImage.kt`、`ImageDecodeAllocation.kt`：不可变图片、原子元数据、位图/文件所有权和解码分配错误边界。
- `MainActivity.kt`、`SecondScreenDisplayController.kt`：本地播放/停止取消兜底，最终主线程检查，先挂载新窗口再关闭旧窗口。
- `android/app/src/test/`、`android/app/build.gradle.kts`：仅增加有针对性的 JUnit 测试和测试依赖。
- 安装本轮构建后需先成功播放过一张 HTTP 图片，才有下次开机的持久兜底；不迁移旧版临时下载缓存。本地文件选择不作为 HTTP 兜底缓存。
- 回滚方式：恢复本轮修改文件到改动前版本并移除本轮新增 Kotlin 类与测试，恢复“开机只监听、不恢复媒体”；私有持久图片可留存但不再读取。不要直接回退其他既有修改。

## 2026-11-18 - Task: 延迟缓存恢复预发布 v1.3.0-beta.1

### What was done

- 用户确认使用预发布渠道，将应用版本更新为 `1.3.0-beta.1+18`，同步页面和 HTTP 状态中的版本信息。
- 本次包含开机两分钟无新媒体指令时的本地缓存图片恢复、新图成功后才替换旧图、过时任务保护及 HTTP/解码错误边界。
- 使用新的次版本号标识向后兼容的缓存恢复能力；已有 HTTP 接口保持兼容，原屏幕选择和悬浮窗功能一并保留。
- 发布标签计划为 `v1.3.0-beta.1`，由 `Android APK Release` 工作流编译上传 APK，并标记为预发布；正式版最新标签保持不变。
- 提交范围仅包含应用源码、测试和相关文档，排除本地 `.cursor/`、`.trellis/`、`AGENTS.md`、忽略的工作区笔记及构建产物。

### Testing

- 版本同步后执行 `flutter analyze && flutter test && flutter build apk --release && ./android/gradlew -p android :app:testDebugUnitTest && git diff --check`：全部通过。
- Flutter 测试 3 项、原生 JVM 测试 30 项通过，release APK 本地构建成功。
- RK3588 真机重启、两分钟恢复和长期空闲稳定性尚未验证；不将本次发布描述为已确认修复空闲异常退出。

### Notes

- 安装预发布 APK 后先通过 HTTP 成功显示一张图片，再重启验证两分钟后的离线缓存恢复。
- 回滚使用已发布旧版本 APK；新版本的独立私有缓存无需手动删除，旧版本不会加载它。
- 远端发布状态以 GitHub Release 页面及 Actions 结果为准。

## 2026-11-18 - Task: 固定构建工具链并发布 v1.3.0-beta.2

### What was done

- 首次已推送的 `v1.3.0-beta.1` 在 GitHub Analyze 阶段失败：未固定的 `stable` 选择 Flutter `3.50.0`，对既有 `package:flutter/material.dart` 导入报弃用提示；没有生成 GitHub Release 或 APK。
- 将 CI 和 APK 发布流程同时固定为本地验证通过的 Flutter `3.44.1`，避免远端工具链自动漂移。不关闭分析规则，不在发版过程中迁移 UI 依赖。
- 使用新预发布标签 `v1.3.0-beta.2`，应用版本同步为 `1.3.0-beta.2+19`；不覆盖或强推已发布的 beta.1 标签。
- 本次保留相同的延迟缓存恢复功能，新增的工具链固定不改变 HTTP 或播放行为。正式版本仍需真机验收后另行发布。

### Testing

- Flutter `3.44.1` 下再次执行 `flutter analyze && flutter test && flutter build apk --release && ./android/gradlew -p android :app:testDebugUnitTest && git diff --check`：全部通过。
- Flutter 测试 3 项、原生测试 30 项通过，beta.2 release APK 本地构建成功。
- GitHub 发布将重新运行固定版本下的分析、测试和 APK 构建；不把本地验证结果代替远端发布结果。

### Notes

- 保留失败运行 `44243053535` 和原标签作为历史，后续通过新标签验证工具链修复。
- 仍未验证 RK3588 真机重启和长期空闲稳定性，不宣称进程异常退出已修复。

## 2026-11-18 - Task: 修复 Android 15 开机前台服务类型不兼容

### What was done

- 用户确认设备为 Android 15 并批准修复。将专用设备常驻 HTTP 控制服务从开机受限的 `dataSync` 改为 `specialUse`，声明对应权限和实际用途，移除不再使用的数据同步服务权限。
- 保留无界面自启、常驻通知、`9999` 监听和两分钟本地图片兜底；不修改运行时 Kotlin、HTTP 协议、线程、媒体处理、目标 SDK 或版本号。
- 新增读取实际源码 manifest 的单项回归测试；同步使用说明、系统限制与验证边界，避免把图片等待或捕获异常当作服务已可用。
- 本轮只完成本地修复与构建，不提交、不推送、不打标签、不发布。GitHub 上现有 beta.2 仍是旧包，本地修复包仍标识 `1.3.0-beta.2+19`。

### Testing

- 修复前执行 `./android/gradlew -p android :app:testDebugUnitTest --tests '*ControlServiceManifestTest'`，按预期失败：`expected:<specialUse> but was:<dataSync>`。
- 修复后 `./android/gradlew -p android :app:testDebugUnitTest` 通过；结果 XML 确认 31 项测试、0 失败、0 错误、0 跳过。
- `flutter analyze && flutter test` 通过，Flutter 测试 3 项。
- `flutter build apk --release && ./android/gradlew -p android :app:lintRelease` 通过；Android lint 保留既有升级、版本判断和视图/KTX 建议，没有为本轮关闭规则或扩展清理。
- 使用 `apkanalyzer manifest print` 校验最终 APK：目标 API 36；服务类型 `0x40000000`（specialUse）；通用及 special-use 权限、非空用途存在；不再包含 data-sync 权限。
- 编辑文件 IDE 诊断为空；`git diff --check` 通过；决策笔记目录、格式和归档校验通过。独立定向复核未发现可操作缺陷。
- 本机 Android 15 模拟器尝试以临时只读、无界面模式启动，但没有获得可用设备；`adb devices -l` 仍为空。未完成模拟器或 RK3588 真正重启、端口存活和延迟图片恢复验收；缺少现场退出日志，不能宣称所有异常退出已解决。
- 本地 APK：`build/app/outputs/flutter-apk/app-release.apk`；SHA-256：`a2539cec94bf8099abd3419848c783b0dc7bb121d494900383ac42dd8203b2e0`。

### Notes

- `android/app/src/main/AndroidManifest.xml`：修正服务类型、匹配权限与 special-use 用途说明。
- `android/app/src/test/kotlin/com/example/second_screen_viewer/ControlServiceManifestTest.kt`：验证实际服务声明、权限和非空用途，防止重新使用开机受限类型。
- `README.md`：增加 Android 15 修复范围和真机验证边界。
- `docs/background-service.md`：说明类型选择、系统限制、最终 APK 核验、同签名覆盖安装与异常取证步骤。
- `progress.md`：仅追加本轮修复、验证和回滚证据。
- `.trellis/spec/backend/image-fallback.md`：补充启动类型契约及声明与真实系统验证分离的要求。
- `.trellis/tasks/11-18-android15-boot-service/prd.md`：记录已批准范围、主机验收结果与待验证真机项。
- `.trellis/tasks/11-18-android15-boot-service/implement.jsonl`、`check.jsonl`：分别登记实施和复核所需规范与研究上下文。
- `.trellis/tasks/11-18-android15-boot-service/task.json`：记录新任务及实施状态；会话任务指针同步到此任务。
- `.trellis/tasks/11-18-android15-boot-service/research/foreground-service-type.md`：保存官方规则、方案取舍和验证限制。
- `.trellis/tasks/11-18-android15-boot-service/review.md`：保存独立复核结论和系统级验证缺口。
- `.agents/notes/implemented/bug-fix/2026-11-18-android15-boot-service.md`：记录本轮类型修复理由、拒绝方案与测试；该目录沿用既有忽略规则。
- `.agents/notes/implemented/feature/2026-11-18-delayed-cached-image.md`：仅互链启动配置修复，不改写原图片恢复决定。
- 源码回滚点为 `6946079232c52fe035b4b484a54a81833ad2df1b`。仅撤销本轮应用改动可执行 `git restore --source=6946079232c52fe035b4b484a54a81833ad2df1b -- android/app/src/main/AndroidManifest.xml README.md docs/background-service.md`，再删除本轮新增的 `android/app/src/test/kotlin/com/example/second_screen_viewer/ControlServiceManifestTest.kt` 并重新构建；此回滚会恢复已知 Android 15 启动限制，不应当作修复。进度历史和本地决策记录保留为证据。
- 安装验收需使用与现有应用相同的签名；若覆盖安装报签名不匹配，不要先卸载而丢失缓存及异常证据。下一步在 Android 15 真机确认通知和 `/api/status` 在两分钟前可用，再验证图片恢复。

## 2026-11-18 - Task: Android 15 开机兼容性修复预发布 v1.3.0-beta.3

### What was done

- 用户明确要求提交、推送和发版，并选择继续预发布，将应用版本同步为 `1.3.0-beta.3+20`。
- 本次发布包含 Android 15 开机服务类型、配套权限和用途说明修正，以及实际 manifest 的回归测试；保留无界面自启、`9999` 监听和两分钟图片恢复。
- 提交范围仅限应用修复、版本、测试和使用说明，不纳入本地 `.cursor/`、`.trellis/`、`AGENTS.md`、忽略的决策笔记或构建输出。
- 新增预发布标签 `v1.3.0-beta.3`，不覆盖历史标签；APK 由 GitHub 发布工作流生成，预发布不替换最新正式版 `v1.2.7`。

### Testing

- 版本同步后执行 `flutter analyze && flutter test && flutter build apk --release && ./android/gradlew -p android :app:testDebugUnitTest :app:lintRelease && git diff --check`：全部通过。
- Flutter 测试 3 项、原生测试 31 项通过；Android lint 未引入错误，保留既有无关警告。
- 最终本地 APK 经 `apkanalyzer manifest print` 断言：版本 `1.3.0-beta.3`、build 20、specialUse 类型及对应权限/用途说明有效，data-sync 权限不存在。
- 修改文件 IDE 诊断为空。本轮没有可用 Android 15/RK3588 设备，真实开机、长期空闲和缓存图片恢复仍待验收；不宣称已解决所有异常退出。
- GitHub CI、发布工作流和 APK 上传状态以推送后的远端运行结果为准，本段不以本地通过代替远端结果。

### Notes

- `pubspec.yaml`：版本更新到 `1.3.0-beta.3+20`。
- `lib/main.dart`：界面版本展示同步为 beta.3/build 20。
- `android/app/src/main/kotlin/com/example/second_screen_viewer/ControlHttpService.kt`：HTTP 状态版本展示同步为 beta.3/build 20。
- `android/app/src/main/AndroidManifest.xml`：包含上一轮已验证的 Android 15 服务类型与权限修复。
- `android/app/src/test/kotlin/com/example/second_screen_viewer/ControlServiceManifestTest.kt`：包含上一轮已验证的实际 manifest 回归测试。
- `README.md`：包含 Android 15 修复范围与验证边界说明。
- `docs/background-service.md`：明确修复从 beta.3 起提供，旧 beta.2 APK 不包含修复。
- `progress.md`：追加版本同步、验证和发布范围记录，保留上一轮本地修复历史。
- `.trellis/tasks/11-18-android15-boot-service/prd.md`：补充用户后续提交/推送/预发布授权；仅保留在本地。
- 回滚点为上一标签 `v1.3.0-beta.2`（提交 `6946079232c52fe035b4b484a54a81833ad2df1b`）；可在独立目录执行 `git worktree add --detach /tmp/second-screen-viewer-beta2 v1.3.0-beta.2` 后重建旧包，不改动当前工作区或已推送标签。旧包会恢复已知的 Android 15 启动限制，不建议用作 Android 15 故障修复。
