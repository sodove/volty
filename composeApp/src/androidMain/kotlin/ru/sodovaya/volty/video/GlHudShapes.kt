package ru.sodovaya.volty.video

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import ru.sodovaya.volty.domain.video.OverlayArcOp
import ru.sodovaya.volty.domain.video.OverlayDiscOp
import ru.sodovaya.volty.domain.video.OverlayOp
import ru.sodovaya.volty.domain.video.OverlayPalette
import ru.sodovaya.volty.domain.video.OverlayRectOp
import ru.sodovaya.volty.domain.video.OverlayScrimOp

/**
 * Small GLES2 replay path for the geometric subset of an overlay. Create and close it while the owning GLES2 context
 * is current. The caller supplies the full-frame viewport and premultiplied-alpha blend state (`ONE`,
 * `ONE_MINUS_SRC_ALPHA`). Text and icons are intentionally ignored; the other overlay renderer owns those.
 *
 * Shapes are shader-rendered from a four-vertex quad per op, so the draw path reuses its direct vertex buffer. Fixed
 * one-pixel shader coverage approximates Canvas antialiasing without relying on GLES extensions. Rounded rect and arc
 * edges are distance-field approximations; the scrim and disc color ramps match the Canvas drawer's stops.
 *
 * This pass owns the active GLES program and vertex attribute state while drawing. On return it binds program 0,
 * disables its position attribute, and restores the previous array-buffer binding. It does not change viewport or
 * blend state.
 */
internal class GlHudShapes : AutoCloseable {
    private var program = createProgram()
    private val position = GLES20.glGetAttribLocation(program, "aPosition")

    private val viewportUniform = uniform("uViewport")
    private val modeUniform = uniform("uMode")
    private val rectUniform = uniform("uRect")
    private val colorUniform = uniform("uColor")
    private val strokeColorUniform = uniform("uStrokeColor")
    private val radiusUniform = uniform("uRadius")
    private val strokeWidthUniform = uniform("uStrokeWidth")
    private val directionUniform = uniform("uDarkAtTop")
    private val arcUniform = uniform("uArc")

    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(4 * 2 * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    private val savedArrayBuffer = IntArray(1)
    private var fillAlpha = 255

    /** Draws one op in upright scene pixels, with `(0, 0)` at the output's top-left. */
    fun draw(op: OverlayOp, width: Int, height: Int) {
        if (program == 0 || width <= 0 || height <= 0) return
        val mode = when (op) {
            is OverlayScrimOp -> SCRIM
            is OverlayDiscOp -> DISC
            is OverlayArcOp -> ARC
            is OverlayRectOp -> RECT
            else -> return
        }

        GLES20.glGetIntegerv(GLES20.GL_ARRAY_BUFFER_BINDING, savedArrayBuffer, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glUseProgram(program)
        GLES20.glUniform2f(viewportUniform, width.toFloat(), height.toFloat())
        GLES20.glUniform1i(modeUniform, mode)
        GLES20.glEnableVertexAttribArray(position)
        try {
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices)
            when (op) {
                is OverlayScrimOp -> drawScrim(op, width, height)
                is OverlayDiscOp -> drawDisc(op, width, height)
                is OverlayArcOp -> drawArc(op, width, height)
                is OverlayRectOp -> drawRect(op, width, height)
                else -> Unit
            }
        } finally {
            GLES20.glDisableVertexAttribArray(position)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, savedArrayBuffer[0])
            GLES20.glUseProgram(0)
        }
    }

    override fun close() {
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
    }

    private fun drawScrim(op: OverlayScrimOp, width: Int, height: Int) {
        if (op.bottom <= op.top) return
        val top = op.top
        val bottom = op.bottom
        GLES20.glUniform4f(rectUniform, 0f, top, width.toFloat(), bottom)
        setColor(colorUniform, scrimColor())
        GLES20.glUniform1i(directionUniform, if (op.darkAtTop) 1 else 0)
        setQuad(0f, top - AA_PAD, width.toFloat(), bottom + AA_PAD, width, height)
    }

    private fun drawDisc(op: OverlayDiscOp, width: Int, height: Int) {
        if (op.radius <= 0f) return
        GLES20.glUniform4f(rectUniform, op.cx, op.cy, op.radius, 0f)
        setColor(colorUniform, scrimColor())
        setQuad(op.cx - op.radius - AA_PAD, op.cy - op.radius - AA_PAD,
            op.cx + op.radius + AA_PAD, op.cy + op.radius + AA_PAD, width, height)
    }

