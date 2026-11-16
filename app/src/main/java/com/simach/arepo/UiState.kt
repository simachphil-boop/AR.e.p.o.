package com.simach.arepo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.ConcurrentLinkedQueue

enum class Phase { LOADING, NOSCENE, MENU, ZONE, INTRO, PLAY, PAUSE, DONE, SHOP, DEAD }

data class ShopRow(val id: String, val e: String, val n: String, val d: String, val price: Int, val lvl: Int, val max: Int, val cons: Boolean)
data class MapPoly(val kind: Int, val pts: FloatArray) // 0 — пол комнаты
data class Toast(val text: String, val kind: Int, val time: Long) // 0 обычное, 1 плохое, 2 хорошее, 3 деньги

/** Состояние интерфейса. Пишется только из главного потока. */
object Ui {
    var phase by mutableStateOf(Phase.LOADING)
    var status by mutableStateOf("Загрузка…")
    var lastCrash by mutableStateOf("")
    var level by mutableStateOf(1)
    var money by mutableStateOf(0)
    var best by mutableStateOf(0)
    var hp by mutableStateOf(100)
    var hpMax by mutableStateOf(100)
    var quota by mutableStateOf(0)
    var zoneVal by mutableStateOf(0)
    var left by mutableStateOf(0)
    var rooms by mutableStateOf(0)
    var floorArea by mutableStateOf(0f)
    var surfaces by mutableStateOf(0)
    var hasZone by mutableStateOf(false)
    var aimText by mutableStateOf("")
    var heldText by mutableStateOf("")
    var heldRatio by mutableStateOf(1f)
    var zoneDist by mutableStateOf(-1f)
    var zoneAngle by mutableStateOf(0f)
    var canExtract by mutableStateOf(false)
    var detDist by mutableStateOf(-1f)
    var detAngle by mutableStateOf(0f)
    var monText by mutableStateOf("")
    var danger by mutableStateOf(0f)
    var med by mutableStateOf(0)
    var shock by mutableStateOf(0)
    var magnet by mutableStateOf(0)
    var summary by mutableStateOf(listOf<Pair<String, String>>())
    var shop by mutableStateOf(listOf<ShopRow>())
    var nextQuota by mutableStateOf(0)
    var toasts by mutableStateOf(listOf<Toast>())

    // карта (в координатах мира x,z)
    var mapPolys by mutableStateOf(listOf<MapPoly>())
    var mapMe by mutableStateOf(floatArrayOf(0f, 0f, 0f, 1f, 1f, 0f)) // x, z, fx, fz, rx, rz
    var mapZone by mutableStateOf<FloatArray?>(null)
    var mapItems by mutableStateOf(listOf<FloatArray>())
    var mapMons by mutableStateOf(listOf<FloatArray>())

    val cmds = ConcurrentLinkedQueue<String>()
    fun cmd(c: String) { cmds.add(c) }

    fun toast(text: String, kind: Int = 0) {
        val now = System.currentTimeMillis()
        toasts = (toasts.filter { now - it.time < 3000 } + Toast(text, kind, now)).takeLast(3)
    }
}
