package com.simach.arepo

import android.content.Context
import com.huawei.hiar.ARCamera
import com.huawei.hiar.ARFrame
import com.huawei.hiar.ARPlane
import com.huawei.hiar.ARSession
import com.huawei.hiar.ARTrackable
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt
import kotlin.random.Random

enum class Phase { MENU, SCAN, INTRO, PLAY, DONE, SHOP, DEAD }

// ---------- поверхность из сканирования ----------
class PlaneInfo(val key: Int) {
    var type = 3          // 0 — горизонтальная вверх, 1 — вертикальная, 2 — вниз, 3 — неизвестно
    var label = "PLANE_OTHER"
    var y = 0f
    var pts = FloatArray(0) // мировые x,y,z
    var area = 0f
    var cx = 0f; var cz = 0f
    var minX = 0f; var maxX = 0f; var minZ = 0f; var maxZ = 0f
    var fan: FloatBuffer? = null
    var fanCount = 0

    fun fill(p: ARPlane) {
        val c = p.centerPose
        type = when (p.type) {
            ARPlane.PlaneType.HORIZONTAL_UPWARD_FACING -> 0
            ARPlane.PlaneType.VERTICAL_FACING -> 1
            ARPlane.PlaneType.HORIZONTAL_DOWNWARD_FACING -> 2
            else -> 3
        }
        label = try { p.label?.name ?: "PLANE_OTHER" } catch (e: Throwable) { "PLANE_OTHER" }
        y = c.ty()
        val buf = p.planePolygon ?: return
        buf.rewind()
        val n = buf.remaining() / 2
        if (n < 3) return
        val out = FloatArray(n * 3)
        val tmp = FloatArray(3); val res = FloatArray(3)
        for (i in 0 until n) {
            tmp[0] = buf.get(); tmp[1] = 0f; tmp[2] = buf.get()
            c.transformPoint(tmp, 0, res, 0)
            out[i * 3] = res[0]; out[i * 3 + 1] = res[1]; out[i * 3 + 2] = res[2]
        }
        pts = out
        var sx = 0f; var sz = 0f; var a = 0f
        minX = 1e9f; maxX = -1e9f; minZ = 1e9f; maxZ = -1e9f
        for (i in 0 until n) {
            val x0 = out[i * 3]; val z0 = out[i * 3 + 2]
            val j = (i + 1) % n
            a += x0 * out[j * 3 + 2] - out[j * 3] * z0
            sx += x0; sz += z0
            minX = min(minX, x0); maxX = max(maxX, x0); minZ = min(minZ, z0); maxZ = max(maxZ, z0)
        }
        cx = sx / n; cz = sz / n
        area = if (type == 1) p.extentX * p.extentZ else abs(a) / 2f
        // веер для отрисовки
        val f = FloatArray((n + 2) * 3)
        var cy = 0f
        for (i in 0 until n) cy += out[i * 3 + 1]
        f[0] = cx; f[1] = cy / n; f[2] = cz
        for (i in 0..n) {
            val k = i % n
            f[3 + i * 3] = out[k * 3]; f[4 + i * 3] = out[k * 3 + 1]; f[5 + i * 3] = out[k * 3 + 2]
        }
        val bb = ByteBuffer.allocateDirect(f.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        bb.put(f); bb.position(0)
        fan = bb; fanCount = n + 2
    }

    fun contains(x: Float, z: Float): Boolean {
        if (x < minX || x > maxX || z < minZ || z > maxZ) return false
        val n = pts.size / 3
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = pts[i * 3]; val zi = pts[i * 3 + 2]; val xj = pts[j * 3]; val zj = pts[j * 3 + 2]
            if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) inside = !inside
            j = i
        }
        return inside
    }
}

class Item(val type: ItemType, val pos: V3, val maxValue: Int) {
    var value = maxValue.toFloat()
    val vel = V3()
    var yaw = Random.nextFloat() * 360f
    var tilt = 0f
    var held = false
    var resting = true
    var dead = false
    var inZone = false
    var flash = 0f
    var pendingDmg = 0f
    var dmgTimer = 0f
    fun center() = V3(pos.x, pos.y + type.height / 2f, pos.z)
}

class Monster(val kind: Int, val pos: V3, val speed: Float) {
    var state = 0
    var timer = 0f
    var cool = 0f
    var stun = 0f
    var hitCd = 0f
    var yaw = 0f
    var seen = false
    var appear = 0f
    val target = V3()
    val dash = V3()
}

class Shard(val pos: V3, val vel: V3, val color: FloatArray, var life: Float)

class ShopDef(val id: String, val e: String, val n: String, val d: String, val base: Int, val max: Int, val cons: Boolean)

