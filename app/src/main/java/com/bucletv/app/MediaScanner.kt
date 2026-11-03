package com.bucletv.app

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File

/** imageMs: duración de una imagen en ms (0 = la de los ajustes de la TV). */
data class MediaFile(val file: File, val isVideo: Boolean, val imageMs: Long = 0)

data class ScanResult(
    val items: List<MediaFile>,
    val roots: List<File>,
    val source: String,
    val signature: String
)

/**
 * Busca vídeos e imágenes en los pendrives USB conectados.
 *
 * - Si el USB tiene una carpeta llamada "BUCLE" (da igual mayúsculas), usa solo esa carpeta.
 * - Si no, recorre todo el USB (hasta 4 niveles de subcarpetas).
 * - Si no hay USB, mira la carpeta "Bucle" de la memoria interna.
 * - Ordena por nombre "natural" (2.jpg va antes que 10.jpg), así puedes
 *   controlar el orden poniendo 01_, 02_, 03_… delante de cada archivo.
 */
object MediaScanner {

    private val VIDEO_EXT = setOf(
        "mp4", "m4v", "mkv", "webm", "mov", "3gp", "ts", "m2ts", "mts",
        "mpg", "mpeg", "avi", "flv"
    )
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif")

    private val SKIP_DIRS = setOf(
        "android", "lost.dir", "system volume information", "\$recycle.bin",
        "recycler", "found.000", "lost+found"
    )

    private const val MAX_DEPTH = 4
    private const val MAX_FILES = 3000

    fun isVideo(f: File) = f.extension.lowercase() in VIDEO_EXT
    private fun isMedia(f: File): Boolean {
        val e = f.extension.lowercase()
        return e in VIDEO_EXT || e in IMAGE_EXT
    }

    fun scan(context: Context): ScanResult {
        val internal = internalRoot()
        val usbRoots = findUsbRoots(context, internal)

        val found = ArrayList<File>()
        val sources = ArrayList<String>()

        for (root in usbRoots) {
            val start = findBucleFolder(root) ?: root
            val before = found.size
            collect(start, 0, found)
            if (found.size > before) sources.add(start.absolutePath)
        }

        if (found.isEmpty() && internal != null) {
            val bucle = findBucleFolder(internal)
            if (bucle != null) {
                collect(bucle, 0, found)
                if (found.isNotEmpty()) sources.add(bucle.absolutePath)
            }
        }

        val sorted = found
            .distinctBy { it.absolutePath }
            .sortedWith { a, b -> NaturalOrder.compare(a.absolutePath, b.absolutePath) }

        val items = sorted.map { MediaFile(it, isVideo(it)) }
        val signature = items.joinToString("|") {
            "${it.file.absolutePath}:${it.file.length()}:${it.file.lastModified()}"
        }
        return ScanResult(
            items = items,
            roots = usbRoots,
            source = if (sources.isEmpty()) "—" else sources.joinToString(", "),
            signature = signature
        )
    }

    private fun findBucleFolder(root: File): File? =
        root.listFiles()?.firstOrNull { it.isDirectory && it.name.equals("bucle", ignoreCase = true) }

    private fun collect(dir: File, depth: Int, out: MutableList<File>) {
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (out.size >= MAX_FILES) return
            if (f.name.startsWith(".")) continue          // ocultos y basura de macOS (._archivo)
            if (f.isDirectory) {
                if (depth < MAX_DEPTH && f.name.lowercase() !in SKIP_DIRS) collect(f, depth + 1, out)
            } else if (isMedia(f) && f.length() > 0) {
                out.add(f)
            }
        }
    }

    private fun internalRoot(): File? = try {
        Environment.getExternalStorageDirectory()?.canonicalFile
    } catch (e: Exception) {
        null
    }

    private fun findUsbRoots(context: Context, internal: File?): List<File> {
        val candidates = LinkedHashSet<File>()

        // A) Volúmenes que conoce el sistema
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                val sm = context.getSystemService(StorageManager::class.java)
                for (v in sm.storageVolumes) {
                    if (v.isPrimary) continue
                    val state = v.state
                    if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) continue
                    volumeDir(v)?.let { candidates.add(it) }
                }
            } catch (e: Exception) {
                // seguimos con los otros métodos
            }
        }

        // B) Carpetas de la app en cada unidad -> raíz de la unidad
        try {
            for (d in context.getExternalFilesDirs(null)) {
                if (d == null) continue
                val p = d.absolutePath
                val i = p.indexOf("/Android/data/")
                if (i > 0) candidates.add(File(p.substring(0, i)))
            }
        } catch (e: Exception) {
        }

        // C) /storage/* y rutas habituales de TV boxes
        File("/storage").listFiles()?.forEach {
            if (it.isDirectory && it.name != "emulated" && it.name != "self") candidates.add(it)
        }
        for (p in listOf("/mnt/usb", "/mnt/usbhost", "/mnt/usb_storage", "/mnt/udisk", "/mnt/usbdisk")) {
            File(p).listFiles()?.forEach { if (it.isDirectory) candidates.add(it) }
        }

        val internalPath = internal?.absolutePath
        val result = ArrayList<File>()
        val seenPaths = HashSet<String>()
        val seenFingerprints = HashSet<String>()
        for (c in candidates) {
            val canon = try { c.canonicalFile } catch (e: Exception) { c }
            val path = canon.absolutePath
            if (path == internalPath || path.startsWith("/storage/emulated")) continue
            if (!seenPaths.add(path)) continue
            val children = canon.listFiles() ?: continue      // no se puede leer
            // La misma unidad puede aparecer con dos rutas distintas: evitamos duplicarla
            if (children.isNotEmpty()) {
                val fp = children.sortedBy { it.name }
                    .joinToString("|") { "${it.name}:${it.length()}:${it.lastModified()}" }
                if (!seenFingerprints.add(fp)) continue
            }
            result.add(canon)
        }
        return result
    }

    private fun volumeDir(v: StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= 30) return v.directory
        return try {
            v.javaClass.getMethod("getPathFile").invoke(v) as? File
        } catch (e: Exception) {
            try {
                (v.javaClass.getMethod("getPath").invoke(v) as? String)?.let { File(it) }
            } catch (e2: Exception) {
                null
            }
        }
    }
}

/** Orden natural: "img2" < "img10", sin distinguir mayúsculas. */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ie = i
                while (ie < a.length && a[ie].isDigit()) ie++
                var je = j
                while (je < b.length && b[je].isDigit()) je++
                val na = a.substring(i, ie).trimStart('0')
                val nb = b.substring(j, je).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ie
                j = je
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
