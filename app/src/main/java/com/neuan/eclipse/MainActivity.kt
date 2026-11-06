package com.neuan.eclipse

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    private lateinit var store: LibraryStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = LibraryStore(applicationContext)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { App(store) }
        }
    }

    // Controles físicos (gamepad/teclado) → sessão ativa.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val s = EmulatorSession.active
        if (s != null && InputMap.handleKey(s, event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val s = EmulatorSession.active
        if (s != null && InputMap.handleMotion(s, event)) return true
        return super.onGenericMotionEvent(event)
    }

    // Pausa ao sair do app e retoma ao voltar.
    override fun onPause() {
        super.onPause()
        EmulatorSession.active?.autoPaused = true
    }

    override fun onResume() {
        super.onResume()
        EmulatorSession.active?.autoPaused = false
    }
}

@Composable
private fun App(store: LibraryStore) {
    var playing by remember { mutableStateOf<Game?>(null) }
    val game = playing
    if (game == null) {
        LibraryScreen(store, onPlay = { playing = it })
    } else {
        BackHandler { playing = null }
        GameScreen(game, store, onExit = { playing = null })
    }
}
