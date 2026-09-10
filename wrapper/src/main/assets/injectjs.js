/**
 * script to be injected by the webview to override navigator.credentials and call bridge method instead.
 *
 * Please include this as a string, replacing $JAVASCRIPT_BRIDGE with the name of the android code injected into
 * the webview and optionally $JAVASCRIPT_VISUALIZE_INJECTION to add an 'a' tag to the webview visualizing successful
 * injection.
 */

// check if replaced in Android: if not defined, throws an 'ReferenceError'.
JAVASCRIPT_BRIDGE

// Inject CSS class as early as possible to indicate to the web app, it's
// running inside the wrapper.
document.getElementsByTagName("html")[0].classList.add("is-wrapper-app");

// optionally replaced in android: if not defined no visualization
visualize = (typeof JAVASCRIPT_VISUALIZE_INJECTION) !== 'undefined' ? JAVASCRIPT_VISUALIZE_INJECTION : false

// overwrite console.log before using it
if( typeof JAVASCRIPT_BRIDGE.__real_log__ === 'undefined' ) {
    JAVASCRIPT_BRIDGE.__real_log__ = console.log;
    JAVASCRIPT_BRIDGE.__captured_logs__ = [];

    console.log = (...args) => {
      JAVASCRIPT_BRIDGE.__real_log__(...args);
      JAVASCRIPT_BRIDGE.__captured_logs__.push(Date.now() + ": " + args.join(' '));
    };
}

// actually inject the code
JAVASCRIPT_BRIDGE.__injected__ = true
JAVASCRIPT_BRIDGE.__promise_cache__ = {}

// add visualization on page
if (visualize) {
    body = document.getElementsByTagName('body')[0]
    if (body) {
        if (document.getElementById('android-injection-visualization')) {
            console.log('already injected, ignoring this viz')
        } else {
            link = document.createElement("a")
            link.setAttribute('id', 'android-injection-visualization')
            link.setAttribute('style', 'position:absolute;top:-0px;right:0;padding:0.5em;z-index:9999999;rotate:180deg;')
            link.textContent = '🤖'
            link.onclick = () => JAVASCRIPT_BRIDGE.openDebugMenu()
            body.appendChild(link)
        }
    } else {
        console.log("No <body> found, skipping visualisation of injection. <a href=\"javascript:visualize_injection()\">retry</a>")
    }
}

// override incoming hint
JAVASCRIPT_BRIDGE.__override_hints = []
JAVASCRIPT_BRIDGE.overrideHints = function(newHints) {
    JAVASCRIPT_BRIDGE.__override_hints = newHints;

    if (visualize) {
        var viz = '👀'
        if (newHints.length == 0) {
            viz = '🤖'
        } else if (newHints[0] == 'security-key'){
            viz = '🗝️'
        } else if (newHints[0] == 'client-device'){
            viz = '📲'
        } else if (newHints[0] == 'emulator'){
            viz = '🥸'
        } else {
           viz = '¿¿'
        }

        document.getElementById('android-injection-visualization').textContent = viz
    } else {
        console.log('no viz, no update.')
    }
}

// Store original methods safely in a scope that won't be cleared or overwritten.
if (typeof window.__original_navigator_credentials === 'undefined') {
    window.__original_navigator_credentials = {
        create: navigator.credentials.create.bind(navigator.credentials),
        get: navigator.credentials.get.bind(navigator.credentials)
    };
}

