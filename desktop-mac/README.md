# Mac 试验客户端

TV 是主要维护目标。Mac 端目前仅验证搜索、剧集详情、选集和播放，不承诺与 TV 同步更新；不包含账号登录、收藏、推荐首页、历史记录、自动连播和应用内更新。

## 运行

在本机生成的 `outputs/mac/HongguoTV.app` 可直接打开，也可拖入「应用程序」。当前构建针对 Apple Silicon（arm64），最低 macOS 13。自带精简 Java 17 运行环境，不需要另装 Java。使用本机临时签名，尚未经过 Apple 公证，不适合作为正式公共发行版。

搜索剧名后，点击左侧结果，在右侧选择集数。播放控件支持暂停、音量、进度拖动及全屏。关闭窗口即退出并清理辅助进程。应用不写入账号信息和观看记录；视频只在内存中按需解密，不保存完整视频文件。

## 构建与验证

需 JDK 17（含 jlink）、Gradle 8.14.3 和 Xcode Command Line Tools。构建当前机器架构的应用：

```sh
JAVA_HOME=/你的/JDK17路径 \
HONGGUOTV_GRADLE=/你的/gradle路径 \
bash scripts/build-mac.sh
```

可选联网验证（会访问官方匿名接口并播放一集的部分内容，不需要登录）：

```sh
python3 scripts/test-mac.py
```

验证搜索、分页、详情、本地服务访问限制、Range 响应、AVPlayer 流式解码及拖动、辅助进程退出。测试不操作电视或 Android 模拟器。

## 结构与边界

- `Sources/`：SwiftUI 窗口与 AVKit 原生播放界面。
- `backend/`：复用 `kotlin-tv/core` 中的搜索、详情、播放解析、MP4 解密。仅监听随机的 `127.0.0.1` 端口，随机路径令牌，拒绝携带 Origin 的跨站请求。
- 独立 Gradle 工程，不改变 TV 的构建配置、包名、版本或更新源。
- 视频窗口使用 NSViewRepresentable 包装 AVPlayerView；避免当前系统中 SwiftUI VideoPlayer 初始化 _AVKit_SwiftUI 导致的崩溃。已在实际窗口验证打开详情和播放首集。
- 网站接口及 macOS 支持的媒体格式会影响可用性。已验证的样本不代表全部剧集都兼容。
- GPL-3.0-only；保留仓库历史和第三方许可。构建产物内附 LICENSE 与第三方声明，发布时须提供对应源码。
