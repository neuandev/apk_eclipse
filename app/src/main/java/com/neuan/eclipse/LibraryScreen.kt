package com.neuan.eclipse

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(store: LibraryStore, onPlay: (Game) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val games by store.games.collectAsState()

    var coverTarget by remember { mutableStateOf<Game?>(null) }
    var renameTarget by remember { mutableStateOf<Game?>(null) }
    var deleteTarget by remember { mutableStateOf<Game?>(null) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val added = withContext(Dispatchers.IO) { uris.count { store.importRom(it) != null } }
            val msg = if (added > 0) ctx.getString(R.string.imported, added) else ctx.getString(R.string.unsupported_file)
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // GetContent abre o seletor do sistema: galeria, Arquivos, Drive etc.
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val target = coverTarget
        coverTarget = null
        if (uri != null && target != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { store.setCover(target, uri) }
            Toast.makeText(ctx, if (ok) R.string.cover_set else R.string.cover_failed, Toast.LENGTH_SHORT).show()
        }
    }

    val recents = games.filter { it.lastPlayed != null }.sortedByDescending { it.lastPlayed }.take(10)
    val sorted = games.sortedBy { it.name.lowercase() }

    val actions = TileActions(
        onPlay = onPlay,
        onSetCover = { coverTarget = it; coverPicker.launch("image/*") },
        onRemoveCover = { store.removeCover(it) },
        onToggleRecent = { g -> store.setRecent(g, g.lastPlayed == null) },
        onRename = { renameTarget = it },
        onDelete = { deleteTarget = it },
    )

    Scaffold(
        containerColor = Color(0xFF101014),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.library)) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { importer.launch(arrayOf("*/*")) }) {
                Icon(Icons.Default.Add, stringResource(R.string.add_games))
            }
        },
    ) { pad ->
        if (games.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(pad).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text(stringResource(R.string.empty_message), color = Color(0xCCFFFFFF), modifier = Modifier.padding(top = 8.dp))
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(130.dp),
                modifier = Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (recents.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle(stringResource(R.string.recents)) }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(recents, key = { it.id }) { g ->
                                GameTile(g, store, actions, Modifier.width(130.dp))
                            }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle(stringResource(R.string.all_games)) }
                }
                items(sorted, key = { it.id }) { g -> GameTile(g, store, actions) }
            }
        }
    }

    renameTarget?.let { g ->
        var text by remember(g.id) { mutableStateOf(g.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.rename_title)) },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text(stringResource(R.string.name)) }) },
            confirmButton = { TextButton(onClick = { store.rename(g, text); renameTarget = null }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    deleteTarget?.let { g ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(stringResource(R.string.delete_message, g.name)) },
            confirmButton = { TextButton(onClick = { store.delete(g); deleteTarget = null }) { Text(stringResource(R.string.delete), color = Color(0xFFFF6B6B)) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private class TileActions(
    val onPlay: (Game) -> Unit,
    val onSetCover: (Game) -> Unit,
    val onRemoveCover: (Game) -> Unit,
    val onToggleRecent: (Game) -> Unit,
    val onRename: (Game) -> Unit,
    val onDelete: (Game) -> Unit,
)

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun GameTile(game: Game, store: LibraryStore, a: TileActions, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    Column(modifier.clickable { a.onPlay(game) }) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp))) {
            Cover(game, store)
            Text(
                game.system.short,
                color = Color.White,
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                    .background(Color(0x99000000), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 2.dp),
            )
            Box(Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, stringResource(R.string.more), tint = Color.White)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.play)) }, onClick = { menu = false; a.onPlay(game) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.set_cover)) }, onClick = { menu = false; a.onSetCover(game) })
                    if (game.coverStamp != null) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.remove_cover)) }, onClick = { menu = false; a.onRemoveCover(game) })
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(if (game.lastPlayed == null) R.string.add_to_recents else R.string.remove_from_recents)) },
                        onClick = { menu = false; a.onToggleRecent(game) },
                    )
                    DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menu = false; a.onRename(game) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.delete), color = Color(0xFFFF6B6B)) }, onClick = { menu = false; a.onDelete(game) })
                }
            }
        }
        Text(
            game.name,
            color = Color.White,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun Cover(game: Game, store: LibraryStore) {
    val bitmap by produceState<ImageBitmap?>(null, game.id, game.coverStamp) {
        value = if (game.coverStamp == null) null else withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(store.coverFile(game).absolutePath)?.asImageBitmap()
        }
    }
    val b = bitmap
    if (b != null) {
        Image(b, contentDescription = game.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    } else {
        Box(
            Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(game.system.color), Color(0xFF222222)))),
            contentAlignment = Alignment.BottomStart,
        ) {
            Text(game.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(8.dp))
        }
    }
}
