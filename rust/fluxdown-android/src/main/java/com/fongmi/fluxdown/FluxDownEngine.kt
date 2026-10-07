package com.fongmi.fluxdown

import com.fluxdown.bridge.FluxBridge
import com.fluxdown.core.host.HostEvent
import com.fluxdown.core.host.HostSession
import com.fluxdown.core.host.HostSignal
import com.fluxdown.core.model.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Small lifecycle/interop facade around FluxDown's Kotlin bridge.
 * All methods are non-blocking and safe to call from FongMi's Java code.
 */
object FluxDownEngine {
    interface Listener {
        fun onSnapshot(tasks: List<FluxTaskInfo>)
        fun onTaskChanged(task: FluxTaskInfo)
        fun onTaskDeleted(taskId: String)
        fun onEngineError(message: String)
    }

    interface Callback {
        fun onSuccess(value: String?)
        fun onError(message: String)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val tasks = ConcurrentHashMap<String, FluxTaskInfo>()

    @Volatile private var listener: Listener? = null
    @Volatile private var sessionOpening: Deferred<HostSession>? = null
    @Volatile private var signalJob: Job? = null
    private var dataDir: String? = null
    private var saveDir: String? = null

    @JvmStatic
    fun start(dataDir: String, saveDir: String, listener: Listener) {
        val opening = synchronized(lock) {
            val current = sessionOpening
            if (current != null) {
                check(this.dataDir == dataDir && this.saveDir == saveDir) {
                    "FluxDownEngine is already started with different directories"
                }
                this.listener = listener
                current
            } else {
                this.listener = listener
                this.dataDir = dataDir
                this.saveDir = saveDir
                scope.async { FluxBridge.openLocal(dataDir, saveDir, "android") }.also {
                    sessionOpening = it
                }
            }
        }
        scope.launch {
            try {
                collectSignalsOnce(opening.await())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                synchronized(lock) {
                    if (sessionOpening === opening) {
                        sessionOpening = null
                        this@FluxDownEngine.dataDir = null
                        this@FluxDownEngine.saveDir = null
                    }
                }
                reportError(error)
            }
        }
    }

    @JvmStatic
    fun create(
        url: String,
        fileName: String,
        saveDir: String,
        headers: Map<String, String>,
        callback: Callback,
    ) = command(callback) { session ->
        val normalized = headers.entries.associate { it.key to it.value }
        val request = JSONObject()
            .put("url", url)
            .put("fileName", fileName)
            .put("saveDir", saveDir)
            .put("segments", 0)
            .put("queueId", "")
            .put("startPaused", false)
            .put("cookies", header(normalized, "cookie"))
            .put("referrer", header(normalized, "referer"))
            .put("userAgent", header(normalized, "user-agent"))
            .put("proxyUrl", "")
            .put("checksum", "")
            .put("ignoreTlsErrors", false)
            .put("headers", JSONObject(normalized))
            .put("httpUser", "")
            .put("httpPassword", "")
            .put("saveSiteAuth", false)
        val params = JSONObject().put("request", request).put("unattended", true)
        JSONObject(session.call("daemon.task.create", params.toString())).getString("taskId")
    }

    @JvmStatic fun pause(taskId: String, callback: Callback) = command(callback) { it.pause(taskId); null }
    @JvmStatic fun resume(taskId: String, callback: Callback) = command(callback) { it.resume(taskId); null }
    @JvmStatic fun delete(taskId: String, deleteFiles: Boolean, callback: Callback) =
        command(callback) { it.delete(taskId, deleteFiles); null }

