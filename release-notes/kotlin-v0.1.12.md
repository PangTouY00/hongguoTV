# 红果 TV 0.1.12 更新说明

这一版把 Android 4.4 拉回正式发布线：同一个版本号、同一个 Release，同时提供 Android 6.0+ 与 Android 4.4 两个安装包。

## 低安卓终于跟上 0.1.11 的播放修复

0.1.11 修好了「播放接口只认匿名注册的设备身份、旧代码自己编 19 位随机 ID 导致整集播不了」这个问题，但当时低安卓（`android44-support`，Android 4.4）那条线没有发版。这一版把同一套修复完整合了进来：

- 首次播放前走 `POST /service/2/device_register/` 匿名注册，拿到服务端下发的 `device_id` / `iid` 再取流；首页试播、全屏播放、下一集预热共用同一份身份，`cdid` 全程不变，并发注册只发生一次。
- 注册下发的 `install_id` / `ttreq` / `store-region` / `store-region-src` 按白名单显式随播放请求发出。注册域和播放域是不同子域，这些 Cookie 交给 CookieJar 按域匹配发不出去，不显式回写即使「注册成功」也照样空响应。
- 播放接口偶发 HTTP 200 空响应时换新的 `_rticket` 重新签名再读，最多三次；同一签名重读通常还是空。
- 播放失败面板单独区分「播放设备注册失败」和「取不到播放地址」，不再让人反复换清晰度或去登录。
- 4.4 侧保留自己的播放链路：系统 `MediaPlayer` + 只监听本机的 HTTP 代理（负责带 Cookie/Range 转发、本地缓冲），不依赖 Media3（它要求 Android 5.0+）。OkHttp 仍是 3.12、BouncyCastle jdk15on、jsoup 1.11，字节码目标 Java 8。

## 两个包、一个版本号

- `hongguotv-kotlin-0.1.12-android6.apk`：Android 6.0+（minSdk 23），与 0.1.11 行为一致。
- `hongguotv-kotlin-0.1.12-android44.apk`：Android 4.4+（minSdk 19）。
- 两个包同为 versionName 0.1.12 / versionCode 53，包名相同、签名相同（同一把 CI 密钥），已装同签名版本可直接覆盖安装并保留本机记录。
- 更新清单 `update.json` 新增 `variants`：Android 4.4 的设备自动选 android44 包，6.0+ 选 android6 包；还不认识 `variants` 的旧客户端仍读清单顶层的 android6 包，行为不变。

## 升级注意

- **已经装着 4.4 旧包（0.1.11 之前）的设备，这次需要手动装一次** android44 包：旧版本的更新检查只认识 android6 清单，会把 0.1.12 判成「需要 Android API 23」。装上这一版之后，后续版本就能在应用内自动更新了。
- Android 6.0+ 设备直接覆盖安装，或在应用内「检查更新」。

## 验证

- CI 分别构建并校验两个包：`:core:test`、`:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`、`:app:assembleRelease` 全过；核对包名、versionName、minSdk，并确认两个包签名指纹一致后才发布。
- 真机播放效果仍需装机确认。播不了时先点「查看原因」：出现「播放设备注册失败」是注册没通过，没有这句说明身份已拿到，问题在后面的取址或解码，方向不同。

## 安装

- 直连（Android 6.0+）：https://github.com/PangTouY00/hongguoTV/releases/download/kotlin-v0.1.12/hongguotv-kotlin-0.1.12-android6.apk
- 直连（Android 4.4）：https://github.com/PangTouY00/hongguoTV/releases/download/kotlin-v0.1.12/hongguotv-kotlin-0.1.12-android44.apk
- 镜像加速把 `https://github.com/` 换成 `https://gh-proxy.com/https://github.com/` 即可。
