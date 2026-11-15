package com.simach.arepo

import android.opengl.Matrix

class Part(val mesh: Mesh, val m: FloatArray, val color: FloatArray, val emis: FloatArray, val spec: Float, val alpha: Float = 1f)

fun part(
    mesh: Mesh, color: FloatArray, tx: Float = 0f, ty: Float = 0f, tz: Float = 0f,
    sx: Float = 1f, sy: Float = 1f, sz: Float = 1f, rx: Float = 0f, ry: Float = 0f, rz: Float = 0f,
    emis: FloatArray = floatArrayOf(0f, 0f, 0f), spec: Float = 0.2f
): Part {
    val m = FloatArray(16)
    Matrix.setIdentityM(m, 0)
    Matrix.translateM(m, 0, tx, ty, tz)
    if (ry != 0f) Matrix.rotateM(m, 0, ry, 0f, 1f, 0f)
    if (rx != 0f) Matrix.rotateM(m, 0, rx, 1f, 0f, 0f)
    if (rz != 0f) Matrix.rotateM(m, 0, rz, 0f, 0f, 1f)
    Matrix.scaleM(m, 0, sx, sy, sz)
    return Part(mesh, m, color, emis, spec)
}

fun rgb(r: Float, g: Float, b: Float) = floatArrayOf(r, g, b)

val GOLD = rgb(1f, 0.76f, 0.22f)
val WOOD = rgb(0.45f, 0.26f, 0.12f)
val DARK = rgb(0.08f, 0.08f, 0.1f)

class ItemType(
    val id: String, val name: String, val emoji: String,
    val parts: List<Part>, val radius: Float, val height: Float,
    val fragility: Float, val mass: Float, val baseValue: Int,
    val weight: Float = 1f, val minLevel: Int = 1, val color: FloatArray
)

object Models {
    lateinit var items: List<ItemType>
    lateinit var shadow: List<Part>
    lateinit var statue: List<Part>
    lateinit var jumper: List<Part>
    lateinit var shard: Mesh
    lateinit var zoneRing: Mesh
    lateinit var zoneDisc: Mesh
    lateinit var beam: Mesh
    lateinit var marker: Mesh

