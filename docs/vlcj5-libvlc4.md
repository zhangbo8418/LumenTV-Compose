# 实验分支：vlcj-5 + LibVLC 4

分支：`experiment/vlcj5-libvlc4`

目标：用 LibVLC 4 的 **`libvlc_media_player_stop_async`**（vlcj：`controls().stopAsync()`）替代 3.x 下「同步 stop 易死锁 → 只能用 pause 绕过」的策略。

## 前置条件

1. **Java 绑定**：`uk.co.caprica:vlcj:5.0.0-M4`（依赖 `vlcj-natives`，Gradle 会拉取）
2. **本机原生库**：必须是 **VLC 4.0 nightly**，不能用系统里的 VLC 3.x  
   - 下载：[VideoLAN Nightlies](https://nightlies.videolan.org/)  
   - macOS：选对应架构的 VLC 4.0（Intel / Apple Silicon）  
   - Windows：VLC 4.0 64-bit  
3. 若自动发现失败：设置页填写 **VLC 4** 可执行文件路径（走现有 `CustomDirectoryDiscovery`）

> Nightly **不稳定、无官方支持**。本分支仅供验证，不要当正式发行默认。

## 代码改动要点

| 位置 | 行为 |
|---|---|
| `VlcjController` | 换集 / 离页 / `stop` → `stopAsync()`；`engineStart` 前先 `stopAsync` 再 `prepare` |
| `LiveVlcjController` | 换台 / 停播同样用 `stopAsync()` |
| `VlcjFrameRenderer` | vlcj-5：`CallbackVideoSurface` 构造去掉 `VideoSurfaceAdapter` |

勿在热路径调用 latch 版 `controls().stop()`（会等 `stopped` 事件，换集仍可能拖慢）。

## 本地验证清单

- [ ] 启动能发现 LibVLC 4（日志无「未找到 VLC」）
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
