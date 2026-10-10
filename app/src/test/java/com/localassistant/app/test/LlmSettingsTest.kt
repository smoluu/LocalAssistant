package com.localassistant.app.test

import com.localassistant.app.data.remote.ApiClient
import org.junit.Test

/**
 * Unit tests for the chat-template argument field the Settings screen exposes.
 *
 * That field is typed on a phone and its output is spliced straight into every
 * chat request body, so the two things that matter are that a half-typed line
 * can never inject stray JSON, and that the arguments the servers actually
 * accept (enable_thinking / reasoning_effort / preserve_thinking) survive
 * untouched.
 */
class LlmSettingsTest {

    @Test
    fun emptyFieldSendsNothing() {
        assert(ApiClient.buildChatTemplateKwargsJson("") == null) {
            "an empty field must leave the request body untouched"
        }
        assert(ApiClient.buildChatTemplateKwargsJson("   \n\n") == null) {
            "whitespace is not an argument"
        }
    }

    @Test
    fun booleansAndNumbersKeepTheirJsonType() {
        assert(ApiClient.buildChatTemplateKwargsJson("enable_thinking=false") ==
                "{\"enable_thinking\":false}") {
            "a chat-template boolean must be JSON false, not the string \"false\""
        }
        assert(ApiClient.buildChatTemplateKwargsJson("preserve_thinking=true") ==
                "{\"preserve_thinking\":true}") {
            "a chat-template boolean must be JSON true, not the string \"true\""
        }
        assert(ApiClient.buildChatTemplateKwargsJson("max_thinking_tokens=512") ==
                "{\"max_thinking_tokens\":512}") {
            "a numeric argument must not be quoted"
        }
        assert(ApiClient.buildChatTemplateKwargsJson("temperature=0.7") ==
                "{\"temperature\":0.7}") {
            "a fractional argument must not be quoted"
        }
    }

    @Test
    fun bareWordsAreStringsAndQuotesAreIgnored() {
        assert(ApiClient.buildChatTemplateKwargsJson("reasoning_effort=low") ==
                "{\"reasoning_effort\":\"low\"}") {
            "an enum argument has to be a JSON string"
        }
        // The Settings screen shows the JSON spelling in its help text, so the
        // quoted form has to mean the same thing as the bare one.
        assert(ApiClient.buildChatTemplateKwargsJson("reasoning_effort=\"low\"") ==
                "{\"reasoning_effort\":\"low\"}") {
            "quotes the user copied from a JSON snippet must not be doubled"
        }
        assert(ApiClient.buildChatTemplateKwargsJson("reasoning_effort='medium'") ==
                "{\"reasoning_effort\":\"medium\"}") {
            "single quotes are what a phone keyboard offers"
        }
    }

    @Test
    fun onePerLineAndCommaSeparatedAreTheSame() {
        val multiline = """
            enable_thinking=false
            reasoning_effort=low
            preserve_thinking=true
        """.trimIndent()
        assert(ApiClient.buildChatTemplateKwargsJson(multiline) ==
            "{\"enable_thinking\":false,\"reasoning_effort\":\"low\",\"preserve_thinking\":true}") {
            "one key=value per line is the documented spelling, in the order typed"
        }
        assert(ApiClient.buildChatTemplateKwargsJson("enable_thinking=false, reasoning_effort=low") ==
                "{\"enable_thinking\":false,\"reasoning_effort\":\"low\"}") {
            "the JSON-style comma separator is accepted too"
        }
    }

    @Test
    fun commentsAndHalfTypedLinesAreSkipped() {
        val sloppy = """
            # turn thinking off
            enable_thinking=false

            oops
            =orphan
            =
            bad key=value
        """.trimIndent()
        assert(ApiClient.buildChatTemplateKwargsJson(sloppy) ==
                "{\"enable_thinking\":false}") {
            "only the well-formed pair may be sent, or every request would break"
        }
        assert(ApiClient.buildChatTemplateKwargsJson("# only a comment") == null) {
            "a comment-only field sends nothing"
        }
    }

    @Test
    fun keysAreLimitedToBareIdentifiers() {
        // Dotted and dashed names are real chat-template keys, so they stay
        // allowed; a brace or quote in a key is the only way stray JSON could
        // be injected into the request body.
        assert(ApiClient.buildChatTemplateKwargsJson("mm.start_token=1") ==
                "{\"mm.start_token\":1}") {
            "dotted names are valid chat-template keys"
        }
        assert(ApiClient.buildChatTemplateKwargsJson("eos-token=0") ==
                "{\"eos-token\":0}") {
            "dashed names are valid chat-template keys"
        }
        assert(ApiClient.buildChatTemplateKwargsJson("evil}=bad") == null) {
            "a key that is not an identifier must never reach the request body"
        }
    }
}
