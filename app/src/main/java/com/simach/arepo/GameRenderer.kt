package com.simach.arepo

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.huawei.hiar.ARSession
import com.huawei.hiar.ARTrackable
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sin

class GameRenderer(private val act: MainActivity, private val game: Game) : GLSurfaceView.Renderer {
    @Volatile var session: ARSession? = null
    private val bg = Background()
    private val lit = LitShader()
    private val flat = FlatShader()
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val model = FloatArray(16)
    private val tmp = FloatArray(16)
    private val mvp = FloatArray(16)
    private var width = 1
    private var height = 1
    @Volatile var geometryDirty = true
    private var lastNs = 0L

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        bg.init()
        lit.init()
        flat.init()
        Models.build()
        geometryDirty = true
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
        GLES20.glViewport(0, 0, w, h)
        geometryDirty = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        if (geometryDirty) {
            s.setDisplayGeometry(act.displayRotation(), width, height)
            geometryDirty = false
        }
        s.setCameraTextureName(bg.tex)
        val frame = try { s.update() } catch (e: Throwable) { return }
        bg.draw(frame)
        val cam = frame.camera
        val trk = cam.trackingState == ARTrackable.TrackingState.TRACKING
        cam.getProjectionMatrix(proj, 0, 0.03f, 60f)
        cam.getViewMatrix(view, 0)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0.016f else (now - lastNs) / 1e9f
        lastNs = now
        try {
            game.frame(s, frame, cam, trk, dt)
        } catch (e: Throwable) {
            act.reportError(e)
        }
        if (!trk) return
        drawScene()
    }

    private fun drawScene() {
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        val ph = game.phase

        // поверхности во время сканирования
        if (ph == Phase.SCAN || ph == Phase.INTRO) drawPlanes()

        // точка сдачи
        game.zone?.let { z -> if (ph != Phase.MENU) drawZone(z) }

        GLES20.glUseProgram(lit.prog)
        GLES20.glUniform3f(lit.uCam, game.camPos.x, game.camPos.y, game.camPos.z)
        GLES20.glUniform1f(lit.uAmb, game.ambient)

        if (ph == Phase.PLAY) {
            for (it in game.items) {
                if (it.dead) continue
                Matrix.setIdentityM(model, 0)
                Matrix.translateM(model, 0, it.pos.x, it.pos.y, it.pos.z)
                Matrix.rotateM(model, 0, it.yaw, 0f, 1f, 0f)
                if (it.tilt != 0f) Matrix.rotateM(model, 0, it.tilt, 0f, 0f, 1f)
                var glow = 0f
                if (it === game.aimed) glow = 0.18f + 0.12f * sin(game.time * 8f)
                if (it.held) glow = 0.08f
                if (it.inZone) glow = 0.1f
                val hit = if (it.flash > 0f) 0.6f else 0f
                drawParts(it.type.parts, glow, hit)
            }
            for (m in game.monsters) {
                Matrix.setIdentityM(model, 0)
                Matrix.translateM(model, 0, m.pos.x, m.pos.y, m.pos.z)
                Matrix.rotateM(model, 0, m.yaw, 0f, 1f, 0f)
                val sc = 0.3f + 0.7f * m.appear
                val bob = if (m.kind == 0) 0.03f * sin(game.time * 2f) else 0f
                Matrix.translateM(model, 0, 0f, bob, 0f)
                Matrix.scaleM(model, 0, sc, sc, sc)
                val parts = when (m.kind) { 0 -> Models.shadow; 1 -> Models.statue; else -> Models.jumper }
                drawParts(parts, if (m.stun > 0f) 0.25f else 0f, 0f, stunned = m.stun > 0f)
            }
        }
        for (s in game.shards) {
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, s.pos.x, s.pos.y, s.pos.z)
            Matrix.rotateM(model, 0, s.life * 400f, 1f, 1f, 0f)
            drawMesh(Models.shard, s.color, floatArrayOf(0f, 0f, 0f), 0.6f, 1f)
        }
    }

    private val partModel = FloatArray(16)
    private val emisTmp = FloatArray(3)

    private fun drawParts(parts: List<Part>, glow: Float, hit: Float, stunned: Boolean = false) {
        for (p in parts) {
            Matrix.multiplyMM(partModel, 0, model, 0, p.m, 0)
            emisTmp[0] = p.emis[0] + glow + hit
            emisTmp[1] = p.emis[1] + glow * 0.85f
            emisTmp[2] = p.emis[2] + glow * 0.4f + (if (stunned) 0.5f else 0f)
            drawMeshM(p.mesh, partModel, p.color, emisTmp, p.spec, p.alpha)
        }
    }

    private fun drawMesh(mesh: Mesh, color: FloatArray, emis: FloatArray, spec: Float, alpha: Float) =
        drawMeshM(mesh, model, color, emis, spec, alpha)

    private fun drawMeshM(mesh: Mesh, m: FloatArray, color: FloatArray, emis: FloatArray, spec: Float, alpha: Float) {
        Matrix.multiplyMM(mvp, 0, vp, 0, m, 0)
        GLES20.glUseProgram(lit.prog)
        GLES20.glUniformMatrix4fv(lit.uMVP, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(lit.uModel, 1, false, m, 0)
        GLES20.glUniform3f(lit.uColor, color[0], color[1], color[2])
        GLES20.glUniform3f(lit.uEmis, emis[0], emis[1], emis[2])
        GLES20.glUniform1f(lit.uSpec, spec)
        GLES20.glUniform1f(lit.uAlpha, alpha)
        GLES20.glUniform3f(lit.uCam, game.camPos.x, game.camPos.y, game.camPos.z)
        GLES20.glUniform1f(lit.uAmb, game.ambient)
        mesh.buf.position(0)
        GLES20.glEnableVertexAttribArray(lit.aPos)
        GLES20.glVertexAttribPointer(lit.aPos, 3, GLES20.GL_FLOAT, false, 24, mesh.buf)
        mesh.buf.position(3)
        GLES20.glEnableVertexAttribArray(lit.aNor)
        GLES20.glVertexAttribPointer(lit.aNor, 3, GLES20.GL_FLOAT, false, 24, mesh.buf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mesh.count)
        GLES20.glDisableVertexAttribArray(lit.aPos)
        GLES20.glDisableVertexAttribArray(lit.aNor)
    }

    private fun drawZone(z: V3) {
        val ok = game.phase == Phase.PLAY && game.zoneValue >= game.quota
        val partial = game.phase == Phase.PLAY && game.zoneValue > 0
        val col = when { ok -> floatArrayOf(0.2f, 1f, 0.4f); partial -> floatArrayOf(1f, 0.8f, 0.2f); else -> floatArrayOf(1f, 0.35f, 0.25f) }
        val pulse = 0.75f + 0.25f * sin(game.time * 3f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, z.x, z.y + 0.005f, z.z)
        drawMesh(Models.zoneRing, col, floatArrayOf(col[0] * pulse, col[1] * pulse, col[2] * pulse), 0f, 0.95f)
        drawMesh(Models.zoneDisc, col, floatArrayOf(col[0] * 0.4f, col[1] * 0.4f, col[2] * 0.4f), 0f, 0.22f)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        drawMesh(Models.beam, col, floatArrayOf(col[0] * 0.3f, col[1] * 0.3f, col[2] * 0.3f), 0f, 0.12f * pulse)
        Matrix.translateM(model, 0, 0f, 1.6f + 0.06f * sin(game.time * 2f), 0f)
        Matrix.rotateM(model, 0, game.time * 90f, 0f, 1f, 0f)
        drawMesh(Models.marker, col, col, 0.5f, 0.9f)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun drawPlanes() {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(flat.prog)
        GLES20.glUniformMatrix4fv(flat.uMVP, 1, false, vp, 0)
        val fy = game.floorY
        val t = 0.75f + 0.25f * sin(game.time * 2.5f)
        for (p in game.planes.values) {
            val buf = p.fan ?: continue
            if (p.type == 2) continue
            val c = when {
                p.label == "PLANE_DOOR" -> floatArrayOf(0.3f, 1f, 0.45f, 0.38f)
                p.label == "PLANE_WINDOW" -> floatArrayOf(0.5f, 0.8f, 1f, 0.25f)
                p.type == 1 -> floatArrayOf(0.6f, 0.45f, 1f, 0.16f)
                p.label == "PLANE_TABLE" -> floatArrayOf(1f, 0.82f, 0.2f, 0.36f)
                p.label == "PLANE_SEAT" -> floatArrayOf(1f, 0.55f, 0.2f, 0.36f)
                p.label == "PLANE_BED" -> floatArrayOf(1f, 0.4f, 0.7f, 0.36f)
                !fy.isNaN() && kotlin.math.abs(p.y - fy) < 0.15f -> floatArrayOf(0.15f, 0.85f, 1f, 0.26f * t)
                else -> floatArrayOf(1f, 0.82f, 0.2f, 0.3f)
            }
            GLES20.glUniform4f(flat.uColor, c[0], c[1], c[2], c[3])
            buf.position(0)
            GLES20.glEnableVertexAttribArray(flat.aPos)
            GLES20.glVertexAttribPointer(flat.aPos, 3, GLES20.GL_FLOAT, false, 0, buf)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, p.fanCount)
            GLES20.glDisableVertexAttribArray(flat.aPos)
        }
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }
}
