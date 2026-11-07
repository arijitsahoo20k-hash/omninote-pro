package com.omninote.pro.engine.ocr

/**
 * Result of formatting recognized handwriting.
 *
 * @property markdown plain text with inline `$...$` spans and `$$...$$` lines where maths was detected.
 * @property latexBlocks the display-math lines (without `$$`), in order, ready for a text box's LaTeX list.
 */
data class StemFormatted(
    val markdown: String,
    val latexBlocks: List<String>,
    val hasMath: Boolean
)

/**
 * Turns raw handwriting OCR output into Markdown with LaTeX for maths, physics and chemistry.
 *
 * Recognised, token by token: arrows (`->`, `<=>`, `=>`), comparisons (`<=`, `>=`, `!=`, `+-`, `~=`), Greek names and
 * letters, exponents and subscripts, degrees, fractions (`a/b`, `dy/dx`, `d/dx`), `sqrt(...)`, integrals, sums,
 * limits, trig/log functions, and chemical formulas (`H2SO4`, `Ca(OH)2`, `SO4^2-`, state symbols).
 * Runs of maths tokens are merged into one span; a line that is mostly maths and holds an equals sign, arrow,
 * integral, sum, limit or formula becomes a display block.
 *
 * Plain prose passes through untouched, so ordinary notes never gain stray `$` signs.
 */
object StemTextFormatter {

    fun format(raw: String): StemFormatted {
        val md = StringBuilder()
        val blocks = ArrayList<String>()
        var hasMath = false
        val lines = raw.replace("\r\n", "\n").split('\n')
        for ((i, line) in lines.withIndex()) {
            val result = formatLine(line.trim())
            if (result.block != null) {
                blocks.add(result.block)
                hasMath = true
            } else if (result.mathSpans > 0) {
                hasMath = true
            }
            md.append(result.text)
            if (i < lines.lastIndex) md.append('\n')
        }
        return StemFormatted(md.toString(), blocks, hasMath)
    }

    // =====================================================================================

    private class LineResult(val text: String, val block: String?, val mathSpans: Int)

    private class Tok(val raw: String, val latex: String, val core: Boolean, val structural: Boolean)

    private fun formatLine(line: String): LineResult {
        if (line.isEmpty()) return LineResult("", null, 0)
        val parts = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val toks = parts.map { convertToken(it) }
        if (toks.none { it.core }) return LineResult(line, null, 0)
        val inRun = BooleanArray(toks.size)
        for (i in toks.indices) if (toks[i].core) inRun[i] = true
        // Grow each core token over adjacent glue (coefficients, variables, operators).
        for (i in toks.indices) if (toks[i].core) {
            var l = i - 1
            while (l >= 0 && !inRun[l] && isGlue(toks[l].raw)) inRun[l--] = true
            var r = i + 1
            while (r < toks.size && !inRun[r] && isGlue(toks[r].raw)) inRun[r++] = true
        }
        val runTokens = inRun.count { it }
        val structural = toks.indices.any { inRun[it] && toks[it].structural } || (0 until toks.size).any { inRun[it] && toks[it].raw == "=" }
        if (runTokens == toks.size && structural) {
            val latex = toks.joinToString(" ") { it.latex }
            return LineResult("\$\$" + latex + "\$\$", latex, 1)
        }
        val sb = StringBuilder()
        var spans = 0
        var i = 0
        while (i < toks.size) {
            if (sb.isNotEmpty()) sb.append(' ')
            if (!inRun[i]) {
                sb.append(toks[i].raw)
                i++
                continue
            }
            var j = i
            while (j < toks.size && inRun[j]) j++
            sb.append('$').append((i until j).joinToString(" ") { toks[it].latex }).append('$')
            spans++
            i = j
        }
        return LineResult(sb.toString(), null, spans)
    }

    private fun isGlue(t: String): Boolean =
        GLUE_NUMBER.matches(t) || GLUE_LETTER.matches(t) || GLUE_OPERATOR.matches(t)

