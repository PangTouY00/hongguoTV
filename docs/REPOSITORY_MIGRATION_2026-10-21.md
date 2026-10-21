# 自用仓库迁移

目标：`git@github.com:tsukiyo-zero/hongguoTV.git`，稳定分支 `main`。

保留原 Git 历史与许可证，将当前全部搜索、播放修复、官方扫码登录、云端记录读取及电视登录状态保存修复作为现有功能基线。原远端保留为 `upstream`，用户仓库作为 `origin`。

后续使用 `codex/<功能名>` 分支开发，通过针对 `main` 的 PR 合并，详见 CONTRIBUTING.md 和 AGENTS.md。原生主分支和 PR 已配置构建检查。未通过 GitHub API 设置服务端分支保护，不宣称已强制禁止直推。

0.9.6 / versionCode 15 将应用内更新源改为本仓库 Releases，沿用此前本地签名。旧 0.9.5 需要手动覆盖安装一次迁移版。迁移源码没有发布 Release；APK 与 update.json 放在本地 outputs，发布步骤见 kotlin-tv/AUTO_UPDATES.md。旧跨仓库同步和发布模板已移除，防止向原作者仓库同步或使用其签名配置。

验证：99 项 JVM 测试通过，Android Debug 构建与 lint 通过。没有将本地签名、验证码、账号会话或构建产物提交到 Git。
