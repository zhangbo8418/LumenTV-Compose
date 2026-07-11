package com.corner.server

import com.corner.util.io.Paths
import com.corner.util.json.Jsons
import io.ktor.http.*
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.CRC32

object LocalFileHandler {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun localFile(path: String): File {
        val relative = path
            .removePrefix("file:/")
            .removePrefix("file://")
            .removePrefix("/")
        val root = Paths.root().canonicalFile
        val target = File(root, relative).canonicalFile
        check(target.path.startsWith(root.path)) { "非法路径" }
        return target
    }

    fun playUrl(path: String): String {
        val relative = path
            .removePrefix("file:/")
            .removePrefix("file://")
        val suffix = if (relative.startsWith("/")) relative else "/$relative"
        return "http://127.0.0.1:${KtorD.getPort()}/file$suffix"
    }

    suspend fun handleFile(call: ApplicationCall, subPath: String) {
        val file = localFile(subPath)
        when {
            file.isDirectory -> call.respondText(listFolder(file), ContentType.Application.Json)
            file.isFile -> serveFile(call, file)
            else -> call.respond(HttpStatusCode.NotFound, "文件不存在")
        }
    }

    suspend fun handleUpload(call: ApplicationCall) {
        val multipart = call.receiveMultipart()
        var targetPath = ""
        val saved = mutableListOf<String>()
        multipart.forEachPart { part ->
            when (part) {
                is PartData.FormItem -> if (part.name == "path") targetPath = part.value
                is PartData.FileItem -> {
                    val fileName = part.originalFileName ?: part.name ?: "upload.bin"
                    val dir = localFile(targetPath.ifBlank { "/" })
                    dir.mkdirs()
                    val dest = File(dir, fileName)
                    part.streamProvider().use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    saved.add(fileName)
                }
                else -> Unit
            }
            part.dispose()
        }
        call.respondText("ok")
    }

    suspend fun handleNewFolder(call: ApplicationCall) {
        val params = call.receiveParameters()
        val path = params["path"].orEmpty()
        val name = params["name"].orEmpty()
        if (name.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, "名称不能为空")
            return
        }
        val dir = localFile(path).resolve(name)
        dir.mkdirs()
        call.respondText("ok")
    }

    suspend fun handleDelete(call: ApplicationCall) {
        val params = call.receiveParameters()
        val path = params["path"].orEmpty()
        val file = localFile(path)
        if (file.isDirectory) file.deleteRecursively() else file.delete()
        call.respondText("ok")
    }

    private fun listFolder(dir: File): String {
        val root = Paths.root().canonicalFile
        val rootPath = root.absolutePath
        val files = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).orEmpty()
            .map { child ->
                FileEntry(
                    name = child.name,
                    path = relativeTo(child, rootPath),
                    time = formatter.format(
                        Instant.ofEpochMilli(child.lastModified()).atZone(ZoneId.systemDefault())
                    ),
                    dir = if (child.isDirectory) 1 else 0,
                )
            }
        val info = FolderInfo(
            parent = parentOf(dir, root, rootPath),
            files = files,
        )
        return Jsons.encodeToString(info)
    }

    private suspend fun serveFile(call: ApplicationCall, file: File) {
        val mime = ContentType.fromFilePath(file.name).firstOrNull() ?: ContentType.Application.OctetStream
        val etag = etag(file)
        val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]
        if (ifNoneMatch == "*" || ifNoneMatch == etag) {
            call.respond(HttpStatusCode.NotModified)
            return
        }
        val rangeHeader = call.request.headers[HttpHeaders.Range]
        val fileLen = file.length()
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val parts = rangeHeader.removePrefix("bytes=").split("-", limit = 2)
            val start = parts[0].toLongOrNull() ?: 0L
            var end = parts.getOrNull(1)?.toLongOrNull() ?: (fileLen - 1)
            if (end >= fileLen) end = fileLen - 1
            if (start in 0 until fileLen && start <= end) {
                val length = end - start + 1
                call.response.headers.append(HttpHeaders.ContentRange, "bytes $start-$end/$fileLen")
                call.response.headers.append(HttpHeaders.AcceptRanges, "bytes")
                call.response.headers.append(HttpHeaders.ContentLength, length.toString())
                call.response.headers.append(HttpHeaders.ETag, etag)
                call.respondOutputStream(
                    contentType = mime,
                    status = HttpStatusCode.PartialContent,
                ) {
                    FileInputStream(file).use { input ->
                        input.skip(start)
                        val buffer = ByteArray(8192)
                        var remaining = length
                        while (remaining > 0) {
                            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (read <= 0) break
                            write(buffer, 0, read)
                            remaining -= read
                        }
                    }
                }
                return
            }
        }
        call.response.headers.append(HttpHeaders.AcceptRanges, "bytes")
        call.response.headers.append(HttpHeaders.ContentLength, fileLen.toString())
        call.response.headers.append(HttpHeaders.ETag, etag)
        call.respondFile(file)
    }

    private fun etag(file: File): String {
        val crc = CRC32()
        crc.update((file.absolutePath + file.lastModified() + file.length()).toByteArray())
        return java.lang.Long.toHexString(crc.value)
    }

    private fun relativeTo(file: File, rootPath: String): String {
        val path = file.absolutePath
        return if (path.startsWith(rootPath)) path.substring(rootPath.length) else path
    }

    private fun parentOf(dir: File, root: File, rootPath: String): String {
        if (dir == root) return "."
        val parent = dir.parentFile ?: return "."
        return if (parent == root) "" else relativeTo(parent, rootPath)
    }

    @Serializable
    private data class FileEntry(
        val name: String,
        val path: String,
        val time: String,
        val dir: Int,
    )

    @Serializable
    private data class FolderInfo(
        val parent: String,
        val files: List<FileEntry>,
    )
}
