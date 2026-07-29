package com.opendroid.ai.core.crash

/**
 * Strips credential material out of text on its way into the crash log.
 *
 * This matters because the crash log is a *sharing* sink, not just a storage
 * one: a record is rendered on screen, copied to the clipboard, and put into an
 * `ACTION_SEND` intent aimed at an arbitrary third-party app. Anything a
 * provider echoed back into an exception message travels with it.
 *
 * The exposure is not hypothetical. Several providers build their failure
 * messages straight out of the response body - for example
 * `IOException("OpenAI request failed: Code ${'$'}{response.code} - ${'$'}responseBody")`
 * in `OpenAIProvider` - and OpenAI's 401 body echoes a partially masked copy of
 * the submitted key. Gemini goes further and carries its key in the request URL
 * (`GeminiProvider.kt`, `?key=${'$'}apiKey`), so any message quoting that URL
 * carries the key verbatim.
 *
 * Redaction happens at capture time rather than at share time, so the secret
 * never reaches the database in the first place. There is no un-redacted copy to
 * leak later.
 *
 * Deliberately conservative: it targets shapes that are unambiguously credential
 * material. A blanket "long random-looking token" rule would shred ordinary
 * stack frames. That means this is a mitigation, not a guarantee - a provider
 * that echoes an unprefixed key (Cohere's are bare alphanumerics) still gets
 * through. The project-wide rule is being decided in issue #40; this class is
 * the crash-log sink's share of it and should be folded into that rule when it
 * lands.
 */
object CrashLogRedactor {

    const val REDACTED = "[REDACTED]"

    /**
     * Ordered: the contextual rules run before the prefix rules so that a key
     * inside a URL is replaced whole rather than leaving the `key=` behind.
     */
    private val rules: List<Pair<Regex, String>> = listOf(
        // Secrets carried in a URL query string. Gemini's key transport.
        Regex(
            """([?&](?:key|api[_-]?key|access[_-]?token|auth[_-]?token|token|password|secret)=)[^&\s"'<>\\]+""",
            RegexOption.IGNORE_CASE
        ) to "$1$REDACTED",

        // Authorization / API-key request headers, however they got quoted.
        Regex("""(Bearer\s+)[A-Za-z0-9._\-*+/=]+""", RegexOption.IGNORE_CASE) to "$1$REDACTED",
        // The value alternatives are skipped because the Bearer rule above has
        // already handled that header, and re-matching would collapse the
        // `Bearer ` prefix into the replacement.
        Regex(
            """((?:authorization|x-api-key|x-goog-api-key|api-key)\s*[:=]\s*)(?!Bearer\b|\Q$REDACTED\E)["']?[A-Za-z0-9._\-*+/=]+["']?""",
            RegexOption.IGNORE_CASE
        ) to "$1$REDACTED",

        // JSON/map fields, which is how an echoed provider error body carries one.
        Regex(
            """(["'](?:api[_-]?key|access[_-]?token|token|secret|password)["']\s*:\s*)["'][^"']*["']""",
            RegexOption.IGNORE_CASE
        ) to "$1\"$REDACTED\"",

        // Vendor-prefixed keys anywhere in the text. `*` is allowed inside the
        // token on purpose: OpenAI's 401 body echoes a server-masked form of the
        // real key (`sk-inval**********6789`), which is still key material.
        Regex("""\bsk-[A-Za-z0-9_\-*]{6,}""") to REDACTED,
        Regex("""\bAIza[A-Za-z0-9_\-*]{10,}""") to REDACTED,
        Regex("""\b(?:gsk|hf|r8|shpat|ghp|gho|ghs|glpat)_[A-Za-z0-9_\-*]{8,}""") to REDACTED,
        Regex("""\bxai-[A-Za-z0-9_\-*]{8,}""") to REDACTED,
        Regex("""\bcsk-[A-Za-z0-9_\-*]{8,}""") to REDACTED
    )

    /**
     * Never throws. This runs on the crash path, where an exception would cost
     * the whole report; an unredacted-but-present record would be worse than
     * useless, so a failure returns a placeholder rather than the raw text.
     */
    fun redact(text: String): String = try {
        rules.fold(text) { acc, (pattern, replacement) -> pattern.replace(acc, replacement) }
    } catch (t: Throwable) {
        "[redaction failed: text withheld]"
    }
}
