# 开发与排查笔记

本文件保存仍适用于当前代码的排查结论，替代旧交付报告和按日期堆积的实验记录。涉及接口行为的结论有时效性，改动前对照源码和真实反馈；不将自动化测试当成设备验收。

## 项目基线

- 当前原生工程在 `kotlin-tv/`；`main` 是统一 Android 6+ 基线，不分别维护 Android 6、Android 8 产品线。
- minSdk 23、compileSdk/targetSdk 36、JDK 17；原生构建和发布入口见 [构建说明](../kotlin-tv/README.md)。
- 保留上游 Git 历史、旧 RN/Node 源码、许可证和第三方声明。历史文档可从 Git 恢复，不必保留在根目录。
- 每项需求从最新 main 开短期分支。未合并的功能需要到对应分支读取文档，不能把测试分支描述成已发布功能。
- 只由用户操作电视、模拟器并反馈；自动化检查和打包由开发侧执行。

## 内容与播放

- 搜索使用 `ContentRepository.searchAll()` 的网站搜索，覆盖短剧和漫剧。首页分类与搜索独立；不要重新引入已失败的漫剧搜索链路。
- 首页、搜索正常但播放超时时，先核对播放请求路由、设备标识和响应是否为空。
- 当前播放使用独立的随机设备标识，同一 Repository 实例内保持稳定；不附带搜索签名头。参考来源和许可见 [NOTICE](../kotlin-tv/NOTICE.md)。不要把其他社区项目的播放路由直接替换进来而不验证本项目已有效的链路。
- 保留响应大小限制、请求取消、媒体解密、视频轨道检查和原有清晰度筛选。空响应或平台拒绝不应伪装成空列表或成功播放。
- 只有明确的前后台页面、会话代次允许异步结果更新 UI；离开页面或切换账号后丢弃旧结果。

## 登录与账户

- 红果官网扫码流程可用；业务登录以账号资料存在有效身份为准。匿名设备注册成功或短信发出均不等于账号已登录。
- 短信曾被滑块和会话过期拦截。不要枚举验证码、重复发送、猜测多个验码端点；错误状态要明确显示。
- Android 的 OnDismissListener 异步派发曾导致 busy 未及时清除，后续保存任务被跳过。主动关闭 QR 时先解除旧监听，再同步释放操作状态，防止旧回调干扰后继任务。
- AccountStore 使用 Android Keystore AES-GCM；账号凭据不进入片单备份、文档、Git 或日志。
- 云端历史限制最近 50 部；本机续播与云端续播来源分开，退出、换号立即清空云端资料并使旧请求失效。

## 预约与相关季

- 预约列表为 `/reading/user/subscribe/list/v0/`，`tab_type=13`，`is_online=true/false` 分别取两类；单类失败不能伪装成无记录。
- 已上线通常提供 subscribe_data / video_data 嵌套资料；待上线实际返回外层 name、cover、item_desc、item_id、expected_rec_text。不得因缺少嵌套对象而把整批待上线丢掉。
- 没有可播放 series_id 的预约条目使用带 `reservation:` 前缀的内部显示键，不把它拿去播放。
- 相关季接口为 `/reading/bookapi/plan/v:version/`，`scene=10`、`from=detail_page_more_related`。相关 cell 为 show_type 547；content_type 1004 是短剧，1007 是待上线，即使 disable_play=false 也不能播放。原著小说不在当前展示范围。
- 预约提交必须使用目标季身份，不误用其他预约项编号。请求接受不等于账户列表已确认，不提前显示成功；详见 [相关作品说明](../kotlin-tv/RELATED_WORKS.md)。

## Android 6 兼容

- Base64 使用已有 Bouncy Castle 实现，避免调用 Android 6 不具备的 java.util.Base64。
- 有界封面队列使用旧系统具备的带容量构造方法。
- Android 6 使用 NetworkRequest 监听网络；Android 7+ 才调用默认网络监听。
- Android 8+ 使用逐应用安装权限流程；旧系统交给系统安装器。读取 minSdkVersion 等较新 API 必须做版本判断。
- 分发 APK 保持签名连续，并兼容 Android 6 的 v1 签名；覆盖前核对包名、内部版本和签名。

## 更新与测试

