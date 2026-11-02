package com.magames.tecladoia.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.vosk.Model
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

/**
 * Installs the free Vosk small Portuguese model (Apache 2.0, ~31 MB zip, ~50 MB unpacked)
 * into app-private storage. The "completo" build bundles the zip in assets
 * ([BUNDLED_ASSET]) and extracts it automatically on first launch; when the asset is
 * missing it falls back to downloading it from alphacephei.com.
 */
object VoskModelManager {
    private const val TAG = "VoskModel"
    const val MODEL_NAME = "vosk-model-small-pt-0.3"
    const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"
    const val BUNDLED_ASSET = "$MODEL_NAME.zip"

    sealed class State {
        object NotInstalled : State()
        data class Downloading(val percent: Int, val bytes: Long, val total: Long) : State()
        object Unpacking : State()
        data class Extracting(val percent: Int) : State()
        object Installed : State()
        data class Failed(val message: String) : State()
    }

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val listeners = LinkedHashSet<(State) -> Unit>()
    @Volatile private var state: State? = null
    @Volatile private var cancelRequested = false
    @Volatile private var model: Model? = null

    fun modelDir(context: Context) = File(context.filesDir, MODEL_NAME)
    private fun marker(context: Context) = File(modelDir(context), ".complete")

    fun isInstalled(context: Context) = marker(context).exists()

    fun state(context: Context): State {
        val s = state
        if (s is State.Downloading || s is State.Unpacking || s is State.Extracting || s is State.Failed) return s
        return if (isInstalled(context)) State.Installed else State.NotInstalled
    }

    fun addListener(l: (State) -> Unit) { listeners.add(l) }
    fun removeListener(l: (State) -> Unit) { listeners.remove(l) }

    private fun emit(s: State) {
        state = s
        main.post { listeners.toList().forEach { it(s) } }
    }

    fun cancel() { cancelRequested = true }

    fun isBusy(): Boolean {
        val s = state
        return s is State.Downloading || s is State.Unpacking || s is State.Extracting
    }

    @Volatile private var bundledCache: Boolean? = null

    /** True when this APK ships the model zip in assets (the "completo" build). */
    fun hasBundled(context: Context): Boolean {
        bundledCache?.let { return it }
        val has = try {
            context.assets.list("")?.contains(BUNDLED_ASSET) == true
        } catch (e: Throwable) { false }
        bundledCache = has
        return has
    }

    /**
     * Extracts the bundled model from assets if it's not installed yet. Safe to call often
     * (no-op when installed, busy, or not bundled). Returns true if an install is running/started.
     */
    fun ensureInstalledFromAssets(context: Context): Boolean {
        val app = context.applicationContext
        if (isInstalled(app) || !hasBundled(app)) return false
        synchronized(this) {
            if (isBusy()) return true
            emit(State.Extracting(0))
        }
        cancelRequested = false
        executor.execute {
            if (isInstalled(app)) { emit(State.Installed); return@execute }
            try {
                val total = try {
                    app.assets.openFd(BUNDLED_ASSET).use { it.length }
                } catch (e: Throwable) { -1L }
                val target = modelDir(app)
                target.deleteRecursively()
                app.assets.open(BUNDLED_ASSET).use { raw ->
                    val counting = CountingInputStream(raw) { read ->
                        val pct = if (total > 0) (read * 100 / total).toInt().coerceIn(0, 99) else 0
                        val cur = state
                        if (cur !is State.Extracting || cur.percent != pct) emit(State.Extracting(pct))
                    }
                    unzipStream(counting, app.filesDir)
                }
                val ok = File(target, "final.mdl").exists() || File(target, "am/final.mdl").exists()
                if (!ok) throw IllegalStateException("Modelo incompleto após extrair")
                marker(app).writeText("ok")
                emit(State.Installed)
            } catch (e: Throwable) {
                Log.e(TAG, "Bundled model extraction failed", e)
                modelDir(app).deleteRecursively()
                emit(State.Failed("Falha ao instalar modelo embutido: ${e.message ?: e.javaClass.simpleName}"))
            }
        }
        return true
    }

