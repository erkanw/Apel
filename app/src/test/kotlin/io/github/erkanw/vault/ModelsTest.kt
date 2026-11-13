package io.github.erkanw.vault

import io.github.erkanw.vault.chat.ModelPrefs
import io.github.erkanw.vault.chat.applyModelPrefs
import io.github.erkanw.vault.chat.modelPrefsToJson
import io.github.erkanw.vault.chat.movedModelOrder
import io.github.erkanw.vault.chat.parseModelPrefs
import io.github.erkanw.vault.openrouter.CatalogModel
import io.github.erkanw.vault.openrouter.CatalogSort
import io.github.erkanw.vault.openrouter.catalogToJson
import io.github.erkanw.vault.openrouter.filterCatalog
import io.github.erkanw.vault.openrouter.findCatalogModel
import io.github.erkanw.vault.openrouter.priceDetail
import io.github.erkanw.vault.openrouter.parseCachedCatalog
import io.github.erkanw.vault.openrouter.parseCatalog
import io.github.erkanw.vault.openrouter.presetNameFor
import io.github.erkanw.vault.openrouter.priceLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {
    private val settings = """{"agent":{"model_preset":"flash"},
        "model_call_order":["flash","haiku"],"model_call_order_editable":true,
        "model_presets":[
          {"name":"default","label":"Default","is_default":true,"model":"openai/gpt-oss-120b"},
          {"name":"flash","label":"flash","active":true,"model":"openai/gpt-oss-120b","max_tokens":8192,"context_window_tokens":128000},
          {"name":"coder","label":"coder","model":"qwen/qwen3.8-27b","max_tokens":32768,"context_window_tokens":128000},
          {"name":"haiku","label":"haiku","model":"anthropic/claude-haiku-5.5","max_tokens":4096,"context_window_tokens":128000}]}"""

    @Test
    fun `a model moves one place and stops at the ends`() {
        val order = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), movedModelOrder(order, "b", up = true))
        assertEquals(listOf("a", "c", "b"), movedModelOrder(order, "b", up = false))
        assertEquals(order, movedModelOrder(order, "a", up = true))
        assertEquals(order, movedModelOrder(order, "c", up = false))
        assertEquals(order, movedModelOrder(order, "zzz", up = true))
    }

    @Test
    fun `your choices are kept and read back`() {
        val prefs = ModelPrefs(setOf("coder"), listOf("haiku", "flash"), mapOf("flash" to "Everyday"))
        assertEquals(prefs, parseModelPrefs(modelPrefsToJson(prefs)))
        assertEquals(ModelPrefs(), parseModelPrefs(null))
        assertEquals(ModelPrefs(), parseModelPrefs("not json"))
    }

    @Test
    fun `skins kept by an older version are read past`() {
        val old = """{"hidden":["a"],"order":[],"labels":{},"skins":{"a":"crown"}}"""
        assertEquals(ModelPrefs(hidden = setOf("a")), parseModelPrefs(old))
    }

    private val modelsJson = """{"data":[
        {"id":"openai/gpt-oss-120b","name":"OpenAI: gpt-oss-120b","context_length":131072,"created":1000,
         "pricing":{"prompt":"0.000000039","completion":"0.00000019"},"supported_parameters":["tools","reasoning"]},
        {"id":"qwen/qwen3.7-flash","name":"Qwen: Qwen3.7 Flash","context_length":1000000,"created":3000,
         "pricing":{"prompt":"0.0000001","completion":"0.0000004"},"supported_parameters":["tools"]},
        {"id":"vendor/free-one:free","name":"Vendor: Free One","context_length":65536,"created":2000,
         "pricing":{"prompt":"0","completion":"0"},"supported_parameters":["tools"]},
        {"id":"vendor/no-tools","name":"Vendor: No Tools","context_length":8000,"created":500,
         "pricing":{"prompt":"0.000001","completion":"0.000002"},"supported_parameters":[]}]}"""
    private val zdrJson = """{"data":[
        {"model_id":"openai/gpt-oss-120b","provider_name":"Near AI","pricing":{"prompt":"0.00000003","completion":"0.00000015"}},
        {"model_id":"openai/gpt-oss-120b","provider_name":"Bedrock","pricing":{"prompt":"0.00000015","completion":"0.0000006"}},
        {"model_id":"vendor/no-tools","provider_name":"X","pricing":{"prompt":"0.000001","completion":"0.000002"}}]}"""

    @Test
    fun `the catalogue knows which models have a zero retention provider and its cheapest price`() {
        val all = parseCatalog(modelsJson, zdrJson)
        val gpt = all.first { it.id == "openai/gpt-oss-120b" }
        assertTrue(gpt.zdr)
        assertEquals(0.03, gpt.zdrIn, 1e-9)
        assertEquals(0.15, gpt.zdrOut, 1e-9)
        assertTrue(gpt.tools && gpt.reasoning)
        assertFalse(all.first { it.id == "qwen/qwen3.7-flash" }.zdr)
        assertTrue(all.first { it.id == "vendor/free-one:free" }.free)
    }

    @Test
    fun `the catalogue is filtered and sorted`() {
        val all = parseCatalog(modelsJson, zdrJson)
        fun ids(query: String = "", zdr: Boolean = false, tools: Boolean = false, free: Boolean = false, sort: CatalogSort = CatalogSort.Cheapest) =
            filterCatalog(all, query, zdr, tools, free, sort).map { it.id }
        assertEquals(listOf("openai/gpt-oss-120b", "vendor/no-tools"), ids(zdr = true))
        assertEquals(listOf("openai/gpt-oss-120b"), ids(zdr = true, tools = true))
        assertEquals(listOf("vendor/free-one:free"), ids(free = true))
        assertEquals(listOf("qwen/qwen3.7-flash"), ids(query = "qwen flash"))
        assertEquals(listOf("qwen/qwen3.7-flash", "vendor/free-one:free", "openai/gpt-oss-120b", "vendor/no-tools"), ids(sort = CatalogSort.Newest))
        assertEquals("qwen/qwen3.7-flash", ids(sort = CatalogSort.Context).first())
        assertEquals("vendor/free-one:free", ids(sort = CatalogSort.Cheapest).first())
    }

    @Test
    fun `a preset name comes from the model id and is made unique`() {
        assertEquals("gpt-oss-120b", presetNameFor("openai/gpt-oss-120b", emptySet()))
        assertEquals("gpt-oss-120b-2", presetNameFor("openai/gpt-oss-120b", setOf("gpt-oss-120b")))
        assertEquals("gpt-oss-120b-3", presetNameFor("openai/gpt-oss-120b", setOf("gpt-oss-120b", "gpt-oss-120b-2")))
        assertEquals("free-one", presetNameFor("vendor/Free One:free", emptySet()))
        assertEquals("model", presetNameFor("///", emptySet()))
    }

    @Test
    fun `prices read well and the catalogue survives being kept`() {
        assertEquals("\$0.03 / \$0.15 per M", priceLine(0.03, 0.15))
        assertEquals("\$0.005 / \$0.20 per M", priceLine(0.005, 0.2))
        assertEquals("\$0 / \$0 per M", priceLine(0.0, 0.0))
        val cheap = CatalogModel("vendor/flash", "Flash", 128_000, 0.1, 0.4, true, false, 0, zdr = true, zdrIn = 0.08, zdrOut = 0.3)
        val free = CatalogModel("vendor/free:free", "Free", 128_000, 0.0, 0.0, true, false, 0, zdr = false)
        assertEquals("In \$0.08 · Out \$0.30 per 1M tokens", priceDetail(cheap))
        assertEquals("Free", priceDetail(free))
        assertEquals(cheap, findCatalogModel(listOf(free, cheap), "vendor/flash"))
        assertEquals(cheap, findCatalogModel(listOf(free, cheap), "vendor/flash:nitro"))
        assertEquals(free, findCatalogModel(listOf(free, cheap), "vendor/free:free"))
        assertNull(findCatalogModel(listOf(free, cheap), "vendor/unknown"))
        assertNull(findCatalogModel(emptyList(), "vendor/flash"))
        val all = parseCatalog(modelsJson, zdrJson)
        val (back, at) = parseCachedCatalog(catalogToJson(all, 42L))!!
        assertEquals(all, back)
        assertEquals(42L, at)
        assertNull(parseCachedCatalog("nope"))
    }
}