- versionCode 按所有分发包递增，测试包也算；versionName、分支名和 Release 标签没有自动关联。
- update.json 是本项目自定义更新协议，运行 scripts/build-kotlin.sh 会从实际 APK 自动生成配套清单；普通 Gradle 构建不生成。版本、附件名、大小、SHA256 必须与实际 APK 一致。
- 旧更新源或旧最低系统更新逻辑的已安装 APK，可能需要手动覆盖一次才能切换本仓库更新源。推送或合并源码不会修改已安装 APK。
- 当前构建步骤见 [构建说明](../kotlin-tv/README.md)，发布步骤见 [在线更新说明](../kotlin-tv/AUTO_UPDATES.md)。只有文档改动不需要重新构建 Android；检查相对链接和 Git diff 即可。

## 电视排行榜

排行榜使用 RankingScreen 独立页面，四类官网榜单各读取完整五页后替换内存缓存，榜内题材过滤保留原名次。数据来源、边界和遥控器验收见 [TV_RANKINGS.md](../kotlin-tv/TV_RANKINGS.md)。不将筛选后的作品重新排序或冒充官方题材榜；尚未接入手机推荐榜、预约榜等其他榜单。

## 收藏与历史来源

收藏/观看历史各有本机、账户子页签；LibrarySources 仅保存界面偏好，不迁移记录。账户收藏读取 video_shelf_info，由 AccountController 的会话代次隔离，失败保留同账号旧数据，退出和切换立即清空。顶部删除了独立账户历史入口，预约/设置内部索引变为 5/6。详情及快捷操作中分开提供本机/账户收藏；账户写入使用 video/update，确认账号、先读后写再回读，未确认时不自动重试。协议及尚未验证的边界见 [ACCOUNT_SYNC.md](ACCOUNT_SYNC.md)。

## 找剧入口

顶部原搜索页改为找剧。空关键词走官网 `/category/{real-drama|comic-drama|ai-drama}/{topic?}` 的服务端分类，题材来自 selectorList，必须核对 categoryRoute 和分页。提交关键词仍用跨类型 searchAll，不继承分类条件，不以本页过滤假装全局搜索筛选。返回分类恢复 DramaDiscoveryState 的页码、作品、题材及焦点；滚动键包含类型和题材。详情见 [DRAMA_DISCOVERY.md](../kotlin-tv/DRAMA_DISCOVERY.md)。

## 电视交互整理（2026-10-22）

已实施，待用户实机确认：调整剧集卡片长按弹窗，顺序为「继续观看/直接播放」「查看剧集详情」「加入/取消账户收藏」「加入/取消本机收藏」。两种收藏在弹窗内相邻，顶部页签保持原样，不增加收藏子菜单。移除「加入/移出稍后看」选项；暂时保留已有本机稍后看数据，不做数据清除。保留本机历史管理操作，菜单按动作绑定回调，避免顺序调整误触其他操作。

详情主操作行提供续播、选集，账户/本机收藏与简介放第二行。选集直达当前组及当前集，显示当前集与本机上次保存集；不推断跳过的集数已看。历史卡片补齐总集数与位置，预约卡片补充上线状态/时间。设置分为播放偏好、账户、数据管理、应用更新四组。共享按钮以橙字表示已选，边框表示焦点；首页准备好播放后收起底部状态条，加载、暂停和错误仍显示。找剧的题材展开、返回分类恢复以及账户读取失败保留旧列表已有实现，保留原行为。测试版 0.0.13-tv-usability / versionCode 35，仍在 feature-home-drama-feed 验收。

## 正式播放下一集预缓冲（0.0.14-next-cache / versionCode 36）

分支 feature-next-episode-cache，尚未合并。保持按倍速折算后剩余 30 秒内启动，只预取下一集。当前播放至少准备 1.5 秒且已有 5 秒缓冲时才启动；末尾不足 5 秒时要求剩余部分均已缓冲。继续遵守自动连播开启、前台播放、非计费网络及重试间隔等限制，切集按集 ID/清晰度领取缓存，暂停、退出和切换会取消旧任务。

正式播放预下载开头上限从 1 MiB 增至 2 MiB；首页仍用默认 1 MiB。RemoteVideo.warm 支持有界参数并测试原始数据缓存命中、重复调用不下载与参数上限。日志仅记录切集集数、缓存命中、预取尝试及播放器原有首帧耗时，不输出地址或凭证。

