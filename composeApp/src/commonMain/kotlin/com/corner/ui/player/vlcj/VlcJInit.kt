package com.corner.ui.player.vlcj

import com.corner.util.settings.SettingStore
import com.corner.ui.player.PlayerLifecycleManager
import com.corner.ui.player.VodPlaybackHost
import com.corner.ui.scene.SnackBar
import com.corner.util.thisLogger
import com.corner.ui.getPlayerSetting
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery

/**
 * 点播内嵌 VLC 进程级单例：启动创建一次，进出详情 stopAsync/load，退出 App 才 release。
 * 实验分支（LibVLC 4 / vlcj-5）：依赖本机 VLC 4 nightly，见 docs/vlcj5-libvlc4.md。
 */
class VlcJInit {
    companion object {
        private val log = thisLogger()
        private val lock = Any()

        @Volatile
        private var controller: VlcjFrameController? = null
        @Volatile
        private var lifecycleManager: PlayerLifecycleManager? = null
        @Volatile
        private var isReleased = false
        @Volatile
        private var boundHost: VodPlaybackHost? = null

        fun getController(): VlcjFrameController? = controller

        fun getLifecycleManager(): PlayerLifecycleManager? = lifecycleManager

        /**
         * 懒建全局播放器（幂等）。启动或首次进入详情时调用。
         */
        fun ensureCreated(): VlcjFrameController {
            synchronized(lock) {
                isReleased = false
                val existing = controller
                if (existing != null && !existing.isReleased()) {
                    if (!existing.hasPlayer()) {
                        existing.vlcjFrameInit()
                    }
                    return existing
                }
                log.info("创建点播 VLC 单例")
                val fc = VlcjFrameController()
                val lm = PlayerLifecycleManager(fc)
                fc.vlcController().setLifecycleManager(lm)
                fc.vlcjFrameInit()
                controller = fc
                lifecycleManager = lm
                return fc
            }
        }

        fun bindHost(host: VodPlaybackHost) {
            val ctrl = ensureCreated()
            synchronized(lock) {
                boundHost = host
                ctrl.bindHost(host)
            }
            log.debug("绑定点播 PlaybackHost")
        }

        fun unbindHost(host: VodPlaybackHost) {
            synchronized(lock) {
                if (boundHost === host) {
                    boundHost = null
                    controller?.unbindHost(host)
                    log.debug("解绑点播 PlaybackHost")
                }
            }
        }

        /**
         * 离开页第一步：只关渲染开关 + 清帧引用，不调 libvlc、不碰 Compose Snapshot。
         * 可在 Composition onDispose 里安全调用。
         */
        fun beginLeavePlayback() {
            val ctrl = controller ?: return
            runCatching { ctrl.beginLeavePlayback() }
        }

        /**
         * @deprecated 易在 Composition dispose 时同步进 libvlc 触发 snapshot 崩溃；改用 [beginLeavePlayback] + [stopPlayback]。
         */
        fun stopPlaybackSync() {
            beginLeavePlayback()
            val ctrl = controller ?: return
            // 仍同步 pause，但不再 detach surface（detach 放到异步 endPlayback）
            runCatching { ctrl.vlcController().endPlayback() }
        }

        /** 离开详情：对齐 TV suspend，非阻塞停播，保留原生实例 */
        suspend fun stopPlayback() {
            val ctrl = controller ?: return
            runCatching { ctrl.endPlayback() }
        }

        suspend fun play(url: String) {
            val ctrl = ensureCreated()
            ctrl.loadURL(url, 15_000L)
        }

        /**
         * 初始化 vlcj（native 发现）并预创建单例播放器。
         */
        fun init(notify: Boolean = false) {
            val discover = NativeDiscovery().discover()
            if (!discover && SettingStore.getPlayerSetting()[0] as Boolean) {
                SnackBar.postMsg(
                    "未找到 VLC 4 组件，请安装 VLC 4 nightly 或在设置中配置路径（见 docs/vlcj5-libvlc4.md）",
                    type = SnackBar.MessageType.ERROR
                )
            }
            if (notify) SnackBar.postMsg("VLC加载${if (discover) "成功" else "失败"}", type = SnackBar.MessageType.INFO)
            runCatching { ensureCreated() }
                .onFailure { e -> log.warn("预创建点播 VLC 失败: {}", e.message) }
        }

        /**
         * 仅应用退出时真正释放。
         */
        fun release() {
            if (isReleased) {
                log.debug("VLC已全局释放，跳过当前操作")
                return
            }
            synchronized(lock) {
                if (isReleased) return
                isReleased = true
                try {
                    log.info("Stop Vlcj Player")
                    boundHost = null
                    controller?.let { ctrl ->
                        ctrl.vlcController().currentHost()?.let { h -> ctrl.unbindHost(h) }
                        if (!ctrl.isReleased()) {
                            runCatching { ctrl.release() }
                        }
                        runCatching { ctrl.dispose() }
                    }
                } catch (e: Throwable) {
                    log.error("VLC释放异常", e)
                } finally {
                    controller = null
                    lifecycleManager = null
                }
            }
        }
    }
}
