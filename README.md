# second_screen_viewer

RK3588 Android 副屏媒体显示工具，用于在主屏正常操作时，把图片或视频投放到指定副屏。

## GitHub Actions 打包

普通提交会运行 `flutter analyze` 和 `flutter test`。

创建并推送 `v*` tag 后，会自动编译 release APK，并上传到 GitHub Release：

```bash
git tag v1.1.2
git push origin v1.1.2
```

也可以在 GitHub Actions 页面手动运行 `Android APK Release` workflow，并填写 release tag。

## 开机后台运行

App 安装后会注册开机广播接收器。设备开机完成或应用被替换安装后，系统会启动 Android 前台服务 `ControlHttpService`，服务在后台监听 `9999` 端口，不会自动打开主界面。

注意：为了长期后台监听端口，Android 会显示一条“副屏控制服务运行中”的常驻通知。这是前台服务机制要求。

开机初始阶段只启动后台服务和监听 `9999` 端口，不读取、解码媒体，也不创建播放窗口。真正收到 `BOOT_COMPLETED` 后等待两分钟；如果期间没有图片、视频、本地播放或停止指令，服务会尝试从本机持久缓存显示最后一张成功播放的 HTTP 图片一次，不联网、不打开主界面、不恢复视频或整个轮播。

新媒体指令优先：即使图片请求的 JSON、参数或下载失败，也会取消本次开机兜底；本地显示、隐藏和长按关闭同样取消。查询状态、查询屏幕和单纯打开页面不会取消或重置等待。重复启动服务、进程重建沿用本次开机的原截止时间与取消结果；仅打开 App 或更新安装不会新建等待。

正常 HTTP 图片轮播仍然保留，每张成功显示的图片都可成为下次开机的单图兜底。替换图片先在后台准备可用候选，成功挂载窗口后才更新持久缓存；下载、解码或窗口失败保留当前画面和旧缓存。持久缓存放在 App 私有 `filesDir/last_good_image/`，与可清理的临时下载分开。目标屏不存在、所需悬浮窗权限缺失、缓存损坏或无法读取开机标识时只记录并跳过，不打开设置页。

安装支持此功能的版本后，需先成功显示一张上游 HTTP 图片，才有下次重启使用的持久缓存。旧版临时缓存不自动迁移，本地文件选择不写入此缓存；没有缓存时，两分钟后继续保持空闲并监听端口。

这是显示连续性功能，不表示已修复此前尚未确认原因的空闲进程退出。RK3588 真机的开机广播、屏幕 ID 稳定性、权限、休眠迟到和突然断电仍需现场验证，详见 [后台服务说明](docs/background-service.md)。

## 选择屏幕

页面会列出检测到的所有屏幕，包括 Android 系统默认屏（display 0），每块屏都可以选择。页面上选择的屏幕会保存下来，HTTP 请求不传 `displayId` 时就用它；从未选择过时，使用第一块非系统默认屏。

播放到系统默认屏（display 0）或其他不支持 Presentation 的屏时，App 改用悬浮窗显示，需要先开启“显示在其他应用上层”权限。页面上第一次播放到这类屏时会自动打开权限设置页；也可以用 adb 授权：

```bash
adb shell appops set com.example.second_screen_viewer SYSTEM_ALERT_WINDOW allow
```

悬浮窗会盖住同一块屏上的桌面和 App 界面。按住画面 3 秒可以关闭显示，也可以调用停止接口。

## HTTP 控制接口

后台控制服务会在安卓设备上监听 `9999` 端口，打开 App 页面时也会显示当前控制地址：

```text
http://<安卓设备IP>:9999
```

例如安卓设备 IP 是 `192.168.112.90`，后端就调用：

```text
http://192.168.112.90:9999
```

Android 日志会记录：

- HTTP 服务启动成功和监听地址
- 每次 HTTP 接口被调用的路径和来源 IP
- 视频播放参数、图片轮播参数、停止显示请求

### 播放视频

接口：

```http
POST http://<安卓设备IP>:9999/robot_task/screen_control
Content-Type: application/json
```

最小请求：

```json
{
  "url": "http://192.168.112.89:8080/videos/22.mp4"
}
```

带显示参数：

```json
{
  "url": "http://192.168.112.89:8080/videos/22.mp4",
  "displayId": 3,
  "scaleMode": "centerCrop",
  "rotationDegrees": 90
}
```

### 轮播图片

接口：

```http
POST http://<安卓设备IP>:9999/robot_task/screen_control_Img_display
Content-Type: application/json
```

最小请求：

```json
{
  "time_sleep": "2000",
  "url": [
    "http://192.168.112.89:8080/picture/test.png",
    "http://192.168.112.89:8080/picture/test1.png",
    "http://192.168.112.89:8080/picture/test2.png"
  ]
}
```

带显示参数：

```json
{
  "time_sleep": 2000,
  "url": [
    "http://192.168.112.89:8080/picture/test.png",
    "http://192.168.112.89:8080/picture/test1.png",
    "http://192.168.112.89:8080/picture/test2.png"
  ],
  "displayId": 3,
  "scaleMode": "fitXY",
  "rotationDegrees": 90
}
```

### 参数说明

- `displayId`：指定目标屏幕 ID，可以是任意检测到的屏幕；不传则使用页面上选择的屏幕，从未选择过时使用第一块非系统默认屏
- `scaleMode`：显示缩放模式，不传默认 `centerCrop`
- `rotationDegrees`：旋转/翻转角度，不传默认 `0`
- `time_sleep`：图片轮播间隔，单位毫秒，最小按 `500` 处理
- `url`：视频接口传字符串；图片轮播接口传字符串数组，也兼容单个字符串

`scaleMode` 可选值：

- `centerCrop`：铺满屏幕，保持比例，超出部分裁切
- `fitCenter`：完整显示，保持比例，可能有黑边
- `fitXY`：强制拉伸铺满，不保持比例
- `centerInside`：居中显示，小图不放大，大图按比例缩小

`rotationDegrees` 可选值：

- `0`：不旋转
- `90`：顺时针旋转 90 度
- `180`：旋转 180 度
- `270`：逆时针旋转 90 度

### 辅助接口

查看当前状态：

```http
GET http://<安卓设备IP>:9999/api/status
```

查看屏幕列表：

```http
GET http://<安卓设备IP>:9999/api/displays
```

停止副屏显示：

```http
POST http://<安卓设备IP>:9999/api/hide
```

兼容停止接口：

```http
POST http://<安卓设备IP>:9999/robot_task/screen_stop
```

### 后端调用示例

JavaScript：

```js
await fetch('http://192.168.112.90:9999/robot_task/screen_control', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    url: 'http://192.168.112.89:8080/videos/22.mp4',
    scaleMode: 'centerCrop',
    rotationDegrees: 90
  })
});
```

curl：

```bash
curl -X POST 'http://192.168.112.90:9999/robot_task/screen_control_Img_display' \
  -H 'Content-Type: application/json' \
  -d '{
    "time_sleep": 2000,
    "url": [
      "http://192.168.112.89:8080/picture/test.png",
      "http://192.168.112.89:8080/picture/test1.png"
    ],
    "scaleMode": "fitXY",
    "rotationDegrees": 90
  }'
```

注意：后端提供的图片/视频 URL 必须能被安卓设备直接访问。
