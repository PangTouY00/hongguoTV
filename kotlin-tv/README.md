# 红果 TV 原生客户端

工程：Kotlin + Android View + Media3。最低 Android 6.0 / API 23；compileSdk、targetSdk 为 36。包名 `com.hongguotv.nativeapp`，与旧 React Native 应用 `com.hongguotv` 的数据独立。

功能介绍见 [项目主页](../README.md)，工作约定见 [开发流程](../CONTRIBUTING.md)。

## 构建环境

需要 JDK 17、Android SDK Platform 36 和对应 Build Tools。设置 `JAVA_HOME`、`ANDROID_HOME`，或在本地 `local.properties` 配置 SDK 路径。可以用 Android Studio，或在支持 Android/Gradle 的 IDEA 中打开本目录；也可直接使用终端。

以下命令均从仓库根目录执行：

```sh
# 回归检查和开发 APK
./kotlin-tv/gradlew -p kotlin-tv :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug

# 只生成开发 APK
./kotlin-tv/gradlew -p kotlin-tv :app:assembleDebug
```

默认产物：`kotlin-tv/app/build/outputs/apk/debug/app-debug.apk`。

## 版本与签名

修改 `app/build.gradle.kts` 中 `versionCode`、`versionName` 后重新构建。环境变量 `HONGGUOTV_VERSION_CODE`、`HONGGUOTV_VERSION_NAME` 会覆盖文件中的默认值。正式上线时递增版本号与 versionCode，编译调试期间保持不变，测试包通过文件名区分；当前正式版为 0.1.3（versionCode 44），后续正式发布再递增。分支名、APK 文件名或 Git 标签不会自动改变 APK 内部版本。

开发 APK 使用本机 Debug 签名。电视已经安装的版本只能由相同包名、相同签名的新版覆盖；换电脑或改用其他签名可能无法覆盖安装。不要删除、替换现有签名密钥，不要将密钥或密码提交到仓库。

Release 构建读取 `kotlin-tv/keystore.properties`（已忽略）或对应环境变量：

| 本地配置字段 | 环境变量 |
| --- | --- |
| storeFile | HONGGUOTV_KEYSTORE |
| storePassword | HONGGUOTV_STORE_PASSWORD |
| keyAlias | HONGGUOTV_KEY_ALIAS |
| keyPassword | HONGGUOTV_KEY_PASSWORD |

`storeFile` 建议填写已有密钥的绝对路径。配置正确且确认签名连续后，可执行：

```sh
./scripts/build-kotlin.sh
```

脚本执行测试、Release lint、Release 构建，从实际 APK 读取版本和最低系统，计算大小与 SHA256，自动在 `outputs/releases/<tag>/` 生成配套 APK 和 `update.json`。需要 Python 3，以及 Android SDK 中的 aapt。它不会推送代码或创建 GitHub Release。发布完整步骤见 [AUTO_UPDATES.md](AUTO_UPDATES.md)。

## 验证边界

框架回归覆盖 API 23、API 26。具体电视的解码、声音、遥控器和实际账号同步仍需要设备验证。由维护者改代码、自动化检查和打包，用户安装测试并反馈截图；不自动操作用户模拟器。

竖屏与横屏视频都按设备解码能力选流：启动时探测 AVC/HEVC 解码器上限（logcat 搜索 `Decoder capabilities`），选流优先跳过盒子声明解不了的档位，并在播放前用 MP4 头里的实际编码尺寸复核，超出上限时自动沿清晰度阶梯降档（HUD 显示"自动降清"）。部分盒子会虚报解码能力（声明可解、实际失败）：此类运行时失败会按"编码+编码尺寸"记入持久化的设备能力记录（跨重启、跨剧集生效，竖屏失败不影响横屏），并当场自动降档重试（每集至多 2 次，无需手动切换），logcat 出现 `Learned decoder failure` / `Auto downgrading`。全部档位都不可解时保持原"兼容资源"行为交由系统尝试；播放失败面板的"尝试 xP"只在确有更低档位时出现；"播放设置 → 重置设备能力记录"可清除学习结果（应对盒子固件升级）。

播放中持续监测视频帧处理偏移（logcat 搜索 `Video frame offset` / `Resync after audio/video drift`）：平均偏移持续超过 150ms 时自动对当前位置做一次无感重同步（间隔至少 30 秒），用于消除随播放时长累积、倍速下更快的音画不同步。

排查播放、账户或旧系统兼容问题前，先读 [开发排查笔记](../docs/DEVELOPMENT_NOTES.md)。第三方来源与许可见 [NOTICE.md](NOTICE.md)。

评论与弹幕调试入口和验收范围见 [SOCIAL.md](SOCIAL.md)。