val SHOP = listOf(
    ShopDef("hp", "❤️", "Здоровье", "+25 к максимуму здоровья", 900, 5, false),
    ShopDef("grip", "💪", "Сила захвата", "Вещи в руках бьются на 18% меньше", 1100, 4, false),
    ShopDef("reach", "🦾", "Длинные руки", "Хватаешь на 40 см дальше", 700, 3, false),
    ShopDef("armor", "🛡️", "Броня", "Монстры бьют на 15% слабее", 1300, 3, false),
    ShopDef("map", "🗺️", "Карта ценностей", "Все ценности видны на карте", 2500, 1, false),
    ShopDef("det", "📡", "Детектор", "Стрелка и писк к ближайшей ценности", 1600, 1, false),
    ShopDef("radar", "👁️", "Радар монстров", "Монстры видны на карте", 1800, 1, false),
    ShopDef("med", "🩹", "Аптечка", "+40 здоровья", 350, 99, true),
    ShopDef("shock", "⚡", "Шокер", "Оглушает монстров перед тобой на 6 с", 600, 99, true),
    ShopDef("magnet", "🧲", "Магнит", "Притягивает ближайшую ценность (до 4 м)", 500, 99, true)
)

// ---------- сохранение ----------
class Save(ctx: Context) {
    private val sp = ctx.getSharedPreferences("arepo", Context.MODE_PRIVATE)
    var level = sp.getInt("level", 1)
    var money = sp.getInt("money", 0)
    var best = sp.getInt("best", 0)
    var hp = sp.getFloat("hp", -1f)
    var runEarned = sp.getInt("runEarned", 0)
    val up = HashMap<String, Int>()

    init { for (d in SHOP) up[d.id] = sp.getInt("up_" + d.id, 0) }

    fun lvl(id: String) = up[id] ?: 0

    fun store() {
        val e = sp.edit()
        e.putInt("level", level).putInt("money", money).putInt("best", best).putFloat("hp", hp).putInt("runEarned", runEarned)
        for ((k, v) in up) e.putInt("up_$k", v)
        e.apply()
    }

    fun resetRun() {
        level = 1; money = 0; hp = -1f; runEarned = 0
        for (d in SHOP) up[d.id] = 0
        up["med"] = 1
        store()
    }
}

class Game(private val act: MainActivity) {
    val cmds = ConcurrentLinkedQueue<JSONObject>()
    var phase = Phase.MENU
    val planes = HashMap<Int, PlaneInfo>()
    private var planesVersion = 0
    private var sentPlanesVersion = -1
    var floorY = Float.NaN

    val camPos = V3(); val fwd = V3(0f, 0f, -1f); val right = V3(1f, 0f, 0f)
    var tracking = false
    var ambient = 1f

    private var walked = 0f
    private val lastCam = V3(); private var hasLast = false
    private var scanStart: V3? = null
    var zone: V3? = null

    val items = ArrayList<Item>()
    val monsters = ArrayList<Monster>()
    val shards = ArrayList<Shard>()
    var held: Item? = null
    var aimed: Item? = null
    var quota = 0
    var zoneValue = 0
    var hp = 100f
    private var brokenCount = 0
    private var brokenValue = 0
    private var lastSummary = JSONObject()

    val save = Save(act)

    private var planeTimer = 0f
    private var uiTimer = 0f
    private var mapTimer = 0f
    var time = 0f

    init {
        if (save.hp < 0f) save.hp = hpMax()
        hp = save.hp
        if (save.level == 1 && save.money == 0 && save.lvl("med") == 0 && save.runEarned == 0) { save.up["med"] = 1; save.store() }
    }

    fun hpMax() = 100f + 25f * save.lvl("hp")
    private fun reach() = 1.2f + 0.4f * save.lvl("reach")

    fun quotaFor(l: Int): Int = (round(900.0 * 1.33.pow(l - 1) / 50.0) * 50).toInt()

    // ================== главный цикл ==================
    fun frame(session: ARSession, frame: ARFrame, cam: ARCamera, trk: Boolean, dtIn: Float) {
        val dt = dtIn.coerceIn(0.001f, 0.05f)
        time += dt
        tracking = trk
        val pose = cam.displayOrientedPose
        camPos.set(pose.tx(), pose.ty(), pose.tz())
        val z = pose.zAxis; fwd.set(-z[0], -z[1], -z[2])
        val x = pose.xAxis; right.set(x[0], x[1], x[2])
        try {
            val le = frame.lightEstimate
            if (le != null) ambient = (le.pixelIntensity * 1.6f).coerceIn(0.45f, 1.25f)
        } catch (e: Throwable) { }

        processCmds()

        planeTimer -= dt
        if (trk && planeTimer <= 0f) {
            updatePlanes(session)
            planeTimer = if (phase == Phase.SCAN) 0.35f else 1.5f
        }

        when (phase) {
            Phase.SCAN -> scanUpdate()
            Phase.PLAY -> if (trk) playUpdate(dt)
            else -> {}
        }
        updateShards(dt)

        uiTimer -= dt
        if (uiTimer <= 0f) { sendState(); uiTimer = 0.1f }
        mapTimer -= dt
        if (mapTimer <= 0f) { sendMap(); mapTimer = 0.4f }
    }

