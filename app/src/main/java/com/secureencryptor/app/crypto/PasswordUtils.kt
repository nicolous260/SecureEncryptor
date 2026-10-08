package com.secureencryptor.app.crypto

import java.security.SecureRandom
import kotlin.math.min

data class PasswordStrength(
    val score: Int,
    val label: String,
    val colorHex: String,
    val percent: Float
)

object PasswordUtils {
    private val secureRandom = SecureRandom()
    private const val CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@#$%^&*"

    fun checkPasswordStrength(password: String): PasswordStrength {
        if (password.isEmpty()) {
            return PasswordStrength(0, "Very Weak", "#ef4444", 0f)
        }

        var scoreCounter = 0
        if (password.length >= 8) scoreCounter++
        if (password.length >= 12) scoreCounter++
        if (password.any { it.isUpperCase() }) scoreCounter++
        if (password.any { it.isLowerCase() }) scoreCounter++
        if (password.any { it.isDigit() }) scoreCounter++
        if (password.any { !it.isLetterOrDigit() }) scoreCounter++

        val score = min(4, (scoreCounter / 1.5).toInt())

        val colors = listOf("#ef4444", "#f97316", "#eab308", "#22c55e", "#16a34a")
        val labels = listOf("Very Weak", "Weak", "Fair", "Strong", "Very Strong")

        val safeScore = score.coerceIn(0, 4)
        return PasswordStrength(
            score = safeScore,
            label = labels[safeScore],
            colorHex = colors[safeScore],
            percent = (safeScore + 1) * 20f
        )
    }

    fun generatePassword(length: Int = 24): String {
        val sb = StringBuilder(length)
        for (i in 0 until length) {
            val randomIndex = secureRandom.nextInt(CHARS.length)
            sb.append(CHARS[randomIndex])
        }
        return sb.toString()
    }
}
