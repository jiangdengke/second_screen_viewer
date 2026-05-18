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

## HTTP 控制接口

App 启动后会在安卓设备上监听 `9999` 端口，页面会显示当前控制地址：

```text
http://<安卓设备IP>:9999
```

例如安卓设备 IP 是 `192.168.112.90`，后端就调用：

```text
http://192.168.112.90:9999
```

页面日志会记录：

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

- `displayId`：指定副屏 ID，不传则使用页面当前选择的副屏
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
