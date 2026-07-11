package com.corner.service.player

import com.corner.catvodcore.bean.Result
import com.corner.catvodcore.bean.Episode
import com.corner.catvodcore.bean.v
import com.corner.ui.player.PlayerLifecycleManager
import com.corner.ui.player.PlayerLifecycleState.*
import com.corner.ui.player.vlcj.VlcjFrameController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import org.slf4j.LoggerFactory

/**
 * 内部播放器策略（Innie）
 *
 * 使用VLCJ嵌入式播放器进行播放
 */
class InniePlayerStrategy(
    private val controller: VlcjFrameController,
    private val lifecycleManager: PlayerLifecycleManager,
    private val viewModelScope: CoroutineScope
) : PlayerStrategy {

    private val log = LoggerFactory.getLogger(InniePlayerStrategy::class.java)

    override suspend fun play(
        result: Result,
        episode: Episode,
        onPlayStarted: () -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            if (lifecycleManager.lifecycleState.value == Idle) {
                log.debug("<Innie> 播放器未初始化，开始初始化...")
                val initResult = lifecycleManager.initializeSync()
                if (initResult.isFailure) {
                    onError("播放器初始化失败: ${initResult.exceptionOrNull()?.message}")
                    return
                }
            }

            val loadSuccess = loadMediaUrl(result, onError)
            if (loadSuccess) {
                onPlayStarted()
            }
        } catch (e: CancellationException) {
            log.debug("播放已取消")
            throw e
        } catch (e: Exception) {
            log.error("内部播放器播放失败", e)
            onError("播放器初始化失败: ${e.message}")
        }
    }

    override fun getStrategyName(): String = "InniePlayer"

    private suspend fun loadMediaUrl(result: Result, onError: (String) -> Unit): Boolean {
        return try {
            controller.loadURL(result.url.v(), PlayerStrategyConfig.INNIE_LOAD_URL_TIMEOUT_MS)
            true
        } catch (e: CancellationException) {
            log.debug("loadURL 已取消")
            false
        } catch (e: Exception) {
            onError("加载媒体失败: ${e.message}")
            false
        }
    }
}
