# 红果 TV 0.1.12 更新说明

这一版修「找剧」里的搜索：之前搜索会直接报「内容请求失败（HTTP 503）」，什么都搜不到。版本号 versionName 0.1.12 / versionCode 53。

## 根因：搜索走的网页接口被风控关了

客户端原来有两套搜索：

- App 搜索接口 `POST /reading/bookapi/search/tab/v`；
- 找剧页的实际搜索走网页 JSON 接口 `GET /api/search/result`。

2026-10-30 起，网页搜索接口对不带签名的请求一律回 HTTP 503：

```json
{"code":"signature_configuration_unavailable","message":"Search risk control is temporarily unavailable."}
```

这个接口没有公开的签名规则，风控关闭期间没有绕过去的方法。与此同时 App 搜索接口仍然可用，但它和播放接口一样只认**匿名注册下发的设备身份**——用源码里写死的那对旧 `device_id` / `iid`，它只会回 HTTP 200 + 0 字节，看起来和「网络通了但没数据」一模一样。

## 这一版怎么改

- 搜索身份改由 `PlaybackDeviceSession` 匿名注册拿到，和首页试播、正式播放、下一集预热共用同一份 `device_id` / `iid` / `cdid`。
- 「找剧」页的搜索与「加载更多」都改走 App 搜索接口（短剧 tab），不再调用已经关闭的网页接口。
- 去掉「App 接口失败就回退网页接口」的逻辑：那条回退现在必然 503，留着只会把真实原因盖成「内容请求失败（HTTP 503）」。
- 设备注册失败单独提示「搜索需要先完成设备注册，请稍后重试」，和网络错误、服务端错误区分开。
- 服务端把注册身份收回时会退化成 HTTP 200 + 0 字节，这种情况报「搜索接口返回空响应，请稍后重试」，而不是「搜索数据结构已变化」。

## 本次只发布 Android 6.0+ 构建

- 本版只有 `hongguotv-kotlin-0.1.12-android6.apk`（minSdk 23）。
- Android 4.4 的构建这次不发布：`android44-support` 那条线继续本地验证，验收后另行发版。
- 包名与签名不变（与 0.1.11 同一把 CI 密钥），已装 0.1.11 可直接覆盖安装，本机收藏与观看进度保留。

## 验证

- 本机探针对真实接口：写死的旧身份请求 App 搜索接口是 HTTP 200 + 0 字节；换成注册身份后同一个关键词「山中秘法」返回 HTTP 200 + 60787 字节，第 1 页 6 条（第一条即「山中秘法 / 全 50 集」），第 2 页 19 条，游标翻页正常。
- `:core:test` 240 项全过，新增覆盖「搜索只用注册身份」「不再回退网页接口」「注册失败报专门错误」。
- GitHub Actions 会跑 `:core:test`、`:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`、`:app:assembleRelease`，核对包名 / versionName / minSdk 后才发布。

## 安装

- 直连：https://github.com/PangTouY00/hongguoTV/releases/download/kotlin-v0.1.12/hongguotv-kotlin-0.1.12-android6.apk
- 镜像加速：把 `https://github.com/` 换成 `https://gh-proxy.com/https://github.com/`。
- 也可以在 App 内「设置 → 检查更新」直接升级。

如果搜索还是失败，把屏幕上那行错误原样发我：是「搜索需要先完成设备注册」「搜索接口返回空响应」还是「搜索接口暂不可用」，分别对应注册、取数、服务端限流三个方向。
