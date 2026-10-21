# Android 6+ 统一兼容基线

Android 6 兼容开发原在 `android6-support`，用户确认可以使用后，已明确决定将其合入 `main`，以后从主分支开功能分支，不再长期维护 Android 6/8 两条产品线。

统一版本：`0.0.2` / 内部版本号 18；最低系统 Android 6.0 / API 23，同一个 APK 支持 Android 8 及以上。compileSdk/targetSdk 保持 36。原兼容测试版 `0.0.2-android6` / 17 作为开发历史保留。

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

统一版验证：101 项 core 测试与 4 项 Android 框架测试通过；后者分别在 API 23、API 26 启动和关闭 Activity，并处理安装权限返回。Debug 构建、lint 通过（0 errors、33 warnings）。APK 实际元数据和 Android 6 v1 签名经工具核对。

用户已确认原 Android 6 测试版可以使用。统一版框架测试覆盖 API 23 与 API 26 的 Activity 启动/关闭及更新权限返回路径，签名需同时通过 Android 6 v1 验证并与此前安装包一致。

框架测试不等于所有电视芯片的完整验收。特定设备的实际解码、声音、遥控器和长期稳定性仍按设备反馈核对；未关闭 HTTPS 证书校验。

检查命令：

```sh
./kotlin-tv/gradlew -p kotlin-tv :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

统一安装包：`outputs/hongguotv-kotlin-0.0.2-android6.apk`。后缀表示最低支持 Android 6，Android 8 用户使用同一个文件。内部分发编号为 18，可覆盖此前稳定版 16 与兼容测试版 17。
