# Repository workflow

- The stable branch is `main`. Keep existing upstream history and GPL notices.
- The user prefers flat branch names without `codex/` or other grouping prefixes. Use `feature-...` and `fix-...`; `android6-support` records the completed compatibility work.
- `main` supports Android 6+ (minSdk 23), including Android 8+. The user explicitly authorized merging the verified Android 6 compatibility feature as the common baseline. Keep a single APK/version line; check both API 23 and API 26 when changing native compatibility.
- For subsequent features and fixes, branch from the latest `main` using `feature-<short-description>` or `fix-<short-description>`. Implement and validate there, then use a pull request to merge into `main`; do not directly push new feature work to `main` unless the user explicitly authorizes it.
- The initial migration of the existing working features into this repository's `main` is authorized directly by the user.
- The active Android client is `kotlin-tv/`. Keep historical React Native sources unless the task specifically concerns their removal.
- Run relevant checks. Native changes normally require `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`; documentation-only changes do not need Android builds.
- APKs, build output, account sessions, phone numbers, verification codes, passwords, and signing keys must not be committed. Credentials must not appear in logs or documentation.
- Preserve package identity and signing continuity for updates. Increase `versionCode` for each distributed APK.
- App updates use only `tsukiyo-zero/hongguoTV` Releases. A source push or CI artifact is not a release. Follow `kotlin-tv/AUTO_UPDATES.md`; do not reintroduce the upstream cross-repository publishing workflow.
