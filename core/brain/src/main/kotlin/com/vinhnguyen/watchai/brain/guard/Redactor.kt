package com.vinhnguyen.watchai.brain.guard

import kotlin.math.ln

/**
 * Takes secrets and ways out of text before it goes to a cloud model: one-time codes, card
 * numbers, IBANs, passwords, keys and tokens, links, phone numbers and email addresses. Each is
 * replaced by what it was ("[code hidden]"), so the model can still tell the user there was one.
 * A link keeps its site, so "a link to dhl.de" still makes sense; its path (tokens, reset links)
 * doesn't survive.
 */
public object Redactor {
    public enum class Kind {
        PRIVATE_KEY,
        KEY,
        LINK,
        EMAIL,
        IBAN,
        CARD,
        PASSWORD,
        CODE,
        PHONE,
    }

    public data class Cleaned(
        val text: String,
        val hidden: List<Kind>,
    )

    public fun clean(text: String): Cleaned {
        val hidden = ArrayList<Kind>()
        var s = text
        s = PRIVATE_KEY.replace(s) {
            hidden += Kind.PRIVATE_KEY
            "[private key hidden]"
        }
        s = JWT.replace(s) {
            hidden += Kind.KEY
            "[key hidden]"
        }
        s = KNOWN_KEYS.replace(s) {
            hidden += Kind.KEY
            "[key hidden]"
        }
        s = LINK.replace(s) { m ->
            val raw = m.value
            val url = raw.trimEnd('.', ',', ';', ':', '!', '?')
            hidden += Kind.LINK
            "[link to ${host(url)}]" + raw.substring(url.length)
        }
        s = EMAIL.replace(s) {
            hidden += Kind.EMAIL
            "[email address hidden]"
        }
        s = IBAN.replace(s) { m -> if (ibanValid(m.value)) "[IBAN hidden]".also { hidden += Kind.IBAN } else m.value }
        s = CARD.replace(s) { m -> if (luhn(m.value.filter(Char::isDigit))) "[card number hidden]".also { hidden += Kind.CARD } else m.value }
        // Phone numbers before codes, so a number isn't cut into "codes" with half of it left.
        s = PHONE.replace(s) { m ->
            val digits = m.value.count(Char::isDigit)
            if (digits in 8..15 && !DATE.matches(m.value.trim())) "[phone number hidden]".also { hidden += Kind.PHONE } else m.value
        }
        s = codes(s, hidden)
        s = LONG_TOKEN.replace(s) { m -> if (looksRandom(m.value)) "[key hidden]".also { hidden += Kind.KEY } else m.value }
        s = SECRET.replace(s) {
            hidden += Kind.PASSWORD
            "[hidden]"
        }
        return Cleaned(s, hidden)
    }

    /**
     * One-time codes, in whatever language or digits the message is written: nothing here looks for
     * a word like "code". Shapes that are codes anyway (G-482913, X7K9PQ) and bare six-digit numbers
     * always go; other bare numbers of 4 to 8 digits only in a short message, as a code's is. A year,
     * a date, a time or a price stays.
     */
    private fun codes(
        text: String,
        hidden: MutableList<Kind>,
    ): String {
        var s = text
        for (pattern in SHAPED_CODES) {
            s = pattern.replace(s) {
                hidden += Kind.CODE
                "[code hidden]"
            }
        }
        val before = s
        s = BARE_NUMBER.replace(before) { m -> if (isCode(before, m)) "[code hidden]".also { hidden += Kind.CODE } else m.value }
        return s
    }

    private fun isCode(
        text: String,
        m: MatchResult,
    ): Boolean {
        val digits = m.value.filter(Char::isDigit)
        if (priced(text, m.range)) return false
        if (digits.length == 6) return true
        val value = digits.fold(0L) { n, c -> n * 10 + Character.digit(c, 10) }
        if (digits.length == 4 && value in YEARS) return false
        return messageLength(text, m.range) <= SHORT_MESSAGE
    }

    /** A currency sign right before or after it, in any currency: "12,50 €", "₹4999", "$ 1299". */
    private fun priced(
        text: String,
        range: IntRange,
    ): Boolean {
        fun currency(at: Int) = at in text.indices && Character.getType(text[at]) == Character.CURRENCY_SYMBOL.toInt()
        val before = if (range.first >= 2 && text[range.first - 1] == ' ') range.first - 2 else range.first - 1
        val after = if (range.last + 2 < text.length && text[range.last + 1] == ' ') range.last + 2 else range.last + 1
        return currency(before) || currency(after)
    }

    /** How long the one message around [range] is: the data puts " | " or a new line between messages. */
    private fun messageLength(
        text: String,
        range: IntRange,
    ): Int {
        val start = SEPARATORS.maxOf { sep -> text.lastIndexOf(sep, range.first).let { if (it < 0) 0 else it + sep.length } }
        val end = SEPARATORS.minOf { sep -> text.indexOf(sep, range.last).let { if (it < 0) text.length else it } }
        return end - start
    }