此变更只改善数据准备，不承诺无缝切集：播放器仍停止旧媒体、设置新源并 prepare，即使命中缓存也可能短暂缓冲/解码；需结合 cached/preloaded 和首帧耗时区分网络等待与播放器切换等待。不新增保留画面行为。

## 同播放器队列接续（0.0.14-seamless-next / versionCode 37）

继续在 feature-next-episode-cache 开发，未合并。下一集末尾 30 秒内预缓冲 2 MiB 成功后，转移 RemoteVideo 与 HTTP 客户端的所有权并向同一 ExoPlayer 追加 MediaSource。播放器自然进入下一项时保存上一集完成位置，更新当前集数/标题/清晰度/新集进度，移除旧播放源并关闭旧资源；该路径不调用 stop、clearMediaItems、setMediaSource 或 prepare。待播队列只保留下一集，缓存未成功时仍回退到原加载流程；不能保证所有格式/电视解码器都无缝。

手动下一集命中待播项时使用播放器队列跳转，不计为定时停止的已播完集数。暂停到后台、退出、错误和换清晰度会取消待播资源；剩余一集的定时停止不追加下一项，选择「本集播完」会移除已有待播项。自然切换按一集扣减定时计数。队列中的正式播放与首页试播相互独立。

API 23/26 的播放器接口替身测试检查追加/领取资源、无需重启的自动与手动接续、进度归属、退出取消、清晰度隔离与本集停止；并非真实解码性能测试。实机请检查自动切集、遥控器下一集/选集、暂停返回、清晰度切换、定时停止及手机云端进度。

## 首页官方推荐流与换剧队列（0.1.0 / versionCode 38）

新功能在 feature-home-recommendations，带入 main 已合并的测试竞态修复。入口、请求会话、账号隔离及实测边界见 [HOME_FEED.md](../kotlin-tv/HOME_FEED.md)。推荐与分类来源分开；换一批继续服务端游标，账号变化清空旧游标。上下换剧命中预缓冲时在同一播放器队列 seek，队列每项结尾暂停，不自动滚动作品。不要将成功携带 Cookie 等同于已验证手机个性化偏好，也不要将试播进度上报作为隐含功能加入。

版本码 39：修复首页 PublicFeedBackend 公共内容客户端关闭跳转导致 HTTP 301；只对带账号的推荐请求关闭跳转。首页漫剧/真人剧改走 App 分类推荐 landpage/v，sort 为空，按类型隔离 session、offset 和最近 540 个去重 ID；找剧分类保持不变。原生内容跳转通过本地 HTTP 301→200 回归验证；两类推荐及视频头准备通过匿名只读探针验证。

版本码40：首页试播复用明确首集元数据，跳过一次详情请求；分类数据缺首集标识时保持详情回退，不能把推荐片段 vid 当首集。公开连接池跨首页访问保留2分钟、最多3连接，每个页面独立取消请求。边界与验证见 HOME_FEED.md。

- GitCode 更新：先匿名请求 releases/latest?type=latest，再按 tag 读取 update.json；清单标签须与 Latest 一致。附件使用 gitcode.com 稳定地址，跟随 file-cdn.gitcode.com 的临时签名跳转，不能持久化 CDN 地址。源码同步不等于发布，需单独上传配套附件并设为 Latest。GitHub 旧版需一次覆盖升级。

- 搜索网页现改为客户端加载，/search/<keyword> 不再包含 _ROUTER_DATA。全部剧集搜索应读取官方 GET /api/search/result?query=<encoded>&page=N，响应直接包含 searchList、pageNum、totalPages、hasMore；video_data 为对象。不要把 HTML 页面或缺少 searchList 的响应当作空结果。已用“大佬”验证前两页各 10 条。

## 找剧组合筛选（2026-10-24）

