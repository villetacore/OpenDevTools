# OpenDevTools

Five IntelliJ Platform plugins for the services a developer actually touches from the IDE, plus
the small shared library they all sit on.

| Module | Contents |
|--------|----------|
| [`core/`](core) | HTTP transport, JSON access, secret storage, formatting, background helpers and the shared Swing pieces |
| [`proxmox-tools/`](proxmox-tools/README.md) | Proxmox VE cluster management |
| [`vaultwarden-tools/`](vaultwarden-tools/README.md) | Vaultwarden / Bitwarden vault |
| [`matrix-tools/`](matrix-tools/README.md) | Matrix chat |
| [`git-forge-tools/`](git-forge-tools/README.md) | GitHub, GitLab, Gitea and Forgejo, several accounts at once |
| [`gpg-tools/`](gpg-tools/README.md) | OpenPGP certificate manager over your own GnuPG, in the spirit of Kleopatra |

## Build

```bash
./gradlew build          # compile everything and run the tests
./gradlew buildPlugin    # <module>/build/distributions/<module>-<version>.zip
./gradlew verifyPlugin   # JetBrains plugin verifier against the recommended IDEs
./gradlew runIde         # sandbox IDE with the plugin installed (per module)
```

Requirements: JDK 21 and IntelliJ IDEA 2025.3 or newer. If your default `JAVA_HOME` is older,
point Gradle at a JDK 21 in `gradle.properties`:

```properties
org.gradle.java.home=C:\\Users\\<you>\\.jdks\\ms-21.0.10
```

Install a built ZIP through *Settings | Plugins | Install Plugin from Disk*.

The first `verifyPlugin` downloads several gigabytes of IDE builds. The modules' verifications are
ordered rather than parallel, because the verifier unpacks those IDEs into one shared cache and
concurrent runs race on it.

## Why a shared module

Four of the five talk to a self-hosted HTTPS service that commonly runs behind a self-signed
certificate, and keep a secret in the IDE password safe. All five render a tree or a list with a
details pane next to it, and all five have to keep slow work off the EDT. That is what `core`
holds:

```
core/
├── http/        HttpTransport, RequestBody, per-connection TLS relaxation
├── json/        lenient readers (JsonAccess) and a small writer (JsonWriter)
├── secrets/     SecretStore over the IDE password safe
├── format/      byte, percentage, uptime and timestamp rendering
├── async/       Background + Outcome: run off the EDT, continue on it
└── ui/          Notifier, PropertiesTable, TreeState, TimeSeriesChart
```

`core` is compiled as its own Gradle module and its classes are merged into each plugin's jar at
package time, so a plugin ships as a single self-contained artifact. The plugins do not depend on
each other, and none of them depends on a bundled IDE plugin.

`gpg-tools` is the exception to the HTTP half of that list: it is a front end to a local
executable rather than to a service, so it uses `core`'s formatting, async and Swing pieces and
none of its transport. In place of an `api/` layer it has a `cli/` one: a process runner and two
parsers for GnuPG's machine-readable output.

## Layering

Each plugin follows the same four layers, top to bottom:

```
toolwindow/, dialogs/, actions/   Swing and the action system; no JSON, no HTTP
service/                          application services: state, orchestration, model mapping
api/                              endpoint groups over core's HttpTransport (cli/ in gpg-tools)
model/                            plain data classes, no platform types
```

The rule that keeps it honest: nothing above `service/` ever sees a `JsonObject`, and nothing
below `toolwindow/` ever sees a Swing type.

## Security notes

* Secrets - the Proxmox API token or password, the Vaultwarden API key, the Matrix access token,
  every git forge token - live in the IDE password safe, never in the settings XML.
* The Vaultwarden master password is read into a `char[]`, used to derive the vault key, and then
  zeroed. Vault keys and decrypted items live in memory only and are wiped on lock and on exit.
* Certificate validation is relaxed only on the plugin's own connections, and only when the user
  turns it off for that server. The JVM defaults are never touched.
* The Matrix master password is exchanged for a device token and then dropped; signing out
  invalidates that token on the server.
* `gpg-tools` holds no secret at all. Passphrases are gpg-agent's business, asked for through its
  own pinentry dialog, so no private key and no passphrase passes through the IDE process.
* Nothing secret is written to the IDE log.
