package com.vibe.music.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.vibe.music.VibeApp
import okhttp3.Request
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

/**
 * Zpřístupní obaly alb přes `content://` – Android Auto neumí načítat obrázky z http(s).
 * Obrázek se při prvním požadavku stáhne do cache a dál se čte z disku.
 */
class ArtworkProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val remote = uri.getQueryParameter("u") ?: throw FileNotFoundException("Chybí URL")
        if (!remote.startsWith("https://")) throw FileNotFoundException("Nepovolená URL")
        val ctx = context ?: throw FileNotFoundException()
        val dir = File(ctx.cacheDir, "artwork").apply { mkdirs() }
        val file = File(dir, sha1(remote))
        if (!file.exists()) {
            val http = (ctx.applicationContext as VibeApp).container.http
            runCatching {
                http.newCall(Request.Builder().url(remote).build()).execute().use { r ->
                    if (!r.isSuccessful) throw FileNotFoundException("HTTP ${r.code}")
                    val tmp = File(dir, file.name + ".tmp")
                    tmp.outputStream().use { out -> r.body.byteStream().copyTo(out) }
                    tmp.renameTo(file)
                }
            }.onFailure { throw FileNotFoundException(it.message) }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/*"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0

    companion object {
        fun authority(context: Context) = "${context.packageName}.artwork"

        fun uriFor(context: Context, url: String?): Uri? = url?.takeIf { it.startsWith("https://") }?.let {
            Uri.Builder().scheme("content").authority(authority(context)).path("art").appendQueryParameter("u", it).build()
        }

        private fun sha1(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
