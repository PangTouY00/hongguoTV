# 全部搜索（0.9.1-local）

搜索页移除漫剧/短剧分类按钮，统一使用网站搜索，可同时返回两种内容。首页仍保留分类，搜索不再改变首页的分类设置。手机输入和旧搜索历史均使用全部搜索，历史列表按关键词去重显示，不再显示旧类型标签。

保留此前的播放兼容修复。新增 `ContentRepository.searchAll()` 直接访问网站搜索，不再先等待分类搜索接口失败。遥控器从顶部导航向下进入搜索框，从结果卡片向上返回搜索框。

验证：87 项 JVM 测试通过；Debug APK 构建及签名校验通过。实际 Kotlin 探针搜索“大佬”，第 1、2 页各返回 10 部剧集，含漫剧内容。尚待用户电视验证焦点与播放体验。

产物：`outputs/hongguotv-0.9.1-all-search-20261021-debug.apk`。包名保持不变，versionCode 从 9 升至 10，沿用上次本地开发签名，可覆盖本次会话交付的 0.9.0 修复开发版并保留本机记录。不能覆盖其他签名的原项目正式版。

设置好 JDK 17 和 Android SDK 后，在仓库根目录执行：

```sh
HONGGUOTV_VERSION_NAME=0.9.1-local HONGGUOTV_VERSION_CODE=10 \
  ./kotlin-tv/gradlew -p kotlin-tv :core:test :app:assembleDebug
./kotlin-tv/gradlew -p kotlin-tv :core:run --args="--search-all 大佬"
```
