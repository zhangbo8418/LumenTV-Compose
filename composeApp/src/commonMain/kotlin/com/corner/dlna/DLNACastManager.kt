package com.corner.dlna

import com.corner.util.scope.createCoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.jupnp.DefaultUpnpServiceConfiguration
import org.jupnp.UpnpServiceImpl
import org.jupnp.model.message.header.STAllHeader
import org.jupnp.model.meta.RemoteDevice
import org.jupnp.model.types.UDADeviceType
import org.jupnp.model.types.UDAServiceType
import org.jupnp.registry.DefaultRegistryListener
import org.jupnp.registry.Registry
import org.slf4j.LoggerFactory

object DLNACastManager : DefaultRegistryListener() {
    private val log = LoggerFactory.getLogger("DLNACastManager")
    private val scope = createCoroutineScope()
    private val rendererType = UDADeviceType("MediaRenderer", 1)
    val avtType = UDAServiceType("AVTransport", 1)
    private var service: UpnpServiceImpl? = null
    val devices = MutableStateFlow<List<CastDevice>>(emptyList())

    fun init() {
        if (service != null) {
            search()
            return
        }
        service = UpnpServiceImpl(DefaultUpnpServiceConfiguration()).also {
            it.startup()
            it.registry.addListener(this)
            search()
        }
        log.info("DLNA 投屏服务已启动")
    }

    fun search() {
        service?.controlPoint?.search(STAllHeader())
    }

    fun release() {
        service?.let {
            it.registry.removeListener(this)
            it.shutdown()
        }
        service = null
        devices.value = emptyList()
    }

    fun findDevice(device: CastDevice): RemoteDevice? {
        return service?.registry?.getDevices(rendererType)
            ?.map { it as RemoteDevice }
            ?.firstOrNull { it.identity.udn.identifierString == device.uuid }
    }

    fun findAVTransport(device: CastDevice) = findDevice(device)?.findService(avtType)

    fun getControlPoint() = service?.controlPoint

    override fun remoteDeviceAdded(registry: Registry, device: RemoteDevice) {
        if (device.findService(avtType) != null) {
            val bean = CastDevice.from(device)
            devices.update { current ->
                if (current.any { it.uuid == bean.uuid }) current else current + bean
            }
        }
    }

    override fun remoteDeviceRemoved(registry: Registry, device: RemoteDevice) {
        if (device.findService(avtType) != null) {
            val uuid = device.identity.udn.identifierString
            devices.update { it.filterNot { d -> d.uuid == uuid } }
        }
    }
}