    // ================== команды из интерфейса ==================
    private fun processCmds() {
        while (true) {
            val c = cmds.poll() ?: break
            when (c.optString("c")) {
                "start" -> {
                    if (zone != null && floorArea() >= 1.5f) phase = Phase.INTRO else startScan()
                }
                "scan" -> startScan()
                "setZone" -> setZoneHere()
                "finishScan" -> finishScan()
                "startLevel" -> startLevel()
                "grab" -> grab()
                "release" -> release()
                "extract" -> extract()
                "shop" -> if (phase == Phase.DONE) phase = Phase.SHOP
                "buy" -> buy(c.optString("id"))
                "next" -> if (phase == Phase.SHOP) phase = Phase.INTRO
                "use" -> useItem(c.optString("id"))
                "newRun" -> { save.resetRun(); hp = hpMax(); save.hp = hp; save.store(); clearLevel(); phase = if (zone != null) Phase.INTRO else Phase.MENU }
                "abandon" -> { clearLevel(); phase = Phase.INTRO }
                "menu" -> { clearLevel(); phase = Phase.MENU }
            }
        }
    }

    private fun event(type: String, extra: JSONObject = JSONObject()) {
        extra.put("t", type)
        act.js("E($extra)")
    }

    private fun msg(text: String) = event("msg", JSONObject().put("m", text))

    // ================== сканирование ==================
    private fun startScan() {
        clearLevel()
        phase = Phase.SCAN
        hasLast = false
        if (scanStart == null && tracking) scanStart = camPos.copy()
    }

    private fun updatePlanes(session: ARSession) {
        val alive = HashSet<Int>()
        for (p in session.getAllTrackables(ARPlane::class.java)) {
            val key = p.hashCode()
            if (p.subsumedBy != null || p.trackingState == ARTrackable.TrackingState.STOPPED) continue
            alive.add(key)
            if (p.trackingState != ARTrackable.TrackingState.TRACKING) continue
            val info = planes.getOrPut(key) { PlaneInfo(key) }
            try { info.fill(p) } catch (e: Throwable) { }
        }
        val it = planes.keys.iterator()
        while (it.hasNext()) if (!alive.contains(it.next())) it.remove()
        planesVersion++
        computeFloor()
    }

    private fun computeFloor() {
        val hz = planes.values.filter { it.type == 0 && it.area >= 0.35f && it.pts.isNotEmpty() }
        val floors = hz.filter { it.label == "PLANE_FLOOR" }
        val src = if (floors.isNotEmpty()) floors else hz
        if (src.isEmpty()) return
        // самая низкая заметная поверхность
        floorY = src.minByOrNull { it.y }!!.y
    }

    private fun floorLevel(): Float = if (floorY.isNaN()) camPos.y - 1.35f else floorY

    private fun floorArea(): Float {
        if (floorY.isNaN()) return 0f
        return planes.values.filter { it.type == 0 && abs(it.y - floorY) < 0.15f }.sumOf { it.area.toDouble() }.toFloat()
    }

    private fun scanUpdate() {
        if (!tracking) return
        if (scanStart == null) scanStart = camPos.copy()
        if (hasLast) {
            val d = distXZ(camPos, lastCam)
            if (d < 0.5f) walked += d
        }
        lastCam.set(camPos); hasLast = true
    }

    private fun setZoneHere() {
        if (!tracking) { msg("Подожди, пока камера поймает пространство"); return }
        zone = V3(camPos.x, floorLevel(), camPos.z)
        event("zone")
        msg("📍 Точка сдачи поставлена")
    }

    private fun finishScan() {
        val fa = floorArea()
        if (fa < 1.5f) { msg("Мало отсканировано: пол ${"%.1f".format(fa)} м². Походи ещё и води камерой по полу"); return }
        if (zone == null) {
            val s = scanStart ?: camPos
            zone = V3(s.x, floorLevel(), s.z)
            msg("📍 Точка сдачи — там, где ты начал сканирование")
        } else zone!!.y = floorLevel()
        phase = Phase.INTRO
        event("scanDone")
    }

    // ================== уровень ==================
    private fun clearLevel() {
        items.clear(); monsters.clear(); shards.clear()
        held = null; aimed = null; zoneValue = 0
    }

    private fun spawnSurfaces(): List<PlaneInfo> {
        val fy = floorLevel()
        return planes.values.filter { it.type == 0 && it.area >= 0.08f && it.y > fy - 0.2f && it.y < fy + 1.6f && it.pts.isNotEmpty() }
    }

