# 红果 TV 0.1.28

版本：`versionName 0.1.28` / `versionCode 69`，支持 Android 6.0 及以上（minSdk 23）。覆盖安装即可，账号、收藏、观看进度与本机缓存记录都保留。

这一版只修一件事：**所有集都播不了**。上一版之后取址全部回空——首页推荐、榜单、详情、搜索全都正常，只有播放地址取不到，界面上是「已自动重试 3 次，仍无法播放」。

## 一、真正的原因：请求带的签名头族

取址接口对签名头的要求变了。它要的是 v5 那一族 `x-khronos` / `x-helios` / `x-medusa`；带旧的 `x-argus` / `x-ladon` / `x-gorgon` 那一族，或者干脆不签名，一律回 `HTTP 200 + 0 字节`——不给错误码，正文直接是空的。

这台手机上做了四路对照，同一份新注册身份、同一个域名、同一份参数，只有签名头不同：

| 签名头 | 结果 |
| --- | --- |
| `x-argus` + `x-ladon` + `x-gorgon` + `x-khronos`（当时线上形态） | HTTP 200，0 字节 |
| `x-khronos` + `x-helios` + `x-medusa` | HTTP 200，**22070 字节**，`code:0` 与完整播放模型 |
| 不签名 | HTTP 200，0 字节 |
| v5 三头再额外补 `x-argus` / `x-ladon` | HTTP 200，22078 字节 |

决定因素是**有没有 `x-medusa` / `x-helios`**。多带旧头并不会让请求被拒，所以此前照上游把 medusa/helios 停发（把它们称作「伪造痕迹」）恰恰是这次全空的直接原因。

同一轮还排掉了三个看着像变量的东西：三条域名（`api5-normal-sinfonlinea`、`api5-normal-sinfonlineb`、`reading.snssdk.com`）结果一样；上报版本号 7.4.3.32 / 7.4.5.32 / 7.3.9.32 三档一样；那次对照里带账号与不带账号逐轮结果也一样。

## 二、改了什么

- 取址的签名器改用 v5 形态（`PlaybackDeviceSession.Profile.signer` → `Signer(mobileV5 = true)`），medusa 明文里写的是这份注册身份自己的 `device_id` / `iid`，与 query 里的那一对始终一致。
- `ContentRepository.playbackRequest` 只保留 `x-khronos` / `x-helios` / `x-medusa`。GET 没有正文，正文摘要按空内容计算，不带 `x-ss-stub`、不带 `Content-Type`；带正文的 POST 仍然带 `x-ss-stub`（它是正文的 MD5，大写十六进制）。
- video_tab 那条 GET 固定用 App 的 UA，两条 POST 保持播放器 UA。
- 取址顺序（同一分支里一起做的）：首选 `GET /reading/bookapi/video_tab/multi_video_model/v1/`，三条入口按健康度轮换，回空的那条冷却 30 秒并当场换下一条，不再对同一条入口反复撞空响应（`PlayRetry.kt`）。三条都不给结果时报第一条那个错误，并明确提示「播放需要登录红果账号」、界面上给「登录红果」入口。
- 取址签名测试改锁 v5 三头、旧族一律不发，逐轮重新签名改按 medusa 校验。

## 三、把这次经验固定下来

下次再遇到「播不了」，不用重新摸索一遍：

- [`PLAYBACK_DIAGNOSIS.md`](kotlin-tv/PLAYBACK_DIAGNOSIS.md) 的 A 节重写：先分流，A 类（取址回空）的第一件事是跑签名头族对照，并且写明「一次全空不能当结论，换身份、隔十分钟再跑一遍」。
- 对照工具进了仓库：[`tools/probes/PlaybackSignatureProbe.kt`](kotlin-tv/tools/probes/PlaybackSignatureProbe.kt)。它不是单元测试，是排查工具，本机不用 Gradle 就能跑，用法写在文件头上。四路跑完直接看哪一路给数据。
- [`PLAYBACK_V5.md`](kotlin-tv/PLAYBACK_V5.md) 新增「同日更正」一节，把这次的四路对照、被推翻的两个旧结论（「变量是账号身份」、「停发 medusa/helios」）都留在文档里，避免下次照着错的结论改。

## 验证

- core 全量单元测试 318 项通过。
- 本机走完整的取址流程：`tiers=1080P bytevc1`，拿到真实媒体地址。用的正是之前一直取不到的那一集。
- 用户装机实测：改后的调试包可以正常播放。
- GitHub Actions 运行 `:core:test`、`:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`、`:app:assembleRelease`，全部通过后才发布本包。

## 已知仍未解决

- **匿名身份在某些时段仍然全空。** 同一份对照代码在 2026-11-23 上午给出「v5 三头回 22070 字节」，同一天下午四路全空、完整流程报「播放需要登录红果账号」，而同一时刻已登录的 App 能正常播放。上游在 11 月反复开关不同请求形态与放行范围，这不是改一次代码能一劳永逸的；现在的处理是三条入口轮换 + 回空冷却 + 明确提示登录。
- 两条在调研中被多个同类项目独立验证、这一版**没有**改的：把取址重试分散到 25–30 秒再显示失败（成功常落在第一次请求后 6–14 秒），以及设备注册域名 `log.snssdk.com` 直接回 444 时改用 `log5-applog-lq.fqnovel.com`。它们与这次的根因是两件事，写在排查文档里，留给下一版。

## 怎么验证

1. 播之前播不了的那几集，应该能出画面。
2. 播放失败时打开设置页「诊断日志」复制出来，`Playback address attempt` 那几行会写明每条入口回了什么（HTTP 状态、响应字节数、这一轮带没带账号凭据）。如果三条入口都是 `0 字节` 而账号已登录，那是服务端这一时段不放行，不是本包的签名问题。

## 安装与更新

- 下载本页附件 `hongguotv-kotlin-0.1.28-android6.apk`，覆盖安装。
- App 内「检查更新」读的是本仓库标记为 Latest 的正式 Release，附件同时带 `update.json`（含包名、版本、大小与 sha256）。