    fun build() {
        val box = Meshes.box(1f, 1f, 1f)
        val ball = Meshes.sphere(1f)
        val cyl = Meshes.cyl(1f, 1f)
        shard = Meshes.box(0.025f, 0.025f, 0.025f)
        zoneRing = Meshes.ring(0.5f, 0.58f)
        zoneDisc = Meshes.ring(0f, 0.5f)
        beam = Meshes.lathe(floatArrayOf(0.56f, 0f, 0.5f, 2.4f), 32, caps = false)
        marker = Meshes.crystal(0.06f, 0.1f, 0.1f, 4)

        val porcelain = rgb(0.3f, 0.5f, 0.95f)
        val vase = listOf(
            part(Meshes.lathe(floatArrayOf(0.055f, 0f, 0.08f, 0.04f, 0.1f, 0.12f, 0.085f, 0.2f, 0.042f, 0.26f, 0.05f, 0.3f)), porcelain, spec = 0.8f),
            part(cyl, GOLD, ty = 0.115f, sx = 0.102f, sy = 0.014f, sz = 0.102f, spec = 1f)
        )
        val trophy = listOf(
            part(Meshes.lathe(floatArrayOf(0.07f, 0f, 0.07f, 0.02f, 0.02f, 0.035f, 0.016f, 0.13f, 0.06f, 0.16f, 0.085f, 0.25f, 0.08f, 0.27f)), GOLD, spec = 1f),
            part(box, GOLD, tx = 0.095f, ty = 0.2f, sx = 0.025f, sy = 0.07f, sz = 0.012f, spec = 1f),
            part(box, GOLD, tx = -0.095f, ty = 0.2f, sx = 0.025f, sy = 0.07f, sz = 0.012f, spec = 1f)
        )
        val clock = listOf(
            part(box, WOOD, ty = 0.11f, sx = 0.18f, sy = 0.22f, sz = 0.09f),
            part(cyl, rgb(0.95f, 0.92f, 0.8f), ty = 0.14f, tz = 0.045f, sx = 0.065f, sy = 0.01f, sz = 0.065f, rx = 90f),
            part(cyl, GOLD, ty = 0.14f, tz = 0.043f, sx = 0.074f, sy = 0.008f, sz = 0.074f, rx = 90f, spec = 1f),
            part(box, DARK, ty = 0.16f, tz = 0.056f, sx = 0.006f, sy = 0.045f, sz = 0.004f),
            part(box, DARK, tx = 0.015f, ty = 0.14f, tz = 0.056f, sx = 0.035f, sy = 0.006f, sz = 0.004f)
        )
        val globe = listOf(
            part(cyl, WOOD, sx = 0.07f, sy = 0.02f, sz = 0.07f),
            part(cyl, GOLD, sx = 0.012f, sy = 0.12f, sz = 0.012f, spec = 1f),
            part(ball, rgb(0.15f, 0.5f, 0.8f), ty = 0.2f, sx = 0.09f, sy = 0.09f, sz = 0.09f, spec = 0.6f),
            part(ball, rgb(0.3f, 0.7f, 0.3f), tx = 0.03f, ty = 0.23f, tz = 0.05f, sx = 0.05f, sy = 0.04f, sz = 0.045f)
        )
        val crystalCol = rgb(0.7f, 0.3f, 1f)
        val crystal = listOf(
            part(Meshes.crystal(0.07f, 0.22f, 0.05f), crystalCol, ty = 0.05f, emis = rgb(0.22f, 0.05f, 0.38f), spec = 1f),
            part(Meshes.crystal(0.035f, 0.11f, 0.03f), crystalCol, tx = 0.06f, ty = 0.03f, rz = -25f, emis = rgb(0.22f, 0.05f, 0.38f), spec = 1f)
        )
        val bars = listOf(
            part(box, GOLD, tx = -0.04f, ty = 0.025f, sx = 0.07f, sy = 0.05f, sz = 0.15f, spec = 1f),
            part(box, GOLD, tx = 0.04f, ty = 0.025f, sx = 0.07f, sy = 0.05f, sz = 0.15f, spec = 1f),
            part(box, GOLD, ty = 0.075f, sx = 0.07f, sy = 0.05f, sz = 0.15f, ry = 90f, spec = 1f)
        )
        val ivory = rgb(0.92f, 0.88f, 0.78f)
        val statuette = listOf(
            part(box, rgb(0.3f, 0.3f, 0.32f), ty = 0.015f, sx = 0.1f, sy = 0.03f, sz = 0.1f),
            part(Meshes.lathe(floatArrayOf(0.045f, 0.03f, 0.035f, 0.12f, 0.025f, 0.2f, 0.01f, 0.22f)), ivory, spec = 0.5f),
            part(ball, ivory, ty = 0.245f, sx = 0.035f, sy = 0.035f, sz = 0.035f, spec = 0.5f)
        )
        val orbCol = rgb(0.2f, 0.95f, 1f)
        val orb = listOf(
            part(cyl, DARK, sx = 0.06f, sy = 0.035f, sz = 0.06f, spec = 0.8f),
            part(ball, orbCol, ty = 0.11f, sx = 0.08f, sy = 0.08f, sz = 0.08f, emis = rgb(0.1f, 0.5f, 0.6f), spec = 1f)
        )
        val lamp = listOf(
            part(cyl, GOLD, sx = 0.06f, sy = 0.02f, sz = 0.06f, spec = 1f),
            part(cyl, GOLD, sx = 0.01f, sy = 0.2f, sz = 0.01f, spec = 1f),
            part(Meshes.lathe(floatArrayOf(0.1f, 0.16f, 0.05f, 0.28f), 20), rgb(0.95f, 0.75f, 0.45f), emis = rgb(0.35f, 0.22f, 0.05f))
        )
        val chest = listOf(
            part(box, WOOD, ty = 0.055f, sx = 0.22f, sy = 0.11f, sz = 0.14f),
            part(box, rgb(0.35f, 0.18f, 0.08f), ty = 0.125f, sx = 0.23f, sy = 0.035f, sz = 0.15f),
            part(box, GOLD, ty = 0.1f, tz = 0.072f, sx = 0.03f, sy = 0.04f, sz = 0.01f, spec = 1f),
            part(box, GOLD, tx = 0.09f, ty = 0.07f, sx = 0.012f, sy = 0.13f, sz = 0.145f, spec = 1f),
            part(box, GOLD, tx = -0.09f, ty = 0.07f, sx = 0.012f, sy = 0.13f, sz = 0.145f, spec = 1f)
        )
        val painting = listOf(
            part(box, GOLD, ty = 0.11f, sx = 0.3f, sy = 0.22f, sz = 0.025f, rx = -10f, spec = 1f),
            part(box, rgb(0.15f, 0.35f, 0.3f), ty = 0.11f, tz = 0.004f, sx = 0.255f, sy = 0.175f, sz = 0.025f, rx = -10f),
            part(ball, rgb(0.95f, 0.75f, 0.3f), tx = 0.05f, ty = 0.14f, tz = 0.012f, sx = 0.03f, sy = 0.03f, sz = 0.012f)
        )
        val skull = listOf(
            part(ball, GOLD, ty = 0.1f, sx = 0.075f, sy = 0.07f, sz = 0.085f, spec = 1f),
            part(box, GOLD, ty = 0.035f, tz = 0.035f, sx = 0.07f, sy = 0.04f, sz = 0.05f, spec = 1f),
            part(ball, DARK, tx = 0.028f, ty = 0.1f, tz = 0.07f, sx = 0.02f, sy = 0.022f, sz = 0.012f),
            part(ball, DARK, tx = -0.028f, ty = 0.1f, tz = 0.07f, sx = 0.02f, sy = 0.022f, sz = 0.012f)
        )

        items = listOf(
            ItemType("vase", "Ваза", "🏺", vase, 0.11f, 0.3f, 1.6f, 1.0f, 600, color = porcelain),
            ItemType("trophy", "Кубок", "🏆", trophy, 0.11f, 0.27f, 0.5f, 1.4f, 900, color = GOLD),
            ItemType("clock", "Часы", "🕰️", clock, 0.12f, 0.22f, 1.1f, 1.2f, 700, color = WOOD),
            ItemType("globe", "Глобус", "🌍", globe, 0.1f, 0.29f, 0.9f, 1.0f, 650, color = rgb(0.15f, 0.5f, 0.8f)),
            ItemType("crystal", "Кристалл", "🔮", crystal, 0.09f, 0.27f, 2.2f, 0.6f, 1500, 0.8f, 2, crystalCol),
            ItemType("bars", "Слитки", "🧈", bars, 0.1f, 0.1f, 0.15f, 2.4f, 1100, color = GOLD),
            ItemType("statuette", "Статуэтка", "🗿", statuette, 0.07f, 0.28f, 1.3f, 1.0f, 800, color = ivory),
            ItemType("orb", "Сфера", "🫧", orb, 0.09f, 0.19f, 1.8f, 0.8f, 1600, 0.8f, 3, orbCol),
            ItemType("lamp", "Лампа", "🪔", lamp, 0.1f, 0.28f, 1.4f, 0.9f, 400, color = rgb(0.95f, 0.75f, 0.45f)),
            ItemType("chest", "Сундучок", "🧰", chest, 0.13f, 0.14f, 0.4f, 1.9f, 1200, color = WOOD),
            ItemType("painting", "Картина", "🖼️", painting, 0.15f, 0.22f, 1.0f, 1.0f, 900, color = GOLD),
            ItemType("skull", "Золотой череп", "💀", skull, 0.08f, 0.17f, 0.7f, 1.3f, 2500, 0.35f, 4, GOLD)
        )

        // ---- монстры ----
        val eyeRed = rgb(1f, 0.1f, 0.05f)
        val shadowBody = Meshes.lathe(floatArrayOf(0.3f, 0f, 0.24f, 0.5f, 0.17f, 1.05f, 0.15f, 1.3f, 0.13f, 1.42f, 0.1f, 1.6f, 0.04f, 1.7f), 18)
        shadow = listOf(
            part(shadowBody, rgb(0.03f, 0.03f, 0.05f), spec = 0.1f),
            part(ball, rgb(0.03f, 0.03f, 0.05f), ty = 1.52f, sx = 0.13f, sy = 0.15f, sz = 0.13f, spec = 0.1f),
            part(ball, eyeRed, tx = 0.05f, ty = 1.55f, tz = 0.115f, sx = 0.022f, sy = 0.016f, sz = 0.01f, emis = rgb(1f, 0.1f, 0.05f)),
            part(ball, eyeRed, tx = -0.05f, ty = 1.55f, tz = 0.115f, sx = 0.022f, sy = 0.016f, sz = 0.01f, emis = rgb(1f, 0.1f, 0.05f))
        )
        val stone = rgb(0.72f, 0.7f, 0.66f)
        statue = listOf(
            part(Meshes.lathe(floatArrayOf(0.22f, 0f, 0.2f, 0.7f, 0.16f, 1.2f, 0.12f, 1.38f), 16), stone),
            part(ball, stone, ty = 1.5f, sx = 0.12f, sy = 0.14f, sz = 0.12f),
            part(box, stone, tx = 0.17f, ty = 1.25f, tz = 0.2f, sx = 0.07f, sy = 0.07f, sz = 0.45f, rx = -15f),
            part(box, stone, tx = -0.17f, ty = 1.25f, tz = 0.2f, sx = 0.07f, sy = 0.07f, sz = 0.45f, rx = -15f),
            part(ball, DARK, tx = 0.045f, ty = 1.53f, tz = 0.105f, sx = 0.025f, sy = 0.03f, sz = 0.012f),
            part(ball, DARK, tx = -0.045f, ty = 1.53f, tz = 0.105f, sx = 0.025f, sy = 0.03f, sz = 0.012f),
            part(box, DARK, ty = 1.43f, tz = 0.11f, sx = 0.07f, sy = 0.02f, sz = 0.01f)
        )
        val crawl = rgb(0.35f, 0.08f, 0.06f)
        val legs = ArrayList<Part>()
        for (i in 0 until 6) {
            val a = 30f + i * 60f
            legs.add(part(box, crawl, ty = 0.15f, sx = 0.035f, sy = 0.035f, sz = 0.42f, ry = a, rx = 25f))
        }
        jumper = listOf(
            part(ball, crawl, ty = 0.25f, sx = 0.2f, sy = 0.14f, sz = 0.24f, spec = 0.4f),
            part(ball, crawl, ty = 0.3f, tz = 0.2f, sx = 0.1f, sy = 0.09f, sz = 0.1f),
            part(ball, eyeRed, tx = 0.04f, ty = 0.33f, tz = 0.29f, sx = 0.018f, sy = 0.018f, sz = 0.01f, emis = rgb(1f, 0.2f, 0.05f)),
            part(ball, eyeRed, tx = -0.04f, ty = 0.33f, tz = 0.29f, sx = 0.018f, sy = 0.018f, sz = 0.01f, emis = rgb(1f, 0.2f, 0.05f))
        ) + legs
    }
}
