# Android 6 兼容特性分支

分支：`codex/android6-support`，基于 `main` 的 `be2d8e8`（0.0.1 / versionCode 16）。按用户要求作为独立特性，暂不合并主分支，不发布稳定 Release。

本分支测试版本：`0.0.2-android6`，内部版本号 17。最低系统为 Android 6.0 / API 23；compileSdk 与 targetSdk 保持 36，不通过降低目标版本掩盖兼容问题。

## 改动

- 最低系统从 API 26 降为 API 23；现有依赖可正常合并 Manifest，没有强制覆盖依赖的最低版本。
- 协议和二维码解码使用已经引入的 Bouncy Castle Base64，避免旧系统缺少 `java.util.Base64`。支持标准、URL-safe 和省略填充的输入，签名和内容协议保持原样。
- 封面任务队列改用旧系统已支持的带初始容量构造方法。
- Android 6 使用 NetworkRequest 监听网络；Android 7+ 保留默认网络监听。
- 安装更新时，Android 8+ 保留逐应用授权流程；Android 6/7 交给系统安装器处理未知来源安装设置，不调用 Android 8 API。
- Android 6 不读取 API 24 才提供的 `ApplicationInfo.minSdkVersion`，APK 的实际最低系统要求仍由系统安装器检查。保留包名、版本、签名、长度和 SHA256 校验。
- 更新信息支持 API 23 与 `android6.apk` 文件名，仍兼容旧 `android8.apk`；Android 6 不会下载要求 Android 8 的更新。
- 构建脚本根据最低系统生成对应文件名。分支推送会运行原生 CI，包括 Android 6 框架测试。

## 验证与边界

本地结果：101 项 core 测试通过，2 项 Robolectric API 23 框架测试通过；Debug 构建和 Android lint 通过（0 errors、33 warnings）。APK 元数据确认 minSdk 23、版本 0.0.2-android6 / 17，Android 6 v1 签名验证通过，与此前本地签名一致。

安装包须确认 minSdk 23、内部版本 17、沿用此前签名，并带 Android 6 可识别的 v1 签名。Android 6 框架测试使用 Robolectric API 23 的旧版图形模式，覆盖 Activity 启动/关闭与安装权限返回路径；它不等于实体电视解码与播放验收。

当前电脑未连接可用的 Android 6 设备，因此老电视的实际联网、视频解码、声音、遥控器、登录态加密保存以及系统安装器仍需在设备上验证。未关闭 HTTPS 证书校验，也未导入用户已有账号凭据。

检查命令：

```sh
./kotlin-tv/gradlew -p kotlin-tv :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

测试包：`outputs/hongguotv-kotlin-0.0.2-android6.apk`，不放入主分支稳定更新源。可覆盖此前本次会话的本地安装包；若以后希望稳定版覆盖这个测试版，需要使用高于 17 的内部版本号。
