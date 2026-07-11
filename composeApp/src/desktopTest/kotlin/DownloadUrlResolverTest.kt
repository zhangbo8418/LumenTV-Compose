package com.corner.util.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class DownloadUrlResolverTest {

    @Test
    fun detectsMagnetLink() {
        assertTrue(DownloadUrlResolver.isDownloadLink("magnet:?xt=urn:btih:abc"))
    }

    @Test
    fun detectsHttpTorrent() {
        assertTrue(DownloadUrlResolver.isDownloadLink("https://example.com/file.torrent"))
        assertTrue(DownloadUrlResolver.isDownloadLink("https://example.com/movie.mp4?token=1"))
    }

    @Test
    fun decodesThunderLink() {
        val real = "magnet:?xt=urn:btih:test"
        val encoded = Base64.getEncoder().encodeToString("AA${real}ZZ".toByteArray())
        val thunder = "thunder://$encoded"
        assertEquals(real, DownloadUrlResolver.resolve(thunder))
    }

    @Test
    fun ignoresRegularM3u8() {
        assertTrue(!DownloadUrlResolver.isDownloadLink("https://example.com/live.m3u8"))
    }
}
