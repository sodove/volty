package ru.sodovaya.volty.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.LinkedHashMap
import kotlin.math.ceil
import ru.sodovaya.volty.domain.video.OverlayIconOp
import ru.sodovaya.volty.domain.video.OverlayIcons
import ru.sodovaya.volty.domain.video.OverlayOp
import ru.sodovaya.volty.domain.video.OverlayPalette
import ru.sodovaya.volty.domain.video.OverlayTextAlign
import ru.sodovaya.volty.domain.video.OverlayTextOp

/**
 * GLES2 text and icon replay for the already-current output context. Glyphs are rasterized only when their
 * (character, face, size, halo) cache entry is missing; every cached bitmap is a tightly bounded glyph tile.
 *
 * GL coordinates are mapped from the upright output's top-left pixel origin. The caller owns the viewport and
 * premultiplied-alpha blend setup (`GL_ONE`, `GL_ONE_MINUS_SRC_ALPHA`). Create a new instance after context loss.
 * This instance is confined to that GL thread.
 */
internal class GlHudText(context: Context) : AutoCloseable {
    private val fonts = OverlayFonts(context)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textAlign = Paint.Align.LEFT
        letterSpacing = 0f
    }
    private val glyphBounds = android.graphics.Rect()
    private val cache = LinkedHashMap<GlyphKey, GlyphRaster>(32, 0.75f, true)
    private var cachedBytes = 0
    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(6 * 4 * FLOAT_BYTES)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val scratchVertices = FloatArray(6 * 4)
    private val shader = createProgram()
    private val positionLocation = GLES20.glGetAttribLocation(shader, "a_position")
    private val uvLocation = GLES20.glGetAttribLocation(shader, "a_uv")
    private val colorLocation = GLES20.glGetUniformLocation(shader, "u_color")
    private val samplerLocation = GLES20.glGetUniformLocation(shader, "u_texture")
    private val maxTextureSize = IntArray(1).also { GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, it, 0) }[0]
    private var closed = false
    private var lastColor: Int? = null

    fun draw(op: OverlayOp, width: Int, height: Int) {
        if (closed || width <= 0 || height <= 0) return
        if (op !is OverlayTextOp && op !is OverlayIconOp) return

        GLES20.glUseProgram(shader)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(samplerLocation, 0)
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glEnableVertexAttribArray(uvLocation)
        // All glyphs in this op reuse the same client-memory vertex array.
        vertices.position(0)
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, FLOAT_BYTES * 4, vertices)
        vertices.position(2)
        GLES20.glVertexAttribPointer(uvLocation, 2, GLES20.GL_FLOAT, false, FLOAT_BYTES * 4, vertices)
        vertices.position(0)
        try {
            when (op) {
                is OverlayTextOp -> drawText(op, width, height)
                is OverlayIconOp -> drawIcon(op, width, height)
                else -> Unit
            }
        } finally {
            GLES20.glDisableVertexAttribArray(positionLocation)
            GLES20.glDisableVertexAttribArray(uvLocation)
        }
    }

    private fun drawText(op: OverlayTextOp, width: Int, height: Int) {
        val totalWidth = op.runs.sumOf { run ->
            (run.gapBefore + measured(run.text, run.size, run.bold, op.trackingEm)).toDouble()
        }.toFloat()
        var penX = when (op.align) {
            OverlayTextAlign.START -> op.x
            OverlayTextAlign.CENTER -> op.x - totalWidth / 2f
            OverlayTextAlign.END -> op.x - totalWidth
        }

        // Keep the outline on its own pass, matching OverlayCanvasDrawer's neighboring-glyph behavior.
        if (op.haloWidth > 0f) {
            for (run in op.runs) {
                penX += run.gapBefore
                penX = drawRun(run.text, run.size, run.bold, penX, op.baseline, OverlayPalette.HALO.toInt(),
                    op.haloWidth, op.trackingEm, width, height, haloPass = true)
            }
            penX = when (op.align) {
                OverlayTextAlign.START -> op.x
                OverlayTextAlign.CENTER -> op.x - totalWidth / 2f
                OverlayTextAlign.END -> op.x - totalWidth
            }
        }

        for (run in op.runs) {
            penX += run.gapBefore
            penX = drawRun(run.text, run.size, run.bold, penX, op.baseline, run.color,
                op.haloWidth, op.trackingEm, width, height, haloPass = false)
        }
    }

    private fun drawRun(
        text: String,
        size: Float,
        bold: Boolean,
        startX: Float,
        baseline: Float,
        color: Int,
        haloWidth: Float,
        trackingEm: Float,
        width: Int,
        height: Int,
        haloPass: Boolean,
    ): Float {
        if (!size.isUsable()) return startX
        var penX = startX
        val extra = if (haloPass) haloWidth else 0f
        forEachGlyph(text) { glyph ->
            val raster = obtain(glyph, size, if (bold) FACE_BOLD else FACE_MEDIUM, extra)
            if (raster != null) {
                val texture = if (haloPass) raster.haloTexture else raster.fillTexture
                if (texture != 0) {
                    drawGlyph(texture, raster, penX, baseline, color, width, height)
                }
                if (!raster.cached) release(raster)
                penX += raster.advance
                penX += size * trackingEm
            }
        }
        // Paint's letter spacing is between adjacent characters, not after the final glyph.
        if (text.isNotEmpty()) penX -= size * trackingEm
        return penX
    }

    private fun drawIcon(op: OverlayIconOp, width: Int, height: Int) {
        val face = fonts.icons ?: return
        val info = OverlayIcons.byName(op.icon) ?: return
        if (!op.size.isUsable()) return
        val glyph = info.codepoint.toString()
        val raster = obtain(glyph, op.size, FACE_ICON, haloWidth = 0f) ?: return
        if (raster.fillTexture != 0) {
            // Center the ink bounds inside the icon's square, as OverlayCanvasDrawer does.
            val inkWidth = raster.width - raster.padding * 2
            val inkHeight = raster.height - raster.padding * 2
            val baselineX = op.x + (op.size - inkWidth) / 2f - raster.inkLeft
            val baselineY = op.y + (op.size - inkHeight) / 2f - raster.inkTop
            drawGlyph(raster.fillTexture, raster, baselineX, baselineY, op.color, width, height)
        }
        if (!raster.cached) release(raster)
    }

    private fun measured(text: String, size: Float, bold: Boolean, trackingEm: Float): Float {
        if (!size.isUsable()) return 0f
        configurePaint(size, if (bold) fonts.bold else fonts.medium)
        var width = 0f
        var count = 0
        forEachGlyph(text) { glyph ->
            width += glyphPaint.measureText(glyph)
            count++
        }
        if (count > 1) width += (count - 1) * size * trackingEm
        return width
    }

    private fun obtain(glyph: String, size: Float, face: Int, haloWidth: Float): GlyphRaster? {
        if (!size.isUsable() || !haloWidth.isFinite() || haloWidth < 0f) return null
        val key = GlyphKey(glyph, size.toBits(), face, haloWidth.toBits())
        cache[key]?.let { return it }
        val typeface = when (face) {
            FACE_BOLD -> fonts.bold
            FACE_MEDIUM -> fonts.medium
            else -> fonts.icons ?: return null
        }
        configurePaint(size, typeface)
        glyphPaint.getTextBounds(glyph, 0, glyph.length, glyphBounds)
        val inkLeft = glyphBounds.left.toFloat()
        val inkTop = glyphBounds.top.toFloat()
        val inkWidth = glyphBounds.width()
        val inkHeight = glyphBounds.height()
        val advance = glyphPaint.measureText(glyph)
        if (inkWidth <= 0 || inkHeight <= 0) {
            val raster = GlyphRaster(0, 0, 0, inkLeft, inkTop, advance, 0, 0, 0, cached = true)
            remember(key, raster)
            return raster
        }

        // A few pixels keep antialiasing and the rounded halo stroke inside the tile.
        val padding = 2 + if (haloWidth > 0f) ceil(haloWidth / 2f).toInt() else 0
        val tileWidth = inkWidth + padding * 2
        val tileHeight = inkHeight + padding * 2
        if (tileWidth > maxTextureSize || tileHeight > maxTextureSize || tileWidth > MAX_TILE_SIZE || tileHeight > MAX_TILE_SIZE) {
            return GlyphRaster(tileWidth, tileHeight, padding, inkLeft, inkTop, advance, 0, 0, 0, cached = false)
        }

        val fillBitmap = rasterize(glyph, tileWidth, tileHeight, padding, haloWidth = 0f, fill = true)
        val fillTexture = upload(fillBitmap)
        fillBitmap.recycle()
        val haloTexture = if (haloWidth > 0f) {
            val haloBitmap = rasterize(glyph, tileWidth, tileHeight, padding, haloWidth, fill = false)
            upload(haloBitmap).also { haloBitmap.recycle() }
        } else 0
        val bytes = tileWidth.toLong() * tileHeight * BYTES_PER_PIXEL * if (haloTexture == 0) 1 else 2
        val cacheable = bytes <= MAX_CACHE_BYTES
        val raster = GlyphRaster(tileWidth, tileHeight, padding, inkLeft, inkTop, advance,
            fillTexture, haloTexture, if (cacheable) bytes.toInt() else 0, cached = cacheable)
        if (cacheable) remember(key, raster)
        return raster
    }

    private fun remember(key: GlyphKey, raster: GlyphRaster) {
        cache[key] = raster
        cachedBytes += raster.bytes
        trimCache()
    }

    private fun rasterize(
        glyph: String,
        width: Int,
        height: Int,
        padding: Int,
        haloWidth: Float,
        fill: Boolean,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        glyphPaint.style = if (fill) Paint.Style.FILL else Paint.Style.STROKE
        glyphPaint.strokeJoin = Paint.Join.ROUND
        glyphPaint.strokeWidth = haloWidth
        glyphPaint.color = android.graphics.Color.WHITE
        canvas.drawText(glyph, padding - glyphBounds.left.toFloat(), padding - glyphBounds.top.toFloat(), glyphPaint)
        glyphPaint.style = Paint.Style.FILL
        glyphPaint.strokeWidth = 0f
        return bitmap
    }

    private fun upload(bitmap: Bitmap): Int {
        val names = IntArray(1)
        GLES20.glGenTextures(1, names, 0)
        val texture = names[0]
        check(texture != 0) { "Could not allocate a HUD glyph texture" }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        try {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        } catch (error: Throwable) {
            GLES20.glDeleteTextures(1, names, 0)
            throw error
        }
        return texture
    }

    private fun drawGlyph(texture: Int, glyph: GlyphRaster, baselineX: Float, baselineY: Float,
        color: Int, width: Int, height: Int) {
        val left = baselineX + glyph.inkLeft - glyph.padding
        val top = baselineY + glyph.inkTop - glyph.padding
        val right = left + glyph.width
        val bottom = top + glyph.height
        val x0 = left * 2f / width - 1f
        val x1 = right * 2f / width - 1f
        val y0 = 1f - top * 2f / height
        val y1 = 1f - bottom * 2f / height
        putVertex(0, x0, y0, 0f, 0f)
        putVertex(1, x0, y1, 0f, 1f)
        putVertex(2, x1, y0, 1f, 0f)
        putVertex(3, x1, y0, 1f, 0f)
        putVertex(4, x0, y1, 0f, 1f)
        putVertex(5, x1, y1, 1f, 1f)
        vertices.position(0)
        vertices.put(scratchVertices)
        vertices.position(0)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        if (lastColor != color) {
            GLES20.glUniform4f(colorLocation,
                android.graphics.Color.red(color) / 255f,
                android.graphics.Color.green(color) / 255f,
                android.graphics.Color.blue(color) / 255f,
                android.graphics.Color.alpha(color) / 255f)
            lastColor = color
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
    }

    private fun putVertex(index: Int, x: Float, y: Float, u: Float, v: Float) {
        val offset = index * 4
        scratchVertices[offset] = x
        scratchVertices[offset + 1] = y
        scratchVertices[offset + 2] = u
        scratchVertices[offset + 3] = v
    }

    private fun configurePaint(size: Float, typeface: Typeface) {
        glyphPaint.textSize = size
        glyphPaint.typeface = typeface
        glyphPaint.style = Paint.Style.FILL
        glyphPaint.strokeWidth = 0f
    }

    private fun trimCache() {
        val iterator = cache.entries.iterator()
        while ((cache.size > MAX_CACHED_GLYPHS || cachedBytes > MAX_CACHE_BYTES) && iterator.hasNext()) {
            val raster = iterator.next().value
            if (raster.fillTexture != 0 || raster.haloTexture != 0) {
                deleteTextures(raster)
            }
            cachedBytes -= raster.bytes
            iterator.remove()
        }
    }

    private fun release(raster: GlyphRaster) {
        if (raster.fillTexture != 0 || raster.haloTexture != 0) deleteTextures(raster)
    }

    private fun deleteTextures(raster: GlyphRaster) {
        val names = intArrayOf(raster.fillTexture, raster.haloTexture)
        val count = names.count { it != 0 }
        if (count == 2) GLES20.glDeleteTextures(2, names, 0)
        else if (count == 1) GLES20.glDeleteTextures(1, intArrayOf(names.first { it != 0 }), 0)
    }

    private fun forEachGlyph(text: String, action: (String) -> Unit) {
        var index = 0
        while (index < text.length) {
            val start = index
            val first = text[index]
            index += if (Character.isHighSurrogate(first) && index + 1 < text.length && Character.isLowSurrogate(text[index + 1])) 2 else 1
            action(text.substring(start, index))
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        cache.values.forEach(::deleteTextures)
        cache.clear()
        cachedBytes = 0
        GLES20.glDeleteProgram(shader)
    }

    private data class GlyphKey(val glyph: String, val sizeBits: Int, val face: Int, val haloBits: Int)

    private data class GlyphRaster(
        val width: Int,
        val height: Int,
        val padding: Int,
        val inkLeft: Float,
        val inkTop: Float,
        val advance: Float,
        val fillTexture: Int,
        val haloTexture: Int,
        val bytes: Int,
        val cached: Boolean,
    )

    private fun Float.isUsable(): Boolean = isFinite() && this > 0f

    private companion object {
        const val FACE_BOLD = 0
        const val FACE_MEDIUM = 1
        const val FACE_ICON = 2
        const val FLOAT_BYTES = 4
        const val BYTES_PER_PIXEL = 4L
        const val MAX_CACHED_GLYPHS = 192
        const val MAX_CACHE_BYTES = 4L * 1024L * 1024L
        const val MAX_TILE_SIZE = 1024

        fun createProgram(): Int {
            val vertex = compileShader(GLES20.GL_VERTEX_SHADER, """
                attribute vec2 a_position;
                attribute vec2 a_uv;
                varying vec2 v_uv;
                void main() {
                    gl_Position = vec4(a_position, 0.0, 1.0);
                    v_uv = a_uv;
                }
            """.trimIndent())
            val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, """
                precision mediump float;
                uniform sampler2D u_texture;
                uniform vec4 u_color;
                varying vec2 v_uv;
                void main() {
                    float alpha = texture2D(u_texture, v_uv).a * u_color.a;
                    gl_FragColor = vec4(u_color.rgb * alpha, alpha);
                }
            """.trimIndent())
            val program = GLES20.glCreateProgram()
            check(program != 0) { "Could not create HUD text shader program" }
            GLES20.glAttachShader(program, vertex)
            GLES20.glAttachShader(program, fragment)
            GLES20.glLinkProgram(program)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
            if (linked[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(program)
                GLES20.glDeleteProgram(program)
                error("Could not link HUD text shader: $log")
            }
            return program
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            check(shader != 0) { "Could not create HUD text shader" }
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                error("Could not compile HUD text shader: $log")
            }
            return shader
        }
    }
}