    private fun randomPointOn(p: PlaneInfo, margin: Float = 0.08f): V3? {
        for (t in 0 until 30) {
            val x = p.minX + Random.nextFloat() * (p.maxX - p.minX)
            val z = p.minZ + Random.nextFloat() * (p.maxZ - p.minZ)
            if (p.contains(x, z) && p.contains(x + margin, z) && p.contains(x - margin, z) && p.contains(x, z + margin) && p.contains(x, z - margin)) {
                return V3(x, p.y, z)
            }
        }
        return if (p.contains(p.cx, p.cz)) V3(p.cx, p.y, p.cz) else null
    }

    private fun <T> weighted(list: List<T>, w: (T) -> Float): T {
        val total = list.sumOf { w(it).toDouble() }.toFloat()
        var r = Random.nextFloat() * total
        for (e in list) { r -= w(e); if (r <= 0f) return e }
        return list.last()
    }

    private fun startLevel() {
        if (phase != Phase.INTRO) return
        val surfaces = spawnSurfaces()
        if (surfaces.isEmpty()) { msg("Не вижу поверхностей — пересканируй квартиру"); return }
        clearLevel()
        val lvl = save.level
        quota = quotaFor(lvl)
        hp = min(hpMax(), hp + 20f)
        brokenCount = 0; brokenValue = 0
        val fy = floorLevel()
        val z = zone ?: V3(camPos.x, fy, camPos.z)

        // типы и стоимость
        val count = min(6 + lvl * 2, 22)
        val pool = Models.items.filter { it.minLevel <= lvl }
        val types = (0 until count).map { weighted(pool) { t -> t.weight } }
        val raw = types.map { it.baseValue * (0.7f + Random.nextFloat() * 0.6f) }
        val target = quota * (1.7f + Random.nextFloat() * 0.3f)
        val k = target / raw.sum()

        val placed = ArrayList<V3>()
        for (i in types.indices) {
            var best: V3? = null; var bestScore = -1f
            for (tries in 0 until 14) {
                val pl = weighted(surfaces) { p -> p.area.pow(0.6f) * (if (p.y > fy + 0.25f) 1.7f else 1f) }
                val pt = randomPointOn(pl) ?: continue
                if (distXZ(pt, z) < 1.0f) continue
                if (placed.any { distXZ(it, pt) < 0.4f }) continue
                val score = distXZ(pt, z) + Random.nextFloat() * 2.5f
                if (score > bestScore) { bestScore = score; best = pt }
            }
            if (best == null) {
                val pl = weighted(surfaces) { p -> p.area }
                best = randomPointOn(pl, 0.02f) ?: continue
            }
            placed.add(best)
            val v = max(50, (round(raw[i] * k / 10f) * 10).toInt())
            items.add(Item(types[i], best, v))
        }

        // монстры
        val kinds = when {
            lvl <= 1 -> listOf()
            lvl == 2 -> listOf(0)
            lvl == 3 -> listOf(1)
            lvl == 4 -> listOf(0, 1)
            lvl == 5 -> listOf(0, 2)
            lvl < 8 -> listOf(0, 1, 2)
            else -> listOf(0, 0, 1, 2)
        }
        val floorPlanes = planes.values.filter { it.type == 0 && abs(it.y - fy) < 0.15f && it.pts.isNotEmpty() }
        for (kd in kinds) {
            var best: V3? = null; var bd = -1f
            for (t in 0 until 20) {
                val pl = if (floorPlanes.isNotEmpty()) weighted(floorPlanes) { p -> p.area } else weighted(surfaces) { p -> p.area }
                val pt = randomPointOn(pl, 0.02f) ?: continue
                val d = min(distXZ(pt, camPos), distXZ(pt, z) + 1f)
                if (d > bd) { bd = d; best = pt }
            }
            val pos = best ?: V3(camPos.x + 4f, fy, camPos.z + 4f)
            pos.y = fy
            val sp = when (kd) { 0 -> min(1.0f, 0.5f + 0.04f * lvl); 1 -> min(1.1f, 0.55f + 0.04f * lvl); else -> 2.6f }
            val m = Monster(kd, pos, sp)
            m.timer = 3f + Random.nextFloat() * 5f
            m.target.set(pos)
            monsters.add(m)
        }
        phase = Phase.PLAY
        event("levelStart", JSONObject().put("lvl", lvl).put("quota", quota).put("n", items.size).put("mon", monsters.size))
    }

    // ================== игровой процесс ==================
    private fun supportY(x: Float, z: Float, maxY: Float): Float {
        var best = floorLevel()
        for (p in planes.values) {
            if (p.type != 0 || p.y > maxY || p.y <= best) continue
            if (p.contains(x, z)) best = p.y
        }
        return best
    }

