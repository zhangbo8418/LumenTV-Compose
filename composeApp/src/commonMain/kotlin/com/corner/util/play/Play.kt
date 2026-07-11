package com.corner.util.play

import PotPlayer
import cn.hutool.core.util.ZipUtil
import com.corner.util.settings.SettingStore
import com.corner.catvodcore.bean.Result
import com.corner.catvodcore.bean.v
import com.corner.util.io.Paths
import com.corner.ui.getPlayerSetting
import com.corner.ui.scene.SnackBar
import com.corner.util.core.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okio.Path.Companion.toPath
import org.apache.commons.lang3.StringUtils
import org.apache.commons.lang3.SystemUtils
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FilenameFilter
import java.util.*

private val log = LoggerFactory.getLogger("Play")

class Play {
    companion object{
        fun start(result:Result?, title: String?, subtitleUrl: String? = null){
            CoroutineScope(Dispatchers.IO).launch{
                getProcessBuilder(result, title, subtitleUrl)?.start()
            }
        }
        fun start(url: String, title: String?, subtitleUrl: String? = null){
            CoroutineScope(Dispatchers.IO).launch{
                getProcessBuilder(url, title, subtitleUrl)?.start()
            }
        }
    }
}

/**
 * potplauer
 * vlc
 * mpc-be
 */
fun getProcessBuilder(result: Result?, title: String?, subtitleUrl: String? = null): ProcessBuilder? {
    if (result == null) return null
    val playerPath = SettingStore.getPlayerSetting()[1] as String

    if(SystemUtils.IS_OS_MAC){
        return if(checkPlayer(playerPath)){
         ProcessBuilder("open", "-a", playerPath, result.url.v()).redirectOutput(Paths.playerLog())
        }else{
            ProcessBuilder("open", result.url.v()).redirectOutput(Paths.playerLog())
        }
    }

    val compare = File(playerPath).name.lowercase(Locale.getDefault())
    if(compare.contains("potplayer") && checkPlayer(playerPath)){
        return buildExternal(playerPath, PotPlayer.url(result.url.v()), PotPlayer.title(title ?: "TV"), PotPlayer.headers(result.header), subtitleUrl?.let { PotPlayer.subtitle(it) })
    }else if(compare.contains("vlc") && checkPlayer(playerPath)){
        return buildExternal(playerPath, result.url.v(), VLC.title(title ?: "TV"), subtitleUrl?.let { VLC.subtitle(it) })
    }
    else if(compare.contains("mpc-be") && checkPlayer(playerPath)){
        return MPC.getProcessBuilder(result,title ?: "TV", playerPath)
    }
    return null
}

fun getProcessBuilder(url:String, title: String?, subtitleUrl: String? = null): ProcessBuilder? {
    if (StringUtils.isBlank(url)) return null
    val playerPath = SettingStore.getPlayerSetting()[1] as String

    if(SystemUtils.IS_OS_MAC){
        return if(checkPlayer(playerPath)){
            ProcessBuilder("open", "-a", playerPath, url)
        }else{
            ProcessBuilder("open", url)
        }
    }

    val compare = File(playerPath).name.lowercase(Locale.getDefault())
    if(compare.contains("potplayer") && checkPlayer(playerPath)){
        return buildExternal(playerPath, PotPlayer.url(url), PotPlayer.title(title ?: "TV"), subtitleUrl?.let { PotPlayer.subtitle(it) })
    }else if(compare.contains("vlc") && checkPlayer(playerPath)){
        return buildExternal(playerPath, url, VLC.title(title ?: "TV"), subtitleUrl?.let { VLC.subtitle(it) })
    }
    else if(compare.contains("mpc-be") && checkPlayer(playerPath)){
        return MPC.getProcessBuilder(url, title ?: "TV", playerPath)
    }
    return null
}

private fun buildExternal(playerPath: String, vararg args: String?): ProcessBuilder {
    val command = mutableListOf(playerPath)
    args.filterNotNull().filter { it.isNotBlank() }.forEach { command.add(it) }
    return ProcessBuilder(command).redirectOutput(Paths.playerLog())
}


//default player [mpc-hc]
@Suppress("unused")
fun getDefaultPlayerPath():String {
    val resourcesDir = File(System.getProperty("compose.application.resources.dir"))
    // 已经解压
    var exeList = resourcesDir.resolve("mpc-hc").list(FilenameFilter { _, name -> name.lowercase().matches(Regex("mpc-hc\\X*.exe")) })
    if(exeList != null && exeList.isNotEmpty()) return resourcesDir.resolve("mpc-hc").resolve(exeList[0]).path

    val list = resourcesDir.list(FilenameFilter { _, name -> name.lowercase().matches(Regex("mpc-hc\\X*.zip")) })
    if(list == null || list.isEmpty()) {
        log.error("没有找到默认播放器压缩包")
        return ""
    }
    val destDir = resourcesDir.resolve("mpc-hc")
    log.info("解压默认播放器 MPC-HC")

    ZipUtil.unzip(resourcesDir.resolve(list[0]), destDir.path.toPath().toFile())
    exeList = destDir.list(FilenameFilter { _, name -> name.lowercase().matches(Regex("mpc-hc\\X*.exe")) })
    if(exeList == null || exeList.isEmpty()) {
        log.error("没有找到播放器exe")
        return ""
    }
    return destDir.resolve(exeList[0]).path
}

/**
 * @param name dest dir name
 * @param exePattern 匹配exe可执行文件的regx "mpc-hc\\X*.exe"
 */
fun findAndExtract(dirName:String, exePattern:String): String? {
    val resourcesDir = File(System.getProperty(Constants.RES_PATH_KEY))
    var exeList = resourcesDir.resolve(dirName).list(FilenameFilter { _, name -> name.lowercase().matches(Regex(exePattern)) })
    if(exeList != null && exeList.isNotEmpty()) return resourcesDir.resolve(dirName).resolve(exeList[0]).path

    val list = resourcesDir.list(FilenameFilter { _, name -> name.lowercase().matches(Regex(exePattern)) })
    if(list == null || list.isEmpty()) {
        log.error("没有找到压缩包")
        return ""
    }
    val destDir = resourcesDir.resolve(dirName)
    log.info("解压压缩包 $list")

    ZipUtil.unzip(resourcesDir.resolve(list[0]), destDir.path.toPath().toFile())
    exeList = destDir.list(FilenameFilter { _, name -> name.lowercase().matches(Regex(exePattern)) })
    if(exeList == null || exeList.isEmpty()) {
        log.error("没有找到播放器exe")
        return ""
    }
    return exeList.first()
 }

//check player file is exist and can execute
private fun checkPlayer(playerPath:String):Boolean{
    if(StringUtils.isBlank(playerPath)){
        SnackBar.postMsg("请配置播放器路径",type = SnackBar.MessageType.WARNING)
        log.warn("播放器路径为空")
        return false
    }
    val file = File(playerPath)
    if(!file.exists() || !file.canExecute()){
        SnackBar.postMsg("播放器文件不存在：$playerPath, 或不可执行",type = SnackBar.MessageType.ERROR)
        log.error("播放器文件不存在：$playerPath, 或不可执行")
        return false
    }
    return StringUtils.isNotBlank(playerPath) && (file.exists() || file.canExecute())
}