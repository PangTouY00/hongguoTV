# 开发分支、版本与兼容性

`main` 为统一的 Android 6.0+ 稳定基线，同一个 APK 覆盖 Android 6、Android 8 及更高系统。`android6-support` 保留已完成的兼容开发记录，后续不再作为另一套产品持续维护。

## 新功能与修复

- 从最新 `main` 创建短期分支，功能用 `feature-功能名`，修复用 `fix-问题名`，不加 `codex/` 等外层分组。
- 在分支完成开发和验证，创建指向 `main` 的 PR，通过检查后合并。
- 每个分支尽量只处理一个主题；下一个功能从更新后的 `main` 另开分支。
- 源码目录仍为 `kotlin-tv/`；Git 分支名称中的前缀不属于源码文件夹。

```sh
git switch main
git pull --ff-only origin main
git switch -c feature-account-page
# 开发、验证并提交后：
git push -u origin feature-account-page
```

## 单一版本线

当前基线：显示版本 `0.0.2`，内部 `versionCode=18`。下次发布可用 `0.0.3 / 19`，继续按实际发布顺序递增。

- 显示版本表示功能版本，不再分别命名 Android 6 版和 Android 8 版。
- 内部版本号必须比此前所有分发 APK 高，包括旧兼容测试版的 17。
- APK 如 `hongguotv-kotlin-0.0.2-android6.apk`，后缀表示最低 Android 6，同一个 APK 也给 Android 8+ 使用。
- 保留包名和签名，确保覆盖安装时保留账号和本机记录。签名文件、密码不提交到 Git。

合并代码不等于发布 APK；Release 和更新清单步骤见 [更新发布说明](kotlin-tv/AUTO_UPDATES.md)。目前未配置 GitHub 服务端分支保护规则。

## 兼容性检查

保留 `minSdk=23`，compileSdk 与 targetSdk 仍为 36。使用新 API 时做系统版本判断或提供替代实现；升级库前核对最低系统要求，并检查新旧系统上的行为。[最低系统与目标系统说明](https://developer.android.com/guide/topics/manifest/uses-sdk-element)。

```sh
./kotlin-tv/gradlew -p kotlin-tv :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

需要 JDK 17 和 Android SDK 36。框架测试覆盖 API 23 与 API 26；真实电视的芯片解码、音频和遥控器需对应设备验证。支持旧系统的主要成本是兼容检查和更多设备测试，并不要求禁用新系统能力或降低目标 SDK。
