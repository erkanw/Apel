package io.github.erkanw.vault.provider

import java.math.BigDecimal
import java.math.MathContext

/** A model offered during onboarding: its id, the words for it, and why it is there. */
data class RecommendedModel(val id: String, val label: String, val role: String)

/**
 * The onboarding list (PLAN §2.1), chosen from OpenRouter's live catalogue on 2026-11-13 by the plan's rules: tools, a
 * zero-retention provider, context of 128k or more, a major lab, the cheapest that does well on tool use at about $0.005
 * a message or less; one strong model at $0.03 or less; one free model with tools. Refresh each release.
 */
val RECOMMENDED_MODELS = listOf(
    RecommendedModel("anthropic/claude-haiku-5.5", "Claude Haiku 5.5", "Recommended"),
    RecommendedModel("openai/gpt-6.1-sol", "GPT-6.1 Sol", "Stronger"),
    RecommendedModel("inclusionai/ling-3.2-flash:free", "Ling 3.2 Flash", "Free"),
)

/** What a typical message costs (2,000 tokens in, 500 out), in dollars; prices are per million tokens. */
fun pricePerMessage(promptPerM: Double, completionPerM: Double): Double =
    (2_000 * promptPerM + 500 * completionPerM) / 1_000_000.0

/**
 * The price line of a model row (PLAN §2.1): "About $0.002 a message", one significant figure; a free model says its
 * daily limit instead.
 */
fun priceLine(free: Boolean, promptPerM: Double, completionPerM: Double): String {
    if (free) return "Free · 50 messages a day"
    val usd = pricePerMessage(promptPerM, completionPerM)
    // Not listed (NaN) or not a price: never shown as a number.
    if (!usd.isFinite() || usd <= 0.0) return "Price unknown"
    if (usd < 0.0001) return "Less than \$0.0001 a message"
    val rounded = BigDecimal.valueOf(usd).round(MathContext(1, java.math.RoundingMode.HALF_UP)).stripTrailingZeros().toPlainString()
    return "About \$$rounded a message"
}
