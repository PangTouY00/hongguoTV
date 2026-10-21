# 应用内更新与发布

当前仓库及更新源：`tsukiyo-zero/hongguoTV`。

App 从 `https://github.com/tsukiyo-zero/hongguoTV/releases/latest/download/update.json` 检查新版，再从同仓库指定标签下载 APK。当前统一版本为 0.0.2 / 内部版本 18，最低 Android 6.0，支持 Android 8+，沿用迁移后的更新地址；之前安装的 0.9.5 仍指向原作者更新源，需要手动覆盖安装当前版本一次。

旧的 Android 8 基线 0.0.1 只接受 minSdk 26 的更新清单，需要手动安装本统一版一次；Android 6 兼容测试版已接受 minSdk 23，可按正常更新流程升级。源码合并不会修改已安装 APK。

## 开发与发布分别进行

`main` 保存稳定功能，后续在 `feature-<功能名>` 或 `fix-<问题名>` 开发，经 PR 合并回主分支。原生 CI 运行 JVM 测试、Android lint 并生成开发 APK，供下载验证；CI Artifact 不会自动成为 Release，也不保证使用电视当前的签名。

本仓库已经移除原作者的跨仓库同步与发布模板，不再推送到 `N3urda/hongguoTV-updates`。目前没有启用自动发布或配置发布密钥；发布时由维护者上传附件。

## 发布一个更新

1. 在 `kotlin-tv/app/build.gradle.kts` 增加 `versionCode`，调整 `versionName`，保留包名 `com.hongguotv.nativeapp` 和原签名。当前显示版本为 0.0.2，内部版本号为 18；旧稳定版为 16，Android 6 测试版为 17。今后只维护一条版本线，下次可用 0.0.3 / 19。
2. 完成检查并构建 APK。当前电视安装的是本次开发使用的签名，分发包必须沿用它，不能直接换成其他开发机或 GitHub Actions 默认生成的 Debug 签名。不要提交签名文件或密码。
3. APK 命名如 `hongguotv-kotlin-0.0.2-android6.apk`。创建同仓库 Release，标签如 `kotlin-v0.0.2`；上传 APK 与 `update.json`，并正式发布、设为 Latest。草稿和预发布不作为此稳定更新源。
4. `update.json` 的版本、大小（字节）与 SHA256 必须与上传 APK 完全一致。例如下方结构中的 `size` 和 `sha256` 必须填入实际值，不能原样使用占位内容。

```json
{
  "schema": 1,
  "packageName": "com.hongguotv.nativeapp",
  "versionCode": 18,
  "versionName": "0.0.2",
  "minSdk": 23,
  "tag": "kotlin-v0.0.2",
  "apk": {
    "name": "hongguotv-kotlin-0.0.2-android6.apk",
    "size": 12345678,
    "sha256": "填写实际64位小写SHA256"
  },
  "notes": "本次更新说明"
}
```

APK 名称必须满足 `hongguotv-kotlin-版本标识-android8.apk`，标签必须以 `kotlin-v` 开头。文件名与标签允许数字、英文字母、点号、加号和短横线。维护发布时附上相应源码或源码链接，保留 GPL 许可。

## 电视上的行为

- 前台浏览时每 6 小时检查一次，首次进入延迟 8 秒；播放时不打扰。
- 默认自动检查并下载，设置里可关闭，也可以手动检查。
- 下载后核对大小、SHA256、实际包名、版本及签名，确认后调用系统安装，保留账号与本机记录。
- 普通电视仍需允许安装未知来源应用并确认安装，不能静默安装。
- 没有发布过 Release 或缺少 `update.json` 时提示暂无可用更新。网络无法访问 GitHub 时不会影响现有播放功能。

仓库必须能让电视匿名访问公开 Release；App 不保存 GitHub 令牌，不支持从私有仓库下载更新。
