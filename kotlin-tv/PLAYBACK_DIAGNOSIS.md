# 播放不可用时的固定排查顺序

这份文档是给下一次用的。2026-11-17 这一轮「所有集都播不了」最后修好的原因是签名协议落后（见 [PLAYBACK_V5.md](PLAYBACK_V5.md)），但「播不了」本身不是一个原因，而是四类不同故障共用的表象。所以固定的不是「换签名」这个动作，而是**先分流、再按分流走对应路线**这套顺序。

## 第 0 步：先分流，别急着改代码

三个问题按顺序问，答案决定走哪条分支。三个答案都能从这台手机上直接取到，不需要用户抓包。

| 问什么 | 怎么取 | 结论 |
| --- | --- | --- |
| 目录、剧集列表、搜索还正常吗 | 界面上直接看，或 logcat `Home recommendations` / `Home detail` | 正常 → 签名对目录接口仍然有效，问题只在取址，走 A 或 B |
| 取址请求回了什么 | 设置页「诊断日志」复制出来，或 logcat `Playback address attempt`：HTTP 状态、响应字节数、业务码、网关 logid | 决定走 A / B / C |
| 拿到地址之后播放器报什么 | 播放失败面板「查看原因」，或 logcat 里的完整原因链与 `Decoder capabilities` | 有地址但失败 → 走 B，与签名无关 |

界面上「已自动重试 3 次，仍无法播放」「这一集的 xP、xP 这几档这台设备都解不了」「播放需要登录红果账号」这三句长得很像，必须先落到日志里的数字再动手。

## A. 取址回 HTTP 200 + 0 字节：签名形态不对，或这份身份这一刻不被放行

2026-11 这一轮全是这一类。特征是：目录、剧集列表、搜索全都正常，只有播放模型被静默丢弃。两条已经实测确认的事实要一起记住：

- **签名头族不对时必然回空。** 不带 `x-medusa`/`x-helios` 的那几组写法（旧 Argus/Ladon/Gorgon 一族、或干脆不签名）在这条接口上一律 0 字节。
- **带了 v5 三头也不保证给数据。** 同一台手机、同一集号、同一份探针代码，2026-11-23 上午四路里 B 回 22070 字节完整模型，同一天下午再跑四路全空。同一份注册身份一旦被拒会一直回空，换一份新身份或过一段时间才可能放行。

所以这一类的顺序是：**先固定签名形态（第 1 步），再换身份、换时段复测**，不要拿一次全空直接判定「签名错了」。

固定做法，按顺序：

1. **先做「只换签名头族」的四路对照。** 同一份新注册身份、同一个域名、同一份参数，只有签名头不同：A 当前线上正在发的那一组、B 只带 `x-khronos`/`x-helios`/`x-medusa`、C 不签名、D 在 v5 三头上再额外补 `x-argus`/`x-ladon`。工具在 [`tools/probes/PlaybackSignatureProbe.kt`](tools/probes/PlaybackSignatureProbe.kt)，本机不用 Gradle 就能跑，用法写在文件头上。判读：只有 B 给数据 → 变量就是签名头族，照 B 的形态改请求；四路全空 → 先换一份新注册身份、隔十分钟再跑一遍，两轮都全空才转向别的分支（这一类同时受身份与时段影响，见本节开头第二条）；D 也给数据 → 多带旧头不会被拒，别把「少发一个头」当修复方向。2026-11-23 这一轮的结果是 A 与 C 回 0 字节、B 回 22070 字节完整模型、D 回 22078 字节，改的就是 B 那一组（见 [PLAYBACK_V5.md](PLAYBACK_V5.md) 的「同日更正」一节）。

   这一轮实测过、下次不必重测的变量：**域名**（`api5-normal-sinfonlinea`、`api5-normal-sinfonlineb`、`reading.snssdk.com` 三条一样）、**上报版本号**（7.4.3.32 / 7.4.5.32 / 7.3.9.32 三档一样）、**`x-reading-request` 与 `x-tt-store-region` 这两组头**（带与不带三路完全一致，见第 6 步）。**账号身份不是能一次性写死的变量**：2026-11-23 上午带账号与不带账号一样全空，同一天下午匿名四路全空、完整 `streamLadder` 报「播放需要登录红果账号」，而同一时刻装了改后包的用户能正常播放（App 走的是带账号 Cookie 那一轮）。两轮都要试，并把各自的结果连时刻一起记下来。

   同一份对照在不同时刻会给出不同结果，所以**一次全空不能当结论**：换一份新注册身份、隔十分钟再跑一遍，两轮都全空才转向别的分支。上游在 11 月反复开关不同请求形态（`tsukiyo-zero/hongguoTV` 的 PR #113 / #116 / #125 在 11-20 到 11-22 之间三次反向切换），写死单一路径或单一形态的客户端每次都要发版。