- TV 找剧使用官网 App 的 `POST /reading/distribution/category/landpage/v/`，与首页兼容目录和 Mac 使用的网页分类分开。查询版本参数在此请求内设为 73532，沿用本项目签名器；匿名实测全部、真人、漫剧、AI 均返回剧集。
- 条件放在 `select_items`：`genre`、`category_dim_theme`、`category_dim_role`、`category_dim_epoch`、`sort`、`gender`、`online_time`、`creation_status`，各值为数组；空数组代表不限制，不能将 `gender=["0"]` 当成全部，它代表女频。选项 ID 与分组来自 `selector_rows`，不从旧网页分类路径猜测。
- 首批 `offset=0`、`need_selector_panel=true`、`req_type=default`；续批使用响应的 `next_offset` 和 `session_id`，`req_type=only_content`。最多 100 批；偏移不前进或续页会话缺失时报错，不按本地条数伪造游标。会话不进入日志或文档样例。
- 卡片来自 `data.video_data`，读取 `series_id`、`title`、`cover`、`episode_cnt`、`video_desc` 和 `category_schema`；加载失败保留列表，改变条件或离开页面使旧回调作废。组合条件变化从首批开始，切类型清空旧条件。
- HGTV 1.2.3 正式包的分类协议与其公开 main 的旧网页分类实现不同。本轮仅静态核对公开包的接口字段，并直接向平台验证协议，未移植它的实现代码；不以公开 main 的单行分类作为 1.2.3 功能边界。

### 分类场景与 TV 返回行为修复

- `req_scene` 必须与类型匹配：漫剧 `comic_series`、AI 剧 `ai_series`，全部与真人沿用 `default`，首批与续批保持一致。仅传 `select_items.genre` 而场景仍为 default，会出现类型标签错配；此前仅验证返回非空，未充分核对标签语义，本次增加类型场景回归和真实筛选续页核验。
- 用户提供的官方包经静态读取为 com.phoenix.read 6.5.5.32，包含旧版分类入口；未安装或运行。不同官方版本和服务端分组可能变化，不承诺完全复刻所有版本的标签。
- 分类页取消常驻加载更多／回到顶部，普通续页保持网格焦点，失败后才显示手动重试。返回键统一调用列表回顶、筛选区滚动复位与导航焦点复位；不只是把焦点移到顶部导航。
- 返回详情后从条件模型重画选中状态，保存筛选行横向／纵向位置与页头偏移，确保已选项可见。页头滚动步长在当前页面内固定，避免海报随可用高度变化时反复改变偏移。

### 播放空响应：补上设备 Cookie 与不再回退随机身份（2026-10-27）

上一版（`958b4db`）已把播放改成"先匿名注册再取流"，实机仍然播不了。对照参考仓库 `tsukiyo-zero/hongguoTV` 的 `3889318`（其提交说明记录：同一分集用未注册随机身份 5 次全部 HTTP 200 零字节，改用注册身份 5 次全部返回模型），本轮定位到我们这边还差两点，而不是注册接口本身不可用。

1. **注册成功了，但播放请求没带设备 Cookie。** 注册域是 `log5-applog-lf.fqnovel.com`，播放域是 `api5-normal-sinfonlineb.fqnovel.com`；`Set-Cookie` 下发的 `install_id` / `ttreq` / `store-region` / `store-region-src` 没有跨子域可见的 `Domain` 属性，交给 `MemoryCookies` 按域匹配是发不出去的。现在把这四项按白名单显式拼成 `Cookie` 头随播放请求发送，账号会话类 Cookie 不转发。
2. **注册失败会退回随机身份。** 随机身份在平台侧必然空响应，等于把"注册失败"伪装成"播放失败"，还让界面无法把原因归到注册上。现在注册失败直接抛 `PlaybackDeviceRegistrationException`，界面显示「播放设备注册失败」；下一次播放会重新尝试注册，成功后**不再轮换**身份。

同时按参考实现把身份收敛成进程内单例 `PlaybackDeviceSession.shared`：首页试播、正式播放、预热与换剧切集共用一份 `device_id`/`iid`/`cdid`，不再每个 `ContentRepository` 各自注册一次。注册请求本身不带任何 Cookie（`CookieJar.NO_COOKIES`），并跟随调用方的 `RequestScope` 取消；注册成功后才缓存，并发调用只会注册一次。

验证边界：本轮在本机跑通 core 单测 229 项（新增 `PlaybackDeviceSessionTest` 5 项：注册一次跨 repository 复用、并发只注册一次、拒绝注册时绝不用随机身份取流、注册失败可重试且成功后不轮换、取消的作用域不发也不缓存注册），并用本机构建脚本产出 4.4 调试 APK。**没有实机播放验证**，首帧、整集解码与平台是否对本机网络另有拦截都仍未证明；如果实机仍失败，播放面板「查看原因」里的「设备注册」一行会直接说明注册是否完成。