    // =====================================================================================
    // Token conversion
    // =====================================================================================

    private fun convertToken(raw: String): Tok {
        // Trailing prose punctuation stays outside the maths.
        var core = raw
        var trailing = ""
        while (core.length > 1 && core.last() in ",.;:!?") {
            trailing = core.last() + trailing
            core = core.dropLast(1)
        }
        chemistry(core)?.let { return Tok(raw, it + trailing, core = true, structural = true) }

        var s = core
        var changed = false
        var structural = false
        fun sub(re: Regex, with: (MatchResult) -> String, makeStructural: Boolean = false) {
            val out = re.replace(s) { with(it) }
            if (out != s) {
                s = out
                changed = true
                if (makeStructural) structural = true
            }
        }

        // Arrows and relations first (longest forms first).
        sub(Regex("<=>|<->|\u21CC"), { "\\rightleftharpoons" }, true)
        sub(Regex("-->|->|\u2192"), { "\\rightarrow" }, true)
        sub(Regex("<--|<-|\u2190"), { "\\leftarrow" }, true)
        sub(Regex("=>|\u21D2"), { "\\Rightarrow" }, true)
        sub(Regex("<=|\u2264"), { "\\le " })
        sub(Regex(">=|\u2265"), { "\\ge " })
        sub(Regex("!=|\u2260"), { "\\ne " })
        sub(Regex("\\+-|\u00B1"), { "\\pm " })
        sub(Regex("~=|\u2248"), { "\\approx " })
        sub(Regex("\\binfinity\\b|\\binf\\b|\u221E"), { "\\infty " })

        // Calculus and big operators.
        sub(Regex("\u222B|\\bint(?=_|\\^)|\\bintegral\\b"), { "\\int " }, true)
        sub(Regex("\u2211|\\bsum(?=_|\\^)"), { "\\sum " }, true)
        sub(Regex("\u220F|\\bprod(?=_|\\^)"), { "\\prod " }, true)
        sub(Regex("\\bpartial\\b|\u2202"), { "\\partial " })
        sub(Regex("(?:sqrt|\u221A)\\(([^()]*)\\)"), { "\\sqrt{${it.groupValues[1]}}" })
        sub(Regex("(?:sqrt|\u221A)([A-Za-z0-9]+)"), { "\\sqrt{${it.groupValues[1]}}" })
        sub(Regex("\\bd/d([a-z])\\b"), { "\\frac{d}{d${it.groupValues[1]}}" }, true)
        sub(Regex("\\b(d[a-z])/(d[a-z])\\b"), { "\\frac{${it.groupValues[1]}}{${it.groupValues[2]}}" }, true)
        sub(Regex("\\b(arcsin|arccos|arctan|sinh|cosh|tanh|sin|cos|tan|cot|log|ln|exp|lim)\\b(?=\\(|$|_|\\^)"), { "\\${it.groupValues[1]}" }, true)

        // Degrees, Greek.
        sub(Regex("(\\d)\\s?(?:\u00B0|deg\\b)"), { "${it.groupValues[1]}^{\\circ}" })
        for ((name, cmd) in GREEK_NAMES) sub(Regex("\\b$name\\b"), { "$cmd " })
        for ((ch, cmd) in GREEK_CHARS) if (s.indexOf(ch) >= 0) {
            s = s.replace(ch.toString(), "$cmd ")
            changed = true
        }

        // Fractions between simple operands.
        sub(FRACTION, { fractionOf(it) })

        // Exponents and subscripts.
        if (s.indexOf('\u00B2') >= 0 || SUPERSCRIPT_RUN.containsMatchIn(s)) {
            s = SUPERSCRIPT_RUN.replace(s) { "^{${it.value.map { c -> SUPERSCRIPTS.getValue(c) }.joinToString("")}}" }
            changed = true
        }
        sub(Regex("\\^\\(([^()]*)\\)"), { "^{${it.groupValues[1]}}" })
        sub(Regex("\\^(-?\\w+)"), { "^{${it.groupValues[1]}}" })
        sub(Regex("_(-?\\w+)"), { "_{${it.groupValues[1]}}" })
        sub(Regex("(?<=[\\w})])\\*(?=[\\w({\\\\])"), { "\\cdot " })

        if (!changed) return Tok(raw, raw, core = false, structural = false)
        return Tok(raw, s.trim() + trailing, core = true, structural = structural)
    }

