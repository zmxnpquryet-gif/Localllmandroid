package com.localllm.android.ui.markdown

/**
 * A readable Unicode subset of TeX for inline chat math. This is not a full TeX
 * typesetting engine; unsupported commands stay visible rather than disappearing.
 */
object SimpleMathFormatter {

    private val commands = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε",
        "theta" to "θ", "lambda" to "λ", "mu" to "μ", "pi" to "π", "rho" to "ρ",
        "sigma" to "σ", "tau" to "τ", "phi" to "φ", "omega" to "ω", "Gamma" to "Γ",
        "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Pi" to "Π", "Sigma" to "Σ",
        "Phi" to "Φ", "Omega" to "Ω", "times" to "×", "cdot" to "·", "pm" to "±",
        "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥", "ne" to "≠", "neq" to "≠",
        "infty" to "∞", "sum" to "∑", "int" to "∫", "partial" to "∂", "nabla" to "∇",
        "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "approx" to "≈"
    )

    fun format(source: String): String {
        var value = source.trim()
            .replace("\\left", "")
            .replace("\\right", "")
            .replace("\\!", "")
            .replace("\\,", " ")
            .replace("\\;", " ")
            .replace("\\quad", "  ")
            .replace("\\qquad", "    ")

        value = replaceCommandWithGroup(value, "frac", 2) { args -> "(${format(args[0])})/(${format(args[1])})" }
        value = replaceCommandWithGroup(value, "sqrt", 1) { args -> "√(${format(args[0])})" }
        value = Regex("\\\\text\\{([^{}]*)\\}").replace(value) { it.groupValues[1] }
        value = Regex("\\\\mathrm\\{([^{}]*)\\}").replace(value) { format(it.groupValues[1]) }
        value = Regex("\\\\operatorname\\{([^{}]*)\\}").replace(value) { it.groupValues[1] }

        repeat(8) {
            value = Regex("\\\\[A-Za-z]+").replace(value) { match ->
                val name = match.value.removePrefix("\\")
                commands[name] ?: match.value
            }
        }

        value = replaceScripts(value, '^', true)
        value = replaceScripts(value, '_', false)
        return value.replace("{", "").replace("}", "").replace("~", " ")
    }

    private fun replaceCommandWithGroup(
        input: String,
        command: String,
        count: Int,
        transform: (List<String>) -> String
    ): String {
        var value = input
        val needle = "\\$command"
        var start = value.indexOf(needle)
        while (start >= 0) {
            var cursor = start + needle.length
            val args = mutableListOf<String>()
            repeat(count) {
                while (cursor < value.length && value[cursor].isWhitespace()) cursor++
                if (cursor >= value.length || value[cursor] != '{') return@repeat
                val close = matchingBrace(value, cursor)
                if (close < 0) return@repeat
                args += value.substring(cursor + 1, close)
                cursor = close + 1
            }
            if (args.size != count) {
                start = value.indexOf(needle, start + needle.length)
                continue
            }
            val replacement = transform(args)
            value = value.substring(0, start) + replacement + value.substring(cursor)
            start = value.indexOf(needle, start + replacement.length)
        }
        return value
    }

    private fun matchingBrace(value: String, open: Int): Int {
        var depth = 0
        for (i in open until value.length) {
            when (value[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return i
            }
        }
        return -1
    }

    private fun replaceScripts(input: String, marker: Char, superscript: Boolean): String {
        val sb = StringBuilder()
        var i = 0
        while (i < input.length) {
            if (input[i] != marker || i + 1 >= input.length) {
                sb.append(input[i++])
                continue
            }
            val (text, next) = if (input[i + 1] == '{') {
                val close = matchingBrace(input, i + 1)
                if (close < 0) input[i + 1].toString() to i + 2 else input.substring(i + 2, close) to close + 1
            } else {
                input[i + 1].toString() to i + 2
            }
            sb.append(if (superscript) toSuperscript(text) else toSubscript(text))
            i = next
        }
        return sb.toString()
    }

    private fun toSuperscript(text: String): String = text.map { c ->
        when (c) {
            '0' -> '⁰'; '1' -> '¹'; '2' -> '²'; '3' -> '³'; '4' -> '⁴'; '5' -> '⁵'; '6' -> '⁶'
            '7' -> '⁷'; '8' -> '⁸'; '9' -> '⁹'; '+' -> '⁺'; '-' -> '⁻'; '=' -> '⁼'; '(' -> '⁽'
            ')' -> '⁾'; 'n' -> 'ⁿ'; 'i' -> 'ⁱ'; else -> c
        }
    }.joinToString("")

    private fun toSubscript(text: String): String = text.map { c ->
        when (c) {
            '0' -> '₀'; '1' -> '₁'; '2' -> '₂'; '3' -> '₃'; '4' -> '₄'; '5' -> '₅'; '6' -> '₆'
            '7' -> '₇'; '8' -> '₈'; '9' -> '₉'; '+' -> '₊'; '-' -> '₋'; '=' -> '₌'; '(' -> '₍'
            ')' -> '₎'; 'a' -> 'ₐ'; 'e' -> 'ₑ'; 'i' -> 'ᵢ'; 'o' -> 'ₒ'; 'x' -> 'ₓ' else -> c
        }
    }.joinToString("")
}
