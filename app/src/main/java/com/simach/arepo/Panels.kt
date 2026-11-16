package com.simach.arepo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

private val Gold = Color(0xFFFFC23D)
private val Gold2 = Color(0xFFFF9A1F)
private val Card = Color(0xE6101218)
private val Line = Color(0x33FFFFFF)
private val Dim = Color(0xFFA3A8B8)
private val Green = Color(0xFF3BDC6E)
private val Red = Color(0xFFFF4646)
private val Cyan = Color(0xFF38E1FF)

@Composable
private fun Btn(text: String, primary: Boolean = false, color: Color? = null, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg = when {
        !enabled -> Brush.verticalGradient(listOf(Color(0x22FFFFFF), Color(0x22FFFFFF)))
        color != null -> Brush.verticalGradient(listOf(color, color.copy(alpha = 0.8f)))
        primary -> Brush.verticalGradient(listOf(Gold, Gold2))
        else -> Brush.verticalGradient(listOf(Color(0x30FFFFFF), Color(0x22FFFFFF)))
    }
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .border(1.dp, Line, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (primary && enabled) Color(0xFF2A1800) else Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Row2(a: String, b: String, bColor: Color = Color.White) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(a, color = Dim, fontSize = 20.sp)
        Text(b, color = bColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CardBox(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0x22FFFFFF)).border(1.dp, Line, RoundedCornerShape(18.dp)).padding(16.dp)
    ) { content() }
}

