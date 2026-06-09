package com.arcisai.nvr.ui.screens

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import java.io.OutputStream

/**
 * Capture the currently-rendered video frame from a libVLC [VLCVideoLayout]
 * (a ViewGroup wrapping a SurfaceView or TextureView) and save it to the phone
 * gallery. Purely local — works identically for LAN and Remote (P2P) since it
 * reads the already-decoded frame on-screen.
 *
 * SurfaceView content can't be read with the normal view-draw cache, so we use
 * PixelCopy (API 24+); TextureView falls back to getBitmap().
 */
fun saveVideoSnapshot(root: ViewGroup, context: Context, onDone: (Boolean, String) -> Unit) {
    val w = root.width
    val h = root.height
    if (w == 0 || h == 0) { onDone(false, "No video to capture"); return }

    val surface = findChild(root) { it is SurfaceView } as SurfaceView?
    val texture = if (surface == null) findChild(root) { it is TextureView } as TextureView? else null

    when {
        surface != null -> {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            PixelCopy.request(surface, bmp, { result ->
                if (result == PixelCopy.SUCCESS) persist(context, bmp, onDone)
                else onDone(false, "Capture failed")
            }, Handler(Looper.getMainLooper()))
        }
        texture != null -> {
            val bmp = texture.getBitmap(w, h)
            if (bmp != null) persist(context, bmp, onDone) else onDone(false, "Capture failed")
        }
        else -> onDone(false, "No video surface")
    }
}

/** Iterative depth-first search for the first descendant matching [match]. */
private fun findChild(root: ViewGroup, match: (View) -> Boolean): View? {
    val stack = ArrayDeque<View>()
    stack.addLast(root)
    while (stack.isNotEmpty()) {
        val v = stack.removeLast()
        if (v !== root && match(v)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) stack.addLast(v.getChildAt(i))
    }
    return null
}

private fun persist(context: Context, bmp: Bitmap, onDone: (Boolean, String) -> Unit) {
    val name = "ArcisNVR_${System.currentTimeMillis()}.jpg"
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ArcisNVR")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: run { onDone(false, "Couldn't save"); return }
            resolver.openOutputStream(uri).use { out: OutputStream? ->
                out ?: run { onDone(false, "Couldn't save"); return }
                bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            onDone(true, "Saved to Pictures/ArcisNVR")
        } else {
            // API 26–28: app-specific external dir, no runtime permission needed.
            val dir = context.getExternalFilesDir("snapshots")
            val file = java.io.File(dir, name)
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            onDone(true, "Saved to ${file.absolutePath}")
        }
    } catch (t: Throwable) {
        onDone(false, t.message ?: "Save failed")
    }
}
