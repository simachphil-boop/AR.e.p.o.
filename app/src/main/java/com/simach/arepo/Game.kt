package com.simach.arepo

import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Hand
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.mruk.MRUKHit
import com.meta.spatial.mruk.MRUKPlane
import com.meta.spatial.mruk.MRUKRoom
import com.meta.spatial.mruk.SurfaceType
import com.meta.spatial.physics.Physics
import com.meta.spatial.physics.PhysicsState
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.runtime.SceneAudioAsset
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.PlayerBodyAttachmentSystem
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.TransformParent
import com.meta.spatial.toolkit.Visible
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// ---------- помощники ----------
fun v3(x: Float, y: Float, z: Float) = Vector3(x, y, z)
fun yawQuat(deg: Float): Quaternion {
    val a = Math.toRadians(deg.toDouble()) / 2.0
    return Quaternion(cos(a).toFloat(), 0f, sin(a).toFloat(), 0f)
}
fun distXZ(a: Vector3, b: Vector3): Float { val dx = a.x - b.x; val dz = a.z - b.z; return sqrt(dx * dx + dz * dz) }
fun lenOf(v: Vector3) = sqrt(v.x * v.x + v.y * v.y + v.z * v.z)

class RoomInfo(val room: MRUKRoom, val floorY: Float, val ceilY: Float, val corners: List<Vector3>, val area: Float) {
    fun randomFloorPoint(): Vector3 {
        val u = Random.nextFloat(); val w = Random.nextFloat()
        val a = corners[0] + (corners[1] - corners[0]) * u
        val b = corners[3] + (corners[2] - corners[3]) * u
        val p = a + (b - a) * w
        return v3(p.x, floorY, p.z)
    }
}

class Item(val type: ItemType, val entity: Entity, val maxValue: Int) {
    var value = maxValue.toFloat()
    var pos = v3(0f, 0f, 0f)
    var prevPos: Vector3? = null
    var vel = v3(0f, 0f, 0f)
    var prevVel = v3(0f, 0f, 0f)
    var held = false
    var dead = false
    var inZone = false
    var age = 0f
    var pendingDmg = 0f
    var dmgTimer = 0f
    var lastGood = v3(0f, 0f, 0f)
    var holdDist = 1f
    var spinYaw = 0f
}

class Monster(val kind: Int, val entity: Entity, var pos: Vector3, val speed: Float, val soundId: String) {
    var state = 0
    var timer = 3f
    var cool = 0f
    var stun = 0f
    var hitCd = 0f
    var seen = false
    var target = pos
    var dash = v3(0f, 0f, 0f)
    var yaw = 0f
}

class GameSystem(private val act: AREPOActivity) : SystemBase() {
    val save = Save(act)
    private var phase = Phase.LOADING
    private val main = Handler(Looper.getMainLooper())

    val rooms = ArrayList<RoomInfo>()
    private var zone: Vector3? = null
    private var zoneEntity: Entity? = null
    private var zoneRedEntity: Entity? = null
    private val items = ArrayList<Item>()
    private val monsters = ArrayList<Monster>()
    private val shards = ArrayList<Pair<Entity, Float>>()
    private var beam: Entity? = null
    private val pointers = HashMap<Hand, Entity>()
    private var marker: Entity? = null
    var menuPanel: Entity? = null
    var wristPanel: Entity? = null

    private var held: Item? = null
    private var heldHand = Hand.RIGHT
    private var aimed: Item? = null
    private var quota = 0
    private var zoneValue = 0
    private var hp = 100f
    private var brokenCount = 0
    private var brokenValue = 0

    private var head = Pose()
    private var fwd = v3(0f, 0f, 1f)
    private var lastNs = 0L
    private var time = 0f
    private var uiTimer = 0f
    private var mapTimer = 0f
    private var beatTimer = 0f
    private var growlTimer = 0f
    private var detTimer = 0f
    private var ambientOn = false
    private var monsterCounter = 0

    private val sounds = HashMap<String, SceneAudioAsset>()

    init {
        if (save.hp < 0f) save.hp = hpMax()
        hp = save.hp
    }

    private fun hpMax() = 100f + 25f * save.lvl("hp")
    private fun reach() = 2.5f + 1f * save.lvl("reach")
    fun quotaFor(l: Int): Int = (round(900.0 * 1.33.pow(l - 1) / 50.0) * 50).toInt()

    // ================== звук и вибрация ==================
    private fun snd(name: String): SceneAudioAsset? {
        sounds[name]?.let { return it }
        return try { SceneAudioAsset.loadLocalFile("audio/$name.wav").also { sounds[name] = it } } catch (e: Throwable) { null }
    }
    private fun play(name: String, at: Vector3? = null, vol: Float = 1f) {
        val a = snd(name) ?: return
        try { if (at != null) act.sc().playSound(a, at, vol) else act.sc().playSound(a, vol) } catch (e: Throwable) { }
    }
    private fun buzz(hand: Hand, amp: Float, ms: Long) {
        try { act.haptic(hand, amp, ms) } catch (e: Throwable) { }
    }
    private fun ui(block: () -> Unit) = main.post(block)
    private fun toast(t: String, kind: Int = 0) = ui { Ui.toast(t, kind) }

    // ================== главный цикл ==================
    override fun execute() {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0.016f else ((now - lastNs) / 1e9f).coerceIn(0.001f, 0.05f)
        lastNs = now
        time += dt
        try {
            head = act.sc().getViewerPose()
            fwd = (head.q * v3(0f, 0f, 1f))
            processCmds()
            when (phase) {
                Phase.PLAY -> playUpdate(dt)
                else -> {}
            }
            updateShards(dt)
            placeWrist()
            uiTimer -= dt
            if (uiTimer <= 0f) { pushUi(); uiTimer = 0.1f }
            mapTimer -= dt
            if (mapTimer <= 0f) { pushMap(); mapTimer = 0.25f }
        } catch (e: Throwable) {
            toast("Ошибка: ${e.javaClass.simpleName} ${e.message ?: ""}", 1)
        }
    }

