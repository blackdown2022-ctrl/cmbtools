package ir.cmbtools.ledger.sms

import ir.cmbtools.ledger.core.Phone

/** Creation includes a registered customer mobile; renewal keeps the existing four-line format. */
data class SubscriptionSmsCommand(
    val customerName: String,
    val phone: String = "",
    val gb: Double,
    val discountRial: Long
)

object SubscriptionSmsCommandParser {
    fun create(body: String): SubscriptionSmsCommand? = parse(body, "ایجاد", includesPhone = true)

    fun renewal(body: String): SubscriptionSmsCommand? = parse(body, "تمدید", includesPhone = false)

    private fun parse(body: String, expectedVerb: String, includesPhone: Boolean): SubscriptionSmsCommand? {
        val lines = body.replace("\r", "").split('\n').map(String::trim).filter(String::isNotBlank)
        val expectedLines = if (includesPhone) 5 else 4
        if (lines.size != expectedLines || normalizeWord(lines[0]) != expectedVerb) return null

        val customerName = lines[1].trim()
        if (customerName.isBlank()) return null
        val phone = if (includesPhone) {
            Phone.normalize(lines[2]).takeIf { it.isNotBlank() } ?: return null
        } else ""
        val volumeIndex = if (includesPhone) 3 else 2
        val discountIndex = if (includesPhone) 4 else 3

        val volumeText = normalizeDigits(lines[volumeIndex]).trim()
        if (volumeText.isEmpty() || volumeText.any { it !in '0'..'9' && it != '.' }) return null
        val gb = volumeText.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 && it <= 100_000.0 }
            ?: return null

        val discountText = normalizeDigits(lines[discountIndex]).trim()
        if (discountText.isEmpty() || discountText.any { it !in '0'..'9' }) return null
        val discountUnits = discountText.toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val discountRial = runCatching { Math.multiplyExact(discountUnits, 10_000L) }.getOrNull() ?: return null

        return SubscriptionSmsCommand(customerName, phone, gb, discountRial)
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
                    in '۰'..'۹' -> ('0'.code + (char.code - '۰'.code)).toChar()
                    in '٠'..'٩' -> ('0'.code + (char.code - '٠'.code)).toChar()
                    '٫' -> '.'
                    else -> char
                }
            )
        }
    }
}
