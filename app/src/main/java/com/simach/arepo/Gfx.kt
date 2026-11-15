package com.simach.arepo

import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.huawei.hiar.ARFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// ---------- вектор ----------
class V3(@JvmField var x: Float = 0f, @JvmField var y: Float = 0f, @JvmField var z: Float = 0f) {
    fun set(a: Float, b: Float, c: Float): V3 { x = a; y = b; z = c; return this }
    fun set(o: V3): V3 = set(o.x, o.y, o.z)
    fun copy() = V3(x, y, z)
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = V3(x * s, y * s, z * s)
    fun len() = sqrt(x * x + y * y + z * z)
    fun lenXZ() = sqrt(x * x + z * z)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    fun add(o: V3, s: Float = 1f): V3 { x += o.x * s; y += o.y * s; z += o.z * s; return this }
    fun norm(): V3 { val l = len(); if (l > 1e-6f) { x /= l; y /= l; z /= l }; return this }
}

fun distXZ(a: V3, b: V3): Float { val dx = a.x - b.x; val dz = a.z - b.z; return sqrt(dx * dx + dz * dz) }

fun floatBuf(a: FloatArray): FloatBuffer {
    val b = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    b.put(a); b.position(0); return b
}

// ---------- шейдеры ----------
object Sh {
    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw RuntimeException("Shader error: $log")
        }
        return s
    }

    fun program(vs: String, fs: String): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Link error: " + GLES20.glGetProgramInfoLog(p))
        return p
    }
}

// ---------- изображение с камеры ----------
class Background {
    var tex = 0
    private var prog = 0
    private var aPos = 0
    private var aUv = 0
    private var uTex = 0
    private val pos = floatBuf(floatArrayOf(-1f, 1f, -1f, -1f, 1f, 1f, 1f, -1f))
    private val uvIn = floatBuf(floatArrayOf(0f, 0f, 0f, 1f, 1f, 0f, 1f, 1f))
    private val uvOut: FloatBuffer = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var first = true

    fun init() {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        tex = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        prog = Sh.program(
            """
            attribute vec2 aPos; attribute vec2 aUv; varying vec2 vUv;
            void main(){ vUv = aUv; gl_Position = vec4(aPos, 0.0, 1.0); }
            """.trimIndent(),
            """
            #extension GL_OES_EGL_image_external : require
            precision mediump float; varying vec2 vUv; uniform samplerExternalOES uTex;
            void main(){ gl_FragColor = texture2D(uTex, vUv); }
            """.trimIndent()
        )
        aPos = GLES20.glGetAttribLocation(prog, "aPos")
        aUv = GLES20.glGetAttribLocation(prog, "aUv")
        uTex = GLES20.glGetUniformLocation(prog, "uTex")
        first = true
    }

    fun draw(frame: ARFrame) {
        if (first || frame.hasDisplayGeometryChanged()) {
            uvIn.position(0); uvOut.position(0)
            frame.transformDisplayUvCoords(uvIn, uvOut)
            first = false
        }
        uvOut.position(0); pos.position(0)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(prog)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, pos)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, uvOut)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aUv)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }
}

// ---------- освещённые меши ----------
class Mesh(data: FloatArray) {
    val buf: FloatBuffer = floatBuf(data)
    val count = data.size / 6
}

class LitShader {
    var prog = 0
    var aPos = 0; var aNor = 0
    var uMVP = 0; var uModel = 0; var uColor = 0; var uEmis = 0; var uSpec = 0; var uCam = 0; var uAmb = 0; var uAlpha = 0

