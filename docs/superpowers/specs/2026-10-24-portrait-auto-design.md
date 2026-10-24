# 竖屏能力增强：失败自学习 + 解码失败自动降档（设计 v2）

日期：2026-10-24
前置：docs/superpowers/specs/2026-10-24-portrait-decode-design.md（静态探测 + moov 复核已上线）。
分支：fix-portrait-autolearn（叠在 fix-av-sync-drift 之上）。

## 背景

真机实测（用户，2026-10-24）：盒子的 `MediaCodecList` 对竖屏尺寸**虚报支持**（静态可解、实际解码失败），预检放行 1080P 后运行时报"电视无法解码"，需手动"尝试 720P→480"逐档降级。预检缺第三环：设备运行时行为的反馈。

## 目标行为

1. 解码类失败（PlaybackException 3000–4999）发生时：把当前流 `(编码, 宽×高)` 记入**持久化经验表**（"此设备实测解不了"），跨重启、跨剧集生效。
2. 学习后**自动重试**当前集：`openStream` 的能力谓词优先读经验表，阶梯选择自然落到下一个可解档位（HUD 提示"设备不支持该清晰度，自动切换…"）；每集自动降档至多 2 次，超出走现有错误面板。
3. 经验按 `(编码,尺寸)` 记忆：竖屏 1080×1920 失败不影响横屏 1920×1080 继续选 1080P——横屏/竖屏分别取最优。
4. 网络类错误（2xxx/IOException）不学习、不自动降档（走既有自动重试）。
5. 播放设置新增"重置设备能力记录"（应对固件升级后能力变化）；整条阶梯学死时保持"兼容资源"兜底。
6. 虚报事件（静态支持但实测失败）打日志，便于诊断。

## 组件与改动（全部 app 层，core 不动）

- 新增 `DecoderLessons`（纯 Kotlin，可单测）：经验表——`remember(codec,w,h)` / `fails(codec,w,h?)` / `reset()`，键为 `codec|w|h`（mime 归一化复用 CodecCapabilities.mime）。
- `CodecCapabilities`：启动 `init(context)` 注入 SharedPreferences（"native-decoder-lessons-v1"）；`supports()` 先查经验表；`learnFailure(track/rendition)` 返回是否为虚报（静态支持而实测失败）并落盘；`resetLearned()`。
- `PlaybackSources`：`OpenedStream` 增加 `track: VideoTracks.Track?`；新增纯函数 `shouldAutoDowngrade(attempts, tiers, currentQuality)`。
- `MainActivity`：记录 `currentTrack/currentTiers`；`playerFailure` 解码分支先学习再自动重播（`playEpisode` 复入，计数器防循环，HUD 短提示）；播放设置菜单加"重置设备能力记录"。

## 错误处理

- 学习与自动降档只针对解码错误；音频错误（4xxx）仅学习不降档（音频与尺寸无关）→ 简化：4xxx 一并按现有面板处理，不学习。学习键只含视频轨。
- track 与 rendition 元数据都缺失（无法定位能力键）时不学习，仅自动降档 1 次。

## 测试

- app 单测：`DecoderLessonsTest`（记忆/命中/重置/键归一化）、`shouldAutoDowngrade`（阈值、次数、无更低档）。
- 回归：`PlaybackSourcesTest`、既有全部用例。
- 真机（用户）：竖屏 1080P 首次失败后自动切 480P 播放（无手动操作）；第二集起直接 480P 无先失败；横屏仍 1080P；重置后恢复原始行为。

## 不做的事

- 不内置解码测试探针（方案 B）；不做全局保守规则（方案 C）；不回改 core 的阶梯逻辑。