@Composable
private fun Title(t: String, color: Color = Gold) {
    Text(t, color = color, fontSize = 42.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun Hint(t: String) {
    Text(t, color = Dim, fontSize = 18.sp, lineHeight = 24.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}

@Composable
fun MenuPanel() {
    Box(
        Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp))
            .background(Brush.verticalGradient(listOf(Color(0xF02A2414), Color(0xF00B0D12))))
            .border(2.dp, Color(0x55FFC23D), RoundedCornerShape(28.dp))
            .padding(28.dp)
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (Ui.phase) {
                Phase.LOADING -> {
                    Logo()
                    Hint(Ui.status)
                }
                Phase.NOSCENE -> {
                    Logo()
                    CardBox {
                        Text("🏠 Нужно отсканировать квартиру", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(Ui.status, color = Dim, fontSize = 18.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("Нажми кнопку ниже и пройди «Настройку пространства» в каждой комнате, где будешь играть. Шлем сам найдёт стены, пол, столы и диваны. Потом комнаты соединятся в одну квартиру.", color = Color.White, fontSize = 18.sp, lineHeight = 24.sp)
                    }
                    Btn("🔍 Настроить пространство", primary = true, modifier = Modifier.fillMaxWidth()) { Ui.cmd("rescan") }
                }
                Phase.MENU -> {
                    Logo()
                    Hint("Твоя квартира — уровень. Собери ценности, не разбей, не попадись.")
                    CardBox {
                        Row2("🏠 Комнат найдено", "${Ui.rooms}")
                        Row2("📐 Площадь пола", "${"%.1f".format(Ui.floorArea)} м²")
                        Row2("🪑 Мебели", "${Ui.surfaces}")
                        Row2("🏆 Рекорд", if (Ui.best > 0) "уровень ${Ui.best}" else "—")
                        Row2("📈 Текущий уровень", "${Ui.level}")
                        Row2("💰 Деньги", "${Ui.money} $", Gold)
                    }
                    Btn("▶ Играть", primary = true, modifier = Modifier.fillMaxWidth()) { Ui.cmd("start") }
                    Btn("🔍 Добавить / пересканировать комнаты", modifier = Modifier.fillMaxWidth()) { Ui.cmd("rescan") }
                    HowTo()
                }
                Phase.ZONE -> {
                    Title("📍 Точка сдачи")
                    CardBox {
                        Text("Встань туда, куда будешь сносить добычу — например, у входной двери или в коридоре — и нажми кнопку. Там появится светящийся круг.", color = Color.White, fontSize = 20.sp, lineHeight = 27.sp)
                    }
                    Btn(if (Ui.hasZone) "📍 Перенести точку сюда" else "📍 Точка сдачи здесь (у ног)", primary = !Ui.hasZone, modifier = Modifier.fillMaxWidth()) { Ui.cmd("setZone") }
                    if (Ui.hasZone) Btn("✅ Готово", primary = true, modifier = Modifier.fillMaxWidth()) { Ui.cmd("zoneOk") }
                }
                Phase.INTRO -> {
                    Title("УРОВЕНЬ ${Ui.level}")
                    CardBox {
                        Row2("🎯 Квота", "${Ui.nextQuota} $", Gold)
                        Row2("❤️ Здоровье", "${Ui.hp} / ${Ui.hpMax}")
                        Row2("💰 Деньги", "${Ui.money} $")
                        Row2("👹 Опасность", when (Ui.level) { 1 -> "нет"; 2 -> "👤 Тень"; 3 -> "🗿 Статуя"; 4 -> "👤🗿"; 5 -> "👤🕷"; else -> "👤🗿🕷" })
                    }
                    Hint("Ценности разложены по квартире заново. Целься контроллером и зажимай боковую кнопку (хват).")
                    Btn("▶ Начать уровень", primary = true, modifier = Modifier.fillMaxWidth()) { Ui.cmd("startLevel") }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Btn("📍 Перенести точку", modifier = Modifier.weight(1f)) { Ui.cmd("moveZone") }
                        Btn("🔍 Комнаты", modifier = Modifier.weight(1f)) { Ui.cmd("rescan") }
                    }
                }
                Phase.PAUSE -> {
                    Title("Пауза", Color.White)
                    Btn("▶ Продолжить", primary = true, modifier = Modifier.fillMaxWidth()) { Ui.cmd("resume") }
                    Btn("↩ Перезапустить уровень", modifier = Modifier.fillMaxWidth()) { Ui.cmd("abandon") }
                    Btn("📍 Перенести точку сдачи", modifier = Modifier.fillMaxWidth()) { Ui.cmd("moveZone") }
                    Btn("💀 Новая игра (сброс)", color = Color(0x55FF4646), modifier = Modifier.fillMaxWidth()) { Ui.cmd("newRun") }
                    HowTo()
                }
                Phase.DONE -> {
                    Title("СДАНО!", Green)
                    CardBox { Ui.summary.forEach { (a, b) -> Row2(a, b) } }
                    Btn("🛒 В магазин", primary = true, modifier = Modifier.fillMaxWidth()) { Ui.cmd("shop") }
                }
                Phase.SHOP -> ShopView()
                Phase.DEAD -> {
                    Title("ТЫ ПОГИБ", Red)
                    CardBox { Ui.summary.forEach { (a, b) -> Row2(a, b) } }
                    Hint("Улучшения и деньги сгорели. Квартира остаётся отсканированной.")
                    Btn("▶ Новая игра", primary = true, modifier = Modifier.fillMaxWidth()) { Ui.cmd("newRun") }
                }
                Phase.PLAY -> {}
            }
            if (Ui.lastCrash.isNotEmpty() && (Ui.phase == Phase.MENU || Ui.phase == Phase.LOADING || Ui.phase == Phase.NOSCENE)) {
                CardBox {
                    Text("⚠️ Прошлый запуск закрылся с ошибкой. Сделай скриншот и пришли:", color = Red, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(Ui.lastCrash, color = Color.White, fontSize = 13.sp, lineHeight = 16.sp)
                }
                Btn("Скрыть ошибку", modifier = Modifier.fillMaxWidth()) { Ui.cmd("clearCrash") }
            }
            Toasts()
        }
    }
}

@Composable
private fun Logo() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Text("AR", color = Gold, fontSize = 64.sp, fontWeight = FontWeight.Black)
        Text(".E.P.O.", color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun HowTo() {
    CardBox {
        Text("Как играть", color = Gold, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        listOf(
            "✋ Наведи контроллер на ценность (над ней загорится ромб) и зажми боковую кнопку — вещь повиснет на луче.",
            "🕹 Стик вверх/вниз — ближе/дальше. Отпусти кнопку — вещь упадёт или полетит.",
            "💥 Резкие рывки и удары о стены ломают вещи и снижают цену.",
            "🏁 Клади вещи в круг. Когда в нём набрана квота — встань рядом и нажми A (или кнопку на руке).",
            "👤 Тень охотится, 🗿 Статуя ходит, когда не смотришь, 🕷 Прыгун делает рывки. Слушай сердце.",
            "⌚ Смотри на левую руку: квота, здоровье, карта, аптечки. B/Y — пауза.",
            "⚠️ Убери с пути хрупкое и смотри под ноги."
        ).forEach { Text(it, color = Color.White, fontSize = 17.sp, lineHeight = 23.sp, modifier = Modifier.padding(vertical = 3.dp)) }
    }
}

@Composable
private fun ShopView() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("🛒 Магазин", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
        Text("${Ui.money} $", color = Gold, fontSize = 32.sp, fontWeight = FontWeight.Black)
    }
    Btn("▶ Уровень ${Ui.level} · квота ${Ui.nextQuota} $", primary = true, modifier = Modifier.fillMaxWidth()) { Ui.cmd("next") }
    Ui.shop.chunked(2).forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            pair.forEach { r ->
                val max = r.lvl >= r.max
                val can = !max && Ui.money >= r.price
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(Color(0x22FFFFFF)).border(1.dp, Line, RoundedCornerShape(18.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("${r.e} ${r.n}", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text(r.d, color = Dim, fontSize = 15.sp, lineHeight = 19.sp)
                    Text(if (r.cons) "В запасе: ${r.lvl}" else if (r.max > 1) "Уровень ${r.lvl} / ${r.max}" else if (r.lvl > 0) "Куплено ✓" else " ", color = Cyan, fontSize = 15.sp)
                    Btn(if (max) "Максимум" else "${r.price} $", primary = can, enabled = can, modifier = Modifier.fillMaxWidth()) { Ui.cmd("buy:${r.id}") }
                }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun Toasts() {
    val now = System.currentTimeMillis()
    Ui.toasts.filter { now - it.time < 3000 }.forEach { t ->
        val c = when (t.kind) { 1 -> Red; 2 -> Green; 3 -> Gold; else -> Color.White }
        Text(t.text, color = c, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card).padding(8.dp))
    }
}

// ================== панель на руке ==================
@Composable
fun WristPanel() {
    val danger = Ui.danger
    Box(
        Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp))
            .background(Color(0xE60B0D12))
            .border(2.dp, if (danger > 0.05f) Red.copy(alpha = 0.3f + 0.7f * danger) else Line, RoundedCornerShape(20.dp))
            .padding(10.dp)
    ) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1.15f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("УР ${Ui.level}", color = Gold, fontSize = 16.sp, fontWeight = FontWeight.Black)
                    Text("${Ui.zoneVal} / ${Ui.quota} $", color = if (Ui.zoneVal >= Ui.quota) Green else Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Bar(if (Ui.quota > 0) Ui.zoneVal.toFloat() / Ui.quota else 0f, if (Ui.zoneVal >= Ui.quota) Green else Gold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("❤️", fontSize = 13.sp)
                    Box(Modifier.weight(1f)) { Bar(Ui.hp.toFloat() / Ui.hpMax, Red) }
                    Text("${Ui.hp}", color = Color.White, fontSize = 13.sp)
                }
                when {
                    Ui.heldText.isNotEmpty() -> {
                        Text(Ui.heldText, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Bar(Ui.heldRatio, if (Ui.heldRatio > 0.6f) Green else if (Ui.heldRatio > 0.3f) Gold else Red)
                    }
                    Ui.aimText.isNotEmpty() -> Text("🎯 ${Ui.aimText}", color = Gold, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    else -> Text("Ценностей: ${Ui.left}", color = Dim, fontSize = 13.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Arrow(Ui.zoneAngle, Green)
                    Text(if (Ui.zoneDist >= 0) "🏁 ${"%.1f".format(Ui.zoneDist)} м" else "", color = Color.White, fontSize = 13.sp)
                    if (Ui.detDist >= 0) { Arrow(Ui.detAngle, Cyan); Text("📡 ${"%.1f".format(Ui.detDist)}", color = Cyan, fontSize = 13.sp) }
                }
                if (Ui.monText.isNotEmpty()) Text(Ui.monText, color = Color(0xFFFFB3B3), fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    MiniBtn("🩹${Ui.med}", Ui.med > 0) { Ui.cmd("use:med") }
                    MiniBtn("⚡${Ui.shock}", Ui.shock > 0) { Ui.cmd("use:shock") }
                    MiniBtn("🧲${Ui.magnet}", Ui.magnet > 0) { Ui.cmd("use:magnet") }
                    MiniBtn("⏸", true) { Ui.cmd("pause") }
                }
                if (Ui.canExtract) MiniBtn("🏁 СДАТЬ", true, Green, Modifier.fillMaxWidth()) { Ui.cmd("extract") }
            }
            MiniMap(Modifier.weight(0.85f).fillMaxHeight())
        }
    }
}

@Composable
private fun Bar(ratio: Float, color: Color) {
    Box(Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x33FFFFFF))) {
        Box(Modifier.fillMaxWidth(ratio.coerceIn(0f, 1f)).fillMaxHeight().background(color))
    }
}

@Composable
private fun Arrow(deg: Float, color: Color) {
    Text("⬆", color = color, fontSize = 16.sp, modifier = Modifier.rotate(deg))
}

@Composable
private fun MiniBtn(t: String, enabled: Boolean, color: Color? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).background(color?.copy(alpha = 0.85f) ?: Color(0x33FFFFFF))
            .clickable { onClick() }.padding(horizontal = 7.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) { Text(t, color = if (enabled) Color.White else Dim, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun MiniMap(modifier: Modifier) {
    val polys = Ui.mapPolys
    val me = Ui.mapMe
    val zone = Ui.mapZone
    val its = Ui.mapItems
    val mons = Ui.mapMons
    Canvas(modifier.clip(RoundedCornerShape(14.dp)).background(Color(0x55101420))) {
        val w = size.width; val h = size.height
        val scale = min(w, h) / 2f / 4f  // радиус обзора 4 м
        val fx = me[2]; val fz = me[3]
        val rx = if (me.size > 5) me[4] else 1f; val rz = if (me.size > 5) me[5] else 0f // вправо от взгляда
        fun tf(x: Float, z: Float): Offset {
            val dx = x - me[0]; val dz = z - me[1]
            val sx = dx * rx + dz * rz
            val sy = -(dx * fx + dz * fz)
            return Offset(w / 2f + sx * scale, h / 2f + sy * scale)
        }
        polys.forEach { p ->
            val path = Path()
            var i = 0
            while (i + 1 < p.pts.size) {
                val o = tf(p.pts[i], p.pts[i + 1])
                if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                i += 2
            }
            path.close()
            drawPath(path, Color(0x5538E1FF))
            drawPath(path, Color(0xAA9C8CFF), style = Stroke(width = 2f))
        }
        its.forEach { drawCircle(if (it[2] > 0.5f) Green else Gold, 5f, tf(it[0], it[1])) }
        mons.forEach { drawCircle(Red, 7f, tf(it[0], it[1])) }
        zone?.let { drawCircle(Green, 0.55f * scale, tf(it[0], it[1]), style = Stroke(width = 3f)) }
        // игрок — треугольник, всегда смотрит вверх
        val c = Offset(w / 2f, h / 2f)
        val tri = Path().apply { moveTo(c.x, c.y - 10f); lineTo(c.x - 7f, c.y + 7f); lineTo(c.x + 7f, c.y + 7f); close() }
        drawPath(tri, Color.White)
    }
}
