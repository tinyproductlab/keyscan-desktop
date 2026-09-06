package com.keyscan.core.security

import java.security.SecureRandom

data class PasswordGeneratorOptions(
    val length: Int = 20,
    val uppercase: Boolean = true,
    val lowercase: Boolean = true,
    val digits: Boolean = true,
    val symbols: Boolean = true,
    val excludeConfusing: Boolean = true,
)

object PasswordGenerator {
    private const val UPPERCASE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val LOWERCASE = "abcdefghijklmnopqrstuvwxyz"
    private const val DIGITS = "0123456789"
    private const val SYMBOLS = "!@#$%^&*()-_=+[]{}:,.?"
    private const val CONFUSING = "0O1Il"

    fun generate(options: PasswordGeneratorOptions, random: SecureRandom = SecureRandom()): String {
        require(options.length in 4..128) { "Password length must be between 4 and 128" }
        val selected = buildList {
            if (options.uppercase) add(UPPERCASE)
            if (options.lowercase) add(LOWERCASE)
            if (options.digits) add(DIGITS)
            if (options.symbols) add(SYMBOLS)
        }.map { set -> if (options.excludeConfusing) set.filterNot(CONFUSING::contains) else set }
            .filter(String::isNotEmpty)
        require(selected.isNotEmpty()) { "At least one character set is required" }
        require(options.length >= selected.size) { "Password is too short for the selected character sets" }

        val all = selected.joinToString("")
        val output = CharArray(options.length)
        selected.forEachIndexed { index, set -> output[index] = set[random.nextInt(set.length)] }
        for (index in selected.size until output.size) output[index] = all[random.nextInt(all.length)]
        for (index in output.lastIndex downTo 1) {
            val other = random.nextInt(index + 1)
            val value = output[index]; output[index] = output[other]; output[other] = value
        }
        return output.concatToString()
    }
}
