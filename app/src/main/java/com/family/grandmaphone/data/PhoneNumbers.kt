package com.family.grandmaphone.data

object PhoneNumbers {
    /** Enough trailing digits to identify a number whether or not it has a country code. */
    private const val MATCH_DIGITS = 9
    private const val MIN_DIGITS = 7
    private const val TURKEY = "90"

    fun digits(number: String): String = number.filter { it in '0'..'9' }

    /** True when two numbers are the same line, written in any format (+90…, 0090…, 0…). */
    fun same(a: String, b: String): Boolean {
        val x = digits(a)
        val y = digits(b)
        if (x.length < MIN_DIGITS || y.length < MIN_DIGITS) return x.isNotEmpty() && x == y
        // A local number can be shorter than the match length, like Norway's 8 digits.
        val length = minOf(MATCH_DIGITS, x.length, y.length)
        return x.takeLast(length) == y.takeLast(length)
    }

    /**
     * Digits with country code and no plus, the form WhatsApp identifies people by.
     * Returns null when the number is local and no country code has been set.
     */
    fun international(number: String, defaultCountryCode: String): String? {
        val trimmed = number.trim()
        val all = digits(trimmed)
        if (all.isEmpty()) return null
        if (trimmed.startsWith("+")) return all
        if (all.startsWith("00")) return all.drop(2)
        val code = digits(defaultCountryCode)
        // A Turkish mobile written the local way, 0 5xx xxx xx xx, is Turkish wherever she lives.
        if (code != TURKEY && all.length == 11 && all.startsWith("05")) return TURKEY + all.drop(1)
        if (code.isEmpty()) return null
        return code + all.removePrefix("0")
    }
}