    private fun playUpdate(dt: Float) {
        val fy = floorLevel()
        // прицел
        aimed = null
        if (held == null) {
            var bestT = Float.MAX_VALUE
            for (it in items) {
                if (it.dead) continue
                val d = it.center() - camPos
                val t = d.dot(fwd)
                if (t < 0.05f || t > reach() + it.type.radius) continue
                val perp = (d - fwd * t).len()
                val tol = it.type.radius + 0.07f + t * 0.06f
                if (perp < tol && t < bestT) { bestT = t; aimed = it }
            }
        }

        // предмет в руках
        val h = held
        if (h != null) {
            val holdDist = 0.42f + h.type.radius
            val target = V3(camPos.x + fwd.x * holdDist, camPos.y + fwd.y * holdDist - 0.07f, camPos.z + fwd.z * holdDist)
            val k = 150f / h.type.mass
            val c = 2f * sqrt(k) * 0.5f
            val steps = 3
            val sdt = dt / steps
            val grip = 1f - 0.18f * save.lvl("grip")
            for (s in 0 until steps) {
                val cen = h.center()
                val ax = (target.x - cen.x) * k - h.vel.x * c
                val ay = (target.y - cen.y) * k - h.vel.y * c
                val az = (target.z - cen.z) * k - h.vel.z * c
                h.vel.x += ax * sdt; h.vel.y += ay * sdt; h.vel.z += az * sdt
                h.pos.add(h.vel, sdt)
            }
            val stretch = (target - h.center()).len()
            val speed = h.vel.len()
            var dmg = 0f
            if (stretch > 0.15f) dmg += (stretch - 0.15f) * h.type.fragility * h.maxValue * 2.6f * dt
            if (speed > 2.8f) dmg += (speed - 2.8f) * h.type.fragility * h.maxValue * 0.12f * dt
            if (dmg > 0f) damage(h, dmg * grip)
            // покачивание
            val side = h.vel.dot(right)
            h.tilt += ((side * 25f).coerceIn(-35f, 35f) - h.tilt) * min(1f, dt * 8f)
            val camYaw = Math.toDegrees(atan2(fwd.x.toDouble(), fwd.z.toDouble())).toFloat()
            var dy = camYaw - h.yaw
            while (dy > 180f) dy -= 360f
            while (dy < -180f) dy += 360f
            h.yaw += dy * min(1f, dt * 3f)
            if (stretch > 1.5f) { release(); msg("Вещь выскользнула!") }
        }

        // свободные предметы
        for (it in items) {
            if (it.dead) continue
            it.flash = max(0f, it.flash - dt)
            it.dmgTimer -= dt
            if (it.pendingDmg > 0f && it.dmgTimer <= 0f) {
                event("hit", JSONObject().put("v", it.pendingDmg.toInt()).put("n", it.type.name))
                it.pendingDmg = 0f; it.dmgTimer = 0.35f
            }
            if (it.held || it.resting) continue
            it.vel.y -= 9.8f * dt
            it.vel.x *= (1f - 0.5f * dt); it.vel.z *= (1f - 0.5f * dt)
            it.pos.add(it.vel, dt)
            it.tilt *= (1f - 2f * dt)
            val sup = supportY(it.pos.x, it.pos.z, it.pos.y + 0.06f)
            if (it.pos.y <= sup) {
                val vy = -it.vel.y
                it.pos.y = sup
                if (vy > 1.2f) {
                    damage(it, (vy - 1.2f) * it.type.fragility * it.maxValue * 0.45f)
                    event("thud", JSONObject().put("v", vy))
                }
                if (vy > 0.7f) { it.vel.y = vy * 0.25f; it.vel.x *= 0.5f; it.vel.z *= 0.5f }
                else { it.vel.set(0f, 0f, 0f); it.resting = true; it.tilt = 0f }
            }
            if (it.pos.y < fy - 1.5f) { it.pos.y = fy; it.vel.set(0f, 0f, 0f); it.resting = true }
        }

        // зона сдачи
        val z = zone
        zoneValue = 0
        if (z != null) for (it in items) {
            it.inZone = !it.dead && !it.held && it.resting && distXZ(it.pos, z) < 0.55f
            if (it.inZone) zoneValue += it.value.toInt()
        }

        // монстры
        for (m in monsters) updateMonster(m, dt)
    }