// override functions on navigator
function overrideNavigatorCredentialsWithBridgeCall(method) {

    navigator.credentials[method] = (options) => {
        console.log("Executing " + method + " with request: ", options);

        if (
            !("publicKey" in options)
            || !("hints" in options.publicKey)
            || !Array.isArray(options.publicKey.hints)
            || !options.publicKey.hints.includes("security-key")
        ) {
            console.log("Forward to OS, because no security-key hint contained.")

            return window.__original_navigator_credentials[method](options);
        }

        var uuid = crypto.randomUUID()

        var promise = new Promise((resolve, reject) => {
            JAVASCRIPT_BRIDGE.__promise_cache__[uuid] = {'resolve':resolve, 'reject':reject, 'method': method}

            if (options.publicKey) {
                if (JAVASCRIPT_BRIDGE.__override_hints.length > 0) {
                    options.publicKey['hints'] = JAVASCRIPT_BRIDGE.__override_hints
                }

                if (options.publicKey.hasOwnProperty('challenge')) {
                    options.publicKey.challenge = __encode(options.publicKey.challenge)
                }

                if (options.publicKey.hasOwnProperty('user') && options.publicKey.user.hasOwnProperty('id')) {
                    options.publicKey.user.id = __encode(options.publicKey.user.id)
                }

                if (options.publicKey.hasOwnProperty('allowCredentials')) {
                    var allowed = options.publicKey.allowCredentials
                    for(var i = 0; i < allowed.length; ++i) {
                        allowed[i].id = __encode(allowed[i].id);
                    }
                }
            }

            if (options.publicKey.hasOwnProperty('extensions') &&
                options.publicKey.extensions.hasOwnProperty('prf') &&
                options.publicKey.extensions.prf.hasOwnProperty('eval') &&
                options.publicKey.extensions.prf.eval.hasOwnProperty('first') )
            {
                options.publicKey.extensions.prf.eval.first = __encode(options.publicKey.extensions.prf.eval.first)
            }

            if (options.publicKey.hasOwnProperty('extensions') &&
                options.publicKey.extensions.hasOwnProperty('prf') &&
                options.publicKey.extensions.prf.hasOwnProperty('evalByCredential') )
            {
                for (const k of Object.keys(options.publicKey.extensions.prf.evalByCredential)) {
                    if (options.publicKey.extensions.prf.evalByCredential[k].hasOwnProperty('first')) {
                        options.publicKey.extensions.prf.evalByCredential[k].first = __encode(
                            options.publicKey.extensions.prf.evalByCredential[k].first)
                    }

                    if (options.publicKey.extensions.prf.evalByCredential[k].hasOwnProperty('second')) {
                        options.publicKey.extensions.prf.evalByCredential[k].second = __encode(
                            options.publicKey.extensions.prf.evalByCredential[k].second)
                    }
                }
            }

            if (options.publicKey.hasOwnProperty('extensions') &&
                options.publicKey.extensions.hasOwnProperty('prf') &&
                options.publicKey.extensions.prf.hasOwnProperty('eval') &&
                options.publicKey.extensions.prf.eval.hasOwnProperty('second') )
            {
                options.publicKey.extensions.prf.eval.second = __encode(options.publicKey.extensions.prf.eval.second)
            }


            // sign extension v3 https://yubicolabs.github.io/webauthn-sign-extension/3/#sctn-sign-extension
            if (options.publicKey.hasOwnProperty('extensions') &&
                options.publicKey.extensions.hasOwnProperty('sign') &&
                options.publicKey.extensions.sign.hasOwnProperty('generateKey') &&
                options.publicKey.extensions.sign.generateKey.hasOwnProperty('tbs') )
            {
                options.publicKey.extensions.sign.generateKey.tbs = __encode(options.publicKey.extensions.sign.generateKey.tbs)
            }

            if (options.publicKey.hasOwnProperty('extensions') &&
                options.publicKey.extensions.hasOwnProperty('sign') &&
                options.publicKey.extensions.sign.hasOwnProperty('sign') &&
                options.publicKey.extensions.sign.sign.hasOwnProperty('tbs') )
            {
                options.publicKey.extensions.sign.sign.tbs = __encode(options.publicKey.extensions.sign.sign.tbs)
            }

            if (options.publicKey.hasOwnProperty('extensions') &&
                options.publicKey.extensions.hasOwnProperty('sign') &&
                options.publicKey.extensions.sign.hasOwnProperty('sign') &&
                options.publicKey.extensions.sign.sign.hasOwnProperty('keyHandleByCredential'))
            {
                for (const k of Object.keys(options.publicKey.extensions.sign.sign.keyHandleByCredential)) {
                    options.publicKey.extensions.sign.sign.keyHandleByCredential[k] = __encode(options.publicKey.extensions.sign.sign.keyHandleByCredential[k]);
                }
            }

            // call bridge, JAVASCRIPT_BRIDGE.__resolve__(uid, ..) or JAVASCRIPT_BRIDGE.__reject__(uid,..) will be called back from android.
            var options_json = JSON.stringify(options, null, 4)
            console.log('options:', options_json)
            JAVASCRIPT_BRIDGE[method](uuid, options_json)
        })

        return promise
    }
}

function __encode(buffer) {
    if (typeof buffer === 'string') {
        // If it's already a string, assume it's base64url encoded and just return it
        // (Some web apps use wrappers that already convert to string)
        return buffer;
    }
    return btoa(
        Array.from(
            new Uint8Array(buffer),
            function (b) {
                return String.fromCharCode(b);
            }
        ).join('')
    ).replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '')
}

function __decode(value) {
    var m = value.length % 4;

    return Uint8Array
        .from(
            atob(
                value
                    .replace(/-/g, '+')
                    .replace(/_/g, '/')
                    .padEnd(
                        value.length + (m === 0 ? 0 : 4 - m), '='
                    )
            ),
            function (c) {
                return c.charCodeAt(0)
            }
        )
        .buffer;
}

