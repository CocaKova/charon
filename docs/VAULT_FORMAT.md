# .charon Vault File Format (v1)

The vault export is Charon's replacement for paid cloud sync: one passphrase-encrypted
file containing the entire fleet — hosts, identities (private keys included), known
hosts, snippets, port forwards. The format is documented so it's an honest, portable
format, not a lock-in blob.

## Layout

```
offset  size  field
0       7     magic: ASCII "CHARON1"
7       1     format version (0x01)
8       4     Argon2id memory KiB (uint32 BE)      — default 32768 (32 MiB)
12      4     Argon2id iterations (uint32 BE)      — default 3
16      1     Argon2id parallelism                 — default 2
17      16    salt (random)
33      12    AES-GCM nonce (random)
45      …     AES-256-GCM ciphertext + 16-byte tag
```

- KDF: **Argon2id** (BouncyCastle `Argon2BytesGenerator`), 32-byte output key
- Cipher: **AES-256-GCM**; the 45-byte header is the GCM AAD (tamper-evident params)
- Plaintext: UTF-8 JSON document (kotlinx-serialization):

```json
{
  "exportedAt": "2026-07-13T12:00:00Z",
  "appVersion": "0.9.0",
  "hosts": [ … ],
  "identities": [ … ],       // private keys re-encrypted under the vault key only
  "knownHosts": [ … ],
  "snippets": [ … ],
  "snippetPins": [ … ],
  "portForwards": [ … ]
}
```

## Semantics

- All entities carry UUID string PKs and `lastModified` timestamps
- **Import = merge by UUID, newer `lastModified` wins**; unknown fields are preserved
  where possible, unknown format versions are refused loudly
- Identity private keys are decrypted from the Android Keystore at export time and exist
  in plaintext only inside the encrypted payload; on import they are immediately
  re-encrypted under the importing device's Keystore
