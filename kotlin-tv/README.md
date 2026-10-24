# 红果 TV 原生客户端

工程：Kotlin + Android View + Media3。最低 Android 6.0 / API 23；compileSdk、targetSdk 为 36。包名 `com.hongguotv.nativeapp`，与旧 React Native 应用 `com.hongguotv` 的数据独立。

功能介绍见 [项目主页](../README.md)，工作约定见 [开发流程](../CONTRIBUTING.md)。

## 连续浏览

找剧分类接近底部时按服务端返回的偏移和会话游标继续加载，关键词搜索按服务端页码继续加载，结果均去重追加。分类和搜索遇到连接超时、连接中断、域名解析失败或 HTTP 500/502/503/504 时，等待片刻后自动重试同一请求一次；其他 HTTP 错误或响应格式异常不自动重试。仍失败时保留当前列表，底部显示错误类型并可手动重试。搜索返回分类、从详情返回列表时恢复选中作品。排行榜使用六列海报网格，沿用完整榜单和全榜题材筛选，卡片标注原榜名次与可用热度，连续浏览不改变原始名次。两处底部均提供「回到顶部」，长按确认或菜单键管理作品。

输入停顿 350 毫秒后请求官网公开联想接口，最多显示 8 个候选；服务端可将部分拼音输入匹配为中文剧名。选中候选后按该中文词搜索，不自动打开详情；直接搜索、搜索历史与手机输入仍可使用。联想请求使用独立可取消任务，不中断当前结果分页；输入变化、离开页面或切到后台时取消过期任务，失败不阻止直接搜索。不承诺任意拼音、首字母或错别字都能匹配。

分类使用 App 分类接口的真实分组：类型（全部、真人剧、漫剧、AI 剧）、主题、设定、背景、推荐排序、受众、上新时间，以及服务端提供时的作品状态。每组单选，不同组的条件组合提交到服务端，不是只筛选已加载作品；每行「全部」清除本组条件，受众「女频」的值为 `0`，与「全部」区分。切换内容类型会清除旧类型的条件并重新获取分组，重置会回到全部类型、全部条件。

遥控器左右选择同组条件，上下切换分组；较小屏幕可上下滚动筛选区。进入海报列表保持筛选展开状态，可手动收起；列表首行向上可到筛选控制按钮；搜索或详情返回后保留组合条件、列表和选中作品。分类仍最多加载 100 批，未返回的分组或选项不自行补造。可用高度不足时海报缩小，标题改为单行，必要时隐藏副信息以保留列表空间；完整作品信息仍可进入详情查看。旧网页分类入口继续保留给首页兼容路径及 Mac 客户端，本轮未改变它们的行为。

界面使用 Android 系统 GridView 回收离屏卡片，搜索与分类仍遵守接口最多 100 页的范围；切换分类、题材或关键词会重新从第一页读取。账户收藏、历史和预约的读取范围不变。遥控器手感仍需用户在电视上验收。

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

修改 `app/build.gradle.kts` 中 `versionCode`、`versionName` 后重新构建。环境变量 `HONGGUOTV_VERSION_CODE`、`HONGGUOTV_VERSION_NAME` 会覆盖文件中的默认值。正式上线时递增版本号与 versionCode，编译调试期间保持不变，测试包通过文件名区分；当前正式版为 0.1.4（versionCode 45），后续正式发布再递增。分支名、APK 文件名或 Git 标签不会自动改变 APK 内部版本。

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
