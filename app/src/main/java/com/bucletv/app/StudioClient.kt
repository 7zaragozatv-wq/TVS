package com.bucletv.app

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

data class StudioItem(val name: String, val isVideo: Boolean, val ms: Long)

data class StudioState(
    val version: Long,
    val num: Int,
    val nombre: String,
    val modo: String,                 // "lista" | "negro" | "nada"
    val lista: String?,
    val items: List<StudioItem>,
    val inicio: Long,                 // hora del servidor (ms) a la que empieza la lista
    val salto: Int,                   // contador de "siguiente"
    val archivos: Map<String, Long>,  // todo lo que la TV debe tener descargado
    val raw: String
) {
    companion object {
        fun parse(raw: String): StudioState {
            val o = JSONObject(raw)
            val items = ArrayList<StudioItem>()
            val ia = o.optJSONArray("items") ?: JSONArray()
            for (k in 0 until ia.length()) {
                val it = ia.getJSONObject(k)
                items.add(StudioItem(it.getString("f"), it.optString("t") == "v", it.optLong("ms", 0)))
            }
            val files = LinkedHashMap<String, Long>()
            val fa = o.optJSONArray("archivos") ?: JSONArray()
            for (k in 0 until fa.length()) {
                val f = fa.getJSONObject(k)
                files[f.getString("f")] = f.getLong("s")
            }
            return StudioState(
                version = o.optLong("v"),
                num = o.optInt("num"),
                nombre = o.optString("nombre"),
                modo = o.optString("modo", "nada"),
                lista = if (o.isNull("lista")) null else o.optString("lista"),
                items = items,
                inicio = o.optLong("inicio"),
                salto = o.optInt("salto"),
                archivos = files,
                raw = raw
            )
        }
    }
}

/**
 * Conexión con el programa "Bucle TV · Estudio" del ordenador de realización.
 *  - Lo busca solo en la red (o usa la IP escrita a mano en el menú).
 *  - Espera órdenes (petición larga que el servidor responde cuando algo cambia).
 *  - Descarga a la memoria de la TV todos los archivos que hacen falta.
 *  - Sincroniza el reloj con el del ordenador para arrancar todas las TVs a la vez.
 *  - Cada 2 s le cuenta al panel qué está mostrando.
 */
class StudioClient(context: Context, private val listener: Listener) {

    interface Listener {
        fun onStudioState(state: StudioState)
        fun onStudioFilesChanged()
        fun onStudioConnection(connected: Boolean)
        fun onStudioClockChanged()
    }

