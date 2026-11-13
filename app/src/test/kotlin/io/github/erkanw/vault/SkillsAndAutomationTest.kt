package io.github.erkanw.vault

import io.github.erkanw.vault.chat.describeSchedule
import io.github.erkanw.vault.chat.humanCron
import io.github.erkanw.vault.chat.humanDuration
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillsAndAutomationTest {
    private fun strings(o: JSONObject, name: String) = (0 until o.getJSONArray(name).length()).map { o.getJSONArray(name).getString(it) }

    @Test
    fun `cron expressions are put into words when they are simple`() {
        assertEquals("Every day at 20:30", humanCron("30 20 * * *"))
        assertEquals("Every day at 07:05", humanCron("5 7 * * *"))
        assertEquals("Every Monday at 09:00", humanCron("0 9 * * 1"))
        assertEquals("Every Sunday at 09:00", humanCron("0 9 * * 0"))
        assertEquals("Weekdays at 08:00", humanCron("0 8 * * 1-5"))
        assertEquals("Every 15 minutes", humanCron("*/15 * * * *"))
        assertEquals("Every hour at :10", humanCron("10 * * * *"))
        // Anything else stays as written.
        assertEquals("0 9 1 * *", humanCron("0 9 1 * *"))
        assertEquals("not a cron", humanCron("not a cron"))
    }

    @Test
    fun `intervals and one-off schedules read well`() {
        assertEquals("30 minutes", humanDuration(1_800_000))
        assertEquals("hour", humanDuration(3_600_000))
        assertEquals("2 hours", humanDuration(7_200_000))
        assertEquals("day", humanDuration(86_400_000))
        assertEquals("3 days", humanDuration(3 * 86_400_000L))
        assertEquals("Every 2 hours", describeSchedule(JSONObject("""{"kind":"every","every_ms":7200000}""")))
        assertTrue(describeSchedule(JSONObject("""{"kind":"at","at_ms":1791657000000}""")).startsWith("Once, "))
        assertEquals("", describeSchedule(null))
    }

}
