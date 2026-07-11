package com.corner.dlna

import com.corner.server.KtorD
import com.corner.ui.scene.SnackBar
import com.corner.util.net.NetworkUtil
import org.jupnp.controlpoint.ControlPoint
import org.jupnp.model.action.ActionInvocation
import org.jupnp.model.message.UpnpResponse
import org.jupnp.model.meta.RemoteService
import org.jupnp.support.avtransport.callback.Play
import org.jupnp.support.avtransport.callback.Seek
import org.jupnp.support.avtransport.callback.SetAVTransportURI
import org.jupnp.support.contentdirectory.DIDLParser
import org.jupnp.support.model.DIDLContent
import org.jupnp.support.model.DIDLObject
import org.jupnp.support.model.ProtocolInfo
import org.jupnp.support.model.Res
import org.jupnp.support.model.SeekMode
import org.jupnp.support.model.item.VideoItem
import org.jupnp.support.model.item.Item
import com.corner.util.json.Jsons
import java.util.Locale

data class CastVideo(
    val name: String,
    val url: String,
    val position: Long = 0L,
    val headers: Map<String, String> = emptyMap(),
) {
    fun resolvedUrl(): String {
        var resolved = url
        if (resolved.startsWith("file", ignoreCase = true)) {
            val relative = resolved
                .removePrefix("file://")
                .removePrefix("file:/")
            val suffix = if (relative.startsWith("/")) relative else "/$relative"
            resolved = "http://${NetworkUtil.getLanIp()}:${KtorD.getPort()}/file$suffix"
        }
        if (resolved.contains("127.0.0.1")) {
            resolved = resolved.replace("127.0.0.1", NetworkUtil.getLanIp())
        }
        return resolved
    }
}

class DLNACast(
    private val video: CastVideo,
    private val onSuccess: () -> Unit = {},
) {
    fun cast(device: CastDevice) {
        val control = DLNACastManager.getControlPoint()
        val service = DLNACastManager.findAVTransport(device)
        if (service == null || control == null) {
            SnackBar.postMsg("设备离线", type = SnackBar.MessageType.WARNING)
            return
        }
        control.execute(uriAction(control, service))
    }

    private fun buildMetaData(): String {
        return try {
            val content = DIDLContent()
            val playUrl = video.resolvedUrl()
            val item: Item = VideoItem(
                "0",
                "-1",
                video.name,
                "",
                Res(ProtocolInfo("http-get:*:video/*:*"), 0L, playUrl),
            )
            if (video.headers.isNotEmpty()) {
                item.addProperty(DIDLObject.Property.DC.DESCRIPTION(Jsons.encodeToString(video.headers)))
            }
            content.addItem(item)
            DIDLParser().generate(content)
        } catch (_: Exception) {
            ""
        }
    }

    private fun uriAction(control: ControlPoint, service: RemoteService): SetAVTransportURI {
        val playUrl = video.resolvedUrl()
        return object : SetAVTransportURI(service, playUrl, buildMetaData()) {
            override fun success(invocation: ActionInvocation<*>) {
                control.execute(playAction(control, service))
            }

            override fun failure(invocation: ActionInvocation<*>, operation: UpnpResponse, defaultMsg: String) {
                SnackBar.postMsg(defaultMsg, type = SnackBar.MessageType.ERROR)
            }
        }
    }

    private fun playAction(control: ControlPoint, service: RemoteService): Play {
        return object : Play(service) {
            override fun success(invocation: ActionInvocation<*>) {
                if (video.position > 0) control.execute(seekAction(service))
                onSuccess()
            }

            override fun failure(invocation: ActionInvocation<*>, operation: UpnpResponse, defaultMsg: String) {
                SnackBar.postMsg(defaultMsg, type = SnackBar.MessageType.ERROR)
            }
        }
    }

    private fun seekAction(service: RemoteService): Seek {
        return object : Seek(service, SeekMode.REL_TIME, formatMs(video.position)) {
            override fun success(invocation: ActionInvocation<*>) = Unit
            override fun failure(invocation: ActionInvocation<*>, operation: UpnpResponse, defaultMsg: String) = Unit
        }
    }

    private fun formatMs(ms: Long): String {
        if (ms <= 0) return "00:00:00"
        val s = ms / 1000
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }
}