    private fun fractionOf(m: MatchResult): String {
        val a = m.groupValues[1]
        val b = m.groupValues[2]
        val alphaA = a.all { it.isLetter() }
        val alphaB = b.all { it.isLetter() }
        if (alphaA && alphaB && a.length <= 5 && b.length <= 5) return m.value // units like m/s, mol/L
        val na = a.removeSurrounding("(", ")")
        val nb = b.removeSurrounding("(", ")")
        return "\\frac{$na}{$nb}"
    }

    // =====================================================================================
    // Chemistry
    // =====================================================================================

    /** LaTeX for a chemical formula token (optionally with a coefficient, charge, state symbol), or null. */
    private fun chemistry(token: String): String? {
        var t = token
        var state = ""
        STATE_SUFFIX.find(t)?.let {
            state = "\\text{(${it.groupValues[1]})}"
            t = t.substring(0, it.range.first)
        }
        var coefficient = ""
        val lead = Regex("^\\d+").find(t)
        if (lead != null && lead.value.length < t.length) {
            coefficient = lead.value
            t = t.substring(lead.value.length)
        }
        var charge: String? = null
        val chargeMatch = CHARGE_SUFFIX.find(t)
        if (chargeMatch != null) {
            val digits = chargeMatch.groupValues[1]
            val sign = chargeMatch.groupValues[2]
            if (chargeMatch.value.startsWith("^") || chargeMatch.range.first > 0) {
                charge = (if (digits == "1") "" else digits) + sign
                t = t.substring(0, chargeMatch.range.first)
            }
        }
        if (t.isEmpty() || !t[0].isUpperCase() && t[0] != '(') return null

        val out = StringBuilder()
        var i = 0
        var elements = 0
        var hasDigit = false
        var depth = 0
        var parens = false
        while (i < t.length) {
            val c = t[i]
            when {
                c == '(' -> {
                    depth++
                    parens = true
                    out.append('(')
                    i++
                }
                c == ')' -> {
                    if (depth == 0) return null
                    depth--
                    out.append(')')
                    i++
                    val d = digitsAt(t, i)
                    if (d.isNotEmpty()) {
                        out.append("_{").append(d).append('}')
                        hasDigit = true
                        i += d.length
                    }
                }
                c.isUpperCase() -> {
                    val two = if (i + 1 < t.length && t[i + 1].isLowerCase()) t.substring(i, i + 2) else null
                    val symbol = when {
                        two != null && two in ELEMENTS -> two
                        c.toString() in ELEMENTS -> c.toString()
                        else -> return null
                    }
                    out.append(symbol)
                    elements++
                    i += symbol.length
                    val d = digitsAt(t, i)
                    if (d.isNotEmpty()) {
                        out.append("_{").append(d).append('}')
                        hasDigit = true
                        i += d.length
                    }
                }
                else -> return null
            }
        }
        if (depth != 0) return null
        val accepted = elements >= 2 || hasDigit || charge != null || parens
        if (!accepted) return null
        val body = StringBuilder("\\mathrm{").append(out)
        if (charge != null) body.append("^{").append(charge).append('}')
        body.append('}')
        return coefficient + body + state
    }

    private fun digitsAt(s: String, from: Int): String {
        var j = from
        while (j < s.length && s[j].isDigit()) j++
        return s.substring(from, j)
    }

    // =====================================================================================