    fun init() {
        prog = Sh.program(
            """
            uniform mat4 uMVP; uniform mat4 uModel;
            attribute vec3 aPos; attribute vec3 aNor;
            varying vec3 vN; varying vec3 vW;
            void main(){
              vN = (uModel * vec4(aNor, 0.0)).xyz;
              vW = (uModel * vec4(aPos, 1.0)).xyz;
              gl_Position = uMVP * vec4(aPos, 1.0);
            }
            """.trimIndent(),
            """
            precision mediump float;
            uniform vec3 uColor; uniform vec3 uEmis; uniform float uSpec; uniform vec3 uCam; uniform float uAmb; uniform float uAlpha;
            varying vec3 vN; varying vec3 vW;
            void main(){
              vec3 n = normalize(vN);
              vec3 L = normalize(vec3(0.35, 1.0, 0.45));
              vec3 V = normalize(uCam - vW);
              float d = max(dot(n, L), 0.0);
              float back = max(dot(n, -L), 0.0) * 0.18;
              float rim = pow(1.0 - max(dot(n, V), 0.0), 2.5);
              float sp = pow(max(dot(reflect(-L, n), V), 0.0), 28.0) * uSpec;
              vec3 c = uColor * (0.32 + 0.78 * d + back) * uAmb + uColor * rim * 0.35 + vec3(sp) + uEmis;
              gl_FragColor = vec4(c, uAlpha);
            }
            """.trimIndent()
        )
        aPos = GLES20.glGetAttribLocation(prog, "aPos")
        aNor = GLES20.glGetAttribLocation(prog, "aNor")
        uMVP = GLES20.glGetUniformLocation(prog, "uMVP")
        uModel = GLES20.glGetUniformLocation(prog, "uModel")
        uColor = GLES20.glGetUniformLocation(prog, "uColor")
        uEmis = GLES20.glGetUniformLocation(prog, "uEmis")
        uSpec = GLES20.glGetUniformLocation(prog, "uSpec")
        uCam = GLES20.glGetUniformLocation(prog, "uCam")
        uAmb = GLES20.glGetUniformLocation(prog, "uAmb")
        uAlpha = GLES20.glGetUniformLocation(prog, "uAlpha")
    }
}

class FlatShader {
    var prog = 0; var aPos = 0; var uMVP = 0; var uColor = 0
    fun init() {
        prog = Sh.program(
            "uniform mat4 uMVP; attribute vec3 aPos; void main(){ gl_Position = uMVP * vec4(aPos, 1.0); }",
            "precision mediump float; uniform vec4 uColor; void main(){ gl_FragColor = uColor; }"
        )
        aPos = GLES20.glGetAttribLocation(prog, "aPos")
        uMVP = GLES20.glGetUniformLocation(prog, "uMVP")
        uColor = GLES20.glGetUniformLocation(prog, "uColor")
    }
}

// ---------- построение мешей ----------
class MB {
    private var a = FloatArray(1024)
    private var n = 0
    fun v(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) {
        if (n + 6 > a.size) a = a.copyOf(a.size * 2)
        a[n++] = x; a[n++] = y; a[n++] = z; a[n++] = nx; a[n++] = ny; a[n++] = nz
    }
    fun tri(p: FloatArray) { // 9 координат, плоская нормаль
        val ux = p[3] - p[0]; val uy = p[4] - p[1]; val uz = p[5] - p[2]
        val wx = p[6] - p[0]; val wy = p[7] - p[1]; val wz = p[8] - p[2]
        var nx = uy * wz - uz * wy; var ny = uz * wx - ux * wz; var nz = ux * wy - uy * wx
        val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f); nx /= l; ny /= l; nz /= l
        for (i in 0 until 3) v(p[i * 3], p[i * 3 + 1], p[i * 3 + 2], nx, ny, nz)
    }
    fun quad(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float, x3: Float, y3: Float, z3: Float) {
        tri(floatArrayOf(x0, y0, z0, x1, y1, z1, x2, y2, z2))
        tri(floatArrayOf(x0, y0, z0, x2, y2, z2, x3, y3, z3))
    }
    fun build() = Mesh(a.copyOf(n))
}

object Meshes {
    // коробка с центром в нуле
    fun box(w: Float, h: Float, d: Float): Mesh {
        val m = MB(); val x = w / 2; val y = h / 2; val z = d / 2
        m.quad(-x, -y, z, x, -y, z, x, y, z, -x, y, z)
        m.quad(x, -y, -z, -x, -y, -z, -x, y, -z, x, y, -z)
        m.quad(x, -y, z, x, -y, -z, x, y, -z, x, y, z)
        m.quad(-x, -y, -z, -x, -y, z, -x, y, z, -x, y, -z)
        m.quad(-x, y, z, x, y, z, x, y, -z, -x, y, -z)
        m.quad(-x, -y, -z, x, -y, -z, x, -y, z, -x, -y, z)
        return m.build()
    }

