package com.neuan.eclipse

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

/** IDs do joypad libretro: B=0 Y=1 SELECT=2 START=3 UP=4 DOWN=5 LEFT=6 RIGHT=7 A=8 X=9 L=10 R=11 */
object Pad {
    const val B = 0; const val Y = 1; const val SELECT = 2; const val START = 3
    const val UP = 4; const val DOWN = 5; const val LEFT = 6; const val RIGHT = 7
    const val A = 8; const val X = 9; const val L = 10; const val R = 11
}

object InputMap {
    private fun map(code: Int): Int = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> Pad.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> Pad.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> Pad.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Pad.RIGHT
        // Layout "Nintendo": o botão de baixo do controle (A/Xbox) vira B.
        KeyEvent.KEYCODE_BUTTON_A -> Pad.B
        KeyEvent.KEYCODE_BUTTON_B -> Pad.A
        KeyEvent.KEYCODE_BUTTON_X -> Pad.Y
        KeyEvent.KEYCODE_BUTTON_Y -> Pad.X
        KeyEvent.KEYCODE_BUTTON_L1 -> Pad.L
        KeyEvent.KEYCODE_BUTTON_R1 -> Pad.R
        KeyEvent.KEYCODE_BUTTON_START -> Pad.START
        KeyEvent.KEYCODE_BUTTON_SELECT -> Pad.SELECT
        // Teclado
        KeyEvent.KEYCODE_Z -> Pad.B
        KeyEvent.KEYCODE_X -> Pad.A
        KeyEvent.KEYCODE_A -> Pad.Y
        KeyEvent.KEYCODE_S -> Pad.X
        KeyEvent.KEYCODE_Q -> Pad.L
        KeyEvent.KEYCODE_W -> Pad.R
        KeyEvent.KEYCODE_ENTER -> Pad.START
        KeyEvent.KEYCODE_SHIFT_RIGHT -> Pad.SELECT
        else -> -1
    }

    fun handleKey(session: EmulatorSession, e: KeyEvent): Boolean {
        val id = map(e.keyCode)
        if (id < 0) return false
        if (e.repeatCount == 0) session.setButton(id, e.action == KeyEvent.ACTION_DOWN)
        return true
    }

    fun handleMotion(session: EmulatorSession, e: MotionEvent): Boolean {
        if (e.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK || e.action != MotionEvent.ACTION_MOVE) return false
        val hx = e.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val x = if (abs(hx) > 0.5f) hx else e.getAxisValue(MotionEvent.AXIS_X)
        val y = if (abs(hy) > 0.5f) hy else e.getAxisValue(MotionEvent.AXIS_Y)
        session.setButton(Pad.LEFT, x < -0.5f)
        session.setButton(Pad.RIGHT, x > 0.5f)
        session.setButton(Pad.UP, y < -0.5f)
        session.setButton(Pad.DOWN, y > 0.5f)
        return true
    }
}
