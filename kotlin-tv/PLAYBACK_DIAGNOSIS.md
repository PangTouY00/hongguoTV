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

## A. 取址回 HTTP 200 + 0 字节：签名协议落后

2026-11 这一轮就是这一类。特征是：目录与剧集列表全都正常，官方 App 在同一张网络上能播，只有播放模型被静默丢弃；换注册身份、换 Cookie 形态都不改变结果。

固定做法，按顺序：

1. **先做同接口对照，确认变量只有签名。** 同一条 path、同一份新注册匿名身份、同一集号、同一 gzip 正文，只换签名。本仓库留了这个对照：正式路径是 v5，旧签名在 `ContentRepository.legacyStreamLadder` 里，只供离线回归。两条各跑一次，正文从 0 字节变成几 KB 就证明是签名；两条都空就不是 A。
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

   这台手机上不用 Gradle 也能跑真实取址：探针放 `$DSH_WORKSPACE/.toolchain/probe/<Name>.kt`，用 `scripts/run-any-probe.sh <Name>` 编译并运行，`REPO=` 指向要测的仓库。

## B. 取到模型，但没有能解码的档

特征是：`video_list` 有内容、`main_url` 与 `spade_a` 都在，播放器仍然失败。这时签名是对的，别再动签名。

- 先看诊断日志里那几档的编码与尺寸。厂商私有编码（`bytevc1` / `bytevc2`）与 `h264` / `h265` 要分开看。
- 本仓库对全档私有编码的集额外发一条 `codec_type=0&force_codec=h264` 兼容请求（PR #38 起）。注意它的 `key_seed` 必须换成兼容响应那一份，旧 seed 解新 `main_url` 只会得到解不开的东西。
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
