package com.hardwareone.console.pipe

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The pipe's qualification log (row 10): one JSON object per line in app-private storage, for the
 * user to export with the qualification record. Lines are keyed by `corr` (the P4's pipe HELLO nonce,
 * which the P4's F3 record logs too) and stream id. Never an endpoint, address, content or secret.
 * Bounded: past [MAX_BYTES] the file rolls over to one previous generation. Writes happen in order
 * on one background thread, so callers (some under the engine lock) never wait on the disk, and
 * lines written while the pipe stops are kept.
 */
class PipeLog(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "pipe")
    private val file = File(dir, "qualification.jsonl")
    private val previous = File(dir, "qualification.1.jsonl")

    fun append(line: JSONObject) {
        val text = line.toString() + "\n"
        runCatching {
            io.execute {
                runCatching {
                    dir.mkdirs()
                    if (file.length() > MAX_BYTES) {
                        previous.delete()
                        file.renameTo(previous)
                    }
                    file.appendText(text)
                }
            }
        }
    }

    /** Both generations, oldest first, then a final counters line built from [counters]. */
    fun export(counters: JSONObject): String = onIo {
        buildString {
            for (f in listOf(previous, file)) runCatching { if (f.exists()) append(f.readText()) }
            append(counters.toString()).append('\n')
        }
    } ?: (counters.toString() + "\n")

    fun clear() {
        onIo {
            previous.delete()
            file.delete()
        }
    }

    /** Runs after every queued write, so an export or clear sees them all. */
    private fun <T> onIo(block: () -> T): T? = runCatching { io.submit(block).get(5, TimeUnit.SECONDS) }.getOrNull()

    companion object {
        const val MAX_BYTES = 512 * 1024L

        /** One writer for the whole process (the service and the UI's export share the file). */
        private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "hw1-pipe-log").apply { isDaemon = true } }

        /**
         * One per service start. [lastExit] is why the app's previous process ended (Android's
         * ApplicationExitInfo: reason name and code, importance, wall-clock time), or null when
         * Android has none to report; it tells a crash from a low-memory or memory-limiter kill.
         */
        fun runLine(elapsedMs: Long, app: String, lastExit: JSONObject? = null): JSONObject =
            JSONObject().put("t", "run").put("elapsed_ms", elapsedMs).put("app", app)
                .put("last_exit", lastExit ?: JSONObject.NULL)

        fun sessionLine(r: PipeSessionRecord, retiredInstanceCallbacks: Long): JSONObject = JSONObject()
            .put("t", "session")
            .put("corr", r.corr ?: JSONObject.NULL)
            .put("ready_ms", r.readyAt ?: JSONObject.NULL)
            .put("end_ms", r.endedAt)
            .put("end_kind", r.end.kind.name.lowercase())
            .put("end_code", r.end.error?.code ?: JSONObject.NULL)
            .put("authenticated", r.end.authenticated)
            .put("unsettled", r.end.unsettled)
            .put("stale_callbacks", r.staleCallbacks)
            .put("retired_instance_callbacks", retiredInstanceCallbacks)

        fun streamLine(r: PipeStreamRecord): JSONObject = JSONObject()
            .put("t", "stream")
            .put("corr", r.corr ?: JSONObject.NULL)
            .put("id", r.id)
            .put("open_to_result_ms", r.openToResultMs ?: JSONObject.NULL)
            .put("result", r.result)
            .put("end", r.end)
            .put("bytes_to_p4", r.bytesToDevice)
            .put("bytes_from_p4", r.bytesFromDevice)
            .put("peer_reset", r.peerReset)
            .put("peer_close", r.peerClose)

        /** Cumulative per app run (the running pipe's engine), as the P4's counters are per boot. */
        fun countersLine(stats: PipeStats, retiredInstanceCallbacks: Long, elapsedMs: Long): JSONObject {
            val openErr = JSONObject()
            stats.refused.forEach { (e, n) -> openErr.put(e.code.toString(), n) }
            val reset = JSONObject()
            stats.resets.forEach { (e, n) -> reset.put(e.code.toString(), n) }
            return JSONObject()
                .put("t", "counters")
                .put("elapsed_ms", elapsedMs)
                .put("sessions", stats.sessions)
                .put("streams_opened", stats.opened)
                .put("open_err", openErr)
                .put("reset", reset)
                .put("peer_resets", stats.peerResets)
                .put("peer_closes", stats.peerCloses)
                .put("stale_callbacks", stats.staleCallbacks)
                .put("retired_instance_callbacks", retiredInstanceCallbacks)
                .put("last_end_kind", stats.lastEnd?.name?.lowercase() ?: JSONObject.NULL)
                .put("last_end_code", stats.lastError?.code ?: JSONObject.NULL)
        }
    }
}
