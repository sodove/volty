# Encrypted durable invitation drafts on Android

Continue stable invitation creation drafts with actual Android storage, without enabling
production UI/DI. Authoritative actor binding, immutable draft payload and persist-before-send
remain the contract. No group migration; friends/accounts remain unchanged.

1. Pure payload codec: version1 bounded1024-byte strict UTF-8 JSON with exact numeric/string
   fields, canonical actor/invitation/community IDs, bounded TTL/quota and canonical secret.
   Expected actor/slot must match. Malformed storage fails with safe diagnostics, never absence.
   Tests first for roundtrip, actual type mismatches, foreign IDs, corrupt UTF-8/version/bounds.
2. Encrypted record protocol: account-bound atomic lock shared across store instances; cipher
   encrypt/decrypt port plus records peek/write/confirm transaction. Failed commit may mutate
   cached preferences. Every existing cached draft requires successful durable confirmation
   before it may be returned/issued. An atomic retain winner is immutable. Incoherent provider
   records fail closed; no overwriting corrupt ciphertext or silently generating a new token.
   Tests first drive failed-commit cache, actual confirmations/no-HTTP, winner and cancellation.
3. Actual AES/GCM/NoPadding codec in Android source with JCA tests: non-exportable account key
   in Android Keystore, fresh provider IV,128-bit tag, actor/invitation authenticated data,
   bounded canonical envelope and strict payload. Test real JCA authentication against swapped
   actor/slot/key, modified IV/body/tag, malformed envelope and exact payload. JVM fixed keys
   are fixtures, not Android Keystore proof. Clear temporary plaintext bytes in finally.
4. Android binding uses account-specific private preferences and Keystore aliases on IO.
   Global adapter lock serializes atomic retain/checkpoint across instances. Force a fresh
   durability marker on each synchronous commit, including confirmation of existing records;
   commit(false) never permits sending a cached draft. No asynchronous apply for drafts.
   Pure preference policy tests drive changing confirmation fences and existing-profile
   corruption; an existing preference file missing schema must not look like a virgin store.
   Missing/lost key with an existing record fails closed; no ciphertext deletion on failure.
5. Narrow Luna reviews and fresh full Android with exact counts; evidence/commit. Native
   Keystore/preferences/process-death verification explicitly remains device-only. Logout
   cleanup, lifecycle retirement, production account factory/DI and UI are subsequent tasks.

Primary references already read:
- [SharedPreferences](https://developer.android.com/reference/android/content/SharedPreferences)
  documents immediate memory visibility before persistent success.
- [Editor.commit](https://developer.android.com/reference/android/content/SharedPreferences.Editor)
  documents synchronous persistence acknowledgement.
- [Android implementation](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/SharedPreferencesImpl.java)
  is the source checked for memory/disk generation behavior.
- [Cryptography](https://developer.android.com/privacy-and-security/cryptography)
  recommends platform cryptography and Android Keystore key protection.
