package com.hardwareone.console.ble

import org.json.JSONObject

/**
 * Parsed device performance/memory snapshots for the Perf screen — the Android mirror of the
 * firmware's OLED "Perf" mode plus the memory profiler. Each model follows the app convention:
 * a `data class` + a defensive `companion object { fun parse(json): T? }` over `org.json`, so a
 * missing/mis-shaped reply yields `null` (keep-last), never a crash.
 *
 * Sources (firmware): `memreport json`, `fsusage json`, `perftop json`, `taskstats json`.
 */

/** `memreport json` — heap/PSRAM/heapCaps. All values are bytes. `psram*` are 0 when absent. */
data class MemReport(
    val dramTotal: Long,
    val dramUsed: Long,
    val dramFree: Long,
    val dramMinFree: Long,
    val dramPeakUsed: Long,
    val psramAvailable: Boolean,
    val psramTotal: Long,
    val psramUsed: Long,
    val psramFree: Long,
    val internalFree: Long,
    val internalLargest: Long,
    val dmaFree: Long,
    val dmaLargest: Long,
) {
    companion object {
        fun parse(json: String): MemReport? {
            val o = try { JSONObject(json) } catch (_: Exception) { return null }
            val dram = o.optJSONObject("dram") ?: return null // shape guard — reject non-memreport JSON
            val ps = o.optJSONObject("psram")
            val hc = o.optJSONObject("heapCaps")
            return MemReport(
                dramTotal = dram.optLong("total"),
                dramUsed = dram.optLong("used"),
                dramFree = dram.optLong("free"),
                dramMinFree = dram.optLong("minFree"),
                dramPeakUsed = dram.optLong("peakUsed"),
                psramAvailable = ps?.optBoolean("available", false) ?: false,
                psramTotal = ps?.optLong("total") ?: 0L,
                psramUsed = ps?.optLong("used") ?: 0L,
                psramFree = ps?.optLong("free") ?: 0L,
                internalFree = hc?.optLong("internalFree") ?: 0L,
                internalLargest = hc?.optLong("internalLargest") ?: 0L,
                dmaFree = hc?.optLong("dmaFree") ?: 0L,
                dmaLargest = hc?.optLong("dmaLargest") ?: 0L,
            )
        }
    }
}

/** `fsusage json` — LittleFS usage. `ready` false means the filesystem isn't mounted yet. */
data class FsUsage(
    val ready: Boolean,
    val totalBytes: Long,
    val usedBytes: Long,
    val freeBytes: Long,
    val usagePercent: Int,
) {
    companion object {
        fun parse(json: String): FsUsage? {
            val o = try { JSONObject(json) } catch (_: Exception) { return null }
            if (!o.has("ready")) return null // shape guard
            return FsUsage(
                ready = o.optBoolean("ready", false),
                totalBytes = o.optLong("totalBytes"),
                usedBytes = o.optLong("usedBytes"),
                freeBytes = o.optLong("freeBytes"),
                usagePercent = o.optInt("usagePercent"),
            )
        }
    }
}

/** One task's live CPU%. `idle` marks the per-core IDLE tasks (headroom, not load). */
data class PerfTask(val name: String, val cpuPct: Int, val idle: Boolean)

/** Main-loop health strip. `valid` is false until the first 5 s window has accumulated. */
data class PerfLoop(
    val valid: Boolean,
    val lapsPerSec: Long,
    val avgMs: Long,
    val maxMs: Long,
    val stalls5s: Long,
    val totalStalls: Long,
)

/** `perftop json` — the OLED Perf "CPU" page mirror: loop health + per-task CPU% (0.75 s sample). */
data class PerfTop(
    val loop: PerfLoop,
    val cpuValid: Boolean,
    val tasks: List<PerfTask>,
) {
    companion object {
        fun parse(json: String): PerfTop? {
            val o = try { JSONObject(json) } catch (_: Exception) { return null }
            val l = o.optJSONObject("loop") ?: return null // shape guard
            val loop = PerfLoop(
                valid = l.optBoolean("valid", false),
                lapsPerSec = l.optLong("lapsPerSec"),
                avgMs = l.optLong("avgMs"),
                maxMs = l.optLong("maxMs"),
                stalls5s = l.optLong("stalls5s"),
                totalStalls = l.optLong("totalStalls"),
            )
            val arr = o.optJSONArray("tasks")
            val tasks = buildList {
                if (arr != null) for (i in 0 until arr.length()) {
                    val t = arr.optJSONObject(i) ?: continue
                    add(PerfTask(t.optString("name"), t.optInt("cpuPct"), t.optBoolean("idle", false)))
                }
            }
            return PerfTop(loop, o.optBoolean("cpuValid", false), tasks)
        }
    }
}

/**
 * One task's stack figures. All values are BYTES (already correct under ESP-IDF — never *4).
 *
 * [stackFree] is FreeRTOS's `usStackHighWaterMark`: the LOWEST free space the task has had since
 * it started. It is the only stack figure ESP32 firmware can report from `uxTaskGetSystemState` —
 * `TaskStatus_t` carries `pxStackBase` but not the stack's end, because `pxEndOfStack` is compiled
 * out on downward-growing stacks. So peak *consumption* is unknowable unless the device separately
 * reports how big each stack was allocated.
 *
 * [stackSize] is that total, and is 0 when the device doesn't send it — model the union, render
 * the intersection. Once firmware adds a `stackSize` field to `taskstats json`, [stackUsed]
 * starts returning real numbers and the Perf page switches to consumption with no app change.
 */
data class TaskInfo(
    val name: String,
    val state: String,
    val prio: Int,
    val stackFree: Int,
    val stackSize: Int = 0,
) {
    /** Peak bytes consumed, or null when the device didn't report the stack's total size. */
    val stackUsed: Int? get() = if (stackSize > stackFree) stackSize - stackFree else null
}

/** `taskstats json` — the OLED Perf "Stack" page mirror: per-task state/prio/stack min-free. */
data class TaskStats(val total: Int, val tasks: List<TaskInfo>) {
    companion object {
        fun parse(json: String): TaskStats? {
            val o = try { JSONObject(json) } catch (_: Exception) { return null }
            val arr = o.optJSONArray("tasks") ?: return null // shape guard
            val tasks = buildList {
                for (i in 0 until arr.length()) {
                    val t = arr.optJSONObject(i) ?: continue
                    add(TaskInfo(
                        name = t.optString("name"),
                        state = t.optString("state"),
                        prio = t.optInt("prio"),
                        stackFree = t.optInt("stackFree"),
                        stackSize = t.optInt("stackSize", 0),
                    ))
                }
            }
            return TaskStats(o.optInt("total", tasks.size), tasks)
        }
    }
}
