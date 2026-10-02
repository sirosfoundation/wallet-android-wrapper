# Metadata-only Android DC API integration

The wrapper vendors the Android matcher from
[Multipaz 0.101.0](https://github.com/openwallet-foundation/multipaz/tree/0.101.0/multipaz-dcapi)
and provides a metadata-only registration API in
`org.siros.wwwallet.credentials.matching`. All code, assets and tests are part of
the `wrapper` module; no external checkout, symlink or Maven publication is needed.
The common core remains the unmodified `org.multipaz:multipaz:0.101.0` dependency.

This is an Android-only adaptation, not a drop-in replacement for Multipaz's multiplatform
`DigitalCredentials.getDefault()` / `register(DocumentStore, ...)` APIs. It does not create
documents, generate keys, certify credentials, or produce presentations.

## Registration

Call `registerCredentialMetadata(context, credentials, selectedProtocols)` with
`DigitalCredentialMetadata` objects. Each object specifies exactly one mdoc `docType` or
SD-JWT `vct`, a PNG bitmap, and searchable claims. The list replaces the entire index;
an empty list clears it. Registration awaits both Play Services registration tasks
and propagates errors and cancellation.
The registration ID is the app package name, replacing the wrapper's previous
AndroidX registration rather than leaving a second index behind. The fulfillment
action defaults to `androidx.credentials.registry.provider.action.GET_CREDENTIAL`,
which the wrapper's activity handles.

Claim paths are `[namespace, element]` for mdoc and JSON path components for SD-JWT.
The matcher indexes each claim as `[displayName, displayValue, matchValue]`.
Primitive match values are unquoted strings: `"Gary"`, `"true"`, `"18"`.
The inherited matcher does not fully support numeric/array JSON paths or
floating-point value constraints.

The wrapper preserves frontend metadata in `Settings` and rebuilds the index on startup
and after `nativeWrapper.updateAllCredentials`. Registration updates are serialized.
Unsupported credential formats are logged and omitted. Invalid supported metadata
or registration failures are reported in the app log.

Picker IDs contain `<combination> <protocol> <id>`. The wrapper's opaque ID encodes both
the callback URL and frontend credential ID, preventing cross-tenant ID collisions.
The selected protocol determines which request is forwarded to the frontend.
Multiple selected credentials and duplicate requests with the same protocol are
explicitly rejected because the frontend bridge currently accepts one credential ID.
Only `openid4vp`, `openid4vp-v1-signed`, and `openid4vp-v1-unsigned` are advertised.

## ZKP and pairwise pseudonyms

The upstream matcher recognizes `mso_mdoc_zk`, but 0.101.0 has no PPID alias.
This fork adds a narrowly scoped rule: a ZKP request for
`["eu.europa.ec.eudi.pid.1", "pairwise_pseudonym"]` can match an indexed
`pseudonym_seed` in that namespace. The rule does not apply to plain `mso_mdoc`
requests, and cannot satisfy a constraint on an unknown derived pseudonym value.
The picker displays the derived claim rather than the seed.

The wrapper indexes only the seed's presence, with empty display/matching values.
Seed bytes are never exported to the credential registry. The original frontend
metadata remains in the existing encrypted settings store.
The web wallet must still validate the request and generate the actual proof.
A matcher result is not evidence that a requested proof system is supported.

Issuer AKI constraints are supported by the matcher and optional metadata API field.
The current frontend schema does not supply them, so issuer-constrained requests
do not match. No issuer identity is inferred from display labels.

## Building and maintaining the matcher

The rebuilt `src/main/assets/identitycredentialmatcher.wasm` is checked in,
so ordinary Android builds do not need a WASI toolchain.
After changing C++ matcher sources, install WASI SDK **20** at `~/wasi-sdk-20.0`, then run:

```sh
./gradlew :wrapper:updateMatcherAsset
```

The wrapper's JVM tests run the upstream JNI matcher harness against metadata-only
CBOR indexes, covering boolean matching and positive/negative PPID cases:

```sh
./gradlew :wrapper:testDebugUnitTest \
  --tests 'org.siros.wwwallet.credentials.dcApi.*'
```

Host matcher tests require a C++20 compiler with `std::format` support and JDK headers
on macOS or Linux. They do not install or run anything on a connected Android device.

When upgrading Multipaz, review the CBOR schema, protocol handling, claim matching,
picker ID encoding and JNI harness against the new release, then rebuild the WASM asset.
Matcher sources live in `src/main/matcher` and the JNI harness in `src/test/matcher`.
Retain the upstream Apache-2.0 license (`src/main/matcher/LICENSE`) and the bundled
cJSON license (`src/main/matcher/cJSON-LICENSE`).
