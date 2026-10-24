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
- Preserve package identity and signing continuity for updates. Increase `versionCode` for each formal release; keep versionName/versionCode unchanged during debugging and distinguish debug APKs by filename. Current TV release is 0.1.4 (versionCode 45); increase versions only for the next formal release.
- App updates use only `tsukiyo-zero/hongguoTV` Releases. A source push or CI artifact is not a release. Follow `kotlin-tv/AUTO_UPDATES.md`; do not reintroduce the upstream cross-repository publishing workflow.

- The user prefers to install APKs and perform manual emulator/TV tests themselves, then send screenshots. Run automated checks and package locally; do not operate the emulator or install test builds unless explicitly requested again.

- The native Mac client lives in `desktop-mac/`. The user authorized merging the tested Mac client into `main`; continue future Mac development on the long-lived `feature-mac-client` branch, retaining it after PR merges. Sync from `main` as needed. TV remains the priority; Mac has independent version/build numbers and a fixed `mac-stable` GitCode Release with `mac-update.json`, never marked Latest.
