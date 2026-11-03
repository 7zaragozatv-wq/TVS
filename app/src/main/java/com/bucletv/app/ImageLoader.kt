package com.bucletv.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File

/** Carga imágenes reducidas al tamaño de la pantalla (evita quedarse sin memoria con fotos enormes). */
object ImageLoader {

    fun decode(file: File, reqW: Int, reqH: Int): Bitmap? {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (bounds.outWidth / sample > reqW * 2 || bounds.outHeight / sample > reqH * 2) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            var bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null

            val rotation = exifRotation(file)
            if (rotation != 0) {
                val m = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (rotated !== bmp) bmp.recycle()
                bmp = rotated
            }

            // Nunca más grande que la pantalla: evita cierres y imágenes en blanco en TVs
            val scale = minOf(reqW.toFloat() / bmp.width, reqH.toFloat() / bmp.height, 1f)
            if (scale < 1f) {
                val scaled = Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * scale).toInt().coerceAtLeast(1),
                    (bmp.height * scale).toInt().coerceAtLeast(1),
                    true
                )
                if (scaled !== bmp) bmp.recycle()
                bmp = scaled
            }
            return bmp
        } catch (t: Throwable) {   // incluye OutOfMemoryError
            return null
        }
    }

    private fun exifRotation(file: File): Int {
        val ext = file.extension.lowercase()
        if (ext != "jpg" && ext != "jpeg") return 0
        return try {
            when (ExifInterface(file.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Exception) {
            0
        }
    }
}