function __decode__credentials(result) {
    result.rawId = __decode(result.rawId);
    result.response.clientDataJSON = __decode(result.response.clientDataJSON);
    if (result.response.hasOwnProperty('publicKey')) {
        result.response.publicKey = __decode(result.response.publicKey);
    }
    if (result.response.hasOwnProperty('attestationObject')) {
        result.response.attestationObject = __decode(result.response.attestationObject);
    }
    if (result.response.hasOwnProperty('authenticatorData')) {
        result.response.authenticatorData = __decode(result.response.authenticatorData);
    }
    if (result.response.hasOwnProperty('signature')) {
        result.response.signature = __decode(result.response.signature);
    }
    if (result.response.hasOwnProperty('userHandle')) {
        result.response.userHandle = __decode(result.response.userHandle);
    }

    if (result.hasOwnProperty('clientExtensionResults') && result.clientExtensionResults) {
        if (result.clientExtensionResults.hasOwnProperty('prf') &&
            result.clientExtensionResults.prf.hasOwnProperty('results')) {
            console.log("PRF FOUND")
            if(result.clientExtensionResults.prf.results.hasOwnProperty('first')) {
                result.clientExtensionResults.prf.results.first = __decode(
                    result.clientExtensionResults.prf.results.first
                );
            }

            if(result.clientExtensionResults.prf.results.hasOwnProperty('second')) {
                result.clientExtensionResults.prf.results.second = __decode(
                    result.clientExtensionResults.prf.results.second
                );
            }
        }

        if (result.clientExtensionResults.hasOwnProperty('largeBlob')) {
            if (result.clientExtensionResults.largeBlob.hasOwnProperty('blob')) {
                result.clientExtensionResults.largeBlob.blob = __decode(
                    result.clientExtensionResults.largeBlob.blob
                );
            }
        }

        // sign extension v3 https://yubicolabs.github.io/webauthn-sign-extension/3/#sctn-sign-extension
        if (result.clientExtensionResults.hasOwnProperty('sign')) {
            if (result.clientExtensionResults.sign.hasOwnProperty('generatedKey')) {
                if (result.clientExtensionResults.sign.generatedKey.hasOwnProperty('publicKey')) {
                    result.clientExtensionResults.sign.generatedKey.publicKey =
                        __decode(result.clientExtensionResults.sign.generatedKey.publicKey);
                }
                if (result.clientExtensionResults.sign.generatedKey.hasOwnProperty('attestationObject')) {
                    result.clientExtensionResults.sign.generatedKey.attestationObject =
                        __decode(result.clientExtensionResults.sign.generatedKey.attestationObject);
                }
            }

            if (result.clientExtensionResults.sign.hasOwnProperty('signature')) {
                result.clientExtensionResults.sign.signature = __decode(
                    result.clientExtensionResults.sign.signature
                );
            }
        }
    }

    // augment pure json result with functions needed by https://developer.mozilla.org/en-US/docs/Web/API/PublicKeyCredential#instance_properties
    result.getClientExtensionResults = () => result.clientExtensionResults
    result.response.getTransports = () => result.response.transports

    return result
}

JAVASCRIPT_BRIDGE.__resolve__ = (uuid, result) => {
    if (uuid in JAVASCRIPT_BRIDGE.__promise_cache__) {
        var promise = JAVASCRIPT_BRIDGE.__promise_cache__[uuid]
        console.log("Promise resolved:", promise.method, uuid)

        if (promise.method.startsWith('proximity')) {
            result = __b64ToJson(result)
        } else {
            result = __decode__credentials(result)
        }

        JAVASCRIPT_BRIDGE.__promise_cache__[uuid].resolve(result)

        delete JAVASCRIPT_BRIDGE.__promise_cache__[uuid]
    } else {
        console.log("Promise with id", uuid, "does not exist. Not resolving unknown promise.")
    }
}

JAVASCRIPT_BRIDGE.__reject__ = (uuid, result) => {
    if (uuid in JAVASCRIPT_BRIDGE.__promise_cache__) {
        var promise = JAVASCRIPT_BRIDGE.__promise_cache__ [uuid]
        if (promise.method.startsWith('proximity')) {
            try { result = __b64ToJson(result) } catch (e) { /* leave it as the raw string */ }
        }
        console.log("Rejected promise", JSON.stringify(promise), "with uuid", uuid, "and result", result)

        JAVASCRIPT_BRIDGE.__promise_cache__[uuid].reject(result)
        delete JAVASCRIPT_BRIDGE.__promise_cache__[uuid]
    } else {
        console.log("Promise with id", uuid, "does not exist. Not rejecting unknown promise.")
    }
}

overrideNavigatorCredentialsWithBridgeCall("create")
overrideNavigatorCredentialsWithBridgeCall("get")

