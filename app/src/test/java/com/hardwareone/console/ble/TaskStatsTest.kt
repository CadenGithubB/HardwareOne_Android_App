package com.hardwareone.console.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `taskstats json`. The interesting part is [TaskInfo.stackUsed]: today's firmware sends only the
 * high-water mark, so consumption must come back null rather than a fabricated number.
 */
class TaskStatsTest {

    /** What the device sends today — no `stackSize`, so consumption is unknowable. */
    @Test
    fun currentFirmwareShapeHasNoConsumption() {
        val stats = TaskStats.parse(
            """{"schema":1,"total":2,"tasks":[""" +
                """{"name":"loopTask","state":"RUN","prio":1,"stackFree":3200},""" +
                """{"name":"IDLE0","state":"READY","prio":0,"stackFree":512}]}""",
        )
        requireNotNull(stats)
        assertEquals(2, stats.total)
        assertEquals(2, stats.tasks.size)
        assertEquals(3200, stats.tasks[0].stackFree)
        assertEquals(0, stats.tasks[0].stackSize)
        assertNull("must not invent a used figure", stats.tasks[0].stackUsed)
    }

    /** Once firmware adds `stackSize`, used = total - high-water, with no app change. */
    @Test
    fun stackSizeUnlocksConsumption() {
        val stats = TaskStats.parse(
            """{"schema":1,"total":1,"tasks":[""" +
                """{"name":"bleTask","state":"BLOCK","prio":5,"stackFree":1024,"stackSize":4096}]}""",
        )
        requireNotNull(stats)
        val t = stats.tasks.single()
        assertEquals(4096, t.stackSize)
        assertEquals(3072, t.stackUsed)
    }

    /** A nonsensical total (smaller than the free figure) must not produce a negative bar. */
    @Test
    fun inconsistentTotalIsRejectedRatherThanGoingNegative() {
        val stats = TaskStats.parse(
            """{"schema":1,"total":1,"tasks":[""" +
                """{"name":"odd","state":"RUN","prio":1,"stackFree":8192,"stackSize":4096}]}""",
        )
        requireNotNull(stats)
        assertNull(stats.tasks.single().stackUsed)
    }

    @Test
    fun rejectsNonTaskStatsJson() {
        assertNull(TaskStats.parse("""{"dram":{"total":1}}"""))
        assertNull(TaskStats.parse("not json"))
    }
}
