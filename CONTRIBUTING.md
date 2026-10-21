# 分支与合并

- `main`：已经完成并验证的功能基线。
- `codex/<功能名>`：后续新增功能或修复的开发分支，从最新 `main` 创建。
- 功能完成后，推送开发分支并创建指向 `main` 的 Pull Request。说明变化及验证结果，通过检查后合并。
- 合并后拉取最新 `main`，下个功能另开分支，避免把多个未完成的功能混在一起。

示例：

```sh
git switch main
git pull --ff-only origin main
git switch -c codex/account-page
# 完成代码并提交后：
git push -u origin codex/account-page
```

原生客户端检查：

```sh
./kotlin-tv/gradlew -p kotlin-tv :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

需要 JDK 17 和 Android SDK 36。构建签名仅保存在本机或仓库 Secrets，不提交到 Git。每次分发 APK 增加内部 `versionCode`，沿用之前安装包的签名。

合并代码不会自动发布安装包。在线更新发布步骤见 [更新发布说明](kotlin-tv/AUTO_UPDATES.md)。当前配置提供 CI 和开发约定，尚未设置 GitHub 服务端的分支保护规则。