    companion object {
        private const val TAG = "BucleTV-Estudio"
        private const val UDP_PORT = 47800
        const val APP_VERSION = "1.1"
    }

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("ajustes", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    val mediaDir: File = File(app.getExternalFilesDir(null) ?: app.filesDir, "estudio").apply { mkdirs() }
    val tvId: String = prefs.getString("tv_id", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("tv_id", it).apply()
    }

    @Volatile var server: String? = null; private set
    @Volatile var connected = false; private set
    // hora del servidor = SystemClock.elapsedRealtime() + clockOffset
    // (no le afectan los cambios de hora de la TV). Sin servidor: reloj propio de la TV.
    @Volatile var clockOffset = System.currentTimeMillis() - SystemClock.elapsedRealtime(); private set
    @Volatile var clockRtt = -1L; private set
    @Volatile var lastError: String? = null; private set

    /** Archivos que se están reproduciendo ahora: no se borran aunque ya no estén en ninguna lista. */
    @Volatile var inUse: Set<String> = emptySet()

    /** Lo que la TV está mostrando; lo rellena la actividad. */
    @Volatile var playStatus: Map<String, Any?> = emptyMap()

    // estado de descargas (para el panel)
    @Volatile private var dlDone = 0
    @Volatile private var dlTotal = 0
    @Volatile private var dlFile: String? = null
    @Volatile private var dlPct = 0
    @Volatile private var dlError: String? = null

    @Volatile private var running = false
    @Volatile private var latest: StudioState? = null
    private val dlLock = Object()
    private var threads = ArrayList<Thread>()
    @Volatile private var pollConn: HttpURLConnection? = null
    @Volatile private var dlConn: HttpURLConnection? = null

    fun localFile(name: String) = File(mediaDir, name)
    fun isCached(name: String, size: Long): Boolean {
        val f = localFile(name)
        return f.exists() && f.length() == size
    }
    fun serverNow(): Long = SystemClock.elapsedRealtime() + clockOffset

    @Volatile private var session = 0
    private fun alive(s: Int) = running && s == session

    fun start() {
        if (running) return
        running = true
        val s = ++session
        threads = arrayListOf(
            Thread({ connectionLoop(s) }, "estudio-conexion"),
            Thread({ reportLoop(s) }, "estudio-informe"),
            Thread({ downloadLoop(s) }, "estudio-descargas")
        )
        threads.forEach { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        try { pollConn?.disconnect() } catch (e: Exception) { }
        try { dlConn?.disconnect() } catch (e: Exception) { }
        threads.forEach { it.interrupt() }
        synchronized(dlLock) { dlLock.notifyAll() }
        setConnected(false)
    }

    /** Se cambió la IP manual en el menú: volver a buscar el servidor. */
    fun reconnect() {
        server = null
        try { pollConn?.disconnect() } catch (e: Exception) { }
    }

    /** Estado guardado de la última sesión (para seguir funcionando sin red). */
    fun cachedState(): StudioState? {
        val raw = prefs.getString("estudio_estado", null) ?: return null
        return try { StudioState.parse(raw) } catch (e: Exception) { null }
    }

    fun forgetStudio() {
        prefs.edit().remove("estudio_estado").apply()
        latest = null
    }

    private fun setConnected(c: Boolean) {
        if (connected == c) return
        connected = c
        main.post { listener.onStudioConnection(c) }
    }

    // ------------------------------------------------------------------
    // Conexión y órdenes
    // ------------------------------------------------------------------

    private fun connectionLoop(s: Int) {
        var version = 0L
        while (alive(s)) {
            try {
                val base = server ?: findServer()
                if (base == null) {
                    setConnected(false)
                    sleep(4000)
                    continue
                }
                server = base
                if (clockRtt < 0) syncClock(base)
                if (!alive(s)) break

                val url = "$base/api/tv/state?id=${enc(tvId)}&v=$version&modelo=${enc(Build.MANUFACTURER + " " + Build.MODEL)}&app=$APP_VERSION"
                val raw = httpGet(url, readTimeout = 35000, keepRef = alive(s))
                if (!alive(s)) break
                val st = StudioState.parse(raw)
                setConnected(true)
                lastError = null
                if (st.version != version) {
                    version = st.version
                    latest = st
                    prefs.edit().putString("estudio_estado", raw).apply()
                    synchronized(dlLock) { dlLock.notifyAll() }
                    main.post { listener.onStudioState(st) }
                }
            } catch (e: Exception) {
                if (!alive(s)) break
                Log.w(TAG, "Conexión: ${e.message}")
                lastError = e.message
                setConnected(false)
                server = null
                clockRtt = -1
                version = 0
                sleep(2000)
            }
        }
    }

    private fun findServer(): String? {
        val manual = prefs.getString("servidor_manual", "")?.trim().orEmpty()
            .removePrefix("http://").removePrefix("https://").trimEnd('/')
        if (manual.isNotEmpty()) {
            val withPort = if (manual.contains(":")) manual else "$manual:8080"
            return "http://$withPort"
        }
        return discover()
    }

    /** Busca el ordenador en la red con un mensaje UDP de difusión. */
    private fun discover(): String? {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.broadcast = true
            socket.soTimeout = 1500
            val msg = "BUCLETV?".toByteArray()
            val targets = LinkedHashSet<InetAddress>()
            targets.add(InetAddress.getByName("255.255.255.255"))
            try {
                for (ni in NetworkInterface.getNetworkInterfaces()) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (ia in ni.interfaceAddresses) {
                        if (ia.address is Inet4Address) ia.broadcast?.let { targets.add(it) }
                    }
                }
            } catch (e: Exception) { }
            for (attempt in 0 until 2) {
                for (t in targets) {
                    try { socket.send(DatagramPacket(msg, msg.size, t, UDP_PORT)) } catch (e: Exception) { }
                }
                val buf = ByteArray(256)
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(pkt)
                    val reply = String(pkt.data, 0, pkt.length)
                    if (reply.startsWith("BUCLETV:")) {
                        val port = reply.substringAfter(":").trim().toIntOrNull() ?: 8080
                        val ip = pkt.address.hostAddress
                        Log.i(TAG, "Servidor encontrado en $ip:$port")
                        return "http://$ip:$port"
                    }
                } catch (e: SocketTimeoutException) { }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Búsqueda: ${e.message}")
        } finally {
            socket?.close()
        }
        return null
    }

    /** Calcula la diferencia de reloj con el servidor (se queda con la medida más rápida). */
    private fun syncClock(base: String) {
        var bestRtt = Long.MAX_VALUE
        var bestOffset = clockOffset
        for (k in 0 until 7) {
            try {
                val t0 = SystemClock.elapsedRealtime()
                val raw = httpGet("$base/api/time", readTimeout = 3000)
                val rtt = SystemClock.elapsedRealtime() - t0
                val ts = JSONObject(raw).getLong("t")
                if (rtt < bestRtt) {
                    bestRtt = rtt
                    bestOffset = ts - (t0 + rtt / 2)
                }
            } catch (e: Exception) { }
        }
        if (bestRtt != Long.MAX_VALUE) {
            val jump = kotlin.math.abs(bestOffset - clockOffset)
            clockOffset = bestOffset
            clockRtt = bestRtt
            // Si el reloj cambió bastante (primera conexión tras arrancar sin red), avisar
            if (jump > 300) main.post { listener.onStudioClockChanged() }
        }
    }

