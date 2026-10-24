# 电视版竖屏视频解码失败修复设计（方案一：能力感知选流 + 自动降清）

日期：2026-10-24
范围：`kotlin-tv/`（Android TV 客户端）；不涉及 `desktop-mac/`、历史 RN 源码与 server。

## 背景与根因

- 症状：横屏视频可正常播放；竖屏视频报"电视无法解码当前视频，可尝试更低清晰度。"（`MainActivity.playerFailure`，错误码区间 3000–4999），且错误面板"尝试 720P"后仍失败。
- 根因（结合用户实测"720P 仍失败"锁定）：电视盒子硬解码器对**竖屏编码尺寸**的能力上限。竖屏 720P 实际编码尺寸 720×1280、1080P 为 1080×1920；常见盒子 AVC 解码器上报上限 1920×1088 且宽高独立校验，横屏 1920×1080 恰好通过、竖屏因高度超限被拒。
- 放大问题的代码缺陷：
  1. `ContentRepository.stream()` 选流只按清晰度数字、不看编码（字节系 CDN 高档位常仅 HEVC/bytevc1）；
  2. 无 ≤720P 档位时 `rows.lastOrNull()` 静默回退重放同一流，"尝试 720P"可能空转；
  3. 全链路无 `MediaCodecList` 能力感知。

## 目标行为

1. 选流能力优先：过滤掉盒子声明解不了的档位（按接口元数据的编码 + 宽高），在剩余档位中选 ≤ 用户上限的最高档，同档优先 H.264；全部不可解时保留现有"兼容资源"兜底（最高档照播）。
2. moov 实际尺寸校验：`RemoteVideo.prepare()` 解析出的视频头里读取实际 fourcc 与编码宽高（stsd 样本编码尺寸，非展示尺寸），超出能力则沿阶梯自动降档重取（有界，每档一次 256KB 探测），对用户透明。
3. 错误面板按真实可用档位生成"尝试 xP"按钮，消除空转重试。
4. 启动时打印一次设备 AVC/HEVC 解码器能力日志（tag `HongguoTV`），便于真机回传定位。

## 组件与改动

### core（纯 JVM，可单测）

- `ContentRepository`：
  - 新增 `Rendition(quality:Int, codec:String, width:Int?, height:Int?, url:String, key:ByteArray?)` 与 `StreamLadder`（全部可用档位，`select(maxQuality, supported:(Rendition)->Boolean)` 纯函数选择器）。
  - `streamLadder(id)` 一次接口调用构造阶梯（宽高取 `vwidth`/`vheight`，缺失为 null；解密 URL、派生密钥逻辑不变）。
  - `stream(id, maxQuality, supported={true})` 保留旧签名语义：内部走阶梯选择；默认谓词恒真时行为与现状一致（高档优先、排除 bytevc2）。
- 新增 `VideoTracks`：`videoTrack(decryptedHeader:ByteArray): Track(fourcc,width,height)?` 纯解析器——遍历 moov 的 trak，取含 `vmhd` 的视频轨，读 `stsd` 首样本 fourcc 与 VideoSampleEntry 的 width/height（偏移 +24/+26）。加密轨在 `MediaCrypto.prepare` 重写后已是原始 fourcc。
- `RemoteVideo` 暴露 `trackInfo()`（内部读 `prepared.header`），不泄露可变状态。
- 选择器排序键：能力可解优先 → 清晰度数字降序（≤maxQuality）→ H.264 优先（同清晰度 tie-break）→ 稳定保序。

### app

- 新增 `CodecCapabilities`（`android.media.MediaCodecList.REGULAR_CODECS` 封装）：codec_type/fourcc → MIME（h264/avc1/avc3→AVC；bytevc1/hvc1/hev1→HEVC；未知→按可解处理保持兼容）；`supports(codec, width, height)` 走各解码器 `videoCapabilities.isSizeSupported`；启动时打印能力日志；结果缓存。
- 新增共享取流入口 `openStream(id, maxQuality)`：取阶梯 → 按能力排序逐档 `RemoteVideo.prepare()` → `trackInfo()` 校验 → 返回首个可解的已备流；网络/解析异常照旧抛出，只有"能力不符"才降档。MainActivity 播放、下一集预取、首页试播（`PublicFeedBackend`）统一走该入口。
- `MainActivity.playerFailure`：错误面板按钮由当前阶梯生成（仅列真实存在且更低的档位，取相邻 1–2 个）；HUD 清晰度标签在自动降档时追加"（自动降清）"标记（沿用"（兼容资源）"既有样式）。

## 错误处理

- 阶梯为空 → 沿用"没有电视可播放的清晰度"异常文案。
- 全部档位能力不符 → 兼容资源兜底照播（现状语义），失败仍走现有错误面板。
- `prepare()` 的 IOException（网络、索引异常）不触发降档，按现有重试/自动恢复逻辑处理。
- 能力查询失败（个别 ROM 抛异常）→ 该 codec 视为可解，不阻断播放。

## 测试

- core 单测：阶梯选择（能力过滤、档位排序、H.264 tie-break、兼容兜底、元数据缺失）、`VideoTracks` 解析夹具（AVC/HEVC、竖屏/横屏、含音频多轨）、`stream()` 默认谓词语不变（既有 `ContentRepositoryTest` 期望保持）。
- app 单测：`openStream` 用替身阶梯 + 假能力谓词验证降档顺序与"能力不符才降档"；错误面板按钮生成逻辑。
- 自动化检查：`:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`。
- 真机验收（用户手动）：安装 debug APK（版本号不变、文件名区分）后：①原失败竖屏视频可自动降档播放；②横屏 1080P 回归；③回传 logcat（含启动能力日志与 Playback failure 行）与截图。

## 文档

`kotlin-tv/README.md` 增补：竖屏视频按设备解码能力自动降档的行为说明与限制（受盒子硬件上限约束时将以可解的最高档播放）。

## 不做的事

- 不引入软解（FFmpeg 扩展）——留作后续后手。
- 不调整播放接口请求参数。
- 不改版本号（调试阶段）；不改 Mac 客户端。
