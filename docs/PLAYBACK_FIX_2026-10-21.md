# 2026-10-21 本地播放兼容修复

适用症状：首页、搜索正常，开始播放后失败或反复重试。参考上游 [Issue #17](https://github.com/N3urda/hongguoTV/issues/17)、[Issue #19](https://github.com/N3urda/hongguoTV/issues/19) 和 [PR #22](https://github.com/N3urda/hongguoTV/pull/22)。社区结论只反映当时接口行为，不代表接口会永久保持可用。

## 修改

- 播放请求使用独立设备标识，不再附带搜索请求的签名头；同一仓库实例的标识保持稳定。
- 搜索沿用原签名与参数，播放解析、清晰度筛选、解密、请求取消和响应大小限制沿用原逻辑。
- 空播放响应给出明确提示，上游拒绝请求时显示错误码。

## 验证

- `:core:test`：85 项测试通过，无失败、错误或跳过，包含新增播放请求与空响应回归检查。
- `:app:assembleDebug`：成功；安装包签名校验通过，最低 Android API 26（Android 8.0）。
- `:core:run`：实际 Kotlin 代码联网验证通过，搜索返回 10 部、首页 24 部；测试剧集 86 集，取得 1080P 资源；读取视频头部 1 MiB 和尾部 64 KiB，MP4 标识为 `ftyp`。
- 尚未在用户实体电视验证遥控器操作、芯片解码与长时间播放。

## 自行构建

准备 JDK 17 与 Android SDK Platform 36，设置 `JAVA_HOME`、`ANDROID_HOME` 后，在仓库根目录执行：

```sh
./kotlin-tv/gradlew -p kotlin-tv :core:test :app:assembleDebug
./kotlin-tv/gradlew -p kotlin-tv :core:run
```

默认产物：`kotlin-tv/app/build/outputs/apk/debug/app-debug.apk`。

本次交付副本：`outputs/hongguotv-0.9.0-playback-fix-20261021-debug.apk`。

这是开发签名包，不能覆盖不同签名的已安装正式版。先在旧版设置中使用手机备份功能保存记录，再卸载旧版、安装本包并恢复记录。后续以同一开发密钥构建的版本可覆盖本包；请保留本机 Android 开发签名密钥。正式版的内置更新包与本包签名不同，不适用于此开发包。