    private fun updateMonster(m: Monster, dt: Float) {
        m.appear = min(1f, m.appear + dt * 0.7f)
        m.hitCd -= dt
        val fy = floorLevel()
        val toP = V3(camPos.x - m.pos.x, 0f, camPos.z - m.pos.z)
        val d = toP.lenXZ()
        if (m.stun > 0f) { m.stun -= dt; return }
        fun moveTo(tx: Float, tz: Float, sp: Float) {
            val dx = tx - m.pos.x; val dz = tz - m.pos.z; val l = sqrt(dx * dx + dz * dz)
            if (l > 0.05f) { val s = min(l, sp * dt); m.pos.x += dx / l * s; m.pos.z += dz / l * s }
        }
        when (m.kind) {
            0 -> { // Тень: бродит, замечает и охотится
                m.timer -= dt; m.cool -= dt
                if (m.state == 0) {
                    if (distXZ(m.pos, m.target) < 0.3f || m.timer <= 0f) {
                        val floors = planes.values.filter { it.type == 0 && abs(it.y - fy) < 0.15f && it.pts.isNotEmpty() }
                        if (floors.isNotEmpty()) randomPointOn(weighted(floors) { p -> p.area }, 0.02f)?.let { m.target.set(it) }
                        m.timer = 7f + Random.nextFloat() * 5f
                    }
                    moveTo(m.target.x, m.target.z, 0.3f)
                    if (d < 4.5f && m.cool <= 0f) { m.state = 1; m.timer = 9f + save.level; event("spotted", JSONObject().put("k", 0)) }
                } else {
                    moveTo(camPos.x, camPos.z, m.speed)
                    if (m.timer <= 0f || d > 9f) { m.state = 0; m.cool = 6f; m.timer = 0f }
                }
            }
            1 -> { // Статуя: двигается, только когда на неё не смотрят
                val dir = V3(m.pos.x - camPos.x, m.pos.y + 1.3f - camPos.y, m.pos.z - camPos.z)
                val dl = dir.len()
                val seen = dl < 12f && dir.dot(fwd) / dl > 0.8f
                if (!seen) moveTo(camPos.x, camPos.z, m.speed * 1.3f)
                if (seen && !m.seen && d < 4f) event("statue")
                m.seen = seen
            }
            else -> { // Прыгун: рывки
                m.timer -= dt
                if (m.state == 0) {
                    moveTo(camPos.x, camPos.z, 0.25f)
                    if (m.timer <= 0f && d < 6f) {
                        m.state = 1; m.timer = 0.7f
                        m.dash.set(toP.x / d.coerceAtLeast(0.01f), 0f, toP.z / d.coerceAtLeast(0.01f))
                        event("leap")
                    }
                } else {
                    m.pos.x += m.dash.x * m.speed * dt; m.pos.z += m.dash.z * m.speed * dt
                    if (m.timer <= 0f) { m.state = 0; m.timer = 3.5f + Random.nextFloat() * 2f }
                }
            }
        }
        m.pos.y = fy
        m.yaw = Math.toDegrees(atan2(toP.x.toDouble(), toP.z.toDouble())).toFloat()
        if (d < 0.5f && m.hitCd <= 0f) hurtPlayer(m, toP, d)
    }

    private fun hurtPlayer(m: Monster, toP: V3, d: Float) {
        val dmg = 28f * (1f - 0.15f * save.lvl("armor"))
        hp -= dmg
        m.hitCd = 2.5f; m.stun = 1.5f
        if (d > 0.01f) { m.pos.x -= toP.x / d * 1.5f; m.pos.z -= toP.z / d * 1.5f }
        held?.let { h -> h.vel.add(fwd, 2f); release() }
        event("hurt", JSONObject().put("d", dmg.toInt()))
        if (hp <= 0f) {
            hp = 0f
            lastSummary = JSONObject().put("lvl", save.level).put("earned", save.runEarned).put("best", max(save.best, save.level))
            save.best = max(save.best, save.level)
            save.store()
            save.resetRun()
            hp = hpMax(); save.hp = hp; save.store()
            clearLevel()
            phase = Phase.DEAD
            event("dead")
        }
    }

    private fun damage(it: Item, amount: Float) {
        if (amount <= 0f || it.dead) return
        it.value -= amount
        it.flash = 0.2f
        it.pendingDmg += amount
        if (it.value <= it.maxValue * 0.08f) breakItem(it)
    }

    private fun breakItem(it: Item) {
        it.dead = true
        if (held === it) { held = null; it.held = false }
        brokenCount++; brokenValue += it.maxValue
        val c = it.center()
        for (i in 0 until 10) {
            shards.add(Shard(c.copy(), V3((Random.nextFloat() - 0.5f) * 2.2f, Random.nextFloat() * 2.2f, (Random.nextFloat() - 0.5f) * 2.2f), it.type.color, 1.4f))
        }
        event("break", JSONObject().put("n", it.type.name).put("v", it.maxValue))
    }

