# 红果 TV 0.1.24 更新摘要

这一版只改一件事：设备注册不再吊死在一个域名上。

上一版装上能播，过几小时又不行了，界面是「准备恢复播放 · 重试 x/3」，而首页、搜索、榜单全部正常。logcat 里每一轮取址都是 `PlaybackDeviceRegistrationException`——不是取址被拒，是设备注册这一步就失败了。

根因不在服务端：注册用的 `log5-applog-lf.fqnovel.com` 在走 AdGuard 的 WiFi 上被解析成 `0.0.0.0`（它对黑名单域名回的就是这个），同一台手机切到移动数据后同一批域名解析正常。被拦的恰好是 `log5-applog-*`、`log.snssdk`、`mon.snssdk` 这一串遥测域名；首页、搜索、榜单不需要注册，所以照常工作，看起来就像「界面没问题但播不了」。

改法：`DeviceRegister` 带两个候选域名（`is.snssdk.com`、`log5-applog-lf.fqnovel.com`）按顺序试，网络层失败或对该路径回非 2xx 的那个在进程内记下来，不再让每次播放都先撞一遍，成功的那个放回可用。

本机复测：`register ok 968ms http=200`，同一集取址 `ladder ok 651ms` 取到 1080；core 295 项测试通过。装机复测（WiFi，AdGuard 在跑）可以播放。

## 怎么验证

连着家里的 WiFi 点播放，应该直接出清晰度列表。如果哪天又播不了，先看 logcat 是不是 `PlaybackDeviceRegistrationException`：是这一条，说明两个注册域名都解析不通，去 AdGuard 的拦截记录里把 `fqnovel.com`、`snssdk.com` 加白名单就行，不用改 App。

覆盖安装即可，账号、收藏、观看进度与本机缓存记录都保留。
