# 应用内更新与发布

更新源为 GitCode 本仓库公开 Releases：`tsukiyo-zero/hongguoTV`。

电视先请求 `https://api.gitcode.com/api/v5/repos/tsukiyo-zero/hongguoTV/releases/latest?type=latest`，获取最新正式发行版标签，再读取该标签附件中的 `update.json`，比较内部版本号，并从 GitCode 根据清单的标签和附件名下载 APK。清单标签必须与接口返回标签一致。仅允许 HTTPS 和 GitCode 官方 API、站点及 file-cdn.gitcode.com 下载域名。此流程由 App 实现；最终覆盖安装由安卓系统执行。

## 发布流程

1. 在 `app/build.gradle.kts` 调整 versionName，增加 versionCode。正式发布内部版本号必须超过上一正式版本；调试过程中不递增，使用文件名区分。允许跳过未公开的版本号，无需补发每个中间版本。环境变量可覆盖默认版本，打包后核对 APK 实际元数据。
2. 使用同一包名 `com.hongguotv.nativeapp` 和现有签名构建，检查最低系统、版本和签名。不要换用新 Debug 签名或将签名密钥提交到仓库。构建方法见 [README](README.md)。
3. 执行 `./scripts/build-kotlin.sh`，自动在 `outputs/releases/<tag>/` 生成配套 APK 和 `update.json`。清单从实际 APK 提取版本、包名、最低系统，并计算大小和 SHA256；环境变量覆盖后的版本也会正确读取。直接运行 Gradle 或 IDEA 的普通构建任务仍只生成 APK。
4. 在 GitCode 创建同仓库 Release。Target 选择实际打包所用的提交或分支；APK 若来自尚未合并的功能分支，不能误选 main。
5. 上传配套 APK 和 `update.json`，正式发布并设为 Latest。草稿和 Pre-release 不用于稳定在线更新。
6. 在较旧版本的设备上检查更新，核对下载与覆盖安装。推送代码、合并 main 或生成 Actions Artifact 不等于发布在线更新。

## 自动生成参数

默认从 APK 的三段版本号生成标签，例如 `0.0.8-search-input` 对应 `kotlin-v0.0.8`。发布时 GitCode 标签必须与清单一致。脚本不会自动增加版本号。

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
- 仓库和 Release 必须允许电视匿名访问；App 不保存 GitCode 凭据，不支持从私有仓库下载。网络不可达不影响已有播放功能。
- 仍指向上游更新源的旧 APK，或只接受 minSdk 26 的早期 APK，可能需要手动覆盖一次本仓库的 Android 6+ 版本。

本仓库不自动发布 Release；发布由维护者明确执行。在线更新仅使用本仓库，不向上游更新仓库同步。

GitHub 可继续作为源码仓库及备用发布页面；同步 Git 代码不会同步 Release 附件。每次须在 GitCode 上传同一套 APK 和 update.json，并标记 Latest。旧版仍访问 GitHub，需通过旧渠道升级或手动覆盖安装一次新版本，之后才能使用 GitCode 更新。

## 双平台脚本发布

脚本只发布 TV 的版本标签，不修改 Mac `mac-stable`，不自动升版本、建标签或合并 PR。先按上述流程构建、检查签名，推送对应标签，并等待 GitCode 镜像同步。使用 Python 3.9+，无需安装 gh 或第三方 Python 包。

首次在自己的终端执行（不要将 Token 发到聊天或写进命令行）：

```sh
python3 scripts/publish-tv-release.py --configure
```

脚本依次隐藏输入 GitHub 与 GitCode Token，保存到 `~/.config/hongguotv/publish.json`，权限为 `0600`，不在仓库内。此文件为本机明文私密配置，不是加密钥匙串；不应同步或分享。也可通过 `GITHUB_TOKEN` / `GITCODE_TOKEN` 环境变量提供，环境变量优先。GitHub 使用仅授权本仓库、具有 Contents 读写权限的 Token；GitCode 使用具有本仓库发行版及附件读写权限的个人 Token。Token 需要由维护者自行在平台创建，脚本不会申请或扩大权限。

生成文件时指定更新说明，prepare 脚本会自动保存 APK、update.json 和更新说明.md 三个文件：

```sh
python3 scripts/prepare-kotlin-release.py \
  --apk kotlin-tv/app/build/outputs/apk/release/app-release.apk \
  --aapt "$ANDROID_HOME/build-tools/36.0.0/aapt" \
  --output outputs/releases \
  --tag kotlin-v0.1.4 \
  --notes-file kotlin-tv/RELEASE_NOTES.md
```

本地检查（默认模式，不读取凭据、不发起网络请求）：

```sh
python3 scripts/publish-tv-release.py --directory outputs/releases/kotlin-v0.1.4
```

明确发布到两个平台：

```sh
python3 scripts/publish-tv-release.py --directory outputs/releases/kotlin-v0.1.4 --publish
```

脚本校验清单与 APK 的大小、SHA256、包名、minSdk 和说明一致性，然后在 GitHub 建草稿、GitCode 建预发布，上传 APK、说明、最后上传清单。通过服务端 SHA256 或匿名下载重新计算摘要校验附件后，再将两边设为正式 Latest，并检查 Latest 标签。GitCode 没有此脚本使用的草稿 API，上传期间可见预发布；稳定更新不会选取预发布。

两平台发布不是原子操作，任一步失败时终端会非零退出，已上传文件会保留；可重跑同一命令继续。同名相同内容会跳过，不同内容会报错，脚本不删除或替换已有附件。若本标签已经正式上线，补附件也是可见操作；请勿把不同构建复用为同一个正式标签。签名连续性与 APK 内部版本检查仍按前述发布流程执行，单纯 SHA256 校验不代替这些检查。

接口依据：[GitHub Release API](https://docs.github.com/en/rest/releases/releases)、[GitHub 附件 API](https://docs.github.com/en/rest/releases/assets)、[GitCode 创建发行版](https://docs.gitcode.com/docs/apis/post-api-v-5-repos-owner-repo-releases/)、[GitCode 附件上传地址](https://docs.gitcode.com/docs/apis/get-api-v-5-repos-owner-repo-releases-tag-upload-url/)、[GitCode 更新发行版](https://docs.gitcode.com/docs/apis/patch-api-v-5-repos-owner-repo-releases-tag/)。真实写入需配置凭据后由维护者执行；离线测试不会触碰正式 Release。
