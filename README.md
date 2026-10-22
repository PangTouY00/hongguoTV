# hongguoTV

非官方红果短剧 Android TV 客户端，为电视和遥控器操作提供适配。

当前使用 `kotlin-tv/` 中的 Kotlin 原生客户端，支持 **Android 6.0 及以上**。安装一个 APK 即可使用，无需部署 Node 或 Docker。稳定代码在 `main`；实际版本以构建配置和发布附件为准。

## 下载与安装

首次安装请下载 **v0.1.1 或更高版本**，之后可在 App 内更新。更新源已切换至 GitCode，后续安装包仅在 GitCode 发布，GitHub 继续维护源码。低于 v0.1.1 的版本需手动覆盖安装一次。

[GitHub v0.1.1 安装入口](https://github.com/tsukiyo-zero/hongguoTV/releases/tag/kotlin-v0.1.1) · [GitCode 最新版与发布记录](https://gitcode.com/tsukiyo-zero/hongguoTV/releases)

[构建说明](kotlin-tv/README.md) · [在线更新与发布](kotlin-tv/AUTO_UPDATES.md) · [开发流程](CONTRIBUTING.md)

## 功能

- 首页官方推荐流与漫剧/真人剧第一集试播（预缓冲、上下换剧、查看全集）、真人剧/漫剧/AI 剧分类找剧与题材筛选、全部剧集搜索、综合/真人剧/漫剧/AI 剧热播榜与榜内题材筛选。
- 遥控器操作、选集、倍速、清晰度、自动连播及画面模式。
- 本机收藏、本机观看历史、断点续播与记录备份。
- 红果扫码登录、账户收藏增删与读取、账户观看历史及云端续播；收藏与历史均按本机/账户分设子页签。
- 账户预约，区分已上线、待上线，并显示上线提示。
- 剧集详情中的相关作品、前后季跳转和下一季预约入口。
- 局域网手机输入、应用内检查更新。
- 安装v0.1.1版本后，app内部更新即可。更新源已迁到gitCode，裸连github太慢了。

本机记录与账户云端记录分开保存。退出或切换账号会清除旧账号的云端列表，不删除本机历史。账号能力及尚需核对的边界见 [账号同步说明](docs/ACCOUNT_SYNC.md)。

## 维护资料

- [接口、兼容性与排查经验](docs/DEVELOPMENT_NOTES.md)
- [相关作品和预约实现](kotlin-tv/RELATED_WORKS.md)
- [第三方声明](kotlin-tv/NOTICE.md)

`android/`、`ios/`、`src/`、`server/` 等保留旧 React Native 与 Node 服务实现，当前原生 APK 不依赖它们。仅维护旧服务时参阅 [旧服务 API](docs/API.md) 和 [旧服务 Docker 部署](docs/DOCKER.md)。

## 许可证

本项目与红果官方无关联，非官方接口可能随平台变化失效。项目以 [GPL-3.0](LICENSE) 发布；版权及第三方许可证声明予以保留，见 [原生第三方声明](kotlin-tv/NOTICE.md) 和 [旧服务第三方声明](server/vendor/NOTICE.md)。

旧交付报告和历史设计文档已从当前目录清理，需要追溯时可查看 Git 历史。