    fun setPhase(p: Phase) {
        phase = p
        if (p != Phase.PLAY) for (pe in pointers.values) pe.setComponent(Visible(false))
        ui { Ui.phase = p }
        if (p != Phase.PLAY) showMenu() else hideMenu()
    }

    // ================== сцена (комнаты) ==================
    fun onSceneLoaded(ok: Boolean, msg: String) {
        rooms.clear()
        if (ok) {
            for (r in act.mruk.rooms) {
                try {
                    var floorY = Float.NaN
                    var corners: List<Vector3>? = null
                    var area = 0f
                    for (f in r.floors) {
                        val pose = f.getComponent<Transform>().transform
                        val pl = f.getComponent<MRUKPlane>()
                        val mn = pl.min; val mx = pl.max
                        val c = listOf(pose * v3(mn.x, mn.y, 0f), pose * v3(mx.x, mn.y, 0f), pose * v3(mx.x, mx.y, 0f), pose * v3(mn.x, mx.y, 0f))
                        val a = abs((mx.x - mn.x) * (mx.y - mn.y))
                        if (a > area) { area = a; corners = c; floorY = pose.t.y }
                    }
                    if (corners == null) continue
                    var ceilY = floorY + 2.5f
                    for (c in r.ceilings) ceilY = c.getComponent<Transform>().transform.t.y
                    rooms.add(RoomInfo(r, floorY, ceilY, corners, area))
                } catch (e: Throwable) { }
            }
        }
        if (rooms.isEmpty()) {
            ui { Ui.status = msg }
            setPhase(Phase.NOSCENE)
            return
        }
        val surf = countSurfaces()
        ui {
            Ui.rooms = rooms.size
            Ui.floorArea = rooms.sumOf { it.area.toDouble() }.toFloat()
            Ui.surfaces = surf
            Ui.mapPolys = rooms.map { r -> MapPoly(0, floatArrayOf(r.corners[0].x, r.corners[0].z, r.corners[1].x, r.corners[1].z, r.corners[2].x, r.corners[2].z, r.corners[3].x, r.corners[3].z)) }
        }
        if (!ambientOn) { snd("ambient")?.let { try { act.sc().playBackgroundSound(it, 0.35f, true) } catch (e: Throwable) { } }; ambientOn = true }
        setPhase(Phase.MENU)
    }

    private fun countSurfaces(): Int {
        var n = 0
        for (r in rooms) for (a in r.room.anchors) {
            try {
                val t = a.getComponent<Transform>().transform.t
                if (t.y > r.floorY + 0.25f && t.y < r.floorY + 1.6f) n++
            } catch (e: Throwable) { }
        }
        return n
    }

    private fun roomAt(p: Vector3): RoomInfo? = rooms.firstOrNull { try { it.room.isPositionInRoom(v3(p.x, it.floorY + 1f, p.z), false) } catch (e: Throwable) { false } }
    private fun floorAt(p: Vector3): Float = roomAt(p)?.floorY ?: (rooms.minOfOrNull { it.floorY } ?: (head.t.y - 1.6f))

    // точка на поверхности: луч сверху вниз
    private fun surfacePoint(r: RoomInfo): Vector3? {
        val p = r.randomFloorPoint()
        if (!r.room.isPositionInRoom(v3(p.x, r.floorY + 1f, p.z), false)) return null
        val origin = v3(p.x, min(r.ceilY - 0.08f, r.floorY + 2.2f), p.z)
        val hit: MRUKHit? = try { act.mruk.raycastRoom(r.room.anchor.uuid, origin, v3(0f, -1f, 0f), 3f, SurfaceType.PLANE_VOLUME) } catch (e: Throwable) { null }
        if (hit == null) return v3(p.x, r.floorY, p.z)
        if (hit.hitNormal.y < 0.8f) return null
        val y = hit.hitPosition.y
        if (y > r.floorY + 1.7f || y < r.floorY - 0.1f) return null
        return v3(p.x, y, p.z)
    }

    // ================== команды ==================
    private fun processCmds() {
        while (true) {
            val c = Ui.cmds.poll() ?: break
            when {
                c == "start" -> setPhase(if (zone != null) Phase.INTRO else Phase.ZONE)
                c == "setZone" -> setZoneHere()
                c == "zoneOk" -> if (zone != null) setPhase(Phase.INTRO)
                c == "rescan" -> act.requestCapture()
                c == "clearCrash" -> act.runOnUiThread { act.clearCrash() }
                c == "startLevel" -> startLevel()
                c == "extract" -> extract()
                c == "pause" -> if (phase == Phase.PLAY) setPhase(Phase.PAUSE)
                c == "resume" -> if (phase == Phase.PAUSE) setPhase(Phase.PLAY)
                c == "abandon" -> { clearLevel(); setPhase(Phase.INTRO) }
                c == "moveZone" -> { clearLevel(); setPhase(Phase.ZONE) }
                c == "shop" -> if (phase == Phase.DONE) setPhase(Phase.SHOP)
                c == "next" -> if (phase == Phase.SHOP) setPhase(Phase.INTRO)
                c == "newRun" -> { save.resetRun(); hp = hpMax(); save.hp = hp; save.store(); clearLevel(); setPhase(if (zone != null) Phase.INTRO else Phase.ZONE) }
                c.startsWith("buy:") -> buy(c.substring(4))
                c.startsWith("use:") -> useItem(c.substring(4))
            }
        }
    }