    private class CountingInputStream(
        private val inner: java.io.InputStream,
        private val onProgress: (Long) -> Unit,
    ) : java.io.InputStream() {
        private var count = 0L
        private var lastReport = 0L
        private fun add(n: Int) {
            if (n > 0) {
                count += n
                if (count - lastReport >= 256 * 1024) { lastReport = count; onProgress(count) }
            }
        }
        override fun read(): Int = inner.read().also { if (it >= 0) add(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { add(it) }
        override fun close() = inner.close()
    }

    fun download(context: Context) {
        val app = context.applicationContext
        val cur = state
        if (cur is State.Downloading || cur is State.Unpacking || cur is State.Extracting) return
        cancelRequested = false
        emit(State.Downloading(0, 0, 0))
        executor.execute {
            val zip = File(app.cacheDir, "$MODEL_NAME.zip")
            try {
                downloadTo(zip)
                emit(State.Unpacking)
                val target = modelDir(app)
                target.deleteRecursively()
                unzip(zip, app.filesDir)
                val ok = File(target, "final.mdl").exists() || File(target, "am/final.mdl").exists()
                if (!ok) {
                    throw IllegalStateException("Modelo incompleto após descompactar")
                }
                marker(app).writeText("ok")
                emit(State.Installed)
            } catch (e: Throwable) {
                Log.e(TAG, "Model download failed", e)
                modelDir(app).deleteRecursively()
                emit(if (cancelRequested) State.NotInstalled else State.Failed(e.message ?: e.javaClass.simpleName))
            } finally {
                zip.delete()
            }
        }
    }

    private fun downloadTo(file: File) {
        var url = URL(MODEL_URL)
        var conn: HttpURLConnection
        var redirects = 0
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            if (code in 300..399 && redirects < 5) {
                url = URL(url, conn.getHeaderField("Location"))
                conn.disconnect(); redirects++; continue
            }
            if (code != 200) throw IllegalStateException("HTTP $code")
            break
        }
        val total = conn.contentLengthLong
        var done = 0L
        var lastPct = -1
        conn.inputStream.use { input ->
            FileOutputStream(file).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    if (cancelRequested) throw InterruptedException("Cancelado")
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val pct = if (total > 0) (done * 100 / total).toInt() else 0
                    if (pct != lastPct) { lastPct = pct; emit(State.Downloading(pct, done, total)) }
                }
            }
        }
        conn.disconnect()
        if (total > 0 && done != total) throw IllegalStateException("Download incompleto")
    }

    private fun unzip(zip: File, destRoot: File) = unzipStream(zip.inputStream(), destRoot)

    private fun unzipStream(input: java.io.InputStream, destRoot: File) {
        val rootCanonical = destRoot.canonicalPath
        ZipInputStream(input.buffered(64 * 1024)).use { zis ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val e = zis.nextEntry ?: break
                val out = File(destRoot, e.name)
                if (!out.canonicalPath.startsWith(rootCanonical)) throw SecurityException("Zip inválido")
                if (e.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { fos ->
                        while (true) {
                            val n = zis.read(buf)
                            if (n < 0) break
                            fos.write(buf, 0, n)
                        }
                    }
                }
                zis.closeEntry()
            }
        }
    }

    fun delete(context: Context) {
        synchronized(this) { model?.close(); model = null }
        modelDir(context).deleteRecursively()
        emit(State.NotInstalled)
    }

    /** Loads (and caches) the model. Slow (~1-3 s): call off the main thread. */
    fun loadModel(context: Context): Model? = synchronized(this) {
        model?.let { return it }
        if (!isInstalled(context)) return null
        return try {
            Model(modelDir(context).absolutePath).also { model = it }
        } catch (e: Throwable) {
            Log.e(TAG, "Model load failed", e)
            null
        }
    }
}
