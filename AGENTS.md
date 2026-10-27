# Repository workflow

- 后续 Git 提交标题和提交说明使用中文，清楚描述具体修改内容及必要原因；代码标识符、命令和版本号可保留原文。
- 提交标题使用 Conventional Commits 类型前缀，例如 `feat:`、`fix:`、`docs:`、`test:`，前缀后的描述及提交正文继续使用中文。

- The stable branch is `main`. Keep existing upstream history and GPL notices.
- The user prefers flat branch names without grouping prefixes. Use `feature-...` and `fix-...`; `android6-support` records the completed compatibility work.
- `main` supports Android 6+ (minSdk 23), including Android 8+. The user explicitly authorized merging the verified Android 6 compatibility feature as the common baseline. Keep a single APK/version line; check both API 23 and API 26 when changing native compatibility.
- For subsequent features and fixes, branch from the latest `main` using `feature-<short-description>` or `fix-<short-description>`. Implement and validate there, then use a pull request to merge into `main`; do not directly push new feature work to `main` unless the user explicitly authorizes it.
- The initial migration of the existing working features into this repository's `main` is authorized directly by the user.
- The active Android client is `kotlin-tv/`. Keep historical React Native sources unless the task specifically concerns their removal.
- Keep README.md in sync whenever adding, changing, or removing user-facing features. Update platform-specific README and usage/limitations documents when relevant; describe only implemented behavior and distinguish unverified or unavailable capabilities. Include documentation updates in the same change.
- Run relevant checks. Native changes normally require `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`; documentation-only changes do not need Android builds.
- APKs, build output, account sessions, phone numbers, verification codes, passwords, and signing keys must not be committed. Credentials must not appear in logs or documentation.
- Preserve package identity and signing reuse for updates. Increase `versionCode` for each formal release; keep versionName/versionCode unchanged during debugging and distinguish debug APKs by filename. Current TV release is 0.1.10 (versionCode 51); increase versions only for the next formal release.
- App updates use only the user's own repository `PangTouY00/hongguoTV` Releases on GitHub; `tsukiyo-zero/hongguoTV` on GitCode stays a mirror. A source push or CI artifact is not a release. Follow `kotlin-tv/AUTO_UPDATES.md`; do not reintroduce the upstream cross-repository publishing workflow.
- 用户无需每次指定 TV 版本：默认递增补丁版本，versionCode 超过源码与已发布正式包的最大值；只有用户明确要求正式发布时才发版，不为发布流程的内部步骤反复要求确认。
- 正式包的构建与发布在 GitHub Actions 中完成：先把已验收的 PR 合入 `main`，再在合并提交上打 `kotlin-v<版本>` 标签并推送，`.github/workflows/release-tv.yml` 会跑完 `:core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`，用与调试包同一把的缓存签名密钥构建 `:app:assembleRelease`，核对包名／版本／minSdk，生成 `update.json` 与中文更新说明，最后发布为正式 Release。分支推送只产生调试包的预发布。
- 发布说明分两份：`release-notes/<标签>.md` 是给人读的完整更新日志，只进 Release 正文与 `release-notes.md` 附件；`release-notes/<标签>.update.md` 是 App 内显示、不超过 1500 字的短摘要。完整日志超过 1500 字时必须同时提供短摘要，否则 `prepare-kotlin-release.py` 会拒绝；缺短摘要时工作流用 `scripts/shorten-release-notes.py` 按段落截断兜底。
- 本机没有 JDK 与 Android SDK，不在本机跑 Gradle；`scripts/release-tv.py`、`scripts/publish-tv-release.py` 保留用于版本规划、两平台同步与中断续传，不作为本机构建入口。

- The user prefers to install APKs and perform manual emulator/TV tests themselves, then send screenshots. Run automated checks and package locally; do not operate the emulator or install test builds unless explicitly requested again.

- The native Mac client lives in `desktop-mac/`. The user authorized merging the tested Mac client into `main`; continue future Mac development on the long-lived `feature-mac-client` branch, retaining it after PR merges. Sync from `main` as needed. TV remains the priority; Mac has independent version/build numbers and a fixed `mac-stable` GitCode Release with `mac-update.json`, never marked Latest.
