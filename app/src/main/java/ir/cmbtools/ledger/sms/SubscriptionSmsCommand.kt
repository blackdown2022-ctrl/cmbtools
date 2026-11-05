package ir.cmbtools.ledger.sms

/** Strict four-line create/renew command. Discount is written in 10,000-rial units. */
data class SubscriptionSmsCommand(
    val customerName: String,
    val gb: Double,
    val discountRial: Long
)

object SubscriptionSmsCommandParser {
    fun create(body: String): SubscriptionSmsCommand? = parse(body, "ایجاد")

    fun renewal(body: String): SubscriptionSmsCommand? = parse(body, "تمدید")

    private fun parse(body: String, expectedVerb: String): SubscriptionSmsCommand? {
        val lines = body.replace("\r", "").split('\n').map(String::trim).filter(String::isNotBlank)
        if (lines.size != 4 || normalizeWord(lines[0]) != expectedVerb) return null

        val customerName = lines[1].trim()
        if (customerName.isBlank()) return null

        val volumeText = normalizeDigits(lines[2]).trim()
        if (volumeText.isEmpty() || volumeText.any { it !in '0'..'9' && it != '.' }) return null
        val gb = volumeText.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 && it <= 100_000.0 }
            ?: return null

        val discountText = normalizeDigits(lines[3]).trim()
        if (discountText.isEmpty() || discountText.any { it !in '0'..'9' }) return null
        val discountUnits = discountText.toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val discountRial = runCatching { Math.multiplyExact(discountUnits, 10_000L) }.getOrNull() ?: return null

        return SubscriptionSmsCommand(customerName, gb, discountRial)
    }

    private fun normalizeWord(value: String): String = value
        .replace('ي', 'ی')
        .replace('ك', 'ک')
        .replace("\u200c", "")
        .replace("ـ", "")
        .trim()

    private fun normalizeDigits(value: String): String = buildString(value.length) {
        value.forEach { char ->
            append(
                when (char) {
                    in '۰'..'۹' -> ('0'.code + (char.code - '۰'.code')).toChar()
                    in '٠'..'٩' -> ('0'.code + (char.code - '٠'.code)).toChar()
                    '٫' -> '.'
                    else -> char
                }
            )
        }
    }
}