    private fun drawArc(op: OverlayArcOp, width: Int, height: Int) {
        if (op.radius <= 0f || op.strokeWidth <= 0f || abs(op.sweepDegrees) <= 0f) return
        GLES20.glUniform4f(rectUniform, op.cx, op.cy, op.radius, 0f)
        GLES20.glUniform4f(arcUniform, op.startDegrees, op.sweepDegrees, op.strokeWidth, 0f)
        setColor(colorUniform, op.color)
        val reach = op.radius + op.strokeWidth * 0.5f + AA_PAD
        setQuad(op.cx - reach, op.cy - reach, op.cx + reach, op.cy + reach, width, height)
    }

    private fun drawRect(op: OverlayRectOp, width: Int, height: Int) {
        if (op.right <= op.left || op.bottom <= op.top) return
        val strokeWidth = if (op.stroke == null) 0f else op.strokeWidth.coerceAtLeast(0f)
        op.fill?.let { fillAlpha = it ushr 24 }
        GLES20.glUniform4f(rectUniform, op.left, op.top, op.right, op.bottom)
        GLES20.glUniform1f(radiusUniform, op.radius.coerceAtLeast(0f))
        GLES20.glUniform1f(strokeWidthUniform, strokeWidth)
        setColor(colorUniform, op.fill ?: TRANSPARENT)
        setColor(strokeColorUniform, op.stroke ?: TRANSPARENT)
        val pad = strokeWidth * 0.5f + AA_PAD
        setQuad(op.left - pad, op.top - pad, op.right + pad, op.bottom + pad, width, height)
    }

    // Canvas uses one fill Paint. Its previous fill opacity also modulates the next gradient.
    private fun scrimColor(): Int = (OverlayPalette.SCRIM.toInt() and 0x00FFFFFF) or (fillAlpha shl 24)

