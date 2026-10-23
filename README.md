# hongguoTV

非官方红果短剧客户端，主要维护 Android TV 版，同时提供原生 Mac 客户端 hongguoMac。

当前使用 `kotlin-tv/` 中的 Kotlin 原生客户端，支持 **Android 6.0 及以上**。安装一个 APK 即可使用，无需部署 Node 或 Docker。稳定代码在 `main`；实际版本以构建配置和发布附件为准。

## Android TV 下载与安装

首次安装请下载 **v0.1.1 或更高版本**，之后可在 App 内更新。更新源已切换至 GitCode，后续安装包仅在 GitCode 发布（如果我不偷懒的话就都发），GitHub 继续维护源码。低于 v0.1.1 的版本需手动覆盖安装一次。

[GitHub v0.1.1 安装入口](https://github.com/tsukiyo-zero/hongguoTV/releases/tag/kotlin-v0.1.1) · [GitCode 最新版与发布记录](https://gitcode.com/tsukiyo-zero/hongguoTV/releases)

[构建说明](kotlin-tv/README.md) · [在线更新与发布](kotlin-tv/AUTO_UPDATES.md) · [开发流程](CONTRIBUTING.md)

## 功能

- 首页官方推荐流与漫剧/真人剧第一集试播（预缓冲、上下换剧、查看全集）、真人剧/漫剧/AI 剧分类找剧与题材筛选、全部剧集搜索、综合/真人剧/漫剧/AI 剧热播榜与榜内题材筛选。
- 遥控器操作、选集、倍速、清晰度、自动连播及画面模式。
- 本机收藏、本机观看历史和断点续播。
- 红果扫码登录、账户收藏增删与读取、账户观看历史及云端续播；收藏与历史均按本机/账户分设子页签。
- 账户预约，区分已上线、待上线，并显示上线提示。
- 剧集详情中的相关作品、前后季跳转和下一季预约入口。
- 局域网手机输入、应用内检查更新。

本机记录与账户云端记录分开保存。退出或切换账号会清除旧账号的云端列表，不删除本机历史。账号能力及尚需核对的边界见 [账号同步说明](docs/ACCOUNT_SYNC.md)。

TV 0.1.3 新增本集评论连续加载和真实时间轴弹幕，优化详情排版、遥控焦点及首页首读效率。

## Mac 客户端 hongguoMac

支持 **Apple Silicon（M 系列芯片）、macOS 13 及以上**。解压 ZIP，将 `hongguoMac.app` 拖入「应用程序」即可使用，自带运行环境，无需另外安装 Java。

[Mac 固定发布入口](https://gitcode.com/tsukiyo-zero/hongguoTV/releases/mac-stable) · [Mac 构建、更新与使用说明](desktop-mac/README.md)

- 首页推荐、漫剧和真人剧试播，支持切换作品、换批与下一部首集预加载。
- 搜索、分类找剧、排行榜、相关作品与前后季浏览。
- 红果扫码登录、账户收藏、观看历史、预约和正式观看进度同步；另有本机收藏与历史。
- 全屏播放、选集、清晰度、倍速、自动下一集及断点续播；全屏控制栏空闲后隐藏。
- 上下方向键切换试播作品，空格播放或暂停，左右方向键快退或快进 10 秒。
- 本集评论支持下滑连续加载与手动加载更多；整剧剧评提供官网公开节选。支持筛选、排序、复制及打开官方页面。

Mac 登录态保存在本机钥匙串，不随安装包分发。当前使用临时签名，尚未经过 Apple 公证；本集评论支持连续加载；整剧剧评目前仅提供官网节选，发送和回复尚未接入。另支持按视频时间同步的真实弹幕，可关闭及失败重试。

### Mac 更新与开发

Mac 与 TV **分别管理版本号和更新清单**。Mac 固定读取 GitCode 同仓库 `mac-stable` Release 中的 `mac-update.json`；每次发布上传新 ZIP 并替换该清单，保持标签不变，**不要将 Mac Release 设为 Latest／最新版**，最新版保留给 TV。Mac 目前下载并校验更新包后，需要手动解压替换应用。

已验证的 Mac 版本纳入 `main`。后续 Mac 开发继续在长期分支 **`feature-mac-client`** 进行，测试通过后按需通过 PR 合并到 `main`，不随每个 Mac 功能另开分支。TV 仍是主要维护版本，Mac 源码位于 `desktop-mac/`。

## 维护资料

- [接口、兼容性与排查经验](docs/DEVELOPMENT_NOTES.md)
- [相关作品和预约实现](kotlin-tv/RELATED_WORKS.md)
- [第三方声明](kotlin-tv/NOTICE.md)

`android/`、`ios/`、`src/`、`server/` 等保留旧 React Native 与 Node 服务实现，当前原生 APK 不依赖它们。仅维护旧服务时参阅 [旧服务 API](docs/API.md) 和 [旧服务 Docker 部署](docs/DOCKER.md)。

## 许可证

本项目与红果官方无关联，非官方接口可能随平台变化失效。项目以 [GPL-3.0](LICENSE) 发布；版权及第三方许可证声明予以保留，见 [原生第三方声明](kotlin-tv/NOTICE.md) 和 [旧服务第三方声明](server/vendor/NOTICE.md)。

旧交付报告和历史设计文档已从当前目录清理，需要追溯时可查看 Git 历史。