    private fun setZoneHere() {
        val y = floorAt(head.t) + 0.012f
        val z = v3(head.t.x, y, head.t.z)
        zone = z
        if (zoneEntity == null) {
            zoneEntity = Entity.create(listOf(Mesh(Uri.parse("apk:///models/zone.glb"), MeshCollision.NoCollision), Transform(Pose(z, Quaternion(1f, 0f, 0f, 0f)))))
            zoneRedEntity = Entity.create(listOf(Mesh(Uri.parse("apk:///models/zone_red.glb"), MeshCollision.NoCollision), Transform(Pose(z, Quaternion(1f, 0f, 0f, 0f)))))
        } else {
            zoneEntity!!.setComponent(Transform(Pose(z, Quaternion(1f, 0f, 0f, 0f))))
            zoneRedEntity!!.setComponent(Transform(Pose(z, Quaternion(1f, 0f, 0f, 0f))))
        }
        setZoneColor(false)
        play("blip", z)
        ui { Ui.hasZone = true }
        toast("📍 Точка сдачи поставлена")
    }

    private fun setZoneColor(ok: Boolean) {
        zoneEntity?.setComponent(Visible(ok))
        zoneRedEntity?.setComponent(Visible(!ok))
    }

    // ================== уровень ==================
    private fun clearLevel() {
        for (it in items) it.entity.destroy()
        items.clear()
        for (m in monsters) { try { act.sc().stopSound(m.soundId) } catch (e: Throwable) { }; m.entity.destroy() }
        monsters.clear()
        held = null; aimed = null; zoneValue = 0
        beam?.setComponent(Visible(false)); marker?.setComponent(Visible(false))
        for (p in pointers.values) p.setComponent(Visible(false))
        setZoneColor(false)
    }

    private fun <T> weighted(list: List<T>, w: (T) -> Float): T {
        val total = list.sumOf { w(it).toDouble() }.toFloat()
        var r = Random.nextFloat() * total
        for (e in list) { r -= w(e); if (r <= 0f) return e }
        return list.last()
    }

    private fun startLevel() {
        if (phase != Phase.INTRO || rooms.isEmpty()) return
        val z = zone ?: return
        clearLevel()
        val lvl = save.level
        quota = quotaFor(lvl)
        hp = min(hpMax(), hp + 20f)
        brokenCount = 0; brokenValue = 0

        val count = min(6 + lvl * 2, 20)
        val pool = ITEM_TYPES.filter { it.minLevel <= lvl }
        val types = (0 until count).map { weighted(pool) { t -> t.weight } }
        val raw = types.map { it.baseValue * (0.7f + Random.nextFloat() * 0.6f) }
        val k = quota * (1.7f + Random.nextFloat() * 0.3f) / raw.sum()

        val placed = ArrayList<Vector3>()
        for (i in types.indices) {
            var best: Vector3? = null; var bestScore = -1f
            for (t in 0 until 16) {
                val r = weighted(rooms) { it.area }
                val p = surfacePoint(r) ?: continue
                if (distXZ(p, z) < 1.0f) continue
                if (placed.any { distXZ(it, p) < 0.45f && abs(it.y - p.y) < 0.3f }) continue
                val onFurniture = p.y > r.floorY + 0.25f
                val score = distXZ(p, z) + Random.nextFloat() * 3f + (if (onFurniture) 1.2f else 0f)
                if (score > bestScore) { bestScore = score; best = p }
            }
            val p = best ?: rooms.random().randomFloorPoint()
            placed.add(p)
            val type = types[i]
            val value = max(50, (round(raw[i] * k / 10f) * 10).toInt())
            spawnItem(type, v3(p.x, p.y + type.size[1] / 2f + 0.03f, p.z), value)
        }

        val kinds = when {
            lvl <= 1 -> listOf()
            lvl == 2 -> listOf(0)
            lvl == 3 -> listOf(1)
            lvl == 4 -> listOf(0, 1)
            lvl == 5 -> listOf(0, 2)
            lvl < 8 -> listOf(0, 1, 2)
            else -> listOf(0, 0, 1, 2)
        }
        for (kd in kinds) {
            var best: Vector3? = null; var bd = -1f
            for (t in 0 until 24) {
                val p = weighted(rooms) { it.area }.randomFloorPoint()
                val d = min(distXZ(p, head.t), distXZ(p, z) + 1f)
                if (d > bd) { bd = d; best = p }
            }
            val pos = best ?: rooms.first().randomFloorPoint()
            val sp = when (kd) { 0 -> min(1.0f, 0.45f + 0.04f * lvl); 1 -> min(1.1f, 0.5f + 0.04f * lvl); else -> 2.6f }
            val model = when (kd) { 0 -> "shadow"; 1 -> "statue"; else -> "jumper" }
            val e = Entity.create(listOf(Mesh(Uri.parse("apk:///models/$model.glb"), MeshCollision.NoCollision), Transform(Pose(pos, yawQuat(Random.nextFloat() * 360f)))))
            val id = "mon${monsterCounter++}"
            val m = Monster(kd, e, pos, sp, id)
            m.timer = 4f + Random.nextFloat() * 4f
            monsters.add(m)
            snd("monster_loop")?.let { try { act.sc().playSound(it, e, if (kd == 1) 0.25f else 0.6f, id, true) } catch (x: Throwable) { } }
        }
        setPhase(Phase.PLAY)
        play("start")
        toast("Уровень $lvl: собери $quota $ · ценностей: ${items.size}", 3)
        if (monsters.isNotEmpty()) main.postDelayed({ Ui.toast("Ты здесь не один…", 1) }, 2500)
    }

