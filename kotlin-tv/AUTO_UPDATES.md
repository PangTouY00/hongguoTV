# 应用内更新与发布

更新源为本仓库公开 Releases：`tsukiyo-zero/hongguoTV`。

电视读取 `https://github.com/tsukiyo-zero/hongguoTV/releases/latest/download/update.json`，比较内部版本号，再根据清单的标签和附件名下载 APK。此流程由 App 实现；最终覆盖安装由安卓系统执行。

## 发布流程

1. 在 `app/build.gradle.kts` 调整 versionName，增加 versionCode。内部版本号必须超过此前所有分发 APK，包含内部测试包。允许跳过未公开的版本号，无需补发每个中间版本。环境变量可覆盖默认版本，打包后核对 APK 实际元数据。
2. 使用同一包名 `com.hongguotv.nativeapp` 和现有签名构建，检查最低系统、版本和签名。不要换用新 Debug 签名或将签名密钥提交到仓库。构建方法见 [README](README.md)。
3. 执行 `./scripts/build-kotlin.sh`，自动在 `outputs/releases/<tag>/` 生成配套 APK 和 `update.json`。清单从实际 APK 提取版本、包名、最低系统，并计算大小和 SHA256；环境变量覆盖后的版本也会正确读取。直接运行 Gradle 或 IDEA 的普通构建任务仍只生成 APK。
4. 创建同仓库 Release。Target 选择实际打包所用的提交或分支；APK 若来自尚未合并的功能分支，不能误选 main。
5. 上传配套 APK 和 `update.json`，正式发布并设为 Latest。草稿和 Pre-release 不用于稳定在线更新。
6. 在较旧版本的设备上检查更新，核对下载与覆盖安装。推送代码、合并 main 或生成 Actions Artifact 不等于发布在线更新。

## 自动生成参数

默认从 APK 的三段版本号生成标签，例如 `0.0.8-search-input` 对应 `kotlin-v0.0.8`。发布时 GitHub 标签必须与清单一致。脚本不会自动增加版本号。

可以指定标签及 UTF-8 更新说明文件（最多 1500 字；不指定时说明为空）：

```sh
HONGGUOTV_RELEASE_TAG=kotlin-v0.0.8 \
HONGGUOTV_RELEASE_NOTES_FILE=/绝对路径/更新说明.txt \
./scripts/build-kotlin.sh
```

默认读取 Android SDK 的 aapt，也可用 `HONGGUOTV_AAPT` 指定工具路径。已有 APK 可以单独生成清单：

```sh
python3 scripts/prepare-kotlin-release.py \
  --apk /绝对路径/app-release.apk \
  --aapt "$ANDROID_HOME/build-tools/36.0.0/aapt" \
  --output outputs/releases
```

同一标签再次生成会覆盖该目录的 `update.json`；上传前确认附件名和清单一致。生成清单不代表验证了签名连续性，发布前仍需核对签名和已分发的最高 versionCode。

## 清单格式

以下只展示结构。版本、大小、SHA256 必须来自实际 APK，示例值不得直接发布。

```json
{
  "schema": 1,
  "packageName": "com.hongguotv.nativeapp",
  "versionCode": 23,
  "versionName": "0.0.7-search-input",
  "minSdk": 23,
  "tag": "kotlin-v0.0.7",
  "apk": {
    "name": "hongguotv-kotlin-0.0.7-search-input-android6.apk",
    "size": 12345678,
    "sha256": "填写实际64位小写SHA256"
  },
  "notes": "面向用户的本次更新说明"
}
```

- versionName、versionCode 必须与 APK 内部版本完全一致，不能只改文件名或清单。
- tag 必须以 `kotlin-v` 开头，且与 Release 标签一致。
- APK 名称必须符合 `hongguotv-kotlin-版本标识-android6.apk`；仍兼容 `-android8.apk`。后缀表示最低系统，不是两条独立产品线。文件名和标签允许数字、英文字母、点号、加号和短横线。
- apk.name 与实际附件名一致；size 是字节数，sha256 是实际文件的 SHA256 小写十六进制值。
- 发布时同时提供对应源码或源码链接，保留 GPL 许可和第三方声明。

## 设备行为与边界

- 前台浏览时定期检查，播放时不打扰；默认自动检查下载，设置中可关闭或手动检查。
- 下载后核对大小、SHA256、实际包名、版本及签名，再调用系统安装器。
- 普通电视需要确认安装，并按系统要求允许此应用安装 APK，不能静默安装。
- 相同签名和包名允许覆盖升级并保留数据。签名不匹配时不要通过卸载旧版来强行升级，否则可能丢失本机记录和登录态。
- 仓库和 Release 必须允许电视匿名访问；App 不保存 GitHub 凭据，不支持从私有仓库下载。网络不可达不影响已有播放功能。
- 仍指向上游更新源的旧 APK，或只接受 minSdk 26 的早期 APK，可能需要手动覆盖一次本仓库的 Android 6+ 版本。

本仓库不自动发布 Release；发布由维护者明确执行。在线更新仅使用本仓库，不向上游更新仓库同步。