    // ------------------------------------------------------------------
    // Informe de estado (cada 2 s) y reloj (cada 30 s)
    // ------------------------------------------------------------------

    private fun reportLoop(s: Int) {
        var lastClock = 0L
        while (alive(s)) {
            val base = server
            if (base != null && connected) {
                try {
                    if (System.currentTimeMillis() - lastClock > 30000) {
                        syncClock(base)
                        lastClock = System.currentTimeMillis()
                    }
                    val o = JSONObject()
                    o.put("id", tvId)
                    o.put("version", APP_VERSION)
                    for ((k, v) in playStatus) o.put(k, v ?: JSONObject.NULL)
                    val d = JSONObject()
                    d.put("hechos", dlDone)
                    d.put("total", dlTotal)
                    d.put("archivo", dlFile ?: JSONObject.NULL)
                    d.put("pct", dlPct)
                    d.put("error", dlError ?: JSONObject.NULL)
                    o.put("descarga", d)
                    o.put("libre", mediaDir.usableSpace)
                    o.put("rtt", clockRtt)
                    httpPost("$base/api/tv/report", o.toString())
                } catch (e: Exception) {
                    Log.w(TAG, "Informe: ${e.message}")
                }
            }
            sleep(2000)
        }
    }

    // ------------------------------------------------------------------
    // Descargas
    // ------------------------------------------------------------------

    private fun downloadLoop(s: Int) {
        while (alive(s)) {
            val st = latest
            val base = server
            if (st == null || base == null) {
                waitForWork(3000)
                continue
            }
            val needed = st.archivos
            // Borrar lo que ya no se usa (y restos de descargas cortadas)
            val keep = inUse
            mediaDir.listFiles()?.forEach { f ->
                if (f.name.endsWith(".part") || (!needed.containsKey(f.name) && f.name !in keep)) f.delete()
            }
            // Primero los archivos de la lista que tiene que salir en pantalla
            val active = st.items.map { it.name }.toSet()
            val missing = needed.entries
                .filter { !isCached(it.key, it.value) }
                .sortedBy { if (it.key in active) 0 else 1 }
            dlTotal = needed.size
            dlDone = needed.size - missing.size
            var failed = false
            for ((name, size) in missing) {
                if (!alive(s) || latest !== st) break
                when (download(base, name, size, s)) {
                    DL_OK -> {
                        dlDone++
                        main.post { listener.onStudioFilesChanged() }
                    }
                    DL_NO_SPACE -> failed = true          // seguimos con los demás (pueden caber)
                    else -> { failed = true; break }      // error de red: reintentar más tarde
                }
            }
            dlFile = null
            if (latest === st) {
                if (!failed) dlError = null
                waitForWork(if (failed) 5000 else 30000)
            }
        }
    }

    private val DL_OK = 0
    private val DL_NO_SPACE = 1
    private val DL_ERROR = 2

    private fun download(base: String, name: String, size: Long, s: Int): Int {
        val target = localFile(name)
        val part = File(mediaDir, "$name.part")
        if (mediaDir.usableSpace < size + 150L * 1024 * 1024) {
            dlError = "Sin espacio en la TV para $name"
            return DL_NO_SPACE
        }
        dlFile = name
        dlPct = 0
        var conn: HttpURLConnection? = null
        try {
            conn = URL("$base/media/${encPath(name)}").openConnection() as HttpURLConnection
            dlConn = conn
            conn.connectTimeout = 5000
            conn.readTimeout = 30000
            if (conn.responseCode != 200) throw RuntimeException("HTTP ${conn.responseCode}")
            conn.inputStream.use { input ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        if (size > 0) dlPct = (100 * total / size).toInt()
                        if (!alive(s)) throw RuntimeException("cancelado")
                    }
                    out.fd.sync()
                }
            }
            if (part.length() != size) throw RuntimeException("tamaño incorrecto")
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw RuntimeException("no se pudo guardar")
            return DL_OK
        } catch (e: Exception) {
            Log.w(TAG, "Descarga $name: ${e.message}")
            if (alive(s)) {
                dlError = "Error descargando $name"
                part.delete()
            }
            return DL_ERROR
        } finally {
            if (dlConn === conn) dlConn = null
            conn?.disconnect()
        }
    }

    private fun waitForWork(ms: Long) {
        synchronized(dlLock) {
            try { dlLock.wait(ms) } catch (e: InterruptedException) { }
        }
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    private fun httpGet(url: String, readTimeout: Int, keepRef: Boolean = false): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        if (keepRef) pollConn = conn
        try {
            conn.connectTimeout = 4000
            conn.readTimeout = readTimeout
            conn.useCaches = false
            val code = conn.responseCode
            if (code != 200) throw RuntimeException("HTTP $code")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            if (keepRef) pollConn = null
            conn.disconnect()
        }
    }

    private fun httpPost(url: String, body: String) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 4000
            conn.readTimeout = 5000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun encPath(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (e: InterruptedException) { }
    }
}