    private fun spawnItem(type: ItemType, pos: Vector3, value: Int) {
        val sx = type.size[0]; val sy = type.size[1]; val sz = type.size[2]
        val ph = Physics()
        ph.shape = "box"
        ph.dimensions = v3(sx / 2f, sy / 2f, sz / 2f)
        ph.state = PhysicsState.DYNAMIC
        ph.density = (type.mass / (sx * sy * sz)).coerceIn(50f, 3000f)
        ph.restitution = 0.15f
        ph.linearDamping = 0.05f
        ph.angularDamping = 0.3f
        val e = Entity.create(listOf(
            Mesh(Uri.parse("apk:///models/${type.id}.glb"), MeshCollision.NoCollision),
            Transform(Pose(pos, yawQuat(Random.nextFloat() * 360f))),
            Scale(v3(1f, 1f, 1f)),
            ph
        ))
        val it = Item(type, e, value)
        it.pos = pos; it.lastGood = pos
        items.add(it)
    }

    // ================== игра ==================
    private fun body() = systemManager.tryFindSystem<PlayerBodyAttachmentSystem>()?.tryGetLocalPlayerAvatarBody()

    private fun playUpdate(dt: Float) {
        val b = body()
        val right = b?.rightHand
        val left = b?.leftHand
        val rc = right?.tryGetComponent<Controller>()
        val lc = left?.tryGetComponent<Controller>()
        val rPose = right?.tryGetComponent<Transform>()?.transform
        val lPose = left?.tryGetComponent<Transform>()?.transform

        // позиции предметов и удары
        for (it in items) {
            if (it.dead) continue
            it.age += dt
            val p = it.entity.getComponent<Transform>().transform.t
            val prev = it.prevPos
            if (prev != null && !it.held) {
                val v = (p - prev) * (1f / dt)
                val dv = lenOf(v - it.vel)
                if (it.age > 1.2f && dv > 1.7f) {
                    damage(it, (dv - 1.7f) * it.type.fragility * it.maxValue * 0.22f)
                    if (dv > 2.2f) play("thud", p, min(1f, dv / 5f))
                }
                it.prevVel = it.vel
                it.vel = v
            }
            it.prevPos = p
            it.pos = p
            val fy = floorAt(p)
            if (p.y < fy - 1.0f) { // провалилось сквозь пол — вернём
                it.entity.setComponent(Transform(Pose(v3(it.lastGood.x, it.lastGood.y + 0.1f, it.lastGood.z), Quaternion(1f, 0f, 0f, 0f))))
                it.prevPos = null; it.vel = v3(0f, 0f, 0f)
            } else if (!it.held && lenOf(it.vel) < 0.05f) it.lastGood = p
            it.dmgTimer -= dt
            if (it.pendingDmg >= 1f && it.dmgTimer <= 0f) {
                toast("💥 ${it.type.name} −${it.pendingDmg.toInt()} $", 1)
                play("hit", p, 0.8f)
                it.pendingDmg = 0f; it.dmgTimer = 0.4f
            }
        }

        // указки из контроллеров, чтобы было видно, куда целишься
        for ((hand, pose) in listOf(Hand.RIGHT to rPose, Hand.LEFT to lPose)) {
            val pe = pointers.getOrPut(hand) {
                Entity.create(listOf(Mesh(Uri.parse("apk:///models/pointer.glb"), MeshCollision.NoCollision), Transform(), Scale(v3(1f, 1f, 1f))))
            }
            val holding = held != null && heldHand == hand
            if (pose == null || holding) { pe.setComponent(Visible(false)); continue }
            val dir = pose.q * v3(0f, 0f, 1f)
            val target = findAimed(pose)
            val len = if (target != null) lenOf(target.pos - pose.t) else reach()
            pe.setComponent(Transform(Pose(pose.t, Quaternion.lookRotation(dir))))
            pe.setComponent(Scale(v3(1f, 1f, len)))
            pe.setComponent(Visible(true))
        }

        // захват лучом
        val h = held
        if (h == null) {
            // на что указывает правая (или левая) рука
            aimed = null
            var aimHand = Hand.RIGHT
            for ((hand, pose) in listOf(Hand.RIGHT to rPose, Hand.LEFT to lPose)) {
                if (pose == null) continue
                val a = findAimed(pose)
                if (a != null) { aimed = a; aimHand = hand; break }
            }
            val sqR = rc != null && pressed(rc, ButtonBits.ButtonSqueezeR)
            val sqL = lc != null && pressed(lc, ButtonBits.ButtonSqueezeL)
            if ((sqR || sqL)) {
                val hand = if (sqR) Hand.RIGHT else Hand.LEFT
                val pose = if (sqR) rPose else lPose
                val a = if (pose != null) findAimed(pose) else null
                if (a != null) grab(a, hand, pose!!)
            }
            // маркер над целью
            val am = aimed
            if (am != null) {
                val mk = marker ?: Entity.create(listOf(Mesh(Uri.parse("apk:///models/marker.glb"), MeshCollision.NoCollision), Transform())).also { marker = it }
                mk.setComponent(Transform(Pose(v3(am.pos.x, am.pos.y + am.type.size[1] / 2f + 0.1f + 0.02f * sin(time * 4f), am.pos.z), yawQuat(time * 120f))))
                mk.setComponent(Visible(true))
            } else marker?.setComponent(Visible(false))
            beam?.setComponent(Visible(false))
        } else {
            marker?.setComponent(Visible(false))
            val ctrl = if (heldHand == Hand.RIGHT) rc else lc
            val pose = if (heldHand == Hand.RIGHT) rPose else lPose
            val bit = if (heldHand == Hand.RIGHT) ButtonBits.ButtonSqueezeR else ButtonBits.ButtonSqueezeL
            if (ctrl == null || pose == null || (ctrl.buttonState and bit) == 0) {
                release()
            } else {
                holdUpdate(h, ctrl, pose, dt)
            }
        }

        // зона сдачи
        val z = zone
        zoneValue = 0
        if (z != null) for (it in items) {
            it.inZone = !it.dead && !it.held && distXZ(it.pos, z) < 0.55f && it.pos.y < z.y + 0.6f
            if (it.inZone) zoneValue += it.value.toInt()
        }
        setZoneColor(zoneValue >= quota)

        // монстры
        for (m in monsters) updateMonster(m, dt)

        // кнопки: Y/B — пауза
        if ((lc != null && pressed(lc, ButtonBits.ButtonY)) || (rc != null && pressed(rc, ButtonBits.ButtonB))) setPhase(Phase.PAUSE)
        // A — сдать, когда можно
        if (rc != null && pressed(rc, ButtonBits.ButtonA)) extract()

        // звуки опасности
        val near = monsters.filter { it.stun <= 0f }.minByOrNull { distXZ(it.pos, head.t) }
        if (near != null) {
            val d = distXZ(near.pos, head.t)
            beatTimer -= dt
            if (d < 6f && beatTimer <= 0f) {
                play("heartbeat", null, (0.25f + (6f - d) / 6f * 0.75f).coerceIn(0f, 1f))
                beatTimer = 0.35f + d / 6f * 1.1f
                if (d < 2f) { buzz(Hand.LEFT, 0.3f, 40); buzz(Hand.RIGHT, 0.3f, 40) }
            }
            growlTimer -= dt
            if (d < 3.2f && (near.state == 1 || near.kind == 1) && growlTimer <= 0f) {
                play("growl", near.pos, 1f); growlTimer = 2.5f + Random.nextFloat() * 2f
            }
        }
        if (save.lvl("det") > 0) {
            val t = items.filter { !it.dead && !it.inZone && !it.held }.minByOrNull { distXZ(it.pos, head.t) }
            detTimer -= dt
            if (t != null && detTimer <= 0f) { play("detector", t.pos, 0.5f); detTimer = 0.3f + min(2f, distXZ(t.pos, head.t) * 0.35f) }
        }
    }