    private val PRIVATE_KEY = Regex("""-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\s\S]*?(?:-----END [A-Z0-9 ]*PRIVATE KEY-----|$)""")
    private val JWT = Regex("""\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}""")
    private val KNOWN_KEYS =
        Regex(
            """\b(?:sk-(?:proj-|ant-)?[A-Za-z0-9_-]{16,}|[sr]k_(?:live|test)_[A-Za-z0-9]{16,}|gh[pousr]_[A-Za-z0-9]{20,}|""" +
                """github_pat_[A-Za-z0-9_]{20,}|glpat-[A-Za-z0-9_-]{20,}|xox[abprs]-[A-Za-z0-9-]{10,}|AKIA[0-9A-Z]{16}|""" +
                """AIza[0-9A-Za-z_-]{35}|hf_[A-Za-z0-9]{30,})""",
        )
    private val LINK = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"'()\[\]]+""")
    private val EMAIL = Regex("""\b[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}\b""")
    private val IBAN = Regex("""\b[A-Z]{2}\d{2}(?: ?[A-Z0-9]{4}){2,7}(?: ?[A-Z0-9]{1,4})?\b""")
    private val CARD = Regex("""(?<!\d)(?:\d[ -]?){12,18}\d(?!\d)""")

    /**
     * A word of letters and digits, like most passwords ("Sonne123", "hunter2!"), found by its shape
     * rather than by a word like "password" before it, so it works whatever the language. Latin
     * letters only: passwords are typed on a keyboard, and scripts without spaces (Chinese, Thai)
     * would otherwise turn a whole sentence into one "word". It may stand in quotes or brackets and end a
     * sentence ("is Sonne123."); a dot or a colon inside it makes it a version, a file or a time instead.
     */
    private val SECRET =
        Regex(
            """(?:(?<=[("'])|(?<![\x21-\x7E]))(?=[\x21-\x7E]*[A-Za-z])(?=[\x21-\x7E]*[0-9])[\x21-\x2C\x30-\x39\x3B-\x7E]{6,}""" +
                """(?=[.,:;!?]*(?![\x21-\x7E]))""",
        )

    private val SHAPED_CODES =
        listOf(
            // G-123456, Google's style.
            Regex("""\b[A-Z]-\d{4,8}\b"""),
            // Letters and digits mixed, all caps, like X7K9PQ.
            Regex("""\b(?=[A-Z0-9]*\d[A-Z0-9]*\d)(?=[A-Z0-9]*[A-Z])[A-Z0-9]{6,8}\b"""),
        )

    /** 4 to 8 digits of any script on their own (or 123 456), not part of a date, a time, a price or a longer number. */
    private val BARE_NUMBER =
        Regex("""(?<!\p{Nd}|\p{Nd}[.:/,])(?:\p{Nd}{3}[ -]\p{Nd}{3}|\p{Nd}{4,8})(?!\p{Nd}|[.:/,]\p{Nd})""")
    private val YEARS = 1900L..2099L

    /** A code comes in a short message (an SMS is at most 160 letters); in a long one, a number is more often something else. */
    private const val SHORT_MESSAGE = 200
    private val SEPARATORS = listOf(" | ", "\n")
    private val PHONE =
        Regex(
            """(?<![\w+])(?:\+|00)\d[\d \-/().]{6,20}\d|(?<!\w)0\d{2,5}[ \-/.]?\d[\d \-/.]{4,14}\d|""" +
                """\(\d{3}\)\s?\d{3}[ -]\d{4}\b|\b\d{3}[ -]\d{3}[ -]\d{4}\b""",
        )
    private val DATE = Regex("""\d{1,4}[./-]\d{1,2}[./-]\d{1,4}""")
    private val LONG_TOKEN = Regex("""\b[A-Za-z0-9+/=_-]{32,}\b""")

    private fun host(url: String): String = url
        .replaceFirst(Regex("(?i)^https?://"), "")
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .substringAfter('@')
        .substringBefore(':')
        .lowercase()
        .ifEmpty { "a website" }

    private fun ibanValid(candidate: String): Boolean {
        val iban = candidate.replace(" ", "")
        if (iban.length !in 15..34) return false
        val rearranged = iban.substring(4) + iban.substring(0, 4)
        var remainder = 0
        for (c in rearranged) {
            val value = if (c.isDigit()) c.digitToInt().toString() else (c - 'A' + 10).toString()
            for (d in value) remainder = (remainder * 10 + d.digitToInt()) % 97
        }
        return remainder == 1
    }

    private fun luhn(digits: String): Boolean {
        if (digits.length !in 13..19) return false
        var sum = 0
        digits.reversed().forEachIndexed { i, c ->
            var d = c.digitToInt()
            if (i % 2 == 1) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
        }
        return sum % 10 == 0
    }

    /** Keys and tokens: long, with letters and digits mixed, and close to random. */
    private fun looksRandom(token: String): Boolean {
        if (token.none(Char::isDigit) || token.none(Char::isLetter)) return false
        val counts = token.groupingBy { it }.eachCount().values
        val entropy = -counts.sumOf { n -> (n.toDouble() / token.length).let { p -> p * ln(p) / ln(2.0) } }
        return entropy >= 3.5
    }
}
