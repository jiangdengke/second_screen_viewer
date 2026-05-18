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

App 启动后会在安卓设备上监听 `9095` 端口，页面会显示当前控制地址。

播放视频：

```http
POST http://<安卓设备IP>:9095/robot_task/screen_control
Content-Type: application/json
```

```json
{
  "url": "http://192.168.112.89:8080/videos/22.mp4"
}
```

轮播图片：

```http
POST http://<安卓设备IP>:9095/robot_task/screen_control_Img_display
Content-Type: application/json
```

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

可选字段：

- `displayId`：指定副屏 ID，不传则使用页面当前选择的副屏
- `scaleMode`：`centerCrop`、`fitCenter`、`fitXY`、`centerInside`
- `rotationDegrees`：`0`、`90`、`180`、`270`

辅助接口：

- `GET /api/status`：查看当前状态
- `GET /api/displays`：查看屏幕列表
- `POST /api/hide`：停止副屏显示