    private fun pressed(c: Controller, bit: Int) = (c.buttonState and c.changedButtons and bit) != 0

    private fun findAimed(pose: Pose): Item? {
        val dir = pose.q * v3(0f, 0f, 1f)
        var best: Item? = null; var bestT = Float.MAX_VALUE
        for (it in items) {
            if (it.dead) continue
            val d = it.pos - pose.t
            val t = d.dot(dir)
            if (t < 0.02f || t > reach()) continue
            val perp = lenOf(d - dir * t)
            val r = max(it.type.size[0], it.type.size[2]) / 2f + 0.06f + t * 0.04f
            if (perp < r && t < bestT) { bestT = t; best = it }
        }
        return best
    }

    private fun setPhysicsState(it: Item, state: PhysicsState, vel: Vector3? = null) {
        val ph = it.entity.getComponent<Physics>()
        ph.state = state
        if (vel != null) ph.linearVelocity = vel
        it.entity.setComponent(ph)
    }

    private fun grab(it: Item, hand: Hand, pose: Pose) {
        held = it; heldHand = hand
        it.held = true; it.inZone = false
        it.holdDist = lenOf(it.pos - pose.t).coerceIn(0.35f, reach())
        setPhysicsState(it, PhysicsState.KINEMATIC)
        play("grab", it.pos, 0.6f)
        buzz(hand, 0.6f, 40)
    }

    private fun release() {
        val h = held ?: return
        held = null
        h.held = false
        h.age = 0.9f // короткая пауза перед проверкой ударов
        setPhysicsState(h, PhysicsState.DYNAMIC, h.vel)
        h.prevPos = null
        beam?.setComponent(Visible(false))
        play("release", h.pos, 0.5f)
        buzz(heldHand, 0.3f, 30)
    }

    private fun holdUpdate(h: Item, ctrl: Controller, pose: Pose, dt: Float) {
        val dir = pose.q * v3(0f, 0f, 1f)
        // стик вверх/вниз — ближе/дальше
        val up = if (heldHand == Hand.RIGHT) ButtonBits.ButtonThumbRU else ButtonBits.ButtonThumbLU
        val dn = if (heldHand == Hand.RIGHT) ButtonBits.ButtonThumbRD else ButtonBits.ButtonThumbLD
        if ((ctrl.buttonState and up) != 0) h.holdDist = min(reach(), h.holdDist + 1.2f * dt)
        if ((ctrl.buttonState and dn) != 0) h.holdDist = max(0.3f, h.holdDist - 1.2f * dt)
        val target = pose.t + dir * h.holdDist
        // пружина: предмет тянется за точкой
        val k = 120f / h.type.mass
        val c = 2f * sqrt(k) * 0.55f
        var p = h.pos
        var v = h.vel
        val steps = 3
        val sdt = dt / steps
        for (s in 0 until steps) {
            val acc = (target - p) * k - v * c
            v = v + acc * sdt
            p = p + v * sdt
        }
        h.vel = v
        h.pos = p
        h.spinYaw += (lenOf(v) * 40f) * dt
        val yaw = Math.toDegrees(atan2(dir.x.toDouble(), dir.z.toDouble())).toFloat()
        h.entity.setComponent(Transform(Pose(p, yawQuat(yaw + h.spinYaw))))
        h.prevPos = p
        // повреждения от рывков
        val stretch = lenOf(target - p)
        val speed = lenOf(v)
        val grip = 1f - 0.18f * save.lvl("grip")
        var dmg = 0f
        if (stretch > 0.18f) dmg += (stretch - 0.18f) * h.type.fragility * h.maxValue * 2.4f * dt
        if (speed > 3.2f) dmg += (speed - 3.2f) * h.type.fragility * h.maxValue * 0.1f * dt
        if (dmg > 0f) { damage(h, dmg * grip); buzz(heldHand, 0.25f, 20) }
        if (stretch > 2.0f) { release(); toast("Вещь выскользнула!", 1) }
        // луч
        val bm = beam ?: Entity.create(listOf(Mesh(Uri.parse("apk:///models/beam.glb"), MeshCollision.NoCollision), Transform(), Scale(v3(1f, 1f, 1f)))).also { beam = it }
        val d = p - pose.t
        val len = lenOf(d)
        if (len > 0.01f) {
            bm.setComponent(Transform(Pose(pose.t, Quaternion.lookRotation(d * (1f / len)))))
            bm.setComponent(Scale(v3(1f, 1f, len)))
            bm.setComponent(Visible(true))
        }
    }