2. **同一条接口有两种 path 写法，先分清用的是哪一种。** `POST /novel/player/multi_video_model/player/v1` 与 `POST /novel/player/multi_video_model/v1/`（不带 `player` 那一段、结尾带斜杠）共用同一份 gzip 正文与同一套 v5 签名，服务端对它们的反应不同：2026-11-17 的对照里前者在三个域名上一律 HTTP 200 + 0 字节，后者一律回 code=0 与五档模型。App 里当前写法没有结果时会当场换另一条（`ContentRepository.v5Ladder`，测试见 [PlaybackPathFallbackTest.kt](core/src/test/kotlin/com/hongguotv/core/PlaybackPathFallbackTest.kt)），所以诊断日志里出现「当前取址写法没有结果 → 换 …」紧接着取到，是预期行为。反过来，做签名对照时必须固定在同一条写法上，别把两条写法的差异读成签名的差异。
3. **查上游有没有已经修好的版本。** 这条链上有两个活跃仓库，签名代码与本仓库同源（同一份 drpys `22261ad` 的移植），所以它们的 diff 能直接落进来：
   - `N3urda/hongguoTV`（GPL-3.0-only，签名协议的原始出处，v5 出自它的 `18c37c2`）
   - `tsukiyo-zero/hongguoTV`（本仓库的 fork 源，v5 出自它的 `d3389947` / PR #109）

   先看提交标题里有没有 signing / protocol / playback 字样，再看它新增的 `SIGNING_*.md` 一类文档：

   ```sh
   curl -s "https://api.github.com/repos/N3urda/hongguoTV/commits?per_page=40" \
     | python3 -c "import sys,json;[print(d['sha'][:7],d['commit']['author']['date'][:16],d['commit']['message'].split(chr(10))[0]) for d in json.load(sys.stdin)]"
   ```

   协议常量、分支要求、license 号都写在那份文档里，照抄即可。**别自己猜新常量。**
4. **移植完先对拍，再联网。** 上游随代码一起给出合成向量（`core/src/test/resources/signing-v5-carrier.json`、`signing-v5-f13.json`），先跑 [SignerV5Test.kt](core/src/test/kotlin/com/hongguotv/core/SignerV5Test.kt) 与 [MobilePlaybackTest.kt](core/src/test/kotlin/com/hongguotv/core/MobilePlaybackTest.kt)。逐字节对得上才说明移植没错；对不上先修移植，不要开始试网络。
5. **上游也没有时，去找 oracle，不要硬猜。** 官方 App 能播说明协议本身可用，缺的是观察手段。可用的观察手段按成本排：
   - 这台手机上读红果 `base.apk` 的 dex 字符串（`pm path com.phoenix.read` 取路径，python 解可打印字符串），能拿到接口 path、参数名、版本号；
   - 读 logcat 里红果自己打的 tag；
   - 官方 SDK 的摘要可以用 VM 类研究工具观察（v5 就是这么找到的：`it00021hot/hongguo-desktop` 的 VM 工具当 oracle，把 VM 里的分支归约成普通旋转/加法/XOR/查表写回 `Signer.hashF13`，那份实现是 PolyForm 许可，不复制进仓库）。
6. **杂头加不加会不会改变结果，用三路对照测，别靠猜。** 官方取址请求里还有 `x-reading-request`（值的前半就是本次 `_rticket`）与 `x-tt-store-region` / `x-tt-store-region-src`。2026-11-23 用 [`tools/probes/PlaybackHeaderProbe.kt`](tools/probes/PlaybackHeaderProbe.kt) 在同一份注册身份、同一集上跑三路：不带这两条、两条都带、只带 region —— 三路都是 HTTP 200 + 0 字节，一模一样。所以这两条头**不是**这条接口的变量：带上它们不会让请求变得更坏，也不会把空响应变成有数据。App 里照红果带上它们（`ContentRepository.playbackRequest`，测试见 [PlaybackRequestSignatureTest.kt](core/src/test/kotlin/com/hongguotv/core/PlaybackRequestSignatureTest.kt) 的 `playbackRequestCarriesTheReadingAndRegionHeaders`）是为了与官方形态一致，不是为了让取址出数据，别把它当修复。

   同一轮顺带确认一个容易写错的对应关系：设备注册 `Set-Cookie` 下发的是 `store-region=cn-sd`（省级，随注册身份变），请求头名却是 `x-tt-store-region`：**两个名字不同**，取值要照 Cookie，不能写死 `cn`。探针第二参数可以复用一个已知的 device_id（注册照做、只换身份编号），用来把「身份」这个变量按住不动，只测请求头。

   这台手机上不用 Gradle 也能跑真实取址：探针放 `$DSH_WORKSPACE/.toolchain/probe/<Name>.kt`，用 `scripts/run-any-probe.sh <Name>` 编译并运行，`REPO=` 指向要测的仓库。

