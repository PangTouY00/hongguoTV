# 播放协议 v5 修复与验证

## 本次原因

2026-11-16 当前旧播放接口返回 HTTP 200 空正文，全新注册匿名身份也未恢复。在同一新接口、同一匿名身份、同一集号和 gzip 请求格式下，仅切换签名的真实对照：旧签名正文 0 字节，v5 签名正文 24345 字节、业务码 0、包含 5 档模型。该结果证明本次样本存在签名协议兼容问题，不代表服务端所有空响应均由同一原因引起。

## 实现及来源

签名、协议常量、请求结构和合成测试向量参考 [N3urda/hongguoTV](https://github.com/N3urda/hongguoTV/tree/6672b55d5308ea1ec26d2fe0bc0b2401ef4aa0c3)，固定提交 `6672b55d5308ea1ec26d2fe0bc0b2401ef4aa0c3`，许可证 GPL-3.0-only，保留原有 drpys 来源声明。不引入参考仓库提及的第三方 VM、研究工具、原生 SDK 或远程签名服务。协议常量不是用户账户凭据，合成向量不含真实会话。

播放改用 `/novel/player/multi_video_model/player/v1`、gzip JSON，以及 Android `v04.10.03` Medusa/Helios/Khronos 签名。签名绑定实际压缩字节、最终请求票据与当前匿名身份；目录、搜索等仍使用原有签名，不受切换影响。

响应必须精确匹配请求集号，支持平铺或 `data.1004` 结构及字符串模型。沿用现有资源解析、编码过滤、密钥派生和媒体解密，不把 HTTP 200 或模型存在当成电视播放成功。单次只读取目标集，下一集仍由既有后台准备流程单独获取，不再依赖旧接口批量携带后续模型。

## 失败边界

本仓库接入的是签名与请求结构，取址轮次与身份更换策略沿用既有实现：每一轮先作废再重新注册一份匿名身份，最多四轮，带账号凭据时第一轮带账号 Cookie；v5 的两条写法都不给清晰度才接着试 `video_tab` GET 与旧 POST。旧传输保留为 `legacyStreamLadder`，只供离线回归与既有测试使用，正式播放不走它。取消、业务拒绝、格式异常与超大响应仍明确失败。

## 同一条接口的两种写法与当场切换（2026-11-17）

同一个 host、同一份 gzip 正文、同一套 v5 签名，path 有两种写法，服务端对它们的反应不一样：

| 写法 | 实测结果（三个域名各试一次，每次用新注册身份） |
|---|---|
| `/novel/player/multi_video_model/player/v1` | 一律 HTTP 200 + 0 字节 |
| `/novel/player/multi_video_model/v1/` | 一律 code=0，五档 `main_url` + `spade_a` |

对照脚本与完整数据在 `tmp/hongguo-net/`（`probe_matrix.py`、`v5.py`、`Python取址复现结论.md`）；上游 `N3urda/hongguoTV` 的 `PlayerSource.PHOENIX_URL` 保留的也是后一种写法。

因此 `ContentRepository.v5Ladder` 在第一条写法没有结果时当场换第二条再要一次：

- 换写法用的是**同一份设备身份、同一份正文、同一套签名**，只有 path 不同，一轮里各发一次请求；
- 触发条件是「这条写法没给出结果」：HTTP 200 空正文、404、5xx、连接失败；
- 业务码非 0（`110001` 那类账号侧拒绝）与响应结构不对**不触发**换写法——换写法不会改变结果，不为此多花一次请求；
- 两条都不给结果才接着试 `video_tab` GET 与旧 POST，顺序与原来一致。

代价要说清楚：当前写法真的挂了以后，每次取址会多花一次失败请求（实测一次约 0.3～1 秒）。诊断日志里会先出现「当前取址写法没有结果 … → 换 …」，紧接着「v5 取址成功（备用写法）」，这两行是预期行为，不是新的故障。

## 本仓库的实测（2026-11-17，同一台 Android 16 手机）

签名移植与上游随代码给出的合成向量逐字节一致：4 条 AES v5 carrier、32 条 `hashF13`、32 个连续时间戳的 `signPlayback` 全部对拍通过，`x-medusa` 长度稳定。真实网络：同一份新注册匿名身份、同一集号，v5 请求 1.1 秒回完整模型，五档 360p/480p/540p/720p/1080p 都带 `main_url` 与 `spade_a`；旧签名在同一条接口上仍是 0 字节。

**画面已真机验收。** 用户 2026-11-17 装本分支的调试包后可以正常播放，包括上面那三集只有 `bytevc1` 的。这条接口给的编码 `force_codec=h264`、`codec_type=0`、`use_os_player=true`、`need_mp4_align=true` 四种写法都不改变，而私有编码在这台设备上解得出来——此前「系统播放器解不出厂商私有编码」的判断不成立，具体走的是哪条解码路径没有查。取址与播放两条都通了。

## 2026-11-23：这条改作后备，首选换回 video_tab

同一条 v5 请求在 2026-11-17 回完整模型，到 2026-11-23 在同一台手机上对匿名请求回 `HTTP 200 + 0 字节`：同一份新注册身份、同一集号，`/novel/player/multi_video_model/player/v1` 与它的备用写法各试一次，全部空正文；`GET /reading/bookapi/video_tab/multi_video_model/v1/` 在 `api5-normal-sinfonlineb`、`api5-normal-hl`、`api5-normal-lf` 三条入口上各试一次，同样全部空正文。同一时刻用户已登录的 fork 版本能正常播放同一集。所以这条接口给不出结果的原因不在签名协议上，v5 的移植与对拍结论仍然成立，只是它不再能取到匿名身份下的清晰度。

照主仓库 `tsukiyo-zero/hongguoTV` 现行实现把取址顺序换了过来：首选 video_tab GET、三条入口按健康度轮换、停发 `x-medusa`/`x-helios`/`x-gorgon`（主仓库把它们称作伪造痕迹，与 `PlayerSigner` 真头同时出现时整条请求被拒；本机带这几个头的两种写法在三条入口上全部回空）。这条 v5 保留作后备，排在带账号的 `POST /novel/player/video_model/v1/` 之后。域名表与「回空冷却 30 秒」的语义移植自 `PlayRetryPlan`，在 `PlayRetry.kt`。

**这一段的「停发 medusa/helios」在同日被推翻，它本身就是取址全空的原因，见下一节。**

## 2026-11-23 同日更正：全空的原因是签名头族

同一台手机、同一份新注册身份、同一个域名、同一份参数，只换签名头族做四路对照：

| 签名头 | 结果 |
| --- | --- |
| `x-argus` + `x-ladon` + `x-gorgon` + `x-khronos`（当时线上形态） | HTTP 200，0 字节 |
| `x-khronos` + `x-helios` + `x-medusa` | HTTP 200，**22070 字节**，`code:0` 与完整 `video_model_data` |
| 不签名 | HTTP 200，0 字节 |
| v5 三头再额外补 `x-argus` / `x-ladon` | HTTP 200，22078 字节 |

决定因素是**有没有 `x-medusa` / `x-helios`**。多带旧头不会让请求被拒，所以「与真头同时出现必然被拒」这条说法在 video_tab 上不成立。

同日另外排除掉的变量（同一份新注册身份，注册返回 `new_user=true`）：

- v5 那条的两写法 × 三个域名（`api5-normal-sinfonlinea`、`api5-normal-sinfonlineb`、`reading.snssdk.com`）× 三个上报版本号（7.4.3.32、7.4.5.32、7.3.9.32）共 18 个组合，全部 `HTTP 200 + 0 字节`。**版本号不是变量。**
- 真机上带账号 Cookie 与不带账号 Cookie 的取址结果逐轮一致：`账号凭据=有` 那一轮同样三条入口全空。**账号不是变量。** 上一节里「同一时刻已登录的 fork 版本能正常播放」那次对照没有控制签名头族，结论不成立。

改法：`PlaybackDeviceSession.Profile.signer` 改用 v5 形态的签名器；`playbackRequest` 只保留 `x-khronos` / `x-helios` / `x-medusa`（POST 带正文时仍带 `x-ss-stub` 与 `Content-Type`，GET 不带）；video_tab 那条 GET 固定用 App 的 UA。改完在本机走完整的 `streamLadder` 取到 `1080P bytevc1` 与真实 `main_url`。

外部同期记录指向同一条结论：beilunyang/openhongguo#16、jackotom/hongguo-drama v7.7、aston314/hongguo-tv#76 三个互不相关的项目都写明 video_tab GET 要带 v5 签名族、正文摘要按空内容计算、不发 `x-ss-stub` 与旧 Argus/Ladon。

## 下一次

「播不了」不止这一类。取址空正文、选不出可解码的档、业务码非 0、目录也空，是四类不同故障，分流与各自的路线写在 [PLAYBACK_DIAGNOSIS.md](PLAYBACK_DIAGNOSIS.md)。
