package org.siros.wwwallet.bridging

import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import timber.log.Timber
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Calls INTO the page and waits for an answer.
 *
 * The existing bridge only goes one way: JavaScript calls a
 * `@JavascriptInterface` method and native resolves a promise the page is
 * holding. Hosting protocol rather than forwarding bytes needs the other
 * direction too, because the things the page still owns - the candidate
 * credential list, the user's consent, a signature from a key that never
 * leaves the page - are needed *during* a native session, not before it.
 *
 * ## Wire shape
 *
 * Native evaluates `nativeWrapper.__invoke__(id, name, payloadB64)`. The page
 * looks up a handler registered under `name`, awaits it, and answers with
 * `nativeWrapper.__reply__(id, resultB64)` or
 * `nativeWrapper.__replyError__(id, code, message)`.
 *
 * ## Why base64
 *
 * The existing resolve path interpolates its argument into a single-quoted
 * JavaScript string with no escaping, which is why one caller emits `\\n`
 * by hand to survive the trip. That path is left alone here - WebAuthn is
 * its only remaining user and its fields are already base64url - but nothing
 * new should be built on it. Payloads in both directions are UTF-8 JSON in
 * base64, so quoting, newlines and the U+2028/U+2029 hazard all stop
 * existing, and a binary payload needs no separate encoding.
 *
 * ## Page lifecycle
 *
 * A navigation replaces the page and its handler registry with it, so every
 * call outstanding at that moment can never be answered. [invalidateAll] is
 * called from the injection point, which runs on every page commit, and
 * fails those calls rather than leaving a session waiting on a promise no
 * one holds any more.
 */
class JsCallHost(
    private val webView: WebView,
    private val scope: CoroutineScope,
    private val bridgeName: String = WalletJsBridge.JAVASCRIPT_BRIDGE_NAME,
) {
    /** A call the page answered with `__replyError__`, or that was invalidated. */
    class JsCallException(
        val code: String,
        override val message: String,
    ) : Exception("$code: $message")

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Result<JsonElement>>>()

    /**
     * Invokes [name] in the page and suspends until it answers.
     *
     * @throws JsCallException if the page reports an error, does not answer
     *   within [timeoutMs], or is replaced before answering.
     */
    suspend fun call(
        name: String,
        payload: JsonElement,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): JsonElement {
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Result<JsonElement>>()
        pending[id] = deferred

        evaluate("$bridgeName.__invoke__('$id', '${escapeName(name)}', '${encode(payload)}')")

        val outcome = withTimeoutOrNull(timeoutMs) { deferred.await() }
        pending.remove(id)

        if (outcome == null) {
            // Tell the page to stop working on it. Best-effort: if the page has
            // gone, this evaluation is a no-op.
            evaluate("$bridgeName.__cancel__('$id')")
            throw JsCallException("timeout", "the page did not answer '$name' within ${timeoutMs}ms")
        }
        return outcome.getOrThrow()
    }

    /** Fire-and-forget. Used for progress and terminal notifications, where there is nothing to wait for. */
    fun notify(
        name: String,
        payload: JsonElement,
    ) {
        evaluate("$bridgeName.__notify__('${escapeName(name)}', '${encode(payload)}')")
    }

    /** Called from the bridge's `@JavascriptInterface` reply methods. */
    fun reply(
        id: String,
        payloadB64: String,
    ) {
        val deferred = pending.remove(id)
        if (deferred == null) {
            Timber.w("Reply for unknown call '$id' - it timed out or the page was replaced.")
            return
        }
        deferred.complete(
            runCatching { decode(payloadB64) },
        )
    }

    /** Called from the bridge's `@JavascriptInterface` reply methods. */
    fun replyError(
        id: String,
        code: String,
        message: String,
    ) {
        val deferred = pending.remove(id)
        if (deferred == null) {
            Timber.w("Error reply for unknown call '$id' - it timed out or the page was replaced.")
            return
        }
        deferred.complete(Result.failure(JsCallException(code, message)))
    }

    /**
     * Fails every outstanding call. Called when the page is (re)injected, which
     * happens on every page commit and therefore after every navigation.
     */
    fun invalidateAll(reason: String) {
        if (pending.isEmpty()) return
        Timber.i("Invalidating ${pending.size} outstanding call(s) into the page: $reason")
        val snapshot = pending.keys.toList()
        snapshot.forEach { id ->
            pending.remove(id)?.complete(
                Result.failure(JsCallException("page_replaced", reason)),
            )
        }
    }

    private fun evaluate(script: String) {
        scope.launch {
            webView.evaluateJavascript(script) {}
        }
    }

    private fun encode(payload: JsonElement): String = Base64.getEncoder().encodeToString(json.encodeToString(JsonElement.serializer(), payload).toByteArray(Charsets.UTF_8))

    private fun decode(payloadB64: String): JsonElement {
        if (payloadB64.isEmpty()) return JsonNull
        val bytes = Base64.getDecoder().decode(payloadB64)
        return json.parseToJsonElement(String(bytes, Charsets.UTF_8))
    }

    /**
     * Handler names are our own constants rather than anything the page or a
     * verifier supplies, so this is a guard against a typo becoming a
     * syntax error, not a sanitiser for untrusted input.
     */
    private fun escapeName(name: String): String {
        require(name.all { it.isLetterOrDigit() || it == '.' || it == '_' }) {
            "handler name '$name' must be alphanumeric with '.' or '_'"
        }
        return name
    }

    companion object {
        /**
         * Long enough that a consent prompt can wait for a person, short
         * enough that a wedged page does not hold a reader's session open
         * until the reader itself gives up.
         */
        const val DEFAULT_TIMEOUT_MS: Long = 60_000
    }
}
