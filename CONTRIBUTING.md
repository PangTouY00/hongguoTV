# 分支与合并

- `main`：已经完成并验证的功能基线。
- `feature-<功能名>` 或 `fix-<问题名>`：后续新增功能或修复的开发分支，从最新 `main` 创建。
- 功能完成后，推送开发分支并创建指向 `main` 的 Pull Request。说明变化及验证结果，通过检查后合并。
- 合并后拉取最新 `main`，下个功能另开分支，避免把多个未完成的功能混在一起。

示例：

```sh
git switch main
git pull --ff-only origin main
git switch -c feature-account-page
# 完成代码并提交后：
git push -u origin feature-account-page
```

原生客户端检查：

```sh
./kotlin-tv/gradlew -p kotlin-tv :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

需要 JDK 17 和 Android SDK 36。构建签名仅保存在本机或仓库 Secrets，不提交到 Git。每次分发 APK 增加内部 `versionCode`，沿用之前安装包的签名。

合并代码不会自动发布安装包。在线更新发布步骤见 [更新发布说明](kotlin-tv/AUTO_UPDATES.md)。当前配置提供 CI 和开发约定，尚未设置 GitHub 服务端的分支保护规则。

## Android 6 与 Android 8

当前 `main` 仍为 Android 8+ 稳定基线，`android6-support` 独立保存 Android 6+ 兼容修改。后续功能从最新 `main` 开功能分支，验证并经 PR 合入 `main`。需要同步功能时，在 `android6-support` 合并 `origin/main`，处理冲突、保留 API 23 兼容代码，再跑该分支的完整检查。不要用重置或复制目录覆盖兼容分支。

```sh
git fetch origin
git switch android6-support
git merge origin/main
# 处理冲突、检查旧系统兼容并验证后再提交和推送。
```

这个合并方向不会把 Android 6 特性合入主分支。使用本命令前要保证工作区干净，并先读取对应分支的 AGENTS.md。

### 版本与安装包命名

- App 显示版本表示功能版本，按 `0.0.1`、`0.0.2`、`0.0.3` 递增，不按 Android 系统版本建立两套独立编号。
- 两条线并存期间：稳定版可叫 `0.0.2`，Android 6 兼容测试可叫 `0.0.2-android6`。当前主分支是 0.0.1 / 16，兼容测试分支是 0.0.2-android6 / 17。
- APK 用系统标识区分，例如 `hongguotv-kotlin-0.0.2-android8.apk` 与 `hongguotv-kotlin-0.0.2-android6.apk`。
- 相同包名和签名的所有分发 APK 共用递增的 `versionCode`。已经分发到 17，下一次需要覆盖这些测试安装时必须使用 18 或更高，不能两条分支各自从 1 计数。
- 兼容测试包不发布到稳定更新源。当前更新器只有一个 latest 清单，不支持同时独立选择两条发布通道；长期维护两套发布需要另行实现多通道更新。

### 推荐的长期方案（尚未执行）

Android 6+ 表示最低 Android 6，包含 Android 8 及以上，并非只适用于 Android 6。[Android 最低系统说明](https://developer.android.com/guide/topics/manifest/uses-sdk-element#min)。

等 Android 6 与 Android 8 的真实设备都验证通过，再由用户明确决定是否将兼容分支合入 `main`。届时可以维护一套代码、一套版本和一个兼容 APK，避免长期双分支同步、重复发包和更新通道分流。

成本是新增功能需要照顾 API 23，并在新旧系统测试；使用新系统 API 时提供版本判断或替代实现，升级第三方库时核对最低系统要求。最低系统改为 6 不要求降低 compileSdk/targetSdk，也不需要禁用 Android 8 的可用能力。
