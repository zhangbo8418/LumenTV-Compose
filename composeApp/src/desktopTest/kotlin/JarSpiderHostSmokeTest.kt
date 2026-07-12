package com.corner.catvodcore.loader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 JarSpiderHost 能拉起并完成 ping（不依赖具体 CSP 源）。
 */
class JarSpiderHostSmokeTest {

    @Test
    fun hostPingWorks() {
        val pong = JarSpiderHostClient.call("ping")
        assertEquals("pong", pong)
        // clear 应能杀掉进程且不抛
        JarSpiderHostClient.clear()
        // 再次 ping 会重建
        assertEquals("pong", JarSpiderHostClient.call("ping"))
        JarSpiderHostClient.clear()
        assertTrue(true)
    }
}
