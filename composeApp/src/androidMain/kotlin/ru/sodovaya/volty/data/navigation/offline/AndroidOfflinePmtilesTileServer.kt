package ru.sodovaya.volty.data.navigation.offline

import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

/**
 * Serves one local PMTiles archive through a loopback Z/X/Y endpoint.
 *
 * MapLibre Native's Android SDK consumes regular vector tile sources; unlike
 * MapLibre GL JS it does not expose a pluggable protocol callback. A local
 * range-style reader keeps the PMTiles file intact and only reads the tile
 * requested by the renderer. The server binds to 127.0.0.1 and accepts no
 * external connections.
 */
class AndroidOfflinePmtilesTileServer : Closeable {
    private val lock = Any()
    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "VoltyOfflinePmtiles").apply { isDaemon = true }
    }
    private var server: ServerSocket? = null
    private var archive: PmtilesArchive? = null
    private var archivePath: String? = null

    fun sourceUrl(file: File): String = synchronized(lock) {
        val canonicalPath = file.canonicalPath
        if (server == null) {
            server = ServerSocket(0, 8, InetAddress.getLoopbackAddress()).also { socket ->
                executor.execute { acceptLoop(socket) }
            }
        }
        if (archivePath != canonicalPath) {
            val next = PmtilesArchive(file)
            archive?.close()
            archive = next
            archivePath = canonicalPath
        }
        "http://127.0.0.1:${server!!.localPort}/tiles/{z}/{x}/{y}.pbf"
    }

    override fun close() {
        synchronized(lock) {
            archive?.close()
            archive = null
            archivePath = null
            server?.close()
            server = null
            executor.shutdownNow()
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            try {
                val client = socket.accept()
                executor.execute { client.use(::handle) }
            } catch (_: IOException) {
                return
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = REQUEST_TIMEOUT_MILLIS
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
        val requestLine = reader.readLine()?.takeIf { it.length <= MAX_REQUEST_LINE } ?: return
        val parts = requestLine.split(' ', limit = 3)
        if (parts.size < 2 || parts[0] != "GET") {
            respond(socket, 405, "text/plain", ByteArray(0))
            return
        }
        while (true) {
            val header = reader.readLine() ?: return
            if (header.isEmpty()) break
            if (header.length > MAX_REQUEST_LINE) return
        }
        val path = parts[1].substringBefore('?')
        val response = synchronized(lock) {
            val current = archive ?: return@synchronized HttpResponse(404, "", ByteArray(0), false)
            TILE_PATH.matchEntire(path)?.let { match ->
                val z = match.groupValues[1].toIntOrNull()
                val x = match.groupValues[2].toIntOrNull()
                val y = match.groupValues[3].toIntOrNull()
                if (z == null || x == null || y == null) {
                    HttpResponse(400, "text/plain", ByteArray(0), false)
                } else {
                    current.tile(z, x, y)?.let { bytes ->
                        HttpResponse(200, "application/x-protobuf", bytes, current.tileIsGzip)
                    } ?: HttpResponse(404, "", ByteArray(0), false)
                }
            } ?: if (path == "/tilejson.json") {
                HttpResponse(200, "application/json", current.tileJson(sourceUrlForJson()), false)
            } else {
                HttpResponse(404, "", ByteArray(0), false)
            }
        }
        respond(socket, response.code, response.contentType, response.body, response.gzip)
    }

    private fun sourceUrlForJson(): String =
        "http://127.0.0.1:${server?.localPort ?: 0}/tiles/{z}/{x}/{y}.pbf"

    private fun respond(
        socket: Socket,
        code: Int,
        contentType: String,
        body: ByteArray,
        gzip: Boolean = false,
    ) {
        val reason = when (code) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Error"
        }
        val output = BufferedOutputStream(socket.getOutputStream())
        output.write("HTTP/1.1 $code $reason\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write("Content-Length: ${body.size}\r\n".toByteArray(StandardCharsets.US_ASCII))
        if (contentType.isNotEmpty()) {
            output.write("Content-Type: $contentType\r\n".toByteArray(StandardCharsets.US_ASCII))
        }
        if (gzip) output.write("Content-Encoding: gzip\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write("Connection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    private data class HttpResponse(
        val code: Int,
        val contentType: String,
        val body: ByteArray,
        val gzip: Boolean,
    )

    private companion object {
        const val REQUEST_TIMEOUT_MILLIS = 5_000
        const val MAX_REQUEST_LINE = 8_192
        val TILE_PATH = Regex("/tiles/(\\d+)/(\\d+)/(\\d+)\\.(?:pbf|mvt)")
    }
}

private class PmtilesArchive(private val file: File) : Closeable {
    private val randomAccess = RandomAccessFile(file, "r")
    private val header: Header
    private val rootDirectory: List<DirectoryEntry>
    val tileIsGzip: Boolean
        get() = header.tileCompression == COMPRESSION_GZIP

    init {
        if (file.length() < HEADER_BYTES) throw IOException("PMTiles archive is too small")
        header = readHeader()
        if (header.specVersion != SPEC_VERSION) throw IOException("Unsupported PMTiles version")
        if (header.internalCompression != COMPRESSION_GZIP &&
            header.internalCompression != COMPRESSION_NONE
        ) throw IOException("Unsupported PMTiles directory compression")
        if (header.tileCompression != COMPRESSION_GZIP &&
            header.tileCompression != COMPRESSION_NONE
        ) throw IOException("Unsupported PMTiles tile compression")
        rootDirectory = decodeDirectory(
            readSection(header.rootDirectoryOffset, header.rootDirectoryLength),
        )
    }

    fun tile(z: Int, x: Int, y: Int): ByteArray? {
        if (z !in 0..26 || x < 0 || y < 0 || x >= (1 shl z) || y >= (1 shl z)) return null
        val tileId = zxyToTileId(z, x, y)
        var entry = findTile(rootDirectory, tileId) ?: return null
        if (entry.runLength == 0L) {
            val leaf = decodeDirectory(
                readSection(header.leafDirectoryOffset + entry.offset, entry.length),
            )
            entry = findTile(leaf, tileId) ?: return null
        }
        if (entry.runLength <= 0L || tileId < entry.tileId || tileId - entry.tileId >= entry.runLength) {
            return null
        }
        return readSection(header.tileDataOffset + entry.offset, entry.length)
    }

    fun tileJson(tilesUrl: String): ByteArray {
        val bounds = "${header.minLon},${header.minLat},${header.maxLon},${header.maxLat}"
        val json = """
            {"tilejson":"3.0.0","tiles":["$tilesUrl"],"minzoom":${header.minZoom},"maxzoom":${header.maxZoom},"bounds":[$bounds],"attribution":"© OpenStreetMap"}
        """.trimIndent()
        return json.toByteArray(StandardCharsets.UTF_8)
    }

    override fun close() = randomAccess.close()

    private fun readHeader(): Header {
        val bytes = readAt(0L, HEADER_BYTES)
        if (!bytes.copyOfRange(0, 7).contentEquals("PMTiles".toByteArray(StandardCharsets.US_ASCII))) {
            throw IOException("Invalid PMTiles magic")
        }
        return Header(
            specVersion = bytes[7].toInt() and 0xff,
            rootDirectoryOffset = littleLong(bytes, 8),
            rootDirectoryLength = littleLong(bytes, 16),
            leafDirectoryOffset = littleLong(bytes, 40),
            leafDirectoryLength = littleLong(bytes, 48),
            tileDataOffset = littleLong(bytes, 56),
            tileDataLength = littleLong(bytes, 64),
            internalCompression = bytes[97].toInt() and 0xff,
            tileCompression = bytes[98].toInt() and 0xff,
            minZoom = bytes[100].toInt() and 0xff,
            maxZoom = bytes[101].toInt() and 0xff,
            minLon = littleInt(bytes, 102) / 10_000_000.0,
            minLat = littleInt(bytes, 106) / 10_000_000.0,
            maxLon = littleInt(bytes, 110) / 10_000_000.0,
            maxLat = littleInt(bytes, 114) / 10_000_000.0,
        ).also { parsed ->
            listOf(
                parsed.rootDirectoryOffset to parsed.rootDirectoryLength,
                parsed.leafDirectoryOffset to parsed.leafDirectoryLength,
                parsed.tileDataOffset to parsed.tileDataLength,
            ).forEach { (offset, length) ->
                if (length < 0L || offset < 0L ||
                    (length > 0L && (offset < HEADER_BYTES || offset > file.length() - length))
                ) {
                    throw IOException("PMTiles section is outside the archive")
                }
            }
        }
    }

    private fun decodeDirectory(compressed: ByteArray): List<DirectoryEntry> {
        val bytes = if (header.internalCompression == COMPRESSION_GZIP) {
            GZIPInputStream(ByteArrayInputStream(compressed)).use { input -> input.readBounded(MAX_DIRECTORY_BYTES) }
        } else {
            compressed
        }
        val cursor = VarintCursor(bytes)
        val count = cursor.next().toInt().also {
            if (it <= 0 || it > MAX_DIRECTORY_ENTRIES) throw IOException("Invalid PMTiles directory size")
        }
        val entries = List(count) { DirectoryEntry(cursor.next(), 0L, 0L, 1L) }.toMutableList()
        var lastId = 0L
        entries.forEach { entry ->
            lastId += entry.tileId
            entry.tileId = lastId
        }
        entries.forEach { it.runLength = cursor.next() }
        entries.forEach { it.length = cursor.next().also { value -> if (value <= 0L) throw IOException("Invalid PMTiles entry length") } }
        entries.forEachIndexed { index, entry ->
            val encoded = cursor.next()
            entry.offset = if (encoded == 0L && index > 0) {
                entries[index - 1].offset + entries[index - 1].length
            } else {
                encoded - 1L
            }
        }
        return entries
    }

    private fun readSection(offset: Long, length: Long): ByteArray {
        if (length > MAX_SECTION_BYTES || length < 0L || offset < 0L || offset > file.length() - length) {
            throw IOException("PMTiles section is too large")
        }
        return readAt(offset, length.toInt())
    }

    private fun readAt(offset: Long, length: Int): ByteArray = ByteArray(length).also {
        synchronized(randomAccess) {
            randomAccess.seek(offset)
            randomAccess.readFully(it)
        }
    }

    private fun java.io.InputStream.readBounded(maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) return output.toByteArray()
            total += count
            if (total > maxBytes) throw IOException("PMTiles directory is too large")
            output.write(buffer, 0, count)
        }
    }

    private data class Header(
        val specVersion: Int,
        val rootDirectoryOffset: Long,
        val rootDirectoryLength: Long,
        val leafDirectoryOffset: Long,
        val leafDirectoryLength: Long,
        val tileDataOffset: Long,
        val tileDataLength: Long,
        val internalCompression: Int,
        val tileCompression: Int,
        val minZoom: Int,
        val maxZoom: Int,
        val minLon: Double,
        val minLat: Double,
        val maxLon: Double,
        val maxLat: Double,
    )

    private class DirectoryEntry(
        var tileId: Long,
        var offset: Long,
        var length: Long,
        var runLength: Long,
    )

    private class VarintCursor(private val bytes: ByteArray) {
        private var index = 0

        fun next(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (index >= bytes.size || shift > 63) throw IOException("Invalid PMTiles varint")
                val value = bytes[index++].toInt() and 0xff
                result = result or ((value and 0x7f).toLong() shl shift)
                if (value and 0x80 == 0) return result
                shift += 7
            }
        }
    }

    private companion object {
        const val HEADER_BYTES = 127
        const val SPEC_VERSION = 3
        const val COMPRESSION_NONE = 1
        const val COMPRESSION_GZIP = 2
        const val MAX_DIRECTORY_BYTES = 8 * 1024 * 1024
        const val MAX_DIRECTORY_ENTRIES = 2_000_000
        const val MAX_SECTION_BYTES = 64L * 1024L * 1024L

        fun findTile(entries: List<DirectoryEntry>, tileId: Long): DirectoryEntry? {
            var low = 0
            var high = entries.lastIndex
            while (low <= high) {
                val middle = (low + high) ushr 1
                val entry = entries[middle]
                when {
                    tileId > entry.tileId -> low = middle + 1
                    tileId < entry.tileId -> high = middle - 1
                    else -> return entry
                }
            }
            if (high < 0) return null
            val entry = entries[high]
            return if (entry.runLength == 0L || tileId - entry.tileId < entry.runLength) entry else null
        }

        fun zxyToTileId(z: Int, x: Int, y: Int): Long {
            var tileId = ((1L shl z) * (1L shl z) - 1L) / 3L
            var a = z - 1
            var tx = x
            var ty = y
            var size = if (a < 0) 0 else 1 shl a
            while (size > 0) {
                val rx = tx and size
                val ry = ty and size
                tileId += (((3 * rx) xor ry).toLong()) * (1L shl a)
                val rotated = rotate(size, tx, ty, rx, ry)
                tx = rotated.first
                ty = rotated.second
                a--
                size = if (a < 0) 0 else 1 shl a
            }
            return tileId
        }

        fun rotate(size: Int, x: Int, y: Int, rx: Int, ry: Int): Pair<Int, Int> = when {
            ry == 0 && rx != 0 -> size - 1 - y to size - 1 - x
            ry == 0 -> y to x
            else -> x to y
        }

        fun littleLong(bytes: ByteArray, offset: Int): Long {
            var result = 0L
            repeat(8) { index -> result = result or ((bytes[offset + index].toLong() and 0xffL) shl (index * 8)) }
            return result
        }

        fun littleInt(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                (bytes[offset + 3].toInt() shl 24)
    }
}
