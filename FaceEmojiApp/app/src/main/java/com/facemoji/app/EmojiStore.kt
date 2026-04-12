package com.facemoji.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

/**
 * Persists generated face emoji PNGs in internal storage so both the main
 * app and the keyboard IME (same package) can read them.
 *
 * Stores up to [MAX_SAVED] files; oldest is deleted when the limit is hit.
 */
object EmojiStore {

    private const val DIR       = "face_emojis"
    private const val MAX_SAVED = 8

    private fun dir(ctx: Context): File =
        File(ctx.filesDir, DIR).also { it.mkdirs() }

    /** Save [bitmap] as a PNG and return the file. */
    fun save(ctx: Context, bitmap: Bitmap): File {
        pruneOldest(ctx)
        val file = File(dir(ctx), "emoji_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    /** Return all saved emoji files, newest first. */
    fun all(ctx: Context): List<File> =
        (dir(ctx).listFiles { f -> f.extension == "png" } ?: emptyArray())
            .sortedByDescending { it.lastModified() }

    /** Decode a saved file back to a Bitmap. */
    fun load(file: File): Bitmap? = BitmapFactory.decodeFile(file.absolutePath)

    private fun pruneOldest(ctx: Context) {
        val files = all(ctx)
        if (files.size >= MAX_SAVED) files.drop(MAX_SAVED - 1).forEach { it.delete() }
    }
}