    private val GLUE_NUMBER = Regex("^[\\d.]+$")
    private val GLUE_LETTER = Regex("^\\(?[A-Za-z]\\)?$")
    private val GLUE_OPERATOR = Regex("^[=+\\-*/<>]+$")
    private val FRACTION = Regex(
        "(?<![\\w)}])(\\d+(?:\\.\\d+)?|[A-Za-z][A-Za-z0-9]*|\\([^()]+\\))/(\\d+(?:\\.\\d+)?|[A-Za-z][A-Za-z0-9]*|\\([^()]+\\))"
    )
    private val STATE_SUFFIX = Regex("\\((s|l|g|aq)\\)$")
    private val CHARGE_SUFFIX = Regex("\\^?(\\d*)([+-])$")
    private const val SUPERSCRIPT_CHARS = "\u2070\u00B9\u00B2\u00B3\u2074\u2075\u2076\u2077\u2078\u2079\u207B\u207A"
    private val SUPERSCRIPT_RUN = Regex("[$SUPERSCRIPT_CHARS]+")
    private val SUPERSCRIPTS: Map<Char, String> = mapOf(
        '\u2070' to "0", '\u00B9' to "1", '\u00B2' to "2", '\u00B3' to "3", '\u2074' to "4",
        '\u2075' to "5", '\u2076' to "6", '\u2077' to "7", '\u2078' to "8", '\u2079' to "9",
        '\u207B' to "-", '\u207A' to "+"
    )

    private val GREEK_NAMES: List<Pair<String, String>> = listOf(
        "alpha" to "\\alpha", "beta" to "\\beta", "gamma" to "\\gamma", "delta" to "\\delta",
        "epsilon" to "\\epsilon", "zeta" to "\\zeta", "theta" to "\\theta", "kappa" to "\\kappa",
        "lambda" to "\\lambda", "mu" to "\\mu", "nu" to "\\nu", "xi" to "\\xi", "pi" to "\\pi",
        "rho" to "\\rho", "sigma" to "\\sigma", "tau" to "\\tau", "upsilon" to "\\upsilon",
        "phi" to "\\phi", "chi" to "\\chi", "psi" to "\\psi", "omega" to "\\omega",
        "Gamma" to "\\Gamma", "Delta" to "\\Delta", "Theta" to "\\Theta", "Lambda" to "\\Lambda",
        "Xi" to "\\Xi", "Pi" to "\\Pi", "Sigma" to "\\Sigma", "Phi" to "\\Phi", "Psi" to "\\Psi",
        "Omega" to "\\Omega"
    )

    private val GREEK_CHARS: List<Pair<Char, String>> = listOf(
        '\u03B1' to "\\alpha", '\u03B2' to "\\beta", '\u03B3' to "\\gamma", '\u03B4' to "\\delta",
        '\u03B5' to "\\epsilon", '\u03B8' to "\\theta", '\u03BB' to "\\lambda", '\u03BC' to "\\mu",
        '\u03BD' to "\\nu", '\u03C0' to "\\pi", '\u03C1' to "\\rho", '\u03C3' to "\\sigma",
        '\u03C4' to "\\tau", '\u03C6' to "\\phi", '\u03C7' to "\\chi", '\u03C8' to "\\psi",
        '\u03C9' to "\\omega", '\u0394' to "\\Delta", '\u03A3' to "\\Sigma", '\u03A9' to "\\Omega",
        '\u03A6' to "\\Phi", '\u03A8' to "\\Psi", '\u0398' to "\\Theta", '\u039B' to "\\Lambda"
    )

    private val ELEMENTS: Set<String> = (
        "H He Li Be B C N O F Ne Na Mg Al Si P S Cl Ar K Ca Sc Ti V Cr Mn Fe Co Ni Cu Zn Ga Ge As Se Br Kr " +
            "Rb Sr Y Zr Nb Mo Tc Ru Rh Pd Ag Cd In Sn Sb Te I Xe Cs Ba La Ce Pr Nd Pm Sm Eu Gd Tb Dy Ho Er Tm " +
            "Yb Lu Hf Ta W Re Os Ir Pt Au Hg Tl Pb Bi Po At Rn Fr Ra Ac Th Pa U Np Pu Am Cm"
        ).split(' ').toSet()
}