    private fun setColor(location: Int, argb: Int) {
        val a = ((argb ushr 24) and 0xFF) / 255f
        val r = ((argb ushr 16) and 0xFF) / 255f
        val g = ((argb ushr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        GLES20.glUniform4f(location, r, g, b, a)
    }

    /** Top-left pixel coordinates become NDC; the vertex strip covers just the op's shape bounds. */
    private fun setQuad(left: Float, top: Float, right: Float, bottom: Float, width: Int, height: Int) {
        val x0 = 2f * left / width - 1f
        val x1 = 2f * right / width - 1f
        val y0 = 1f - 2f * top / height
        val y1 = 1f - 2f * bottom / height
        vertices.put(0, x0)
        vertices.put(1, y0)
        vertices.put(2, x1)
        vertices.put(3, y0)
        vertices.put(4, x0)
        vertices.put(5, y1)
        vertices.put(6, x1)
        vertices.put(7, y1)
        vertices.position(0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun uniform(name: String): Int = GLES20.glGetUniformLocation(program, name)

    private fun createProgram(): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = try {
            compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        } catch (failure: Throwable) {
            GLES20.glDeleteShader(vertexShader)
            throw failure
        }
        val result = GLES20.glCreateProgram()
        if (result == 0) {
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
            error("Unable to create GLES2 HUD program")
        }
        try {
            GLES20.glAttachShader(result, vertexShader)
            GLES20.glAttachShader(result, fragmentShader)
            GLES20.glBindAttribLocation(result, 0, "aPosition")
            GLES20.glLinkProgram(result)
            val status = IntArray(1)
            GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { "Unable to link GLES2 HUD program: ${GLES20.glGetProgramInfoLog(result)}" }
            return result
        } catch (failure: Throwable) {
            GLES20.glDeleteProgram(result)
            throw failure
        } finally {
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        check(shader != 0) { "Unable to create GLES2 HUD shader" }
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val message = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("Unable to compile GLES2 HUD shader: $message")
        }
        return shader
    }

    private companion object {
        const val SCRIM = 0
        const val DISC = 1
        const val ARC = 2
        const val RECT = 3
        const val AA_PAD = 1f
        const val TRANSPARENT = 0x00000000

        val VERTEX_SHADER = """
            attribute vec2 aPosition;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """.trimIndent()

        val FRAGMENT_SHADER = """
            precision highp float;
            uniform vec2 uViewport;
            uniform int uMode;
            uniform vec4 uRect;
            uniform vec4 uColor;
            uniform vec4 uStrokeColor;
            uniform float uRadius;
            uniform float uStrokeWidth;
            uniform int uDarkAtTop;
            uniform vec4 uArc;

            const float PI = 3.14159265358979323846;

            float coverage(float signedDistance) {
                return 1.0 - smoothstep(-0.5, 0.5, signedDistance);
            }

            float roundedRectDistance(vec2 p, vec2 center, vec2 halfSize, float radius) {
                vec2 q = abs(p - center) - halfSize + vec2(radius);
                return length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - radius;
            }

            float ramp(float t, float middle, float endAlpha) {
                if (t <= middle) return mix(0.0, 0.46, t / middle);
                return mix(0.46, endAlpha, (t - middle) / (1.0 - middle));
            }

            vec4 premultiplied(vec4 color, float alpha) {
                float a = color.a * clamp(alpha, 0.0, 1.0);
                return vec4(color.rgb * a, a);
            }

            void main() {
                vec2 p = vec2(gl_FragCoord.x, uViewport.y - gl_FragCoord.y);
                if (uMode == 0) {
                    float top = uRect.y;
                    float bottom = uRect.w;
                    float t = uDarkAtTop == 1
                        ? (bottom - p.y) / max(bottom - top, 0.0001)
                        : (p.y - top) / max(bottom - top, 0.0001);
                    float alpha = uDarkAtTop == 1 ? ramp(clamp(t, 0.0, 1.0), 0.6, 0.78)
                                                  : ramp(clamp(t, 0.0, 1.0), 0.55, 0.82);
                    float edge = smoothstep(top - 0.5, top + 0.5, p.y)
                               * (1.0 - smoothstep(bottom - 0.5, bottom + 0.5, p.y));
                    gl_FragColor = premultiplied(uColor, alpha * edge);
                    return;
                }
                if (uMode == 1) {
                    float distanceFromCenter = length(p - uRect.xy);
                    float t = distanceFromCenter / max(uRect.z, 0.0001);
                    float alpha = mix(0.36, 0.0, clamp((t - 0.5) * 2.0, 0.0, 1.0));
                    gl_FragColor = premultiplied(uColor, alpha);
                    return;
                }
                if (uMode == 2) {
                    vec2 delta = p - uRect.xy;
                    float distanceFromCenter = length(delta);
                    float angle = degrees(atan(delta.y, delta.x));
                    float start = mod(uArc.x, 360.0);
                    float sweep = uArc.y;
                    float magnitude = abs(sweep);
                    float along = sweep >= 0.0 ? mod(angle - start + 720.0, 360.0)
                                               : mod(start - angle + 720.0, 360.0);
                    float distanceToPath;
                    if (magnitude >= 360.0 || along <= magnitude) {
                        distanceToPath = abs(distanceFromCenter - uRect.z);
                    } else {
                        float end = radians(uArc.x + sweep);
                        vec2 first = uRect.xy + vec2(cos(radians(uArc.x)), sin(radians(uArc.x))) * uRect.z;
                        vec2 last = uRect.xy + vec2(cos(end), sin(end)) * uRect.z;
                        distanceToPath = min(length(p - first), length(p - last));
                    }
                    float alpha = coverage(distanceToPath - uArc.z * 0.5);
                    gl_FragColor = premultiplied(uColor, alpha);
                    return;
                }

                vec2 center = (uRect.xy + uRect.zw) * 0.5;
                vec2 halfSize = (uRect.zw - uRect.xy) * 0.5;
                float radius = min(uRadius, min(halfSize.x, halfSize.y));
                float fillCoverage = coverage(roundedRectDistance(p, center, halfSize, radius));
                float fillAlpha = uColor.a * fillCoverage;
                float strokeCoverage = 0.0;
                if (uStrokeWidth > 0.0 && uStrokeColor.a > 0.0) {
                    float halfStroke = uStrokeWidth * 0.5;
                    float outerRadius = min(radius + halfStroke, min(halfSize.x + halfStroke, halfSize.y + halfStroke));
                    float outerDistance = roundedRectDistance(p, center, halfSize + vec2(halfStroke), outerRadius);
                    vec2 innerSize = max(halfSize - vec2(halfStroke), vec2(0.0));
                    float innerRadius = max(radius - halfStroke, 0.0);
                    float innerDistance = roundedRectDistance(p, center, innerSize, innerRadius);
                    strokeCoverage = coverage(outerDistance) * (1.0 - coverage(innerDistance));
                }
                float strokeAlpha = uStrokeColor.a * strokeCoverage;
                float outAlpha = strokeAlpha + fillAlpha * (1.0 - strokeAlpha);
                vec3 outRgb = uStrokeColor.rgb * strokeAlpha
                            + uColor.rgb * fillAlpha * (1.0 - strokeAlpha);
                gl_FragColor = vec4(outRgb, outAlpha);
            }
        """.trimIndent()
    }
}
