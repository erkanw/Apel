package io.github.erkanw.vault

import io.github.erkanw.vault.openrouter.OrActivityRow
import io.github.erkanw.vault.openrouter.OrCredits
import io.github.erkanw.vault.openrouter.OrKey
import io.github.erkanw.vault.openrouter.OrKeyUse
import io.github.erkanw.vault.openrouter.OrSnapshot
import io.github.erkanw.vault.openrouter.compactCount
import io.github.erkanw.vault.openrouter.parseActivity
import io.github.erkanw.vault.openrouter.parseCredits
import io.github.erkanw.vault.openrouter.parseKey
import io.github.erkanw.vault.openrouter.parseKeys
import io.github.erkanw.vault.openrouter.parseSnapshot
import io.github.erkanw.vault.openrouter.shortModel
import io.github.erkanw.vault.openrouter.statsOf
import io.github.erkanw.vault.openrouter.toJson
import io.github.erkanw.vault.openrouter.usd
import io.github.erkanw.vault.openrouter.utcDate
import io.github.erkanw.vault.openrouter.weekOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterTest {
    private fun row(date: String, model: String, usage: Double, requests: Int = 1, prompt: Long = 100, cached: Long = 0) =
        OrActivityRow(date, model, "P", usage, requests, prompt, 50, 0, cached)

    @Test
    fun `the key, the credits, the keys and the activity are read as OpenRouter writes them`() {
        val key = parseKey(
            """{"data":{"label":"sk-or-v1-abc...123","limit":100,"limit_remaining":74.5,"usage":25.5,"usage_daily":1.5,
            "usage_weekly":7,"usage_monthly":25.5,"is_management_key":false}}""",
        )!!
        assertEquals(74.5, key.limitRemaining!!, 1e-9)
        assertEquals(1.5, key.daily, 1e-9)
        assertFalse(key.management)
        // No limit is a null, not a zero: "no limit" must not read as "nothing left".
        val unlimited = parseKey("""{"data":{"label":"k","limit":null,"limit_remaining":null,"usage":1,"is_management_key":true}}""")!!
        assertNull(unlimited.limit)
        assertNull(unlimited.limitRemaining)
        assertTrue(unlimited.management)

        val credits = parseCredits("""{"data":{"total_credits":100.5,"total_usage":25.75}}""")!!
        assertEquals(74.75, credits.left, 1e-9)

        val keys = parseKeys("""{"data":[{"name":"Laptop","label":"sk-or...","disabled":false,"usage":3,"usage_daily":0.5,"usage_weekly":2,"usage_monthly":3}]}""")
        assertEquals("Laptop", keys.single().name)
        assertEquals(0.5, keys.single().daily, 1e-9)

        val rows = parseActivity(
            """{"data":[{"date":"2026-10-09","model":"openai/gpt-4.1","provider_name":"OpenAI","usage":0.015,"requests":5,
            "prompt_tokens":50,"completion_tokens":125,"reasoning_tokens":25,"cached_tokens":10},
            {"date":"","model":"x/y","usage":1}]}""",
        )
        assertEquals(1, rows.size) // the one with no date is dropped
        assertEquals(5, rows[0].requests)
        assertEquals(25L, rows[0].reasoningTokens)
    }

    @Test
    fun `things that are not OpenRouter's answers are refused, not guessed at`() {
        assertNull(parseKey("<html>login</html>"))
        assertNull(parseKey("""{"error":{"code":401,"message":"Missing Authentication header"}}"""))
        assertNull(parseCredits("""{"data":{}}"""))
        assertTrue(parseKeys("nonsense").isEmpty())
        assertTrue(parseActivity("""{"data":"x"}""").isEmpty())
        assertNull(parseSnapshot("nope"))
    }

    @Test
    fun `a snapshot survives being kept and read back`() {
        val snapshot = OrSnapshot(
            at = 1_760_000_000_000L,
            key = OrKey("lbl", 25.5, 1.5, 7.0, 25.5, 100.0, 74.5, management = true),
            credits = OrCredits(100.5, 25.75),
            keys = listOf(OrKeyUse("Laptop", "sk", false, 3.0, 0.5, 2.0, 3.0)),
            rows = listOf(row("2026-10-09", "openai/gpt-4.1", 0.015, 5, 50, 10)),
        )
        val back = parseSnapshot(snapshot.toJson())
        assertNotNull(back)
        assertEquals(snapshot, back)
    }

    @Test
    fun `what the page calls today and balance follows the key's power`() {
        val regular = OrSnapshot(1, OrKey("k", 10.0, 0.4, 2.0, 5.0, 20.0, 10.0, false), null, emptyList(), emptyList())
        assertEquals(0.4, regular.today, 1e-9)
        assertEquals(10.0, regular.balance!!, 1e-9)
        assertFalse(regular.detailed)
        val noLimit = regular.copy(key = regular.key.copy(limit = null, limitRemaining = null))
        assertNull(noLimit.balance)

        val manager = OrSnapshot(
            1, OrKey("m", 0.0, 0.0, 0.0, 0.0, null, null, true), OrCredits(50.0, 20.0),
            listOf(OrKeyUse("a", "a", false, 1.0, 0.25, 1.0, 1.0), OrKeyUse("b", "b", false, 2.0, 0.5, 1.5, 2.0)), emptyList(),
        )
        // The account's day is every key's day added up, and the balance is credits bought minus used.
        assertEquals(0.75, manager.today, 1e-9)
        assertEquals(2.5, manager.thisWeek, 1e-9)
        assertEquals(30.0, manager.balance!!, 1e-9)
        assertTrue(manager.detailed)
    }

    @Test
    fun `the statistics add up, fill the quiet days and rank the models`() {
        val rows = listOf(
            row("2026-10-09", "openai/gpt-4.1", 3.0, 30, 3000, 600),
            row("2026-10-09", "qwen/qwen3", 1.0, 10, 1000, 0),
            row("2026-10-07", "openai/gpt-4.1", 2.0, 20, 2000, 400),
            row("2026-08-01", "old/model", 99.0), // outside the 30 days
        )
        val s = statsOf(rows, endDate = "2026-10-09", dayCount = 30)
        assertEquals(30, s.days.size)
        assertEquals("2026-10-09", s.days.last().date)
        assertEquals("2026-09-10", s.days.first().date)
        assertEquals(0.0, s.days.first { it.date == "2026-10-08" }.usage, 0.0) // a quiet day is an empty bar, not a gap
        assertEquals(6.0, s.total, 1e-9)
        assertEquals(60, s.requests)
        assertEquals(listOf("openai/gpt-4.1", "qwen/qwen3"), s.models.map { it.model })
        assertEquals(5.0 / 6.0, s.models[0].share.toDouble(), 1e-4)
        assertEquals("2026-10-09", s.busiest!!.date)
        assertEquals(6.0 / 30.0, s.perDay, 1e-9)
        assertEquals(0.1, s.perRequest!!, 1e-9)
        // Cached: (600 + 0 + 400) of 6000 prompt tokens.
        assertEquals(1000.0 / 6000.0, s.cachedShare!!.toDouble(), 1e-4)
        // Last seven days (3 Oct to 9 Oct) hold all 6.0; the week before holds nothing.
        assertEquals(6.0, s.last7, 1e-9)
        assertEquals(0.0, s.before7, 1e-9)
        assertEquals(6.0 / 7.0 * 30.0, s.monthAtThisPace, 1e-9)
    }

    @Test
    fun `no activity at all is quiet, not an error`() {
        val s = statsOf(emptyList(), endDate = "2026-10-09")
        assertEquals(0.0, s.total, 0.0)
        assertNull(s.perRequest)
        assertNull(s.busiest)
        assertNull(s.cachedShare)
        assertTrue(s.models.isEmpty())
        assertEquals(30, s.days.size)
    }

    @Test
    fun `money and counts are written so a cheap day isn't zero`() {
        assertEquals("$0.00", usd(0.0))
        assertEquals("$12.35", usd(12.345))
        assertEquals("$1,234.50", usd(1234.5))
        assertEquals("$0.042", usd(0.042))
        assertEquals("$0.0031", usd(0.0031))
        assertEquals("-$2.00", usd(-2.0))
        assertEquals("$0.00", usd(Double.NaN))
        assertEquals("812", compactCount(812))
        assertEquals("1.2k", compactCount(1_234))
        assertEquals("340k", compactCount(340_000))
        assertEquals("1.2M", compactCount(1_200_000))
        assertEquals("gpt-4.1", shortModel("openai/gpt-4.1"))
        assertEquals("plain", shortModel("plain"))
    }

    @Test
    fun `OpenRouter's days are UTC days`() {
        // 23:30 UTC on 10 Oct is already 11 Oct in Stockholm; OpenRouter still calls it the 10th.
        assertEquals("2026-10-10", utcDate(java.time.Instant.parse("2026-10-10T23:30:00Z").toEpochMilli()))
        assertEquals("2026-10-11", utcDate(java.time.Instant.parse("2026-10-11T00:00:00Z").toEpochMilli()))
    }
}