## B. 取到模型，但没有能解码的档

特征是：`video_list` 有内容、`main_url` 与 `spade_a` 都在，播放器仍然失败。这时签名是对的，别再动签名。

- 先看诊断日志里那几档的编码与尺寸。厂商私有编码（`bytevc1` / `bytevc2`）与 `h264` / `h265` 要分开看。
- 本仓库对全档私有编码的集额外发一条 `codec_type=0&force_codec=h264` 兼容请求（PR #38 起）。注意它的 `key_seed` 必须换成兼容响应那一份，旧 seed 解新 `main_url` 只会得到解不开的东西。
- **这一问只问一次**（2026-11-18 起）。同一天五集连播的日志里，这条兼容请求对每一集都回 HTTP 200 + 0 字节，一次 0.9～1.8 秒，五次全失败，而最终播通的恰恰是 v5 给的那份 1080P `bytevc1`——首页试播四次取址里它占 32%～47%，正式播放那一次占取址总耗时的 68%。所以现在两种情况下不再重复问：这一集给的私有编码里有一种在这台设备上真渲染出过首帧（记在 `native-decoder-lessons-v1` 的 `playable` 里，跨重启保留），或者这条兼容请求刚刚失败过（10 分钟冷却）。日志相应地写「本机已实测这种编码能播，不再要兼容档」或「兼容请求刚失败过，这一轮不再问」，不会静默少一次请求。判断依据仍然全是实测到的事实，不查设备能力表。
- 选流本身不预判设备能不能解，失败沿清晰度阶梯往下退，真失败过的按「编码+尺寸」记入设备能力记录（`重置设备能力记录` 可清除）。
- **2026-11-17 的实测修正了此前一条判断。** 此前写在 README 与 PLAYBACK_V5.md 里的「系统播放器解不出厂商私有编码」，在这台 Android 16 手机上不成立：v5 只下发 `bytevc1` 的集，用户装机后能正常播放。也就是说私有编码不是必然失败项，具体走的是哪条解码路径没有查。下次遇到私有编码不要先判死，先让它试。

## C. 业务码非 0

- `100103 PARAM_INVALID`：参数形态不对。`video_tab/multi_video_model/v1/` 缺任意一项都回这个码，参数清单写在 README。同一批请求里 `multi_video_detail`、`multi_video_data/get` 也回 100103，说明是接口自己的答复，不是网关在业务处理前截断。
- `100001`：`POST /novel/player/video_model/v1/` 只认 `video_id` 单值，给 `mixed_video_id_map` / `vid` / `video_ids` 都是这个码。
- `110001`：这一档在 2026-11-09 前后表示需要账号；2026-11-11 起同一个请求改回 HTTP 200 + 0 字节，所以它不再是「登录就行」的信号。

## D. 目录与剧集列表也空

不是签名问题。按这个顺序看：设备注册是否成功（`PlaybackDeviceRegistrationException`；`log5-applog-lf.fqnovel.com` 的 A 记录 2026-11-11 起变成 `0.0.0.0`）、注册下发的设备 Cookie 有没有真的随请求发出（注册域与播放域是不同子域，交给 CookieJar 按域匹配发不出去）、本机时钟是否偏离（`x-khronos` 与 `_rticket` 同源，时钟错签名必然对不上）、是否被 CDN 软拦（与榜单页同一类现象）。

## 每次都要跑完的验证顺序

1. 本机探针取址：证明能拿到 `video_list` 与几档清晰度。这一步失败就不要往下走。
2. `:core:test`：签名对拍、请求构造、响应解析、取消与身份轮次。
3. CI 的 `:app:testDebugUnitTest`。**取址轮次一变，首屏缓存那批测试 mock 的请求表就要跟着改** —— 2026-11-17 就是挂在这里（`FeedStartupCacheTest` 的计数按旧轮次写），修在 `8ac1a1e`。
4. 出调试包给用户装机。真机验收由用户完成，Agent 不自己装机截图。

## 这条流程本身从哪来

不是我推出来的。v5 的完整发现过程记在 N3urda/hongguoTV 的 `kotlin-tv/SIGNING_V5.md` 与 `ISSUE59_LIVE_VERIFICATION.md`（起点是它的 issue #59「无法播放」），本仓库通过 fork 源 `tsukiyo-zero` 的 PR #109 接到它。上面 A 分支的四步就是照那次修复的实际顺序写的。它适用于「服务端换了签名协议、第三方客户端没跟上」这一类；B、C、D 三类不适用，硬套会把时间花在错的地方。
