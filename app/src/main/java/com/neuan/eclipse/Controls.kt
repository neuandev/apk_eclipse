package com.neuan.eclipse

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HoldButton(
    label: String,
    modifier: Modifier,
    shape: Shape = CircleShape,
    onChange: (Boolean) -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val callback by rememberUpdatedState(onChange)
    Box(
        modifier
            .clip(shape)
            .background(if (pressed) Color(0xAAFFFFFF) else Color(0x55FFFFFF))
            .pointerInput(Unit) {
                // Cada botão trata o próprio toque: vários botões ao mesmo tempo (multitouch).
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    callback(true)
                    waitForUpOrCancellation()
                    pressed = false
                    callback(false)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
fun PadButton(label: String, id: Int, session: EmulatorSession, modifier: Modifier, shape: Shape = CircleShape) {
    HoldButton(label, modifier, shape) { session.setButton(id, it) }
}

/** Direcional analógico-digital: um único toque que calcula as direções (inclui diagonais). */
@Composable
fun DPad(session: EmulatorSession, size: Dp = 150.dp) {
    var mask by remember { mutableIntStateOf(0) }  // bit0=UP bit1=DOWN bit2=LEFT bit3=RIGHT

    fun setMask(new: Int) {
        for (i in 0..3) {
            val bit = 1 shl i
            val was = mask and bit != 0
            val now = new and bit != 0
            if (was != now) session.setButton(Pad.UP + i, now)
        }
        mask = new
    }

    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0x33FFFFFF))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val w = this.size.width.toFloat()
                    val h = this.size.height.toFloat()
                    fun calc(p: Offset): Int {
                        val dx = p.x - w / 2
                        val dy = p.y - h / 2
                        val dz = w * 0.14f
                        var m = 0
                        if (dy < -dz) m = m or 1
                        if (dy > dz) m = m or 2
                        if (dx < -dz) m = m or 4
                        if (dx > dz) m = m or 8
                        return m
                    }
                    val down = awaitFirstDown()
                    setMask(calc(down.position))
                    do {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        setMask(if (ch.pressed) calc(ch.position) else 0)
                        ch.consume()
                    } while (ev.changes.any { it.id == down.id && it.pressed })
                    setMask(0)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("▲", color = if (mask and 1 != 0) Color.White else Color(0x99FFFFFF), modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp))
        Text("▼", color = if (mask and 2 != 0) Color.White else Color(0x99FFFFFF), modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp))
        Text("◀", color = if (mask and 4 != 0) Color.White else Color(0x99FFFFFF), modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp))
        Text("▶", color = if (mask and 8 != 0) Color.White else Color(0x99FFFFFF), modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp))
    }
}

@Composable
fun ActionCluster(session: EmulatorSession, system: GameSystem, size: Dp = 170.dp) {
    val b = 62.dp
    Box(Modifier.size(size)) {
        if (system.hasXY) {
            // Losango do SNES: X em cima, Y à esquerda, A à direita, B embaixo.
            PadButton("X", Pad.X, session, Modifier.align(Alignment.TopCenter).size(b))
            PadButton("Y", Pad.Y, session, Modifier.align(Alignment.CenterStart).size(b))
            PadButton("A", Pad.A, session, Modifier.align(Alignment.CenterEnd).size(b))
            PadButton("B", Pad.B, session, Modifier.align(Alignment.BottomCenter).size(b))
        } else {
            PadButton("B", Pad.B, session, Modifier.align(Alignment.BottomStart).offset(y = (-4).dp).size(b + 6.dp))
            PadButton("A", Pad.A, session, Modifier.align(Alignment.TopEnd).offset(y = 20.dp).size(b + 6.dp))
        }
    }
}

@Composable
private fun SelectStart(session: EmulatorSession) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        PadButton(stringResource(R.string.select), Pad.SELECT, session, Modifier.width(78.dp).height(34.dp), RoundedCornerShape(17.dp))
        PadButton(stringResource(R.string.start), Pad.START, session, Modifier.width(78.dp).height(34.dp), RoundedCornerShape(17.dp))
    }
}

@Composable
private fun Shoulders(session: EmulatorSession, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.SpaceBetween) {
        PadButton("L", Pad.L, session, Modifier.width(96.dp).height(42.dp), RoundedCornerShape(12.dp))
        PadButton("R", Pad.R, session, Modifier.width(96.dp).height(42.dp), RoundedCornerShape(12.dp))
    }
}

/** Controles na vertical: ficam abaixo da tela do jogo. */
@Composable
fun PortraitControls(session: EmulatorSession, system: GameSystem, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (system.hasShoulder) Shoulders(session, Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            DPad(session)
            ActionCluster(session, system)
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { SelectStart(session) }
    }
}

/** Controles na horizontal: sobrepostos nas laterais da tela. */
@Composable
fun LandscapeControls(session: EmulatorSession, system: GameSystem, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(16.dp)) {
        if (system.hasShoulder) Shoulders(session, Modifier.fillMaxWidth().align(Alignment.TopCenter))
        Box(Modifier.align(Alignment.BottomStart)) { DPad(session, 140.dp) }
        Box(Modifier.align(Alignment.BottomEnd)) { ActionCluster(session, system, 160.dp) }
        Box(Modifier.align(Alignment.BottomCenter)) { SelectStart(session) }
    }
}
