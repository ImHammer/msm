package io.github.imhammer.monitoring

object PayloadValidator {

    private val idRegex = "^[a-zA-Z0-9_\\-:]+$".toRegex()
    private val controlCharsRegex = "[\\x00-\\x1F\\x7F]".toRegex()

    fun validateId(id: String?, fieldName: String): String {
        if (id.isNullOrBlank()) {
            throw IllegalArgumentException("Campo '$fieldName' não pode ser nulo ou vazio.")
        }
        val trimmed = id.trim()
        if (trimmed.length > 64) {
            throw IllegalArgumentException("Campo '$fieldName' excede o limite máximo de 64 caracteres (tamanho: ${trimmed.length}).")
        }
        if (!idRegex.matches(trimmed)) {
            throw IllegalArgumentException("Campo '$fieldName' contém caracteres inválidos ('$trimmed'). Use apenas letras, números, '_', '-' e ':'.")
        }
        return trimmed
    }

    fun validateName(name: String?, fallback: String, maxLen: Int = 100): String {
        if (name.isNullOrBlank()) return fallback
        val clean = name.trim().replace(controlCharsRegex, "")
        if (clean.length > maxLen) {
            throw IllegalArgumentException("O nome excede o limite máximo de $maxLen caracteres.")
        }
        return clean.ifEmpty { fallback }
    }

    fun validateValue(value: String?, valueType: ValueType): String {
        if (value.isNullOrBlank()) {
            throw IllegalArgumentException("O campo 'value' não pode ser nulo ou vazio.")
        }
        val trimmed = value.trim()

        when (valueType) {
            ValueType.PERCENTAGE -> {
                val num = trimmed.replace("%", "").toDoubleOrNull()
                    ?: throw IllegalArgumentException("Valor '$trimmed' inválido para PERCENTAGE. Esperado número entre 0 e 100.")
                if (num < 0.0 || num > 100.0) {
                    throw IllegalArgumentException("Valor percentual '$num' fora da faixa permitida [0.0 - 100.0].")
                }
                return String.format(java.util.Locale.US, "%.2f", num)
            }
            ValueType.CURRENCY -> {
                val num = trimmed.replace("[R$\\s,]".toRegex(), "").toDoubleOrNull()
                    ?: throw IllegalArgumentException("Valor '$trimmed' inválido para CURRENCY. Esperado número monetário.")
                return String.format(java.util.Locale.US, "%.2f", num)
            }
            ValueType.BYTES -> {
                val num = trimmed.toLongOrNull()
                    ?: throw IllegalArgumentException("Valor '$trimmed' inválido para BYTES. Esperado número inteiro de bytes.")
                if (num < 0) {
                    throw IllegalArgumentException("Valor em bytes não pode ser negativo ($num).")
                }
                return num.toString()
            }
            ValueType.NUMBER -> {
                val num = trimmed.toDoubleOrNull()
                    ?: throw IllegalArgumentException("Valor '$trimmed' inválido para NUMBER. Esperado valor numérico.")
                return if (num % 1.0 == 0.0) num.toLong().toString() else num.toString()
            }
            ValueType.BOOLEAN -> {
                val lower = trimmed.lowercase()
                if (lower != "true" && lower != "false") {
                    throw IllegalArgumentException("Valor '$trimmed' inválido para BOOLEAN. Esperado 'true' ou 'false'.")
                }
                return lower
            }
            ValueType.STATUS -> {
                if (trimmed.length > 32) {
                    throw IllegalArgumentException("Valor do status excede 32 caracteres.")
                }
                return trimmed.uppercase()
            }
            ValueType.LOG_LINE -> {
                if (trimmed.length > 4096) {
                    throw IllegalArgumentException("Linha de log excede o tamanho máximo de 4096 caracteres.")
                }
                return trimmed.replace(controlCharsRegex, " ")
            }
            ValueType.TEXT -> {
                if (trimmed.length > 1024) {
                    throw IllegalArgumentException("Texto excede o limite máximo de 1024 caracteres.")
                }
                return trimmed
            }
        }
    }

    fun validateUnit(unit: String?): String? {
        if (unit.isNullOrBlank()) return null
        val clean = unit.trim().replace(controlCharsRegex, "")
        return if (clean.length > 16) clean.substring(0, 16) else clean
    }
}
