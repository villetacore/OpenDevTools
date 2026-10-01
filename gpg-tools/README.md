# GPG Tools

IntelliJ IDEA plugin (Kotlin): a certificate manager and an OpenPGP front end inside the IDE, in
the spirit of [Kleopatra](https://apps.kde.org/kleopatra/).

## The design decision, up front

**The plugin does not implement OpenPGP. It drives your own GnuPG.**

Kleopatra is a front end to GnuPG, and so is this. Every operation is a `gpg` invocation against
the keyring you already have, which has three consequences worth stating:

* **No passphrase and no private key ever passes through the plugin.** gpg-agent asks for
  passphrases through its own pinentry dialog, exactly as it does on the command line. There is no
  password safe entry for this plugin because there is nothing to keep in one.
* **It is your real keyring.** A key generated here is usable from the shell, from Git commit
  signing, from Thunderbird. There is no separate store to keep in sync.
* **It needs GnuPG installed.** Gpg4win on Windows, `gnupg` from the package manager elsewhere.

## The other design decision

**Only GnuPG's machine-readable output is ever parsed** - `--with-colons` for listings and
`--status-fd` for results.

This is not fastidiousness. On the machine this plugin was written on, `gpg` reports
"good signature" as *"Хорошая подпись"*. Anything that matched on gpg's prose would work in
English and fail silently everywhere else - and failing silently, for a signature check, means
telling someone a forgery is genuine. The status stream is documented, stable across versions, and
never translated.

## Features

### Tool window

* Certificates split into **My Certificates** - the ones whose secret key you hold - and
  **Other Certificates**, with a filter over names, addresses, key ids and fingerprints.
* Revoked, expired and disabled certificates are struck through rather than hidden; they are often
  exactly what you came to look at.
* Four tabs per certificate: a summary, its user ids, its subkeys, and the certifications other
  people have made on it.
* The summary ends with a plain-language line - *Usable to: sign, encrypt to* - so the reason an
  action is greyed out is on screen rather than guessed at.

### Certificates

* Generate a key pair: ECC (Curve 25519, NIST, Brainpool) or RSA, with an expiry.
* Add identities and subkeys; move expiry dates, on the primary key and on chosen subkeys
  separately; change the passphrase.
* Certify someone else's key, exportably or locally; set owner trust.
* Revoke an identity, or apply the revocation certificate GnuPG wrote when the key was created.
* Delete from the keyring.

### Import and export

* Import from files or from an OpenPGP block on the clipboard.
* Export the certificate, the secret key, the secret subkeys, or the SSH public key form; to a
  file, armored or binary, or on screen ready to copy.
* Keyserver search, receive, publish and refresh; lookup by mail address through whatever route
  gpg is configured for (WKD, DANE, LDAP).

### Files and editor

From the project view: encrypt to certificates, encrypt to a passphrase, sign (detached or
attached), and one *Decrypt / Verify* that works out from the file which of the two it is.

From the editor context menu: encrypt, sign, decrypt and verify the **selection**. These pipe
through gpg's stdin and stdout, so an editor operation never writes a plaintext to disk.

## Requirements

* JDK 21 for the build (`JAVA_HOME` must point at it).
* IntelliJ IDEA 2025.3 or newer, Community or Ultimate.
* GnuPG 2.1 or newer at runtime. 2.1 is where the `--quick-*` commands arrived, and the plugin uses
  those rather than driving `--edit-key` through a pseudo-terminal.

## Build and run

From the repository root:

```bash
./gradlew :gpg-tools:buildPlugin   # build/distributions/gpg-tools-0.2.0.zip
./gradlew :gpg-tools:runIde        # sandbox IDE with the plugin installed
```

## Configuration

*Settings | Tools | GPG*

| Field | Notes |
|-------|-------|
| gpg executable | Empty means "find it": `PATH` first, then the usual install locations. **Detect** lists what it found, **Test** runs it and reports the version and keyring path. |
| GNUPGHOME | An alternative keyring directory; empty uses that gpg's own default |
| Keyserver | Used by search, receive, publish and refresh |
| Default signing key | Pre-selected in the signing and encryption dialogs; *Set as Default Signing Key* in the tool window fills it in |
| Encrypt to the default signing key as well | Without it, files you encrypt cannot be opened again by you |
| Certify keys locally only | A local certification stays in this keyring and is never exported |
| ASCII armor by default | Text output that can be pasted or mailed, rather than binary |
| Load certifications | Fills the Certifications tab; noticeably slower on a large keyring, so it is off by default |

## Project layout

| Path | Contents |
|------|----------|
| `cli/` | `GpgProcess` (the only place that runs gpg), `ColonParser` (`--with-colons` listings), `StatusParser` (`--status-fd` results) |
| `model/` | certificates, user ids, subkeys, signatures; the results of verifying, decrypting and importing |
| `service/` | `GpgService` - every operation the plugin offers; `GpgLocator` - finding the executable |
| `settings/` | preferences and the settings UI |
| `toolwindow/` | the certificate tree and the details tabs |
| `dialogs/` | key generation, certification, expiry, trust, export, encryption, signing, keyserver search |
| `actions/` | keyring management; file and editor operations |

## Notes on behaviour

* **The public and the secret keyring are listed separately and merged**, with the public listing
  as the spine. `gpg --list-keys --with-secret` would be one call instead of two, but the secret
  listing is the only place that distinguishes a key held in the keyring (`+`) from one held on a
  smartcard (`>`), and getting that wrong would leave a card user unable to see why signing fails.
* **Capability letters mean two things at once.** On a primary key, `scESC` says *this key* signs
  and certifies (lower case) and *somewhere in this certificate* there is also an encryption key
  (upper case). Reading the upper-case letters as if they described the primary key would offer an
  RSA signing key as a recipient. Both readings are kept, and the encryption dialog uses the one
  that is true.
* **Key generation deliberately does not run under `--batch`.** With `--batch` and no
  `--passphrase`, `--quick-generate-key` writes an **unprotected** private key rather than
  prompting. Leaving batch mode off is what makes gpg-agent raise its pinentry.
* **Owner trust is set by feeding `--import-ownertrust`.** The alternative is the `trust` submenu
  of `--edit-key`, which exists only as an interactive dialogue.
* **Encryption to an uncertified recipient is a checkbox, not a default.** GnuPG refuses such a
  recipient in batch mode; the plugin adds `--trust-model always` only when the dialog says so,
  rather than turning the check off behind the user's back.
* **A dismissed pinentry is not an error.** Cancelling a passphrase prompt reports "cancelled"
  rather than a red balloon.
* **Whole-certificate revocation uses the revocation certificate GnuPG already wrote** into
  `openpgp-revocs.d` when the key was created. gpg comments out that file's armor so it cannot be
  imported by accident; the markers are stripped only when the user asks for exactly this.
* **No smartcard management.** Cards are *recognised* - a subkey held on one is shown as such and
  signing through it works, because gpg-agent handles that - but changing a card's PIN, moving keys
  onto it or generating keys on it is not implemented. Use `gpg --card-edit`.
* **No S/MIME.** Kleopatra manages X.509 certificates through `gpgsm` as well; this plugin is
  OpenPGP only.
