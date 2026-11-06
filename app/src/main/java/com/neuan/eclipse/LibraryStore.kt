package com.neuan.eclipse

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Game(
    val id: String,
    val name: String,
    val ext: String,
    val addedAt: Long,
    /** Quando não é nulo, o jogo aparece em "Recentes". */
    val lastPlayed: Long?,
    /** Marca de tempo da capa; nulo = sem capa. */
    val coverStamp: Long?,
) {
    val system: GameSystem get() = GameSystem.fromExt(ext) ?: GameSystem.GBA
}

/** Biblioteca persistida em filesDir/library.json; ROMs copiadas para filesDir/roms. */
class LibraryStore(private val ctx: Context) {
    private val root = ctx.filesDir
    private val romsDir = File(root, "roms").apply { mkdirs() }
    private val coversDir = File(root, "covers").apply { mkdirs() }
    private val statesDir = File(root, "states").apply { mkdirs() }
    private val savesDir = File(root, "saves").apply { mkdirs() }
    private val dbFile = File(root, "library.json")

    private val _games = MutableStateFlow(load())
    val games: StateFlow<List<Game>> = _games

    fun romFile(g: Game) = File(romsDir, "${g.id}.${g.ext}")
    fun coverFile(g: Game) = File(coversDir, "${g.id}.jpg")
    fun stateFile(g: Game, slot: Int) = File(statesDir, "${g.id}.slot$slot.state")
    fun autoStateFile(g: Game) = File(statesDir, "${g.id}.auto.state")
    fun sramFile(g: Game) = File(savesDir, "${g.id}.srm")

    private fun load(): List<Game> = try {
        val arr = JSONArray(dbFile.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Game(
                id = o.getString("id"),
                name = o.getString("name"),
                ext = o.getString("ext"),
                addedAt = o.optLong("addedAt"),
                lastPlayed = if (o.has("lastPlayed")) o.getLong("lastPlayed") else null,
                coverStamp = if (o.has("coverStamp")) o.getLong("coverStamp") else null,
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    @Synchronized
    private fun commit(list: List<Game>) {
        _games.value = list
        val arr = JSONArray()
        list.forEach { g ->
            arr.put(JSONObject().apply {
                put("id", g.id); put("name", g.name); put("ext", g.ext); put("addedAt", g.addedAt)
                g.lastPlayed?.let { put("lastPlayed", it) }
                g.coverStamp?.let { put("coverStamp", it) }
            })
        }
        val tmp = File(root, "library.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(dbFile)
    }

    @Synchronized
    private fun update(id: String, f: (Game) -> Game) {
        commit(_games.value.map { if (it.id == id) f(it) else it })
    }

    private fun displayName(uri: Uri): String? =
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    /** Copia a ROM para o armazenamento do app. Retorna nulo se o formato não for suportado. */
    fun importRom(uri: Uri): Game? {
        val fileName = displayName(uri) ?: return null
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (GameSystem.fromExt(ext) == null) return null
        val game = Game(
            id = UUID.randomUUID().toString().replace("-", "").take(12),
            name = fileName.substringBeforeLast('.').replace('_', ' ').trim().ifEmpty { fileName },
            ext = ext,
            addedAt = System.currentTimeMillis(),
            lastPlayed = null,
            coverStamp = null,
        )
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                romFile(game).outputStream().use { input.copyTo(it) }
            } ?: return null
            commit(_games.value + game)
            game
        } catch (e: Exception) {
            romFile(game).delete()
            null
        }
    }

    /** Aceita qualquer imagem (galeria ou Arquivos); reduz para no máximo 512 px e salva em JPEG. */
    fun setCover(g: Game, uri: Uri): Boolean = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (bmp == null) {
            false
        } else {
            val scale = minOf(1f, 512f / maxOf(bmp.width, bmp.height))
            val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true) else bmp
            coverFile(g).outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            update(g.id) { it.copy(coverStamp = System.currentTimeMillis()) }
            true
        }
    } catch (e: Exception) {
        false
    }

    fun removeCover(g: Game) {
        coverFile(g).delete()
        update(g.id) { it.copy(coverStamp = null) }
    }

    fun rename(g: Game, name: String) {
        val n = name.trim()
        if (n.isNotEmpty()) update(g.id) { it.copy(name = n) }
    }

    /** "Adicionar aos Recentes" / "Remover dos Recentes". */
    fun setRecent(g: Game, recent: Boolean) {
        update(g.id) { it.copy(lastPlayed = if (recent) System.currentTimeMillis() else null) }
    }

    fun markPlayed(g: Game) = setRecent(g, true)

    fun delete(g: Game) {
        romFile(g).delete(); coverFile(g).delete(); sramFile(g).delete(); autoStateFile(g).delete()
        (1..3).forEach { stateFile(g, it).delete() }
        commit(_games.value.filterNot { it.id == g.id })
    }
}