    // тело вращения: профиль (r, y) снизу вверх
    fun lathe(profile: FloatArray, seg: Int = 20, caps: Boolean = true): Mesh {
        val m = MB(); val np = profile.size / 2
        for (i in 0 until np - 1) {
            val r0 = profile[i * 2]; val y0 = profile[i * 2 + 1]
            val r1 = profile[i * 2 + 2]; val y1 = profile[i * 2 + 3]
            val dr = r1 - r0; val dy = y1 - y0; val l = sqrt(dr * dr + dy * dy).coerceAtLeast(1e-6f)
            val nr = dy / l; val ny = -dr / l
            for (j in 0 until seg) {
                val a0 = (j.toFloat() / seg) * 6.2831855f; val a1 = ((j + 1).toFloat() / seg) * 6.2831855f
                val c0 = cos(a0); val s0 = sin(a0); val c1 = cos(a1); val s1 = sin(a1)
                m.v(r0 * c0, y0, r0 * s0, nr * c0, ny, nr * s0)
                m.v(r1 * c0, y1, r1 * s0, nr * c0, ny, nr * s0)
                m.v(r1 * c1, y1, r1 * s1, nr * c1, ny, nr * s1)
                m.v(r0 * c0, y0, r0 * s0, nr * c0, ny, nr * s0)
                m.v(r1 * c1, y1, r1 * s1, nr * c1, ny, nr * s1)
                m.v(r0 * c1, y0, r0 * s1, nr * c1, ny, nr * s1)
            }
        }
        if (caps) {
            val rb = profile[0]; val yb = profile[1]; val rt = profile[profile.size - 2]; val yt = profile[profile.size - 1]
            for (j in 0 until seg) {
                val a0 = (j.toFloat() / seg) * 6.2831855f; val a1 = ((j + 1).toFloat() / seg) * 6.2831855f
                if (rb > 0f) {
                    m.v(0f, yb, 0f, 0f, -1f, 0f); m.v(rb * cos(a0), yb, rb * sin(a0), 0f, -1f, 0f); m.v(rb * cos(a1), yb, rb * sin(a1), 0f, -1f, 0f)
                }
                if (rt > 0f) {
                    m.v(0f, yt, 0f, 0f, 1f, 0f); m.v(rt * cos(a1), yt, rt * sin(a1), 0f, 1f, 0f); m.v(rt * cos(a0), yt, rt * sin(a0), 0f, 1f, 0f)
                }
            }
        }
        return m.build()
    }

    fun cyl(r: Float, h: Float, seg: Int = 20) = lathe(floatArrayOf(r, 0f, r, h), seg)
    fun cone(r: Float, h: Float, seg: Int = 20) = lathe(floatArrayOf(r, 0f, 0f, h), seg)

    // сфера с центром в нуле
    fun sphere(r: Float, seg: Int = 18, rings: Int = 12): Mesh {
        val m = MB()
        fun p(i: Int, j: Int): FloatArray {
            val th = Math.PI.toFloat() * i / rings; val ph = 6.2831855f * j / seg
            val nx = sin(th) * cos(ph); val ny = cos(th); val nz = sin(th) * sin(ph)
            return floatArrayOf(nx * r, ny * r, nz * r, nx, ny, nz)
        }
        for (i in 0 until rings) for (j in 0 until seg) {
            val a = p(i, j); val b = p(i + 1, j); val c = p(i + 1, j + 1); val d = p(i, j + 1)
            for (q in arrayOf(a, b, c, a, c, d)) m.v(q[0], q[1], q[2], q[3], q[4], q[5])
        }
        return m.build()
    }

    // кристалл-октаэдр
    fun crystal(r: Float, top: Float, bottom: Float, sides: Int = 6): Mesh {
        val m = MB()
        for (j in 0 until sides) {
            val a0 = 6.2831855f * j / sides; val a1 = 6.2831855f * (j + 1) / sides
            val x0 = r * cos(a0); val z0 = r * sin(a0); val x1 = r * cos(a1); val z1 = r * sin(a1)
            m.tri(floatArrayOf(0f, top, 0f, x1, 0f, z1, x0, 0f, z0))
            m.tri(floatArrayOf(0f, -bottom, 0f, x0, 0f, z0, x1, 0f, z1))
        }
        return m.build()
    }

    // плоское кольцо на полу
    fun ring(ri: Float, ro: Float, seg: Int = 48): Mesh {
        val m = MB()
        for (j in 0 until seg) {
            val a0 = 6.2831855f * j / seg; val a1 = 6.2831855f * (j + 1) / seg
            val c0 = cos(a0); val s0 = sin(a0); val c1 = cos(a1); val s1 = sin(a1)
            m.v(ri * c0, 0f, ri * s0, 0f, 1f, 0f); m.v(ro * c0, 0f, ro * s0, 0f, 1f, 0f); m.v(ro * c1, 0f, ro * s1, 0f, 1f, 0f)
            m.v(ri * c0, 0f, ri * s0, 0f, 1f, 0f); m.v(ro * c1, 0f, ro * s1, 0f, 1f, 0f); m.v(ri * c1, 0f, ri * s1, 0f, 1f, 0f)
        }
        return m.build()
    }
}
