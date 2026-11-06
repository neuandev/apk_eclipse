package com.neuan.eclipse

import android.view.Surface

/** Ponte JNI para o frontend libretro (app/src/main/cpp/frontend.cpp). */
object NativeBridge {
    init { System.loadLibrary("eclipse_frontend") }

    external fun loadCore(path: String): Boolean
    external fun loadGame(romPath: String, systemDir: String, saveDir: String): Boolean
    external fun getWidth(): Int
    external fun getHeight(): Int
    external fun getAspect(): Float
    external fun getFps(): Double
    external fun getSampleRate(): Double
    external fun run()
    external fun readAudio(out: ShortArray, max: Int): Int
    external fun setButtons(mask: Int)
    external fun setSurface(surface: Surface?)
    external fun saveState(path: String): Boolean
    external fun loadState(path: String): Boolean
    external fun saveSram(path: String): Boolean
    external fun loadSram(path: String): Boolean
    external fun reset()
    external fun unload()
}