    private fun updateShards(dt: Float) {
        val fy = floorLevel()
        val it = shards.iterator()
        while (it.hasNext()) {
            val s = it.next()
            s.vel.y -= 9.8f * dt
            s.pos.add(s.vel, dt)
            if (s.pos.y < fy) { s.pos.y = fy; s.vel.y = -s.vel.y * 0.3f; s.vel.x *= 0.6f; s.vel.z *= 0.6f }
            s.life -= dt
            if (s.life <= 0f) it.remove()
        }
    }

    private fun grab() {
        if (phase != Phase.PLAY || held != null) return
        val a = aimed ?: run { msg("Наведи прицел на ценность поближе"); return }
        held = a; a.held = true; a.resting = false; a.inZone = false
        event("grab", JSONObject().put("n", a.type.name))
    }

    private fun release() {
        val h = held ?: return
        h.held = false; h.resting = false
        held = null
        event("release")
    }

    private fun extract() {
        val z = zone ?: return
        if (phase != Phase.PLAY) return
        if (zoneValue < quota) { msg("Не хватает до квоты: ${quota - zoneValue} $"); return }
        if (distXZ(camPos, z) > 1.5f) { msg("Подойди к точке сдачи"); return }
        val got = zoneValue
        val n = items.count { it.inZone }
        save.money += got
        save.runEarned += got
        val done = save.level
        save.level += 1
        save.best = max(save.best, save.level)
        save.hp = hp
        save.store()
        lastSummary = JSONObject().put("lvl", done).put("got", got).put("quota", quota).put("n", n)
            .put("broken", brokenCount).put("lost", brokenValue).put("left", items.count { !it.dead && !it.inZone })
        clearLevel()
        phase = Phase.DONE
        event("extract", JSONObject().put("v", got))
    }

    private fun shopPrice(d: ShopDef): Int {
        if (d.cons) return d.base
        return (round(d.base * 1.6.pow(save.lvl(d.id)) / 50.0) * 50).toInt()
    }

    private fun buy(id: String) {
        val d = SHOP.firstOrNull { it.id == id } ?: return
        val l = save.lvl(id)
        if (l >= d.max) { msg("Уже максимум"); return }
        val p = shopPrice(d)
        if (save.money < p) { msg("Не хватает денег"); return }
        save.money -= p
        save.up[id] = l + 1
        if (id == "hp") hp += 25f
        save.hp = hp
        save.store()
        event("buy", JSONObject().put("n", d.n))
    }

    private fun useItem(id: String) {
        if (phase != Phase.PLAY) return
        val l = save.lvl(id)
        if (l <= 0) { msg("Нет в запасе — купи в магазине"); return }
        when (id) {
            "med" -> {
                if (hp >= hpMax()) { msg("Здоровье и так полное"); return }
                hp = min(hpMax(), hp + 40f)
                event("heal")
            }
            "shock" -> {
                var n = 0
                for (m in monsters) {
                    val d = V3(m.pos.x - camPos.x, 0f, m.pos.z - camPos.z)
                    val dl = d.lenXZ()
                    val f2 = V3(fwd.x, 0f, fwd.z).norm()
                    if (dl < 3.5f && (dl < 0.8f || (d.x * f2.x + d.z * f2.z) / dl > 0.4f)) { m.stun = 6f; n++ }
                }
                event("shock", JSONObject().put("n", n))
            }
            "magnet" -> {
                if (held != null) { msg("Руки заняты"); return }
                val t = items.filter { !it.dead && !it.inZone && (it.center() - camPos).len() < 4f }.minByOrNull { (it.center() - camPos).len() }
                    ?: run { msg("Рядом нет ценностей"); return }
                held = t; t.held = true; t.resting = false
                event("grab", JSONObject().put("n", t.type.name))
            }
            else -> return
        }
        save.up[id] = l - 1
        save.hp = hp
        save.store()
    }

    // ================== отправка в интерфейс ==================
    private fun angleTo(p: V3): Float {
        val dx = p.x - camPos.x; val dz = p.z - camPos.z
        val f = fwd.x * dx + fwd.z * dz
        val r = right.x * dx + right.z * dz
        return Math.toDegrees(atan2(r.toDouble(), f.toDouble())).toFloat()
    }

