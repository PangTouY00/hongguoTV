# 红果 TV 0.1.11 更新说明

这一版只修播放，其它功能一律不动。

## 根因：播放接口不认客户端自己编的设备 ID

播放信息接口 `POST /novel/player/multi_video_model/v1/` 对请求里的 `device_id` 和 `iid` 有校验：只认通过匿名注册协议 `POST /service/2/device_register/` 拿到的身份。

旧客户端在 `ContentRepository` 里自己生成了 19 位随机数字（首位固定 `3`）当设备 ID，请求照发，接口回 HTTP 200 但响应体为空。空 body 被当成「内容响应为空」，重试两次仍然空，于是报成整集播放失败——界面上就是「准备恢复暂停位置 · N 秒后重试」。

0.1.10 已经给 `streamLadder` 加过空响应重试，但重试的是同一份签名、同一个随机设备 ID，接口照样不认。那一版只能减少「一次空响应就失败」，根治不了。

## 这一版怎么改

- 新增 `PlaybackDeviceSession`（core）：首次播放前用既有的匿名注册协议换取合法身份（`device_id`、`iid` 与注册下发的 Cookie），成功身份留在进程内复用。
- 全屏播放、首页试播、下一集预热共用同一份身份（`PlaybackDeviceSession.shared`）。三处各自注册会被平台看成三台设备，播放进度与限流都对不上。
- 并发注册合并成一次；一个成功的身份之后所有仓库、所有剧集共用，`cdid` 全程不变。
- 注册请求本身不带账号 Cookie；回写只保留 `install_id`、`ttreq`、`store-region`、`store-region-src` 四项，账号身份不会混进匿名播放身份。
- 平台拒注册（返回 `0` 或空串）不再当成功，直接报 `PlaybackDeviceRegistrationException`。
- 播放失败面板单独显示「播放设备注册失败，请检查网络后重试」，不再和取址失败、解码失败混在同一句提示里。
- 空响应改为换新的 `_rticket` 重新签名再读（同一签名重读通常还是空），最多三次；被切集或导航取消的请求不重试。
- 最终抛出的空响应错误改成独立类别 `EmptyPlaybackResponseException`，日志里能直接分辨是「重读之后仍然空」还是别的问题。

## 没有改的部分

- 首页、搜索、榜单、详情、账号、收藏、弹幕、Mac 客户端都没动。
- 低安卓（`android44-support` 分支，Android 4.4）这一版不发布，那条线继续独立维护。

## 兼容与验证

- 仍是单一 APK：minSdk 23（Android 6.0 及以上），versionName 0.1.11 / versionCode 52，包名与签名与 0.1.10 一致，已装 0.1.10 的设备直接覆盖安装并保留本机数据。
- `:core:test` 230 项全部通过，其中新增 6 项：注册一次后跨仓库复用身份、并发调用只注册一次、注册失败独立成类别且不带响应正文、拒注册（`0` / 空串）不入库、空响应换新 `_rticket` 重试到第三次成功、线程被取消时不发起注册。
- Robolectric 在 API 23 与 API 26 上验证：首页与全屏拿到的是同一个 `PlaybackDeviceSession.shared`；注册失败的中文摘要不含响应正文。
- CI 跑 `:core:test`、`:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。

## 已知限制

- 设备注册依赖平台接口可用。注册被拒或网络不通时播放会明确报「播放设备注册失败」，不会退回随机身份硬播。
- 本版在编译、单测与打包层面验证；真机播放效果仍需装机确认。

## 安装

- 已装 0.1.10 的设备直接覆盖安装即可，收藏与观看记录保留；App 内的「检查更新」会自动取到本包。
- 更早版本（0.1.8 及之前）如果自动更新提示失败，请在电脑上下载本包安装。升级前建议先在旧版「设置」里用二维码备份收藏与观看进度，再卸载旧版、安装新包。
- 直连：https://github.com/PangTouY00/hongguoTV/releases/download/kotlin-v0.1.11/hongguotv-kotlin-0.1.11-android6.apk
- 镜像加速：https://gh-proxy.com/https://github.com/PangTouY00/hongguoTV/releases/download/kotlin-v0.1.11/hongguotv-kotlin-0.1.11-android6.apk
