package com.opendroid.ai.core.llm.prompts

/**
 * Makes third-party text unable to form a fence tag such as `</untrusted_message>`.
 *
 * The angle brackets are mapped to look-alikes rather than the tags stripped: stripping is
 * beaten by nesting, because removing the inner tag of `</untrusted_<untrusted_message>message>`
 * is what assembles the outer one.
 */
internal fun neutralizeFenceDelimiters(text: String): String =
    text.replace('<', '\u2039').replace('>', '\u203A')