class OpenRouterWeekTest {
    private fun row(date: String, model: String, usage: Double, requests: Int, tokens: Long) =
        OrActivityRow(date, model, "P", usage, requests, tokens, 0, 0, 0)

    private val today = java.time.LocalDate.parse("2026-10-11") // a Sunday: the week is 5 to 10 Oct

    @Test
    fun `the activity dates OpenRouter really sends, with a time, still make a week`() {
        val rows = parseActivity(
            """{"data":[{"date":"2026-10-10 00:00:00","model":"qwen/qwen3.7-flash","provider_name":"alibaba","usage":0.05,"requests":128,
              "prompt_tokens":2530635,"completion_tokens":30596,"reasoning_tokens":20682,"cached_tokens":0}]}""",
        )
        assertEquals("2026-10-10", rows.single().date)
        assertEquals(128L, weekOf(rows, today)?.requests)
    }

    @Test
    fun `the week is Monday to yesterday, each measure ranked with its own top models and the rest as Others`() {
        val rows = listOf(
            row("2026-10-05", "qwen/qwen3.7-flash", 0.10, 100, 4_000_000),
            row("2026-10-09", "qwen/qwen3.7-flash", 0.10, 371, 5_160_000),
            row("2026-10-09", "anthropic/claude-haiku-4.5", 0.01, 5, 50_000),
            row("2026-10-09", "qwen/qwen3.8-27b", 0.01, 3, 40_000),
            row("2026-10-08", "apodex/apodex-1.1-mini:free", 0.0, 63, 856_000),
            row("2026-10-08", "dots/dots3-note-preview:free", 0.0, 19, 193_000),
            row("2026-10-03", "old/model", 9.0, 999, 9_999_999), // last week: not in it
            row("2026-10-11", "today/partial", 5.0, 5, 5),         // today is not over: not in it
        )
        val week = weekOf(rows, today)!!
        assertEquals("This week", week.label)
        assertEquals("2026-10-09", week.through)
        assertEquals(0.22, week.spend, 1e-9)
        assertEquals(561L, week.requests)
        // Spend: the three that cost something, in order, then the rest (the two free ones) as Others at nothing.
        assertEquals(listOf("qwen/qwen3.7-flash", "anthropic/claude-haiku-4.5", "qwen/qwen3.8-27b", "Others"), week.bySpend.map { it.model })
        assertEquals(0.20, week.bySpend[0].value, 1e-9)
        assertEquals(0.0, week.bySpend.last().value, 1e-9)
        // Requests are ranked by requests, so the free model with 63 comes before the paid ones with 5 and 3.
        assertEquals(listOf("qwen/qwen3.7-flash", "apodex/apodex-1.1-mini:free", "dots/dots3-note-preview:free", "Others"), week.byRequests.map { it.model })
        assertEquals(471.0, week.byRequests[0].value, 1e-9)
        assertEquals(8.0, week.byRequests.last().value, 1e-9)
        // The same model has the same colour in all three; the biggest spender takes the first slot; Others has none.
        assertEquals(0, week.bySpend[0].color)
        assertEquals(0, week.byRequests[0].color)
        assertEquals(-1, week.byTokens.last().color)
        assertEquals(week.byRequests[1].color, week.byTokens.first { it.model == week.byRequests[1].model }.color) // Apodex: the same colour in both
        // Shares add up to the whole.
        assertEquals(1.0, week.byRequests.sumOf { it.share.toDouble() }, 1e-4)
    }

