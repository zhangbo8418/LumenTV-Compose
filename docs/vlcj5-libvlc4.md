# 实验分支：vlcj-5 + LibVLC 4

分支：`experiment/vlcj5-libvlc4`

目标：用 LibVLC 4 的 **`libvlc_media_player_stop_async`**（vlcj：`controls().stopAsync()`）替代 3.x 下「同步 stop 易死锁 → 只能用 pause 绕过」的策略。

## 自动集成（推荐）

与 ffmpeg / JCEF 相同：**CI 打包时下载**，不把百兆 nightly 提交进 git。

| 入口 | 作用 |
|---|---|
| `./gradlew prepareBundledVlc` | 本机拉取当前平台最新 VLC 4 nightly → `appResources/<platform>/` |
| `./gradlew prepareBundledVlc -Plumen.vlc.platform=macos-arm64` | 指定平台 |
| `./gradlew prepareBundledVlc -Plumen.vlc.buildId=20260715-0413` | 固定某日构建 |
| GitHub Actions **Bundle LibVLC 4 nightlies** | 三端矩阵下载并上传 artifact |
| `github-action.yml` / `pre_release` | 正式打包前自动 `prepareBundledVlc` |

源站：[artifacts.videolan.org](https://artifacts.videolan.org/vlc/)（nightlies 索引见 [nightlies.videolan.org](https://nightlies.videolan.org/)）

- **macOS**：`vlc-macos-sdk-*.tar.gz` → `lib/libvlc*.dylib` + `plugins/`
- **Windows**：`vlc-4*-win64-*.zip` → `lib/libvlc.dll` + `lib/plugins/`
- **Linux**：暂未自动捆绑（仍可用系统 VLC）

解包会覆盖仓库里旧的 LibVLC 3 文件，但**保留** `libquickjs-java-wrapper.*`。写入标记文件 `.lumen-vlc-bundle`。

本地开发：

```bash
git checkout experiment/vlcj5-libvlc4
./gradlew prepareBundledVlc
./gradlew :composeApp:run
```

## 依赖

1. **Java 绑定**：`uk.co.caprica:vlcj:5.0.0-M4`
2. **原生库**：由 `prepareBundledVlc` / CI 提供；也可手动装 VLC 4 并在设置里填路径

> Nightly **不稳定、无官方支持**。本分支仅供验证。

## 代码改动要点

| 位置 | 行为 |
|---|---|
| `VlcjController` | 换集 / 离页 / `stop` → `stopAsync()`；`engineStart` 前先 `stopAsync` 再 `prepare` |
| `LiveVlcjController` | 换台 / 停播同样用 `stopAsync()` |
| `VlcjFrameRenderer` | vlcj-5：`CallbackVideoSurface` 构造去掉 `VideoSurfaceAdapter` |
| `vlc-bundle.gradle.kts` | 下载并安装 LibVLC 4 nightly |

勿在热路径调用 latch 版 `controls().stop()`（会等 `stopped` 事件，换集仍可能拖慢）。

## 本地验证清单

- [ ] `.lumen-vlc-bundle` 存在且 `major=4`
- [ ] Anime1（Cookie → `/video/proxy`）起播、换集、离开详情不卡 UI
- [ ] 直播咪咕 / 普通 HLS：换台有画面、无 JNA callback GC
- [ ] 拖动进度、倍速、静音状态正常
- [ ] 连续快速换集不出现「prepare 永远不回」

## 合并回 main 条件

- VLC 4.0 **正式版**发布，且 vlcj-5 **非 milestone** 可用  
- 或确认某一版 nightly + M4 在 Win/mac 双端回归通过，并单独评估捆绑发行风险

## 回退

```bash
git checkout main
```

主线仍为 **vlcj 4.10.x + LibVLC 3 + pause 热路径**。