    private fun damage(it: Item, amount: Float) {
        if (amount <= 0f || it.dead) return
        it.value -= amount
        it.pendingDmg += amount
        if (it.value <= it.maxValue * 0.08f) breakItem(it)
    }

    private fun breakItem(it: Item) {
        it.dead = true
        if (held === it) { held = null; beam?.setComponent(Visible(false)) }
        brokenCount++; brokenValue += it.maxValue
        val c = it.pos
        it.entity.destroy()
        play("break", c, 1f)
        buzz(Hand.RIGHT, 0.9f, 120); buzz(Hand.LEFT, 0.9f, 120)
        for (i in 0 until 8) {
            val ph = Physics()
            ph.shape = "box"; ph.dimensions = v3(0.015f, 0.015f, 0.015f); ph.state = PhysicsState.DYNAMIC; ph.density = 500f; ph.restitution = 0.3f
            ph.linearVelocity = v3((Random.nextFloat() - 0.5f) * 2.5f, Random.nextFloat() * 2.5f, (Random.nextFloat() - 0.5f) * 2.5f)
            val e = Entity.create(listOf(Mesh(Uri.parse("apk:///models/shard.glb"), MeshCollision.NoCollision),
                Transform(Pose(v3(c.x + (Random.nextFloat() - 0.5f) * 0.1f, c.y + Random.nextFloat() * 0.1f, c.z + (Random.nextFloat() - 0.5f) * 0.1f), Quaternion(1f, 0f, 0f, 0f))), ph))
            shards.add(e to 2.5f)
        }
        toast("💔 ${it.type.name} разбилось! −${it.maxValue} $", 1)
    }

    private fun updateShards(dt: Float) {
        if (shards.isEmpty()) return
        val keep = ArrayList<Pair<Entity, Float>>()
        for ((e, life) in shards) {
            val l = life - dt
            if (l <= 0f) e.destroy() else keep.add(e to l)
        }
        shards.clear(); shards.addAll(keep)
    }

    // ================== монстры ==================
    private fun updateMonster(m: Monster, dt: Float) {
        m.hitCd -= dt
        val toP = v3(head.t.x - m.pos.x, 0f, head.t.z - m.pos.z)
        val d = distXZ(m.pos, head.t)
        if (m.stun > 0f) { m.stun -= dt; return }
        fun moveTo(tx: Float, tz: Float, sp: Float) {
            val dx = tx - m.pos.x; val dz = tz - m.pos.z; val l = sqrt(dx * dx + dz * dz)
            if (l > 0.05f) { val s = min(l, sp * dt); m.pos = v3(m.pos.x + dx / l * s, m.pos.y, m.pos.z + dz / l * s) }
        }
        when (m.kind) {
            0 -> { // Тень
                m.timer -= dt; m.cool -= dt
                if (m.state == 0) {
                    if (distXZ(m.pos, m.target) < 0.3f || m.timer <= 0f) {
                        m.target = weighted(rooms) { it.area }.randomFloorPoint()
                        m.timer = 7f + Random.nextFloat() * 5f
                    }
                    moveTo(m.target.x, m.target.z, 0.3f)
                    if (d < 4.5f && m.cool <= 0f) {
                        m.state = 1; m.timer = 9f + save.level
                        play("spotted", m.pos, 1f); toast("👁 Тебя заметили!", 1)
                    }
                } else {
                    moveTo(head.t.x, head.t.z, m.speed)
                    if (m.timer <= 0f || d > 9f) { m.state = 0; m.cool = 6f; m.timer = 0f }
                }
            }
            1 -> { // Статуя — движется, только когда на неё не смотрят
                val dir = v3(m.pos.x - head.t.x, m.pos.y + 1.3f - head.t.y, m.pos.z - head.t.z)
                val dl = lenOf(dir)
                var seen = dl < 12f && dir.dot(fwd) / dl > 0.8f
                if (seen) {
                    // стена между нами — значит не видно
                    val r = roomAt(head.t)
                    if (r != null) {
                        val hit: MRUKHit? = try { act.mruk.raycastRoom(r.room.anchor.uuid, head.t, dir * (1f / dl), dl, SurfaceType.PLANE) } catch (e: Throwable) { null }
                        if (hit != null && hit.hitDistance < dl - 0.3f) seen = false
                    }
                }
                if (!seen) moveTo(head.t.x, head.t.z, m.speed * 1.3f)
                if (seen && !m.seen && d < 4f) play("scrape", m.pos, 1f)
                m.seen = seen
            }
            else -> { // Прыгун
                m.timer -= dt
                if (m.state == 0) {
                    moveTo(head.t.x, head.t.z, 0.25f)
                    if (m.timer <= 0f && d < 6f) {
                        m.state = 1; m.timer = 0.7f
                        m.dash = v3(toP.x / d.coerceAtLeast(0.01f), 0f, toP.z / d.coerceAtLeast(0.01f))
                        play("leap", m.pos, 1f)
                    }
                } else {
                    m.pos = v3(m.pos.x + m.dash.x * m.speed * dt, m.pos.y, m.pos.z + m.dash.z * m.speed * dt)
                    if (m.timer <= 0f) { m.state = 0; m.timer = 3.5f + Random.nextFloat() * 2f }
                }
            }
        }
        m.pos = v3(m.pos.x, floorAt(m.pos), m.pos.z)
        m.yaw = Math.toDegrees(atan2(toP.x.toDouble(), toP.z.toDouble())).toFloat()
        val bob = if (m.kind == 0) 0.03f * sin(time * 2f) else 0f
        m.entity.setComponent(Transform(Pose(v3(m.pos.x, m.pos.y + bob, m.pos.z), yawQuat(m.yaw))))
        if (d < 0.55f && m.hitCd <= 0f) hurtPlayer(m, toP, d)
    }

