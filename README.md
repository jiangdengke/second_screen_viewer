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