    @Test
    fun `on a Monday with no day over yet the last seven days stand in, and with nothing at all there is no week`() {
        val monday = java.time.LocalDate.parse("2026-10-12")
        val rows = listOf(row("2026-10-09", "a/b", 1.0, 2, 3))
        val week = weekOf(rows, monday)!!
        assertEquals("Last 7 days", week.label)
        assertEquals(1.0, week.spend, 1e-9)
        assertNull(weekOf(emptyList(), monday))
        assertNull(weekOf(listOf(row("2026-09-01", "a/b", 1.0, 1, 1)), monday)) // far too old
    }

    @Test
    fun `models are named as the dashboard names them`() {
        assertEquals("Qwen3.7 Flash", io.github.erkanw.vault.openrouter.modelLabel("qwen/qwen3.7-flash"))
        assertEquals("Claude Haiku 4.5", io.github.erkanw.vault.openrouter.modelLabel("anthropic/claude-haiku-4.5"))
        assertEquals("Apodex 1.1 Mini (free)", io.github.erkanw.vault.openrouter.modelLabel("apodex/apodex-1.1-mini:free"))
    }

    @Test
    fun `a snapshot keeps when its activity was fetched`() {
        val snapshot = OrSnapshot(1, OrKey("k", 0.0, 0.0, 0.0, 0.0, null, null, true), OrCredits(1.0, 0.0), emptyList(), emptyList(), activityAt = 12_345L)
        assertEquals(12_345L, parseSnapshot(snapshot.toJson())!!.activityAt)
    }
}