    private fun sendState() {
        val o = JSONObject()
        o.put("ph", phase.name)
        o.put("trk", tracking)
        o.put("lvl", save.level)
        o.put("money", save.money)
        o.put("best", save.best)
        o.put("hp", hp.toInt()); o.put("hpMax", hpMax().toInt())
        o.put("hasZone", zone != null)
        val cons = JSONObject()
        for (id in listOf("med", "shock", "magnet")) cons.put(id, save.lvl(id))
        o.put("cons", cons)
        when (phase) {
            Phase.SCAN, Phase.MENU -> {
                val hz = planes.values.filter { it.type == 0 }
                o.put("floor", floorArea().toDouble())
                o.put("surf", hz.count { !floorY.isNaN() && it.y > floorY + 0.25f && it.area > 0.06f })
                o.put("walls", planes.values.count { it.type == 1 })
                o.put("doors", planes.values.count { it.label == "PLANE_DOOR" })
                o.put("walked", walked.toDouble())
            }
            Phase.INTRO -> {
                o.put("quota", quotaFor(save.level))
                o.put("floor", floorArea().toDouble())
            }
            Phase.PLAY -> {
                o.put("quota", quota)
                o.put("zoneVal", zoneValue)
                o.put("left", items.count { !it.dead })
                aimed?.let { a -> o.put("aim", JSONObject().put("n", a.type.name).put("e", a.type.emoji).put("v", a.value.toInt()).put("fr", a.type.fragility.toDouble())) }
                held?.let { a -> o.put("held", JSONObject().put("n", a.type.name).put("e", a.type.emoji).put("v", a.value.toInt()).put("max", a.maxValue)) }
                zone?.let { z ->
                    val d = distXZ(camPos, z)
                    o.put("zd", d.toDouble()); o.put("za", angleTo(z).toDouble())
                    o.put("canExtract", zoneValue >= quota && d < 1.5f)
                }
                if (save.lvl("det") > 0) {
                    val t = items.filter { !it.dead && !it.inZone && !it.held }.minByOrNull { distXZ(it.pos, camPos) }
                    if (t != null) o.put("det", JSONObject().put("a", angleTo(t.pos).toDouble()).put("d", distXZ(t.pos, camPos).toDouble()))
                }
                var nearest: Monster? = null; var nd = 1e9f
                for (m in monsters) { val d = distXZ(m.pos, camPos); if (d < nd) { nd = d; nearest = m } }
                nearest?.let { m ->
                    val a = angleTo(m.pos)
                    o.put("mon", JSONObject().put("d", nd.toDouble()).put("a", a.toDouble()).put("k", m.kind).put("hunt", m.state == 1 || m.kind == 1).put("stun", m.stun > 0f))
                }
            }
            Phase.DONE, Phase.DEAD -> o.put("sum", lastSummary)
            Phase.SHOP -> {
                val arr = JSONArray()
                for (d in SHOP) arr.put(JSONObject().put("id", d.id).put("e", d.e).put("n", d.n).put("d", d.d).put("p", shopPrice(d)).put("l", save.lvl(d.id)).put("max", d.max).put("cons", d.cons))
                o.put("shop", arr)
                o.put("nextQuota", quotaFor(save.level))
            }
        }
        act.js("S($o)")
    }

    private fun r2(v: Float) = round(v * 100f) / 100f

    private fun sendMap() {
        if (phase == Phase.MENU || phase == Phase.DONE || phase == Phase.SHOP || phase == Phase.DEAD) return
        val o = JSONObject()
        if (sentPlanesVersion != planesVersion) {
            val arr = JSONArray()
            for (p in planes.values) {
                if (p.type == 2 || p.pts.isEmpty()) continue
                val pa = JSONArray()
                val n = p.pts.size / 3
                val step = max(1, n / 24)
                var i = 0
                while (i < n) { pa.put(r2(p.pts[i * 3]).toDouble()); pa.put(r2(p.pts[i * 3 + 2]).toDouble()); i += step }
                val kind = when {
                    p.label == "PLANE_DOOR" -> "door"
                    p.type == 1 -> "wall"
                    !floorY.isNaN() && abs(p.y - floorY) < 0.15f -> "floor"
                    p.type == 0 -> "surf"
                    else -> "other"
                }
                arr.put(JSONObject().put("k", kind).put("p", pa))
            }
            o.put("pl", arr)
            sentPlanesVersion = planesVersion
        }
        o.put("me", JSONArray().put(r2(camPos.x).toDouble()).put(r2(camPos.z).toDouble()))
        val f2 = V3(fwd.x, 0f, fwd.z).norm()
        o.put("f", JSONArray().put(r2(f2.x).toDouble()).put(r2(f2.z).toDouble()))
        zone?.let { o.put("zone", JSONArray().put(r2(it.x).toDouble()).put(r2(it.z).toDouble())) }
        if (phase == Phase.PLAY) {
            if (save.lvl("map") > 0) {
                val ia = JSONArray()
                for (it in items) if (!it.dead) ia.put(JSONArray().put(r2(it.pos.x).toDouble()).put(r2(it.pos.z).toDouble()).put(if (it.inZone) 1 else 0))
                o.put("items", ia)
            }
            if (save.lvl("radar") > 0) {
                val ma = JSONArray()
                for (m in monsters) ma.put(JSONArray().put(r2(m.pos.x).toDouble()).put(r2(m.pos.z).toDouble()))
                o.put("mons", ma)
            }
        }
        act.js("M($o)")
    }
}