    private fun hurtPlayer(m: Monster, toP: Vector3, d: Float) {
        val dmg = 28f * (1f - 0.15f * save.lvl("armor"))
        hp -= dmg
        m.hitCd = 2.5f; m.stun = 1.5f
        if (d > 0.01f) m.pos = v3(m.pos.x - toP.x / d * 1.5f, m.pos.y, m.pos.z - toP.z / d * 1.5f)
        if (held != null) release()
        play("hurt", head.t, 1f)
        buzz(Hand.LEFT, 1f, 250); buzz(Hand.RIGHT, 1f, 250)
        toast("−${dmg.toInt()} ❤️", 1)
        if (hp <= 0f) {
            hp = 0f
            val sum = listOf("Дошёл до уровня" to "${save.level}", "Заработано за забег" to "${save.runEarned} $", "🏆 Рекорд" to "уровень ${max(save.best, save.level)}")
            save.best = max(save.best, save.level)
            save.store()
            save.resetRun()
            hp = hpMax(); save.hp = hp; save.store()
            clearLevel()
            play("dead")
            ui { Ui.summary = sum }
            setPhase(Phase.DEAD)
        }
    }

    // ================== сдача, магазин, расходники ==================
    private fun extract() {
        val z = zone ?: return
        if (phase != Phase.PLAY) return
        if (zoneValue < quota) { toast("Не хватает до квоты: ${quota - zoneValue} $"); return }
        if (distXZ(head.t, z) > 1.6f) { toast("Подойди к точке сдачи"); return }
        val got = zoneValue
        val n = items.count { it.inZone }
        save.money += got
        save.runEarned += got
        val done = save.level
        save.level += 1
        save.best = max(save.best, save.level)
        save.hp = hp
        save.store()
        val sum = listOf("Уровень" to "$done", "Сдано вещей" to "$n", "💰 Заработано" to "+$got $", "🎯 Квота была" to "$quota $",
            "💥 Разбито" to "$brokenCount (−$brokenValue $)", "Осталось в квартире" to "${items.count { !it.dead && !it.inZone }}")
        play("extract", z); play("coins", z)
        buzz(Hand.RIGHT, 0.7f, 150)
        clearLevel()
        ui { Ui.summary = sum }
        setPhase(Phase.DONE)
    }

    private fun shopPrice(d: ShopDef): Int = if (d.cons) d.base else (round(d.base * 1.6.pow(save.lvl(d.id)) / 50.0) * 50).toInt()

    private fun buy(id: String) {
        val d = SHOP.firstOrNull { it.id == id } ?: return
        val l = save.lvl(id)
        if (l >= d.max) { toast("Уже максимум"); return }
        val p = shopPrice(d)
        if (save.money < p) { toast("Не хватает денег", 1); return }
        save.money -= p
        save.up[id] = l + 1
        if (id == "hp") hp += 25f
        save.hp = hp
        save.store()
        play("buy")
        toast("Куплено: ${d.n}", 2)
    }

    private fun useItem(id: String) {
        if (phase != Phase.PLAY) return
        val l = save.lvl(id)
        if (l <= 0) { toast("Нет в запасе — купи в магазине"); return }
        when (id) {
            "med" -> {
                if (hp >= hpMax()) { toast("Здоровье и так полное"); return }
                hp = min(hpMax(), hp + 40f); play("heal"); toast("🩹 +40 ❤️", 2)
            }
            "shock" -> {
                var n = 0
                val f2 = v3(fwd.x, 0f, fwd.z).normalize()
                for (m in monsters) {
                    val dx = m.pos.x - head.t.x; val dz = m.pos.z - head.t.z; val dl = sqrt(dx * dx + dz * dz)
                    if (dl < 3.5f && (dl < 0.8f || (dx * f2.x + dz * f2.z) / dl > 0.4f)) { m.stun = 6f; n++ }
                }
                play("shock", head.t + fwd * 0.5f)
                toast(if (n > 0) "⚡ Оглушено: $n" else "⚡ Мимо — рядом никого")
            }
            "magnet" -> {
                if (held != null) { toast("Руки заняты"); return }
                val pose = body()?.rightHand?.tryGetComponent<Transform>()?.transform ?: return
                val t = items.filter { !it.dead && !it.inZone && lenOf(it.pos - head.t) < 5f }.minByOrNull { lenOf(it.pos - head.t) }
                    ?: run { toast("Рядом нет ценностей"); return }
                grab(t, Hand.RIGHT, pose)
                t.holdDist = 0.6f
            }
            else -> return
        }
        save.up[id] = l - 1
        save.hp = hp
        save.store()
    }

    // ================== панели ==================
    private fun showMenu() {
        val p = menuPanel ?: return
        val f = v3(fwd.x, 0f, fwd.z)
        val l = lenOf(f)
        val dir = if (l > 0.01f) f * (1f / l) else v3(0f, 0f, 1f)
        val pos = v3(head.t.x + dir.x * 1.1f, head.t.y - 0.1f, head.t.z + dir.z * 1.1f)
        p.setComponent(Transform(Pose(pos, Quaternion.lookRotationAroundY(dir))))
        p.setComponent(Visible(true))
    }
    private fun hideMenu() { menuPanel?.setComponent(Visible(false)) }