// Wrap a native *Wrapped(promiseUuid, parameter) method as a promise-returning
// method whose result is decoded from base64 JSON.
function createWrappedMethod(method) {
    console.assert (
        typeof JAVASCRIPT_BRIDGE[method+"Wrapped"] !== 'undefined',
        "Associated wrapper function 'JAVASCRIPT_BRIDGE." + method +"Wrapped(promiseUuid,parameter)' not found."
    )

    JAVASCRIPT_BRIDGE[method] = (parameter) => {
        var promiseUuid = crypto.randomUUID()
        if( typeof parameter !== "string") {
            parameter = JSON.stringify(parameter)
        }
        console.log('Calling', method, '(', parameter, ')', "with promise", promiseUuid)

        var promise = new Promise((resolve, reject) => {
            JAVASCRIPT_BRIDGE.__promise_cache__[promiseUuid] = {
                'resolve': resolve,
                'reject': reject,
                'method': method
            }

            JAVASCRIPT_BRIDGE[method+"Wrapped"](promiseUuid, parameter)
        })

        return promise
    }
}

// ISO 18013-5 proximity is now a session hosted by the SDK, not eight GATT
// methods. The page starts one and gets an engagement URI for its QR code;
// the session then runs natively and asks the page for the three things the
// page still owns.
createWrappedMethod('proximityStart')
createWrappedMethod('proximityStop')

// ---------------------------------------------------------------------------
// Calls FROM native INTO the page.
//
// The page registers handlers; native invokes them by name and waits. Payloads
// are UTF-8 JSON in base64 in both directions, so quoting, newlines and
// U+2028/U+2029 stop being hazards and a binary payload needs no separate
// encoding. (The older __resolve__ path interpolates into a single-quoted
// string with no escaping - WebAuthn is its last user; do not build on it.)
// ---------------------------------------------------------------------------

JAVASCRIPT_BRIDGE.__handlers__ = {}
JAVASCRIPT_BRIDGE.__cancelled__ = {}

/** Register a handler native can invoke. Returns an unregister function. */
JAVASCRIPT_BRIDGE.onRequest = function (name, handler) {
    JAVASCRIPT_BRIDGE.__handlers__[name] = handler
    return function () { delete JAVASCRIPT_BRIDGE.__handlers__[name] }
}

function __b64ToJson(b64) {
    if (!b64) return null
    var binary = atob(b64)
    var bytes = new Uint8Array(binary.length)
    for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
    return JSON.parse(new TextDecoder().decode(bytes))
}

function __jsonToB64(value) {
    var bytes = new TextEncoder().encode(JSON.stringify(value === undefined ? null : value))
    var binary = ''
    for (var i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i])
    return btoa(binary)
}

JAVASCRIPT_BRIDGE.__invoke__ = function (callId, name, payloadB64) {
    var handler = JAVASCRIPT_BRIDGE.__handlers__[name]
    if (typeof handler !== 'function') {
        JAVASCRIPT_BRIDGE.__replyError__(callId, 'no_handler', 'no handler registered for ' + name)
        return
    }

    var payload
    try {
        payload = __b64ToJson(payloadB64)
    } catch (e) {
        JAVASCRIPT_BRIDGE.__replyError__(callId, 'bad_payload', String(e))
        return
    }

    Promise.resolve()
        .then(function () { return handler(payload) })
        .then(function (result) {
            if (JAVASCRIPT_BRIDGE.__cancelled__[callId]) {
                delete JAVASCRIPT_BRIDGE.__cancelled__[callId]
                return
            }
            JAVASCRIPT_BRIDGE.__reply__(callId, __jsonToB64(result))
        })
        .catch(function (e) {
            if (JAVASCRIPT_BRIDGE.__cancelled__[callId]) {
                delete JAVASCRIPT_BRIDGE.__cancelled__[callId]
                return
            }
            JAVASCRIPT_BRIDGE.__replyError__(callId, 'handler_failed', (e && e.message) ? e.message : String(e))
        })
}

/**
 * Native gave up on a call. The handler may still be running - we cannot stop
 * it - so mark the id and drop its answer when it arrives.
 */
JAVASCRIPT_BRIDGE.__cancel__ = function (callId) {
    JAVASCRIPT_BRIDGE.__cancelled__[callId] = true
}

/** One-way: progress and terminal events. Errors in a listener are swallowed. */
JAVASCRIPT_BRIDGE.__notify__ = function (name, payloadB64) {
    var handler = JAVASCRIPT_BRIDGE.__handlers__[name]
    if (typeof handler !== 'function') return
    try {
        handler(__b64ToJson(payloadB64))
    } catch (e) {
        console.log('notify handler for ' + name + ' threw: ' + e)
    }
}

// call out finalization
console.log('injected!')
