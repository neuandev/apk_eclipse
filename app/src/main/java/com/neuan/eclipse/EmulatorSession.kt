package com.neuan.eclipse

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

/**
 * Uma partida. Todas as chamadas nativas acontecem na thread "eclipse-emu";
 * a UI só enfileira comandos (salvar estado, reiniciar...) e muda botões.
 */
class EmulatorSession(
    private val app: Context,
    val game: Game,
    private val store: LibraryStore,
) {
    companion object {
        @Volatile var active: EmulatorSession? = null
    }

    private val cmds = ConcurrentLinkedQueue<() -> Unit>()
    private val buttons = AtomicInteger(0)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var thread: Thread? = null

    var userPaused by mutableStateOf(false)
    @Volatile var autoPaused = false
    var aspect by mutableFloatStateOf(game.system.defaultAspect)
    var failed by mutableStateOf<String?>(null)

    fun start() {
        if (running) return
        running = true
        active = this
        thread = Thread({ loop() }, "eclipse-emu").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(3000)
        thread = null
        if (active === this) active = null
    }

    fun setButton(id: Int, down: Boolean) {
        while (true) {
            val old = buttons.get()
            val new = if (down) old or (1 shl id) else old and (1 shl id).inv()
            if (buttons.compareAndSet(old, new)) {
                NativeBridge.setButtons(new)
                return
            }
        }
    }

    fun saveState(slot: Int) = cmds.add {
        val ok = NativeBridge.saveState(store.stateFile(game, slot).absolutePath)
        toast(if (ok) R.string.state_saved else R.string.state_failed)
    }

    fun loadState(slot: Int) = cmds.add {
        val f = store.stateFile(game, slot)
        when {
            !f.exists() -> toast(R.string.no_state)
            NativeBridge.loadState(f.absolutePath) -> toast(R.string.state_loaded)
            else -> toast(R.string.state_failed)
        }
    }

    fun reset() = cmds.add { NativeBridge.reset() }

    private fun toast(res: Int) {
        main.post { Toast.makeText(app, res, Toast.LENGTH_SHORT).show() }
    }

    private fun fail(message: String) {
        failed = message
        running = false
    }

    private fun buildTrack(rate: Int): AudioTrack {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(min, rate / 8 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun loop() {
        val sys = game.system
        val core = File(app.applicationInfo.nativeLibraryDir, sys.coreLib)
        if (!core.exists()) return fail(app.getString(R.string.err_core_missing, sys.coreLib))
        if (!NativeBridge.loadCore(core.absolutePath)) return fail(app.getString(R.string.err_core_load))

        val sysDir = File(app.filesDir, "system").apply { mkdirs() }
        val saveDir = File(app.filesDir, "saves").apply { mkdirs() }
        if (!NativeBridge.loadGame(store.romFile(game).absolutePath, sysDir.absolutePath, saveDir.absolutePath)) {
            NativeBridge.unload()
            return fail(app.getString(R.string.err_load_rom))
        }

        val sram = store.sramFile(game)
        if (sram.exists()) NativeBridge.loadSram(sram.absolutePath)
        // Roda um quadro antes de restaurar o estado automático (retoma de onde parou).
        NativeBridge.run()
        val auto = store.autoStateFile(game)
        if (auto.exists()) NativeBridge.loadState(auto.absolutePath)

        val w = NativeBridge.getWidth()
        val h = NativeBridge.getHeight()
        val a = NativeBridge.getAspect()
        aspect = when {
            a > 0.1f -> a
            h > 0 -> w.toFloat() / h
            else -> sys.defaultAspect
        }
        val fps = NativeBridge.getFps().takeIf { it > 10.0 } ?: 60.0
        val rate = NativeBridge.getSampleRate().toInt().takeIf { it > 8000 } ?: 44100
        val frameNs = (1_000_000_000.0 / fps).toLong()

        val track = buildTrack(rate)
        track.play()
        var playing = true
        val buf = ShortArray(4096)
        var next = System.nanoTime()

        while (running) {
            while (true) {
                val c = cmds.poll() ?: break
                c()
            }
            if (userPaused || autoPaused) {
                if (playing) { track.pause(); playing = false }
                Thread.sleep(25)
                next = System.nanoTime()
                continue
            }
            if (!playing) { track.play(); playing = true }

            NativeBridge.run()

            var n: Int
            do {
                n = NativeBridge.readAudio(buf, buf.size)
                if (n > 0) track.write(buf, 0, n)
            } while (n == buf.size)

            next += frameNs
            val wait = next - System.nanoTime()
            if (wait > 0) LockSupport.parkNanos(wait)
            else if (wait < -frameNs * 5) next = System.nanoTime()
        }

        // Saída: persiste save e estado automático, depois libera tudo.
        NativeBridge.saveSram(sram.absolutePath)
        NativeBridge.saveState(auto.absolutePath)
        NativeBridge.unload()
        track.stop()
        track.release()
    }
}
