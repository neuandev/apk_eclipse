package com.neuan.eclipse

import android.content.res.Configuration
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun GameScreen(game: Game, store: LibraryStore, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val session = remember(game.id) { EmulatorSession(ctx.applicationContext, game, store) }

    DisposableEffect(session) {
        store.markPlayed(game)   // entra em "Recentes"
        session.start()
        onDispose { session.stop() }
    }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    @Composable
    fun TopBar(modifier: Modifier) {
        var menu by remember { mutableStateOf(false) }
        Row(modifier.fillMaxWidth()) {
            IconButton(onClick = onExit) {
                Icon(Icons.Default.ArrowBack, stringResource(R.string.back), tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, stringResource(R.string.more), tint = Color.White)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(if (session.userPaused) R.string.resume else R.string.pause)) },
                        onClick = { menu = false; session.userPaused = !session.userPaused },
                    )
                    (1..3).forEach { slot ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.save_state_n, slot)) },
                            onClick = { menu = false; session.saveState(slot) },
                        )
                    }
                    (1..3).forEach { slot ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.load_state_n, slot)) },
                            onClick = { menu = false; session.loadState(slot) },
                        )
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.reset)) }, onClick = { menu = false; session.reset() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.quit)) }, onClick = { menu = false; onExit() })
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        if (landscape) {
            GameSurface(Modifier.align(Alignment.Center).fillMaxHeight().aspectRatio(session.aspect))
            LandscapeControls(session, game.system, Modifier.align(Alignment.BottomCenter).fillMaxSize())
            TopBar(Modifier.align(Alignment.TopCenter))
        } else {
            Column(Modifier.fillMaxSize()) {
                TopBar(Modifier)
                GameSurface(Modifier.fillMaxWidth().aspectRatio(session.aspect))
                Spacer(Modifier.weight(1f))
                PortraitControls(session, game.system)
            }
        }
    }

    session.failed?.let { msg ->
        AlertDialog(
            onDismissRequest = onExit,
            title = { Text(stringResource(R.string.error_title)) },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = onExit) { Text(stringResource(R.string.ok)) } },
        )
    }
}

@Composable
private fun GameSurface(modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { c ->
            SurfaceView(c).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) = NativeBridge.setSurface(h.surface)
                    override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hh: Int) {}
                    override fun surfaceDestroyed(h: SurfaceHolder) = NativeBridge.setSurface(null)
                })
            }
        },
    )
}
