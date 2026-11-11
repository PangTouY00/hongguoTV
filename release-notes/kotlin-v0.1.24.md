# 红果 TV 0.1.24

版本：`versionName 0.1.24` / `versionCode 65`，支持 Android 6.0 及以上（minSdk 23）。覆盖安装即可，账号、收藏、观看进度与本机缓存记录都保留。

这一版只改一件事：设备注册不再吊死在一个域名上。

## 现象

0.1.23 装上之后能播，过了几个小时又不行了。界面上是「准备恢复播放 · 重试 x/3」，而首页、搜索、榜单全部正常。logcat 里每一轮取址都是同一个异常：

```
18:00:37 取址第1轮 新注册身份 账号Cookie=带 → PlaybackDeviceRegistrationException
18:00:38 取址第2轮 新注册身份 账号Cookie=不带 → PlaybackDeviceRegistrationException
18:00:39 取址第3轮 … → PlaybackDeviceRegistrationException
18:00:42 取址第4轮 … → PlaybackDeviceRegistrationException
18:00:42 Playback failure: PlaybackDeviceRegistrationException
```

不是取址被服务端拒，是**设备注册这一步就失败了**，四轮全卡在它上面。

## 根因不在服务端

注册接口用的 `log5-applog-lf.fqnovel.com`，在这台手机的 WiFi 上解析成 `0.0.0.0`。家里 WiFi 走 AdGuard，它对黑名单域名回的就是 `0.0.0.0`。同一台手机切到移动数据后，同一批域名解析全部正常：

| 域名 | WiFi（走 AdGuard） | 移动数据 |
|---|---|---|
| `log5-applog-lf.fqnovel.com` | `0.0.0.0` | 182.40.140.147 等四个真实地址 |
| `log.snssdk.com` | `0.0.0.0` | 140.249.88.41 等 |
| `mon.snssdk.com` | `0.0.0.0` | 182.40.140.148 等 |
| `is.snssdk.com` | 正常 | 正常 |

被拦的恰好是 `log5-applog-*`、`log.snssdk`、`mon.snssdk` 这一串遥测域名，而设备注册同样能用的 `is.snssdk.com` 不在列表里。首页、搜索、榜单不需要注册，所以它们照常工作——这就是「界面一切正常但就是播不了」。

一开始我把「解析成 `0.0.0.0`」当成平台改了 A 记录，这个判断是错的。换一次网络就能分清，这一步应该先做。

## 改法

`DeviceRegister` 带两个候选域名（`is.snssdk.com`、`log5-applog-lf.fqnovel.com`）按顺序试；网络层失败或对该路径回非 2xx 的那个在进程内记下来，不再让每次播放都先撞一遍；成功的那个放回可用。`PlaybackDeviceSession` 与 `CloudAccountClient` 在各自的注册请求上回报结果。

顺带把 `kotlin-tv/README.md` 的注册段落照实测重写，写清两个域名的可达性差异和怎么判断是本地 DNS 过滤还是服务端。

## 验证

本机探针：`register ok 968ms http=200 device_id=3014239252139508`，同一集 `ladder ok 651ms`，取到 1080。core 295 项测试通过。

装机复测（WiFi，AdGuard 在跑）：可以播放。

## 检查

GitHub Actions 已运行 `:core:test`、`:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`、`:app:assembleRelease`，全部通过后才发布本包。

## 怎么验证

1. 连着 WiFi（家里这套带 AdGuard 的网络）点播放，应该直接出清晰度列表。
2. 如果哪一天又播不了，先看 logcat 里是不是 `PlaybackDeviceRegistrationException`。是这一条，就说明两个注册域名都解析不通，这时候去 AdGuard 的拦截记录里看 `fqnovel.com` 和 `snssdk.com`，把它们加白名单即可，不用改 App。

## 安装与更新

- 下载本页附件 `hongguotv-kotlin-0.1.24-android6.apk`，覆盖安装。
- App 内「检查更新」读的是本仓库标记为 Latest 的正式 Release，附件同时带 `update.json`（含包名、版本、大小与 sha256）。
