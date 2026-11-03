package com.bucletv.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.DisplayMetrics
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Reproductor en bucle de vídeos e imágenes.
 *
 * Dos orígenes:
 *  - ESTUDIO: el programa "Bucle TV · Estudio" del ordenador de realización manda la lista,
 *    la TV descarga los archivos a su memoria y los reproduce (sincronizada con las demás TVs).
 *  - USB: si el estudio no le ha asignado nada, reproduce lo que haya en el pendrive.
 *
 * Sin cortes:
 *  - Todos los vídeos van en una única lista de ExoPlayer, que precarga el siguiente.
 *  - Antes de una imagen, el vídeo se queda en su último fotograma, la imagen aparece encima
 *    con un fundido y, mientras se ve, el siguiente vídeo queda cargado en su primer fotograma.
 *
 * Sincronización:
 *  - Cada lista tiene una hora de inicio (reloj del ordenador). Todas las TVs empiezan a esa hora.
 *  - En cada elemento la TV compara dónde está con dónde debería estar y corrige:
 *    acortando/alargando la imagen o acelerando/frenando el vídeo un 3 % unos segundos.
 *  - Si una TV se reconecta a mitad, entra directamente en el punto que toca.
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "BucleTV"
        private const val REQ_PERMS = 1
        private const val PREFS = "ajustes"
        private const val SWITCH_LEAD_MS = 700L   // cuánto antes de la hora se prepara la nueva lista
    }

    private lateinit var playerView: PlayerView
    private lateinit var imageLayer: FrameLayout
    private lateinit var imageA: ImageView
    private lateinit var imageB: ImageView
    private lateinit var messageView: TextView
    private lateinit var toastView: TextView
    private lateinit var prefs: SharedPreferences
    private lateinit var studio: StudioClient

    private val handler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    @Suppress("DEPRECATION")
    private val dialogTheme = AlertDialog.THEME_DEVICE_DEFAULT_DARK

    private var player: ExoPlayer? = null

    private var scanned: List<MediaFile> = emptyList()   // lo encontrado en el USB
    private var items: List<MediaFile> = emptyList()     // lo que se reproduce
    private var videoIndexOfItem = IntArray(0)
    private var itemOfVideo = IntArray(0)
    private var signature: String? = null
    private var lastScan: ScanResult? = null

    private var cur = -1
    private var parkedItem = -1
    private var generation = 0
    private var consecutiveErrors = 0
    private var layerShown = false
    private var frontIsA = true
    private var preloadedIndex = -1
    private var preloadedBitmap: Bitmap? = null

    // Origen y estudio
    private var source = ""                 // "usb" | "estudio" | ""
    private var studioState: StudioState? = null
    private var appliedKey: String? = null
    private var appliedSkip = 0
    private var appliedList: String? = null
    private var preparing: String? = null
    private var startToken = 0

    // Sincronización
    private var syncStart = 0L
    private var cycleLen = 0L
    private var itemOffset = LongArray(0)
    private var driftEnabled = false
    private var lastDrift: Long? = null
    private val durationCache = HashMap<String, Long>()   // solo se usa en el hilo worker

    private var started = false
    private var askingPermissions = false
    private var receiverRegistered = false
    private var lastBackPress = 0L
    private var screenW = 1920
    private var screenH = 1080

    // ---- Ajustes ----
    private val imageDurationMs: Long get() = prefs.getInt("imagen_seg", 10) * 1000L
    private val transitionMs: Long get() = prefs.getInt("transicion_ms", 500).toLong()
    private val shuffle: Boolean get() = prefs.getBoolean("aleatorio", false)
    private val sound: Boolean get() = prefs.getBoolean("sonido", true)
    private val autostart: Boolean get() = prefs.getBoolean("autoarranque", true)

    // ------------------------------------------------------------------------
    // Ciclo de vida
    // ------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        studio = StudioClient(this, studioListener)

        playerView = findViewById(R.id.player_view)
        imageLayer = findViewById(R.id.image_layer)
        imageA = findViewById(R.id.image_a)
        imageB = findViewById(R.id.image_b)
        messageView = findViewById(R.id.message)
        toastView = findViewById(R.id.toast)

        playerView.useController = false
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        playerView.setShutterBackgroundColor(Color.BLACK)
        playerView.setKeepContentOnPlayerReset(true)
        playerView.isFocusable = false

        readScreenSize()
        hideSystemUi()
    }

    override fun onStart() {
        super.onStart()
        started = true
        registerStorageReceiver()
        studioState = studio.cachedState()
        studio.start()
        if (hasStoragePermission()) {
            if (autostart) askOverlayPermission(force = false)
        } else if (!askingPermissions && Build.VERSION.SDK_INT >= 23) {
            askingPermissions = true
            requestPermissions(neededPermissions(), REQ_PERMS)
        }
        refreshSource()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        askingPermissions = false
        if (!started) return
        if (autostart) askOverlayPermission(force = false)
        refreshSource()
    }

    override fun onStop() {
        super.onStop()
        started = false
        studio.stop()
        unregisterStorageReceiver()
        handler.removeCallbacks(rescanRunnable)
        startToken++
        stopPlayback()
        source = ""
        appliedKey = null
        appliedList = null
        preparing = null
        signature = null
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun readScreenSize() {
        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(dm)
        screenW = maxOf(dm.widthPixels, dm.heightPixels).coerceIn(640, 3840)
        screenH = minOf(dm.widthPixels, dm.heightPixels).coerceIn(360, 2160)
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    // ------------------------------------------------------------------------
    // Permisos
    // ------------------------------------------------------------------------

    private fun neededPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private fun hasStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        return neededPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun overlayGranted(): Boolean = Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(this)

    /** En Android 10+ hace falta "Mostrar sobre otras apps" para abrirse sola al encender. */
    private fun askOverlayPermission(force: Boolean) {
        if (overlayGranted()) {
            if (force) simpleDialog("Arranque automático", "Listo: Bucle TV se abrirá sola al encender la TV.")
            return
        }
        if (!force && prefs.getBoolean("overlay_preguntado", false)) return
        prefs.edit().putBoolean("overlay_preguntado", true).apply()

        AlertDialog.Builder(this, dialogTheme)
            .setTitle("Arranque automático")
            .setMessage(
                "Para que Bucle TV se abra sola al encender la TV, Android necesita el permiso " +
                        "«Mostrar sobre otras aplicaciones».\n\n" +
                        "En la pantalla que se abrirá, busca Bucle TV y actívalo. Después pulsa ATRÁS para volver."
            )
            .setPositiveButton("Abrir ajustes") { _, _ -> openOverlaySettings() }
            .setNegativeButton("Ahora no", null)
            .show()
    }

    private fun openOverlaySettings() {
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        )
        for (i in intents) {
            try {
                startActivity(i)
                return
            } catch (e: Exception) {
                // probamos la siguiente opción
            }
        }
        simpleDialog(
            "Arranque automático",
            "Esta TV no deja abrir ese ajuste directamente.\n\nSe puede activar desde un ordenador con ADB:\n" +
                    "adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow"
        )
    }

    // ------------------------------------------------------------------------
    // Elegir origen: estudio o USB
    // ------------------------------------------------------------------------

    private fun studioWantsScreen(): Boolean {
        val st = studioState ?: return false
        return st.modo == "lista" || st.modo == "negro"
    }

    private fun refreshSource() {
        if (!started) return
        if (studioWantsScreen()) {
            handler.removeCallbacks(rescanRunnable)
            applyStudio()
        } else {
            if (source == "estudio") {
                startToken++
                stopPlayback()
                source = ""
                appliedKey = null
                signature = null
            }
            appliedList = null
            preparing = null
            rescan()
        }
        updateStatus()
    }

    private val studioListener = object : StudioClient.Listener {
        override fun onStudioState(state: StudioState) {
            studioState = state
            if (started) refreshSource()
        }

        override fun onStudioFilesChanged() {
            if (started && studioWantsScreen()) applyStudio()
        }

        override fun onStudioClockChanged() {
            // El reloj del ordenador difiere del de la TV: volver a colocar la lista en su sitio
            if (started && source == "estudio" && syncStart > 0 && appliedKey != "negro") {
                appliedKey = null
                applyStudio()
            }
        }

        override fun onStudioConnection(connected: Boolean) {
            if (!started) return
            // Refrescar la pantalla de espera para que diga si hay conexión
            if (!isRunning() && source != "estudio") lastScan?.let { showWaiting(it) }
            updateStatus()
        }
    }

    private fun applyStudio() {
        val st = studioState ?: return

        if (st.modo == "negro" || st.lista == null || st.items.isEmpty()) {
            if (appliedKey != "negro") {
                appliedKey = "negro"
                appliedList = null
                preparing = null
                startToken++
                stopPlayback()
                source = "estudio"
                messageView.text = ""
                messageView.visibility = View.VISIBLE
            }
            updateStatus()
            return
        }

        val key = st.lista + "|" + st.inicio + "|" + st.items.joinToString(",") { it.name + ":" + it.ms }
        if (key != appliedKey) {
            val ready = st.items.count { item ->
                val size = st.archivos[item.name]
                size != null && studio.isCached(item.name, size)
            }
            if (ready < st.items.size) {
                // Mientras se descarga, se sigue viendo lo anterior
                preparing = st.lista
                if (!isRunning()) {
                    messageView.text = "${st.nombre}\n\nPreparando «${st.lista}»…\n$ready de ${st.items.size} archivos listos"
                    messageView.visibility = View.VISIBLE
                }
                updateStatus()
                return
            }
            appliedKey = key
            appliedSkip = st.salto
            appliedList = st.lista
            preparing = null
            source = "estudio"
            handler.removeCallbacks(rescanRunnable)
            val list = st.items.map { MediaFile(studio.localFile(it.name), it.isVideo, it.ms) }
            startList(list, st.inicio)
            return
        }

        if (st.salto != appliedSkip) {
            appliedSkip = st.salto
            if (isRunning()) {
                driftEnabled = false   // un salto manual rompe la sincronía hasta el próximo arranque
                goTo(next(cur))
            }
        }
    }

    /** Lo que se muestra en el panel del ordenador. */
    private fun updateStatus() {
        val estado = when {
            source == "estudio" && appliedKey == "negro" -> "negro"
            preparing != null -> "preparando"
            source == "estudio" && (isRunning() || appliedList != null) -> "lista"
            source == "usb" && isRunning() -> "usb"
            else -> "esperando"
        }
        val actual = if (isRunning()) "${cur + 1}/${items.size} · ${items[cur].file.name}" else null
        studio.playStatus = mapOf(
            "estado" to estado,
            "lista" to (preparing ?: appliedList),
            "actual" to actual,
            "desfase" to (if (driftEnabled) lastDrift else null)
        )
    }

    // ------------------------------------------------------------------------
    // USB
    // ------------------------------------------------------------------------

    private val rescanRunnable = Runnable { rescan() }

    private fun scheduleRescan(ms: Long) {
        handler.removeCallbacks(rescanRunnable)
        handler.postDelayed(rescanRunnable, ms)
    }

    private fun rescan() {
        handler.removeCallbacks(rescanRunnable)
        worker.execute {
            val result = try {
                MediaScanner.scan(this)
            } catch (t: Throwable) {
                Log.e(TAG, "Error buscando archivos", t)
                null
            }
            handler.post {
                if (!started) return@post
                if (result != null) onScanResult(result) else scheduleRescan(5000)
            }
        }
    }

    private fun isRunning() = cur >= 0 && items.isNotEmpty()

    private fun onScanResult(result: ScanResult) {
        lastScan = result
        if (studioWantsScreen()) return
        if (result.signature == signature && isRunning() && source == "usb") return
        signature = result.signature
        startToken++
        stopPlayback()
        scanned = result.items
        if (scanned.isEmpty()) {
            source = ""
            showWaiting(result)
            scheduleRescan(5000)
            updateStatus()
            return
        }
        startPlayback()
    }

    private fun startPlayback() {
        startToken++
        source = "usb"
        appliedList = null
        beginList(if (shuffle) scanned.shuffled() else scanned, null, 0L)
    }

    private fun showWaiting(result: ScanResult) {
        val sb = StringBuilder()
        sb.append("BUCLE TV\n\n")
        val st = studioState
        when {
            studio.connected && st != null ->
                sb.append("Conectada al estudio como ${st.nombre}.\nEsperando una lista desde el panel del ordenador.\n\n")
            studio.connected -> sb.append("Conectada al estudio.\n\n")
            else -> sb.append("Buscando el ordenador del estudio…\n\n")
        }
        sb.append("También puedes conectar un pendrive USB con vídeos o imágenes\n")
        sb.append("(si los pones en una carpeta llamada BUCLE, se usará solo esa carpeta).\n\n")
        if (!hasStoragePermission()) {
            sb.append("FALTA EL PERMISO para leer el USB.\nActívalo en Ajustes > Aplicaciones > Bucle TV > Permisos.\n\n")
        }
        if (result.roots.isNotEmpty()) {
            sb.append("USB detectado: ")
            sb.append(result.roots.joinToString(", ") { it.absolutePath })
            sb.append(" (sin vídeos ni imágenes)\n\n")
        }
        sb.append("Pulsa OK en el mando para ver las opciones.")
        messageView.text = sb.toString()
        messageView.visibility = View.VISIBLE
    }

    private val storageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (source == "estudio") return
            if (intent.action == Intent.ACTION_MEDIA_MOUNTED) {
                scheduleRescan(1500)
                return
            }
            val volName = intent.data?.path?.let { File(it).name }
            val affected = volName.isNullOrEmpty() ||
                    items.any { it.file.absolutePath.contains("/$volName/") }
            if (affected && isRunning()) {
                stopPlayback()
                signature = null
                messageView.text = getString(R.string.buscando)
                messageView.visibility = View.VISIBLE
            }
            scheduleRescan(1500)
        }
    }

    private fun registerStorageReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(storageReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(storageReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterStorageReceiver() {
        if (!receiverRegistered) return
        try {
            unregisterReceiver(storageReceiver)
        } catch (e: Exception) {
        }
        receiverRegistered = false
    }

    // ------------------------------------------------------------------------
    // Arranque de una lista (con o sin hora sincronizada)
    // ------------------------------------------------------------------------

    private fun serverNow(): Long = studio.serverNow()

    /** Lista del estudio: mide la duración de los vídeos y arranca a la hora indicada. */
    private fun startList(list: List<MediaFile>, syncStartMs: Long) {
        val token = ++startToken
        val defaultImageMs = imageDurationMs
        worker.execute {
            val durs = LongArray(list.size) { i ->
                val m = list[i]
                if (m.isVideo) videoDurationMs(m.file) else if (m.imageMs > 0) m.imageMs else defaultImageMs
            }
            handler.post {
                if (token != startToken || !started) return@post
                val wait = if (syncStartMs > 0) syncStartMs - serverNow() - SWITCH_LEAD_MS else 0L
                if (wait > 0) {
                    handler.postDelayed({
                        if (token == startToken && started) beginList(list, durs, syncStartMs)
                    }, wait)
                } else {
                    beginList(list, durs, syncStartMs)
                }
            }
        }
    }

    private fun videoDurationMs(f: File): Long {
        val key = f.absolutePath + ":" + f.length()
        durationCache[key]?.let { return it }
        val r = MediaMetadataRetriever()
        val d = try {
            r.setDataSource(f.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            try { r.release() } catch (e: Exception) { }
        }
        if (d > 0) durationCache[key] = d
        return d
    }

    private fun beginList(list: List<MediaFile>, durs: LongArray?, syncStartMs: Long) {
        stopPlayback()
        items = list
        if (items.isEmpty()) return

        val videos = ArrayList<MediaItem>()
        val videoItems = ArrayList<Int>()
        videoIndexOfItem = IntArray(items.size) { -1 }
        items.forEachIndexed { i, m ->
            if (m.isVideo) {
                videoIndexOfItem[i] = videos.size
                videoItems.add(i)
                videos.add(MediaItem.fromUri(Uri.fromFile(m.file)))
            }
        }
        itemOfVideo = videoItems.toIntArray()
        consecutiveErrors = 0
        messageView.visibility = View.GONE

        if (videos.isNotEmpty()) {
            val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
            val p = ExoPlayer.Builder(this, renderers).build()
            p.addListener(playerListener)
            p.repeatMode = Player.REPEAT_MODE_ALL
            p.volume = if (sound) 1f else 0f
            p.setMediaItems(videos)
            p.playWhenReady = false
            p.prepare()
            playerView.player = p
            player = p
            parkedItem = itemOfVideo[0]
        }

        // Que la limpieza de descargas no borre lo que está en pantalla
        studio.inUse = if (source == "estudio") items.map { it.file.name }.toSet() else emptySet()

        // Tabla de tiempos para la sincronización
        syncStart = syncStartMs
        if (durs != null && durs.size == items.size && durs.all { it > 0 }) {
            itemOffset = LongArray(durs.size)
            var acc = 0L
            for (k in durs.indices) {
                itemOffset[k] = acc
                acc += durs[k]
            }
            cycleLen = acc
        } else {
            itemOffset = LongArray(0)
            cycleLen = 0L
        }
        driftEnabled = syncStart > 0 && cycleLen > 0
        lastDrift = null

        if (syncStart <= 0) {
            goTo(0)
            return
        }
        val delay = syncStart - serverNow()
        if (delay > 0) {
            // Todo listo; esperamos a la hora exacta
            if (!items[0].isVideo) preloadImage(0)
            val gen = generation
            handler.postDelayed({ if (gen == generation) goTo(0) }, delay)
            updateStatus()
        } else if (cycleLen > 0) {
            // Llegamos tarde (reconexión, reinicio…): entramos en el punto que toca
            val pos = (-delay) % cycleLen
            var k = 0
            while (k < items.size - 1 && itemOffset[k + 1] <= pos) k++
            goTo(k, pos - itemOffset[k])
        } else {
            goTo(0)
        }
    }

    private fun stopPlayback() {
        generation++
        handler.removeCallbacks(advanceRunnable)
        handler.removeCallbacks(parkRunnable)
        handler.removeCallbacks(resetSpeedRunnable)
        player?.let {
            it.removeListener(playerListener)
            it.release()
        }
        player = null
        playerView.player = null
        cur = -1
        parkedItem = -1
        preloadedIndex = -1
        preloadedBitmap = null
        imageLayer.animate().cancel()
        imageLayer.alpha = 0f
        imageLayer.visibility = View.INVISIBLE
        layerShown = false
        clearImages()
    }

    // ------------------------------------------------------------------------
    // Reproducción
    // ------------------------------------------------------------------------

    private fun next(i: Int) = (i + 1) % items.size
    private fun prev(i: Int) = (i - 1 + items.size) % items.size

    private fun imageDurOf(i: Int): Long {
        val ms = items[i].imageMs
        return if (ms > 0) ms else imageDurationMs
    }

    private fun goTo(i: Int, startAt: Long = 0L) {
        if (items.isEmpty()) return
        handler.removeCallbacks(advanceRunnable)
        handler.removeCallbacks(parkRunnable)
        if (items[i].isVideo) playVideo(i, startAt) else showImage(i, startAt)
        updateStatus()
    }

    private val advanceRunnable = Runnable { if (cur >= 0) goTo(next(cur)) }
    private val parkRunnable = Runnable { parkNextVideo() }
    private val resetSpeedRunnable = Runnable { player?.setPlaybackSpeed(1f) }

    private fun playVideo(i: Int, startAt: Long) {
        val p = player ?: return
        cur = i
        if (parkedItem != i || startAt > 0) p.seekTo(videoIndexOfItem[i], startAt)
        parkedItem = -1
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        updatePauseFlag()
        p.playWhenReady = true
        if (layerShown) hideLayer()
        if (startAt == 0L) correctVideoDrift(i)
    }

    /** Si después del vídeo actual va una imagen, que el vídeo se quede quieto en su último fotograma. */
    private fun updatePauseFlag() {
        val p = player ?: return
        if (cur < 0) return
        val n = next(cur)
        val nextIsImage = !items[n].isVideo
        p.pauseAtEndOfMediaItems = nextIsImage
        if (nextIsImage) preloadImage(n)
    }

    /** Mientras se ve una imagen, deja el siguiente vídeo cargado y parado en su primer fotograma. */
    private fun parkNextVideo() {
        val p = player ?: return
        if (cur < 0 || items[cur].isVideo) return
        var j = next(cur)
        var guard = 0
        while (!items[j].isVideo && guard++ < items.size) j = next(j)
        if (!items[j].isVideo || parkedItem == j) return
        p.playWhenReady = false
        p.setPlaybackSpeed(1f)
        p.seekTo(videoIndexOfItem[j], 0L)
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        parkedItem = j
    }

    private fun showImage(i: Int, startAt: Long) {
        cur = i
        player?.let { if (it.playWhenReady) it.playWhenReady = false }

        val ready = if (preloadedIndex == i) preloadedBitmap else null
        if (ready != null) {
            preloadedIndex = -1
            preloadedBitmap = null
            onImageReady(i, ready, startAt)
            return
        }
        if (preloadedIndex == i) preloadedIndex = -1
        val gen = generation
        val file = items[i].file
        worker.execute {
            val bmp = ImageLoader.decode(file, screenW, screenH)
            handler.post {
                if (gen != generation || cur != i) return@post
                if (bmp == null) onItemFailed(i) else onImageReady(i, bmp, startAt)
            }
        }
    }

    private fun onImageReady(i: Int, bmp: Bitmap, startAt: Long) {
        consecutiveErrors = 0
        displayBitmap(bmp)
        if (items.size > 1) {
            val full = imageDurOf(i)
            var dur = full - startAt
            if (startAt == 0L) {
                val d = driftAt(i)
                if (d != null) {
                    lastDrift = d
                    dur -= d          // si vamos tarde, la imagen dura un poco menos (y al revés)
                }
            }
            dur = dur.coerceIn(300L, maxOf(300L, full * 2))
            handler.postDelayed(advanceRunnable, dur)
            handler.postDelayed(parkRunnable, minOf(transitionMs + 150, dur / 2))
            val n = next(i)
            if (!items[n].isVideo) preloadImage(n)
        }
        updateStatus()
    }

    private fun preloadImage(n: Int) {
        if (preloadedIndex == n) return
        preloadedIndex = n
        preloadedBitmap = null
        val gen = generation
        val file = items[n].file
        worker.execute {
            val bmp = ImageLoader.decode(file, screenW, screenH)
            handler.post {
                if (gen == generation && preloadedIndex == n) preloadedBitmap = bmp
            }
        }
    }

    // ---- Sincronización ----

    /** Cuánto vamos tarde (+) o adelantados (−) respecto a la hora prevista del elemento i. */
    private fun driftAt(i: Int): Long? {
        if (!driftEnabled || cycleLen <= 0 || i !in itemOffset.indices) return null
        val rel = serverNow() - syncStart - itemOffset[i]
        val c = Math.round(rel.toDouble() / cycleLen)
        val d = rel - c * cycleLen
        return if (abs(d) < minOf(5000L, cycleLen / 2)) d else null
    }

    private fun correctVideoDrift(i: Int) {
        val p = player ?: return
        handler.removeCallbacks(resetSpeedRunnable)
        val d = driftAt(i)
        if (d == null) {
            p.setPlaybackSpeed(1f)
            return
        }
        lastDrift = d
        val ad = abs(d)
        when {
            ad < 40 -> p.setPlaybackSpeed(1f)
            ad <= 1500 -> {
                // Corrección suave: un 3 % más rápido o más lento durante unos segundos
                p.setPlaybackSpeed(if (d > 0) 1.03f else 0.97f)
                handler.postDelayed(resetSpeedRunnable, (ad / 0.03).toLong())
            }
            d > 0 -> {
                p.setPlaybackSpeed(1f)
                p.seekTo(videoIndexOfItem[i], p.currentPosition + d)
            }
            else -> {
                p.setPlaybackSpeed(1f)
                p.playWhenReady = false
                val gen = generation
                handler.postDelayed({
                    if (gen == generation && cur == i) player?.playWhenReady = true
                }, -d)
            }
        }
        updateStatus()
    }

    private fun onItemFailed(i: Int) {
        consecutiveErrors++
        Log.w(TAG, "No se pudo abrir: ${items.getOrNull(i)?.file}")
        if (consecutiveErrors >= maxOf(items.size, 3)) {
            startToken++
            stopPlayback()
            signature = null
            appliedKey = null
            messageView.text = "No se ha podido reproducir ningún archivo.\n\n" +
                    "Comprueba que son vídeos MP4 / MKV o imágenes JPG / PNG.\n" +
                    "Se volverá a intentar en unos segundos…"
            messageView.visibility = View.VISIBLE
            handler.postDelayed({ if (started) refreshSource() }, 15000)
            return
        }
        val gen = generation
        handler.post {
            if (gen == generation && items.isNotEmpty()) goTo(next(i))
        }
    }

    private val playerListener = object : Player.Listener {

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
            ) return
            if (cur < 0 || !items[cur].isVideo) return
            val p = player ?: return
            val idx = p.currentMediaItemIndex
            if (idx in itemOfVideo.indices) cur = itemOfVideo[idx]
            updatePauseFlag()
            correctVideoDrift(cur)
            updateStatus()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                if (cur >= 0) goTo(next(cur))
            }
        }

        override fun onRenderedFirstFrame() {
            consecutiveErrors = 0
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Error de reproducción", error)
            val p = player ?: return
            val idx = p.currentMediaItemIndex
            val failed = if (idx in itemOfVideo.indices) itemOfVideo[idx] else maxOf(cur, 0)
            parkedItem = -1
            if (cur >= 0 && !items[cur].isVideo) return
            onItemFailed(failed)
        }
    }

    // ------------------------------------------------------------------------
    // Capa de imágenes y fundidos
    // ------------------------------------------------------------------------

    private fun displayBitmap(bmp: Bitmap) {
        val incoming = if (frontIsA) imageB else imageA
        val outgoing = if (frontIsA) imageA else imageB
        frontIsA = !frontIsA
        val t = transitionMs

        incoming.animate().cancel()
        outgoing.animate().cancel()
        incoming.setImageBitmap(bmp)
        incoming.bringToFront()

        if (layerShown && t > 0) {
            outgoing.alpha = 1f
            incoming.alpha = 0f
            incoming.visibility = View.VISIBLE
            incoming.animate().alpha(1f).setDuration(t).withEndAction {
                outgoing.visibility = View.INVISIBLE
                outgoing.setImageDrawable(null)
            }.start()
        } else {
            incoming.alpha = 1f
            incoming.visibility = View.VISIBLE
            outgoing.visibility = View.INVISIBLE
            outgoing.setImageDrawable(null)
            showLayer()
        }
    }

    private fun showLayer() {
        layerShown = true
        imageLayer.animate().cancel()
        imageLayer.visibility = View.VISIBLE
        val t = transitionMs
        if (t <= 0) {
            imageLayer.alpha = 1f
            return
        }
        imageLayer.animate().alpha(1f).setDuration(t).start()
    }

    private fun hideLayer() {
        layerShown = false
        imageLayer.animate().cancel()
        val t = transitionMs
        if (t <= 0) {
            imageLayer.alpha = 0f
            imageLayer.visibility = View.INVISIBLE
            clearImages()
            return
        }
        imageLayer.animate().alpha(0f).setDuration(t).withEndAction {
            imageLayer.visibility = View.INVISIBLE
            clearImages()
        }.start()
    }

    private fun clearImages() {
        imageA.animate().cancel()
        imageB.animate().cancel()
        imageA.setImageDrawable(null)
        imageB.setImageDrawable(null)
        imageA.visibility = View.INVISIBLE
        imageB.visibility = View.INVISIBLE
    }

    // ------------------------------------------------------------------------
    // Mando a distancia
    // ------------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val firstDown = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (firstDown) step(+1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (firstDown) step(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                if (firstDown) openMenu()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (firstDown) {
                    val now = System.currentTimeMillis()
                    if (now - lastBackPress < 2500) {
                        finish()
                    } else {
                        lastBackPress = now
                        showToast("Pulsa ATRÁS otra vez para salir", 2500)
                    }
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun step(direction: Int) {
        if (!isRunning()) return
        driftEnabled = false
        goTo(if (direction > 0) next(cur) else prev(cur))
        if (cur >= 0) showToast("${cur + 1}/${items.size} · ${items[cur].file.name}", 2500)
    }

    private val hideToastRunnable = Runnable { toastView.visibility = View.GONE }

    private fun showToast(text: String, ms: Long) {
        toastView.text = text
        toastView.visibility = View.VISIBLE
        handler.removeCallbacks(hideToastRunnable)
        handler.postDelayed(hideToastRunnable, ms)
    }

    // ------------------------------------------------------------------------
    // Menú de opciones (botón OK)
    // ------------------------------------------------------------------------

    private fun studioLabel(): String = when {
        studio.connected -> "Estudio: conectada (${studio.server?.removePrefix("http://") ?: ""})"
        prefs.getString("servidor_manual", "").isNullOrBlank() -> "Estudio: buscando el ordenador…"
        else -> "Estudio: sin conexión con ${prefs.getString("servidor_manual", "")}"
    }

    private fun openMenu() {
        val title = if (isRunning()) "${cur + 1}/${items.size} · ${items[cur].file.name}" else getString(R.string.app_name)
        val entries = ArrayList<Pair<String, () -> Unit>>()
        entries.add(studioLabel() to { studioDialog() })
        entries.add("Duración de las imágenes del USB: ${imageDurationMs / 1000} s" to { chooseImageDuration() })
        entries.add("Transición: ${transitionLabel(transitionMs.toInt())}" to { chooseTransition() })
        entries.add("Orden del USB: ${if (shuffle) "aleatorio" else "por nombre"}" to {
            prefs.edit().putBoolean("aleatorio", !shuffle).apply()
            if (source == "usb" && scanned.isNotEmpty()) startPlayback()
        })
        entries.add("Sonido: ${if (sound) "sí" else "no"}" to {
            prefs.edit().putBoolean("sonido", !sound).apply()
            player?.volume = if (sound) 1f else 0f
        })
        entries.add("Arranque automático al encender: ${if (autostart) "sí" else "no"}" to {
            val on = !autostart
            prefs.edit().putBoolean("autoarranque", on).apply()
            if (on) askOverlayPermission(force = true)
            else showToast("Bucle TV ya no se abrirá sola al encender", 3000)
        })
        entries.add("Volver a leer el USB" to {
            if (source != "estudio") {
                startToken++
                stopPlayback()
                signature = null
                messageView.text = getString(R.string.buscando)
                messageView.visibility = View.VISIBLE
            }
            rescan()
        })
        entries.add("Información" to { showInfo() })
        entries.add("Salir de Bucle TV" to { finish() })

        AlertDialog.Builder(this, dialogTheme)
            .setTitle(title)
            .setItems(entries.map { it.first }.toTypedArray()) { _, which -> entries[which].second() }
            .show()
    }

    private fun studioDialog() {
        val st = studioState
        val manual = prefs.getString("servidor_manual", "").orEmpty()
        val msg = buildString {
            append(if (studio.connected) "Conectada a ${studio.server}\n" else "Sin conexión con el ordenador del estudio.\n")
            if (st != null) append("Nombre en el panel: ${st.nombre}\n")
            append("Búsqueda: ${if (manual.isBlank()) "automática" else "IP fija $manual"}\n")
            if (studio.connected && studio.clockRtt >= 0) append("Latencia de red: ${studio.clockRtt} ms\n")
            if (!studio.connected && studio.lastError != null) append("Último error: ${studio.lastError}\n")
            append("\nSi la TV no encuentra el ordenador sola, escribe su IP (aparece arriba en el panel).")
        }
        AlertDialog.Builder(this, dialogTheme)
            .setTitle("Conexión con el estudio")
            .setMessage(msg)
            .setPositiveButton("Escribir IP") { _, _ -> manualIpDialog() }
            .setNeutralButton("Automático") { _, _ ->
                prefs.edit().putString("servidor_manual", "").apply()
                studio.reconnect()
                showToast("Buscando el ordenador automáticamente…", 3000)
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun manualIpDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        input.hint = "192.168.1.20"
        input.setText(prefs.getString("servidor_manual", ""))
        AlertDialog.Builder(this, dialogTheme)
            .setTitle("IP del ordenador del estudio")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                prefs.edit().putString("servidor_manual", input.text.toString().trim()).apply()
                studio.reconnect()
                showToast("Conectando…", 3000)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun chooseImageDuration() {
        val values = intArrayOf(3, 5, 8, 10, 15, 20, 30, 45, 60, 120)
        val labels = values.map { "$it segundos" }.toTypedArray()
        val current = values.indexOf(prefs.getInt("imagen_seg", 10))
        AlertDialog.Builder(this, dialogTheme)
            .setTitle("Duración de las imágenes del USB")
            .setSingleChoiceItems(labels, current) { d, which ->
                prefs.edit().putInt("imagen_seg", values[which]).apply()
                d.dismiss()
            }
            .show()
    }

    private fun chooseTransition() {
        val values = intArrayOf(0, 300, 500, 1000, 2000)
        val labels = values.map { transitionLabel(it) }.toTypedArray()
        val current = values.indexOf(prefs.getInt("transicion_ms", 500))
        AlertDialog.Builder(this, dialogTheme)
            .setTitle("Transición con las imágenes")
            .setSingleChoiceItems(labels, current) { d, which ->
                prefs.edit().putInt("transicion_ms", values[which]).apply()
                d.dismiss()
            }
            .show()
    }

    private fun transitionLabel(ms: Int) = when (ms) {
        0 -> "corte directo"
        300 -> "fundido 0,3 s"
        500 -> "fundido 0,5 s"
        1000 -> "fundido 1 s"
        2000 -> "fundido 2 s"
        else -> "fundido $ms ms"
    }

    private fun showInfo() {
        val s = lastScan
        val text = buildString {
            append("Origen: ${when (source) { "estudio" -> "estudio"; "usb" -> "USB"; else -> "—" }}\n")
            if (isRunning()) append("Mostrando: ${cur + 1}/${items.size} · ${items[cur].file.name}\n")
            if (appliedList != null) append("Lista del estudio: $appliedList\n")
            append("\n${studioLabel()}\n")
            append("Archivos del estudio en la TV: ${studio.mediaDir.listFiles()?.count { !it.name.endsWith(".part") } ?: 0}\n")
            append("Espacio libre: ${studio.mediaDir.usableSpace / 1_000_000} MB\n")
            val roots = s?.roots.orEmpty()
            append("\nUSB: ${if (roots.isEmpty()) "ninguno" else roots.joinToString { it.absolutePath }}\n")
            append("\nPantalla: ${screenW}×${screenH} · Android ${Build.VERSION.RELEASE} · app ${StudioClient.APP_VERSION}\n")
            append("Arranque automático: ${if (!autostart) "desactivado" else if (overlayGranted()) "activado" else "falta permiso"}\n")
            append("Permiso de lectura USB: ${if (hasStoragePermission()) "sí" else "NO"}")
        }
        simpleDialog("Información", text)
    }

    private fun simpleDialog(title: String, message: String) {
        AlertDialog.Builder(this, dialogTheme)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Cerrar", null)
            .show()
    }
}
