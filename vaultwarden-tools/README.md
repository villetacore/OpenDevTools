# Vaultwarden Tools

IntelliJ IDEA plugin (Kotlin) that reads and writes a
[Vaultwarden](https://github.com/dani-garcia/vaultwarden) vault straight from the IDE, over the
Bitwarden-compatible REST API. No `bw` CLI, no other runtime dependency: the plugin implements the
Bitwarden key hierarchy itself.

## How authentication works here

The plugin authenticates with a **personal API key** (`grant_type=client_credentials`). That path
keeps working when interactive login goes through an external identity provider such as Keycloak,
which is exactly why Bitwarden recommends it for non-interactive clients.

Unlocking still asks for the **master password**, and that is not an oversight: in the Bitwarden
design single sign-on proves *who you are*, while the vault key is derived from the master
password. Only Key Connector and Trusted Device Encryption change that, and Vaultwarden implements
neither.

## Features

### Tool window

* Tree of the vault grouped by **folder**, **item type** or **collection**, or as a flat list,
  with favorites pinned to the top, plus **Trash** and **Sends** branches.
* Instant search across names, user names, URIs, notes and custom field names.
* Details pane with four tabs - Item, Fields, Attachments, History - and per-row copy.

### Items

* Create, edit and delete **logins, secure notes, cards, identities and SSH keys**.
* Custom fields (text, hidden, boolean), URIs with match rules, folder, favorite and per-item
  master-password re-prompt.
* Move to trash, restore from trash, delete permanently, move to another folder, toggle favorite.
* Password history: the superseded password is recorded on every change and shown in its own tab.

### Copy and paste

* Copy the user name, password, a locally generated TOTP code (RFC 6238), notes, the first URI,
  a card number or security code, or an SSH public or private key.
* The clipboard is wiped after a configurable delay, and only if it still holds that exact value.
* **Insert Password at Caret** and **Insert Credentials at Caret** for `.env` files and local
  configs - both warn that a secret was just written into a file.

### Tools

* **Password generator**: passwords with per-class minimums and an ambiguous-character filter, or
  passphrases; the chosen shape is remembered.
* **Vault report**: weak passwords, reused passwords, sites reachable over plain HTTP, and logins
  without a second factor. Everything is computed locally.
* **Attachments**: download and decrypt, encrypt and upload, delete.
* **Sends** are listed with their view counts and expiry; the share link can be copied and a Send
  can be deleted. Creating one is left to the web vault - see the scope note below.
* Unlock, sync and lock, plus auto-lock after N minutes of inactivity.

## Security design

* The master password is read into a `char[]`, used to derive the key, then zeroed. It is never
  stored, and it never leaves the machine.
* Vault keys, the access token and the decrypted items live **in memory only** and are wiped on
  lock, auto-lock and IDE exit. Only non-secret preferences reach `vaultwarden-tools.xml`; the API
  key secret goes to the IDE password safe.
* HMAC is verified before decryption on every `EncString`, using a constant-time comparison.
* Writes follow the same key hierarchy as the official clients: every item gets its own key,
  wrapped with the account or organization key, so sharing an item later re-wraps one key instead
  of re-encrypting the item.
* Values are **masked by default**; *Show Secrets* reveals them in the details pane.
* Nothing secret is written to the IDE log.
* **No breach check.** The web vault's equivalent report sends a hash prefix of every password to
  a third party. A plugin that promises secrets stay on the machine should not quietly do that, so
  that report is missing on purpose.
* Caveat: decrypted values become JVM `String`s, which cannot be zeroed - they live until GC.
* Caveat: "Insert Password at Caret" writes a secret into a file. Do not commit it.

## The crypto, and how it is verified

The plugin implements the Bitwarden key hierarchy in both directions:

```
master password --PBKDF2-SHA256 | Argon2id--> master key
master key --HKDF-Expand("enc"/"mac")--> stretched key --unwraps--> account key
account key --unwraps--> RSA private key --unwraps--> organization keys
account/organization key --wraps/unwraps--> per-item key --en/decrypts--> item fields
per-item key --wraps--> per-attachment key --en/decrypts--> attachment bytes
```

Hand-written crypto deserves evidence, so `BitwardenCryptoTest` runs against **golden vectors
produced by other implementations**: Python's `hashlib` and the `cryptography` package (OpenSSL)
for PBKDF2, Argon2id, HKDF, AES-256-CBC + HMAC-SHA256 and RSA-OAEP-SHA1, plus the published
RFC 6238 vectors for TOTP. The expectations in that test were not copied from this plugin's output.

```bash
./gradlew :vaultwarden-tools:test
```

## Requirements

* JDK 21 for the build (`JAVA_HOME` must point at it).
* IntelliJ IDEA 2025.3 or newer, Community or Ultimate.
* Argon2id support comes from the BouncyCastle bundled with the IDE; PBKDF2 is plain JDK.

## Build and run

From the repository root:

```bash
./gradlew :vaultwarden-tools:buildPlugin   # build/distributions/vaultwarden-tools-0.2.0.zip
./gradlew :vaultwarden-tools:runIde        # sandbox IDE with the plugin installed
```

## Configuration

*Settings | Tools | Vaultwarden*

| Field | Notes |
|-------|-------|
| Server URL | your Vaultwarden instance, e.g. `https://vault.example.com` |
| Verify SSL certificate | turn off only for a self-signed certificate |
| Client ID | from the web vault: *Settings \| Security \| Keys \| View API Key*, the `user.<uuid>` value |
| Client secret | the API key secret; kept in the IDE password safe |
| Group items by | folders, item types, collections, or a flat list |
| Show favorites first | pins a Favorites branch to the top of the tree |
| Confirm destructive actions | deleting an item, a folder or a Send asks first |
| Clear the clipboard after | seconds, `0` keeps copied secrets |
| Lock the vault after | minutes of inactivity, `0` disables auto-lock |

**Test Connection** authenticates with the API key alone - no master password - and reports the
account email and how many items, folders and Sends the server returns.

## Project layout

| Path | Contents |
|------|----------|
| `crypto/` | `BitwardenCrypto` (KDF, HKDF, AES+HMAC both ways, RSA), `EncString`, `SymmetricCryptoKey`, `Totp`, `PasswordGenerator` |
| `api/` | `VaultwardenTransport` (bearer token, device headers, errors) and `VaultwardenApi` |
| `model/` | items and their five type-specific sections, folders, collections, organizations, Sends, snapshot, report |
| `service/` | `VaultwardenService` (session, keys, CRUD), `VaultDecryptor`, `VaultEncryptor`, `VaultReports` |
| `settings/` | preferences and the settings UI |
| `toolwindow/` | the panel, `tree/` (items, builder, renderer), `details/` (the tabbed item view) |
| `dialogs/` | item editor, password generator, folder, move-to-folder, report |
| `actions/` | session, CRUD, copy and editor-insert actions |

## Notes on behaviour

* **A write is always followed by a sync.** The server is the source of truth for revision dates
  and generated ids, so the tool window reloads rather than patching its local copy.
* **Editing reuses the existing item key** where the server sent one, so attachments - encrypted
  with that key - keep working. A pre-item-key cipher is given one on its first edit, which is
  what the official clients also do.
* **Item type cannot be changed after creation**, because the server cannot convert one kind of
  item into another.
* **Deleting a folder keeps its items**; they fall back to "No Folder".
* **Attachment uploads are capped at 50 MB** by the plugin: the file is held in memory twice while
  it is encrypted.
* **Sends are read, linked and deleted, but not created here.** A Send has its own key hierarchy
  (HKDF with the `bitwarden-send` salt) and file Sends need a second upload round trip; the plugin
  implements the read side of that and leaves creation to the web vault rather than shipping a
  half-tested write path for it.