    @JvmStatic
    fun offlineMediaWarning(taskId: String, callback: Callback) = command(callback) { session ->
        var warning = ""
        var beforeId: Long? = null
        var truncated = false
        do {
            val params = JSONObject().put("taskId", taskId).put("limit", 500)
            beforeId?.let { params.put("beforeId", it) }
            val page = JSONObject(session.call("daemon.task.activity", params.toString()))
            val entries = page.optJSONArray("entries")
                ?: throw IllegalStateException("FluxDown returned invalid task activity")
            truncated = truncated || page.optBoolean("truncated")
            for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index) ?: continue
                if (!entry.optString("kind").equals("warning", ignoreCase = true)) continue
                val message = entry.optString("message")
                val lower = message.lowercase()
                if ((message.contains("音频") && (lower.contains("ffmpeg") || message.contains("没有声音"))) ||
                    (lower.contains("audio") && lower.contains("ffmpeg"))) {
                    warning = message
                }
            }
            if (warning.isNotEmpty() || !page.optBoolean("hasMore")) break
            if (entries.length() == 0) throw IllegalStateException("FluxDown task activity pagination stalled")
            val nextBeforeId = entries.getJSONObject(0).getLong("id")
            if (nextBeforeId == beforeId) throw IllegalStateException("FluxDown task activity cursor did not advance")
            beforeId = nextBeforeId
        } while (true)
        if (warning.isEmpty() && truncated) {
            warning = "FluxDown task activity was truncated; audio completeness cannot be proven"
        }
        warning
    }

    @JvmStatic
    fun stop() {
        val current = synchronized(lock) {
            signalJob?.cancel()
            signalJob = null
            val value = sessionOpening
            sessionOpening = null
            dataDir = null
            saveDir = null
            listener = null
            value
        }
        current?.cancel()
        scope.launch {
            runCatching { current?.await()?.close() }
            runCatching { FluxBridge.shutdownLocal() }
            tasks.clear()
        }
    }

    private fun command(callback: Callback, block: suspend (HostSession) -> String?) {
        scope.launch {
            try {
                callback.onSuccess(block(awaitSession()))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                callback.onError(message(error))
            }
        }
    }

    private suspend fun awaitSession(): HostSession {
        val opening = sessionOpening ?: throw IllegalStateException("FluxDownEngine.start must be called first")
        return opening.await()
    }

    private fun collectSignalsOnce(session: HostSession) {
        synchronized(lock) {
            if (signalJob?.isActive == true) return
            signalJob = scope.launch {
                try {
                    session.signals.collect { signal -> handleSignal(session, signal) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    reportError(error)
                }
            }
        }
    }

    private suspend fun handleSignal(session: HostSession, signal: HostSignal) {
        when (signal) {
            is HostSignal.Snapshot -> {
                tasks.clear()
                signal.snapshot.tasks.map { it.toInfo() }.forEach { tasks[it.taskId] = it }
                listener?.onSnapshot(tasks.values.sortedByDescending { it.createdAt })
                signal.snapshot.pendingSelections.forEach {
                    session.resolveSelection(it.requestId, it.defaultChoice)
                }
            }
            is HostSignal.Event -> when (val event = signal.event) {
                is HostEvent.TaskChanged -> publish(
                    event.task.toInfo(
                        speedBytesPerSecond = tasks[event.task.taskId]
                            ?.speedBytesPerSecond
                            ?.takeIf { event.task.status.isActive }
                            ?: 0,
                    ),
                )
                is HostEvent.TaskProgress -> {
                    val previous = tasks[event.taskId] ?: return
                    publish(
                        previous.copy(
                            fileName = event.fileName.ifEmpty { previous.fileName },
                            status = event.status,
                            downloadedBytes = event.downloadedBytes,
                            totalBytes = event.totalBytes,
                            speedBytesPerSecond = event.speed,
                            errorMessage = event.errorMessage,
                        ),
                    )
                }
                is HostEvent.TaskDeleted -> {
                    tasks.remove(event.taskId)
                    listener?.onTaskDeleted(event.taskId)
                }
                is HostEvent.FileMissingChanged -> event.updates.forEach { (taskId, missing) ->
                    tasks[taskId]?.copy(fileMissing = missing)?.let(::publish)
                }
                is HostEvent.SelectionPending -> session.resolveSelection(event.request.requestId, event.request.defaultChoice)
                else -> Unit
            }
            is HostSignal.Fatal -> reportError(signal.error)
            // Stale is FluxDown's recoverable, read-only reconnect state.  The same flow can
            // subsequently emit a fresh Snapshot, so treating it as fatal would strand a
            // still-running native download in FongMi's FAILED state.
            HostSignal.Stale -> Unit
        }
    }

    private fun publish(task: FluxTaskInfo) {
        tasks[task.taskId] = task
        listener?.onTaskChanged(task)
    }

    private fun Task.toInfo(speedBytesPerSecond: Long = 0) = FluxTaskInfo(
        taskId = taskId,
        url = url,
        originUrl = originUrl,
        fileName = fileName,
        saveDir = saveDir,
        status = status.wire,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        speedBytesPerSecond = speedBytesPerSecond,
        errorMessage = errorMessage,
        createdAt = createdAt,
        completedAt = completedAt,
        fileMissing = fileMissing,
    )

    private fun header(headers: Map<String, String>, name: String): String =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value.orEmpty()

    private fun reportError(error: Throwable) {
        if (error is CancellationException) return
        listener?.onEngineError(message(error))
    }

    private fun message(error: Throwable): String = error.message ?: error.javaClass.simpleName
}
