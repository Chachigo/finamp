package com.unicornsonlsd.finamp

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL

class MediaItemContentProvider : ContentProvider() {

    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    private lateinit var memoryCache: LruCache<String, ByteArray>

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val fragment = uri.encodedFragment
        if (fragment != null) {
            // We store the original scheme://host in fragment since it should be unused
            val origin = fragment.toUri()
            val fixedUri = uri.buildUpon()
                .fragment(null)
                .scheme(origin.scheme)
                .encodedAuthority(origin.encodedAuthority)
                .toString()

            // Check if we already cached the image
            val bytes = memoryCache.get(fixedUri)
            if (bytes != null) {
                return openSafePipeHelper(
                    uri,
                    "application/octet-stream",
                    null,
                    bytes,
                ) { output, _, _, _, b ->
                    FileOutputStream(output.fileDescriptor).write(b)
                }
            }

            val response = URL(fixedUri).readBytes()
            memoryCache.put(fixedUri, response)
            return openSafePipeHelper(
                uri,
                "application/octet-stream",
                null,
                response,
            ) { output, _, _, _, b ->
                FileOutputStream(output.fileDescriptor).write(b)
            }
        }

        // serve a pre-rendered drawable PNG (rendered in onCreate on main thread)
        if (uri.pathSegments.firstOrNull() == "drawable") {
            val resName = uri.pathSegments.getOrNull(1)
                ?: throw FileNotFoundException("No drawable name in URI: $uri")
            val cacheFile = File(context!!.cacheDir, "drawable_$resName.png")
            Log.d(TAG, "openFile: drawable request for $resName, exists=${cacheFile.exists()}, path=${cacheFile.absolutePath}")
            if (!cacheFile.exists()) throw FileNotFoundException("Pre-rendered drawable not found: $resName")
            return ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        // this means it's a local image (downloaded or placeholder art)
        return ParcelFileDescriptor.open(File(uri.path!!), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun onCreate(): Boolean {
        // Get max available VM memory, exceeding this amount will throw an
        // OutOfMemory exception. Stored in kilobytes as LruCache takes an
        // int in its constructor.
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()

        // Use 1/8th of the available memory for this memory cache.
        val cacheSize = maxMemory / 8
        memoryCache = object : LruCache<String, ByteArray>(cacheSize) {

            override fun sizeOf(key: String, value: ByteArray): Int {
                // The cache size will be measured in kilobytes rather than
                // number of items.
                return value.size / 1024
            }
        }

        // Pre-render drawable icons on the main thread so they're ready for openFile() worker calls.
        preRenderDrawable("tabler_icons_arrows_shuffle_24")

        return true
    }

    private fun preRenderDrawable(resName: String) {
        try {
            val ctx = context ?: return
            val cacheFile = File(ctx.cacheDir, "drawable_$resName.png")
            if (cacheFile.exists()) return
            val resId = ctx.resources.getIdentifier(resName, "drawable", ctx.packageName)
            if (resId == 0) { Log.e(TAG, "preRenderDrawable: resource not found: $resName"); return }
            val drawable = ContextCompat.getDrawable(ctx, resId)
                ?: run { Log.e(TAG, "preRenderDrawable: null drawable for $resName"); return }
            val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, 128, 128)
            drawable.draw(Canvas(bitmap))
            FileOutputStream(cacheFile).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Log.d(TAG, "preRenderDrawable: saved to ${cacheFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "preRenderDrawable: failed for $resName", e)
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        return null
    }

    override fun getType(uri: Uri): String? {
        if (uri.pathSegments.firstOrNull() == "drawable") return "image/png"
        return null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        return null
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        return 0
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        return 0
    }

    /**
     * Own implementation of [openPipeHelper].
     *
     * Differs from the framework implementation in that it has an additional try/catch block
     * around the writeDataToPipe call.
     *
     * This is to catch the EPIPE (Broken pipe) error that can occur
     * when writing large files (like GIF cover arts) to the pipe.
     */
    private fun <T> openSafePipeHelper(
        uri: Uri,
        mimeType: String,
        opts: Bundle? = null,
        args: T?,
        func: PipeDataWriter<T>,
    ): ParcelFileDescriptor = try {
        val (read, write) = ParcelFileDescriptor.createPipe()

        coroutineScope.launch(Dispatchers.IO) {
            try {
                func.writeDataToPipe(write, uri, mimeType, opts, args)
            } catch (e: IOException) {
                Log.e(TAG, "Failure writing to pipe", e)
            }
            try {
                write.close()
            } catch (e: IOException) {
                Log.w(TAG, "Failure closing pipe", e)
            }
        }

        read
    } catch (e: IOException) {
        throw FileNotFoundException("failure making pipe")
    }

    companion object {
        private const val TAG = "MediaItemCP"
    }
}