    private var wristParent: Entity? = null

    private fun placeWrist() {
        val w = wristPanel ?: return
        val left = body()?.leftHand
        val lp = left?.tryGetComponent<Transform>()?.transform
        if (phase != Phase.PLAY || left == null || lp == null) { w.setComponent(Visible(false)); return }
        // панель прикреплена к левому контроллеру — двигается вместе с ним без задержки
        if (wristParent != left) { w.setComponent(TransformParent(left)); wristParent = left }
        val offset = v3(0f, 0.1f, 0.03f)
        val worldPos = lp.t + lp.q * offset
        val toHead = head.t - worldPos
        val l = lenOf(toHead)
        val worldQ = if (l > 0.01f) Quaternion.lookRotation(toHead * (-1f / l)) else lp.q
        val localQ = lp.q.inverse() * worldQ
        w.setComponent(Transform(Pose(offset, localQ)))
        w.setComponent(Visible(true))
    }

    // ================== интерфейс ==================
    private fun angleTo(p: Vector3): Float {
        val dx = p.x - head.t.x; val dz = p.z - head.t.z
        val f = v3(fwd.x, 0f, fwd.z).normalize()
        val r0 = head.q * v3(1f, 0f, 0f)
        val r = v3(r0.x, 0f, r0.z).normalize() // вправо от взгляда
        val fd = f.x * dx + f.z * dz
        val rd = r.x * dx + r.z * dz
        return Math.toDegrees(atan2(rd.toDouble(), fd.toDouble())).toFloat()
    }

    private fun pushUi() {
        val s = save
        val ph = phase
        val hpI = hp.toInt(); val hpM = hpMax().toInt()
        val a = aimed; val h = held
        val aimT = if (a != null && h == null) "${a.type.emoji} ${a.type.name} · ${a.value.toInt()} $" else ""
        val heldT = if (h != null) "${h.type.emoji} ${h.type.name} · ${h.value.toInt()} $" else ""
        val heldR = if (h != null) h.value / h.maxValue else 1f
        val z = zone
        val zd = if (z != null) distXZ(head.t, z) else -1f
        val za = if (z != null) angleTo(z) else 0f
        val can = z != null && zoneValue >= quota && zd < 1.6f
        var detD = -1f; var detA = 0f
        if (s.lvl("det") > 0) {
            val t = items.filter { !it.dead && !it.inZone && !it.held }.minByOrNull { distXZ(it.pos, head.t) }
            if (t != null) { detD = distXZ(t.pos, head.t); detA = angleTo(t.pos) }
        }
        var monT = ""; var danger = 0f
        val near = monsters.minByOrNull { distXZ(it.pos, head.t) }
        if (near != null && near.stun <= 0f) {
            val d = distXZ(near.pos, head.t)
            danger = ((5f - d) / 4.2f).coerceIn(0f, 1f)
            if (d < 4f) {
                val ang = angleTo(near.pos)
                val side = when { abs(ang) < 40f -> "впереди"; abs(ang) > 130f -> "сзади"; ang > 0 -> "справа"; else -> "слева" }
                val who = when (near.kind) { 0 -> "👤 Тень"; 1 -> "🗿 Статуя"; else -> "🕷 Прыгун" }
                monT = "$who $side · ${"%.1f".format(d)} м"
            }
        }
        val shopRows = if (ph == Phase.SHOP) SHOP.map { ShopRow(it.id, it.e, it.n, it.d, shopPrice(it), s.lvl(it.id), it.max, it.cons) } else null
        val lv = s.level; val money = s.money; val best = s.best
        val q = if (ph == Phase.PLAY || ph == Phase.PAUSE) quota else quotaFor(lv)
        val zv = zoneValue; val left = items.count { !it.dead }
        val med = s.lvl("med"); val shock = s.lvl("shock"); val magnet = s.lvl("magnet")
        ui {
            Ui.level = lv; Ui.money = money; Ui.best = best; Ui.hp = hpI; Ui.hpMax = hpM
            Ui.quota = q; Ui.zoneVal = zv; Ui.left = left
            Ui.aimText = aimT; Ui.heldText = heldT; Ui.heldRatio = heldR
            Ui.zoneDist = zd; Ui.zoneAngle = za; Ui.canExtract = can
            Ui.detDist = detD; Ui.detAngle = detA; Ui.monText = monT; Ui.danger = danger
            Ui.med = med; Ui.shock = shock; Ui.magnet = magnet
            Ui.nextQuota = quotaFor(lv)
            if (shopRows != null) Ui.shop = shopRows
            val now = System.currentTimeMillis()
            if (Ui.toasts.any { now - it.time > 3000 }) Ui.toasts = Ui.toasts.filter { now - it.time <= 3000 }
        }
    }

    private fun pushMap() {
        if (phase != Phase.PLAY) return
        val f = v3(fwd.x, 0f, fwd.z).normalize()
        val r0 = head.q * v3(1f, 0f, 0f)
        val r = v3(r0.x, 0f, r0.z).normalize()
        val me = floatArrayOf(head.t.x, head.t.z, f.x, f.z, r.x, r.z)
        val z = zone?.let { floatArrayOf(it.x, it.z) }
        val its = if (save.lvl("map") > 0) items.filter { !it.dead }.map { floatArrayOf(it.pos.x, it.pos.z, if (it.inZone) 1f else 0f) } else emptyList()
        val ms = if (save.lvl("radar") > 0) monsters.map { floatArrayOf(it.pos.x, it.pos.z) } else emptyList()
        ui { Ui.mapMe = me; Ui.mapZone = z; Ui.mapItems = its; Ui.mapMons = ms }
    }
}
