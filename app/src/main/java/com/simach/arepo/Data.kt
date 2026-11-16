package com.simach.arepo

import android.content.Context

// ---------- ценности ----------
class ItemType(
    val id: String, val name: String, val emoji: String,
    val size: FloatArray,          // габариты модели, м (x, y, z)
    val fragility: Float, val mass: Float, val baseValue: Int,
    val weight: Float = 1f, val minLevel: Int = 1
)

val ITEM_TYPES = listOf(
    ItemType("vase", "Ваза", "🏺", floatArrayOf(0.204f, 0.3f, 0.204f), 1.6f, 1.0f, 600),
    ItemType("trophy", "Кубок", "🏆", floatArrayOf(0.215f, 0.27f, 0.17f), 0.5f, 1.4f, 900),
    ItemType("clock", "Часы", "🕰️", floatArrayOf(0.18f, 0.22f, 0.103f), 1.1f, 1.2f, 700),
    ItemType("globe", "Глобус", "🌍", floatArrayOf(0.18f, 0.29f, 0.185f), 0.9f, 1.0f, 650),
    ItemType("crystal", "Кристалл", "🔮", floatArrayOf(0.201f, 0.272f, 0.121f), 2.2f, 0.6f, 1500, 0.8f, 2),
    ItemType("bars", "Слитки", "🧈", floatArrayOf(0.15f, 0.1f, 0.15f), 0.15f, 2.4f, 1100),
    ItemType("statuette", "Статуэтка", "🗿", floatArrayOf(0.1f, 0.28f, 0.1f), 1.3f, 1.0f, 800),
    ItemType("orb", "Сфера", "🫧", floatArrayOf(0.16f, 0.19f, 0.16f), 1.8f, 0.8f, 1600, 0.8f, 3),
    ItemType("lamp", "Лампа", "🪔", floatArrayOf(0.2f, 0.28f, 0.2f), 1.4f, 0.9f, 400),
    ItemType("chest", "Сундучок", "🧰", floatArrayOf(0.23f, 0.142f, 0.152f), 0.4f, 1.9f, 1200),
    ItemType("painting", "Картина", "🖼️", floatArrayOf(0.3f, 0.221f, 0.063f), 1.0f, 1.0f, 900),
    ItemType("skull", "Золотой череп", "💀", floatArrayOf(0.15f, 0.155f, 0.17f), 0.7f, 1.3f, 2500, 0.35f, 4),
    ItemType("piano", "Мини-рояль", "🎹", floatArrayOf(0.34f, 0.22f, 0.16f), 1.2f, 2.0f, 2000, 0.5f, 3)
)

// ---------- магазин ----------
class ShopDef(val id: String, val e: String, val n: String, val d: String, val base: Int, val max: Int, val cons: Boolean)

val SHOP = listOf(
    ShopDef("hp", "❤️", "Здоровье", "+25 к максимуму здоровья", 900, 5, false),
    ShopDef("grip", "💪", "Сила захвата", "Вещи в руках бьются на 18% меньше", 1100, 4, false),
    ShopDef("reach", "🦾", "Длинный луч", "Хватаешь на 1 м дальше", 700, 3, false),
    ShopDef("armor", "🛡️", "Броня", "Монстры бьют на 15% слабее", 1300, 3, false),
    ShopDef("map", "🗺️", "Карта ценностей", "Все ценности видны на карте", 2500, 1, false),
    ShopDef("det", "📡", "Детектор", "Стрелка и писк к ближайшей ценности", 1600, 1, false),
    ShopDef("radar", "👁️", "Радар монстров", "Монстры видны на карте", 1800, 1, false),
    ShopDef("med", "🩹", "Аптечка", "+40 здоровья", 350, 99, true),
    ShopDef("shock", "⚡", "Шокер", "Оглушает монстров перед тобой на 6 с", 600, 99, true),
    ShopDef("magnet", "🧲", "Магнит", "Притягивает ближайшую ценность (до 5 м)", 500, 99, true)
)

// ---------- сохранение ----------
class Save(ctx: Context) {
    private val sp = ctx.getSharedPreferences("arepo_quest", Context.MODE_PRIVATE)
    var level = sp.getInt("level", 1)
    var money = sp.getInt("money", 0)
    var best = sp.getInt("best", 0)
    var hp = sp.getFloat("hp", -1f)
    var runEarned = sp.getInt("runEarned", 0)
    val up = HashMap<String, Int>()

    init {
        for (d in SHOP) up[d.id] = sp.getInt("up_" + d.id, if (d.id == "med") 1 else 0)
    }

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
