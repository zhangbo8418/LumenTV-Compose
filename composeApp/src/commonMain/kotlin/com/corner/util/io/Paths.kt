package com.corner.util.io

import com.corner.util.system.OperatingSystem
import com.corner.util.system.SysVerUtil
import java.io.File
import java.nio.file.Path

object Paths {
    private const val APP_NAME = "Lumen-TV"

    private val userDataDir = getUserDataDir()

    private fun getUserDataDir() = run {
        when (SysVerUtil.currentOs) {
            OperatingSystem.Windows -> File(System.getenv("AppData"), "$APP_NAME/cache")
            OperatingSystem.Linux -> File(System.getProperty("user.home"), ".cache/$APP_NAME")
            OperatingSystem.MacOS -> File(System.getProperty("user.home"), "Library/Caches/$APP_NAME")
            OperatingSystem.Unknown -> throw RuntimeException("未知操作系统")
        }
    }

    private fun File.check(): File {
        if (!exists()) {
            mkdirs()
        }
        return this
    }

    fun root():File{
        return userDataDir.resolve("data")
    }

    fun userDataRoot():File{
        return userDataDir
    }

    fun doh(): File {
        return cache( "doh").check()
    }

    private fun cache(path: String): File {
        return root().resolve("cache").resolve(path)
    }

    fun db():String{
        val path = userDataRoot().resolve("db").check().resolve("tv.db")
        return path.path
    }

    /**
     * 对齐 TV Path.local：解析 file: 路径并返回 File，不存在也不弹「Jar」提示。
     * jar 缺失提示应由 JarLoader 在真正加载 spider.jar 时负责。
     */
    fun local(path: String): File {
        val converted = when {
            path.startsWith("file:", ignoreCase = true) -> Urls.convert(path)
            else -> path
        }
        return File(converted)
    }

    fun jar(): File {
        return cache("jar").check()
    }

    fun jar(fileName:String):File{
        return File(jar(), com.corner.util.net.Utils.md5(fileName) + ".jar")
    }

    fun py(): File {
        return cache("py").check()
    }

    fun write(path:File, bytes: ByteArray?):File{
        if(bytes == null || bytes.isEmpty()){
            return path
        }else{
            path.writeBytes(bytes)
        }

        return path
    }

    fun picCache(): File {
        return cache("pic").check()
    }

    fun setting(): Path {
        val file = userDataRoot().check().resolve("setting.ini")
        return file.toPath()
    }

    fun playerLog(): File {
        return root().check().resolve("playerLog.txt")
    }

    fun logPath():File{
        return root().resolve("log")
    }

    fun jcefBundle(): File {
        return userDataRoot().resolve("jcef-bundle").check()
    }

    fun epg(name: String): File {
        return cache("epg").check().resolve(name)
    }

    fun download(): File {
        val home = System.getProperty("user.home")
        val dir = when (SysVerUtil.currentOs) {
            OperatingSystem.Windows -> File(System.getenv("USERPROFILE") ?: home, "Downloads")
            OperatingSystem.MacOS -> File(home, "Downloads")
            OperatingSystem.Linux -> File(home, "Downloads")
            OperatingSystem.Unknown -> userDataRoot().resolve("downloads")
        }
        return dir.check()
    }

    fun wall(): File {
        return cache("wall").check().resolve("wall.bin")
    }
}
