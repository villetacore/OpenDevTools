# Matrix Tools

IntelliJ IDEA plugin (Kotlin) for reading and writing [Matrix](https://matrix.org/) chats from
inside the IDE, over the Client-Server API. Works with matrix.org and with any self-hosted
homeserver - Synapse, Dendrite, Conduit.

## The one limitation, up front

**End-to-end encrypted rooms are listed but cannot be read or written.**

Reading them needs the Olm/Megolm key exchange: a device identity, key uploads, one-time keys,
room key sharing and device verification. That is not a feature that can be added in passing - it
is a second plugin's worth of cryptography, and a partly correct implementation of it is worse
than none, because it would look like it works. The plugin therefore shows encrypted rooms with a
clear note and leaves them to a full Matrix client.

Unencrypted rooms - which is most bridged, public and bot-driven rooms, and any room you create
with encryption off - work fully.

## Features

### Tool window

* Room list with unread and highlight counts, ordered by recent activity, alphabetically, or split
  into direct messages and rooms.
* Invitations pinned to the top: they are the only rows that need an answer.
* A filter across room names, aliases, topics and message bodies.
* Message timeline grouped by day and by sender, with a composer underneath. Enter sends,
  Shift+Enter starts a new line.

### Live updates

The homeserver's `/sync` endpoint long-polls: it holds the request open and answers the moment
something happens. The plugin runs one sync loop for the session and pushes updates at the UI.
There is no polling timer and no refresh button that matters - "Reconnect" exists only for when a
connection drops.

Failures back off exponentially, and a `M_LIMIT_EXCEEDED` response is honoured for exactly as long
as the server asked. An expired token stops the loop rather than hammering the server with a
credential that will never work.

### Actions

* Send messages, load earlier history a page at a time, mark a room read.
* Join a room by id or alias, accept or decline an invitation, leave a room, invite someone.
* Copy the room alias, copy a selection out of the timeline, or paste that selection straight into
  the editor - handy for lifting a stack trace out of a chat.
* Balloon notifications for mentions, or for every message; the room you are looking at never
  produces one.

## Requirements

* JDK 21 for the build (`JAVA_HOME` must point at it).
* IntelliJ IDEA 2025.3 or newer, Community or Ultimate.
* A Matrix account on any homeserver.

## Build and run

From the repository root:

```bash
./gradlew :matrix-tools:buildPlugin   # build/distributions/matrix-tools-0.2.0.zip
./gradlew :matrix-tools:runIde        # sandbox IDE with the plugin installed
```

## Configuration

*Settings | Tools | Matrix*

Sign in one of two ways:

| Method | When to use it |
|--------|----------------|
| User name and password | The usual case. The plugin exchanges them for a device access token and forgets the password immediately. |
| Access token | When the account signs in through an identity provider and the password flow is unavailable. Create one in Element under *Settings \| Help & About*. |

| Field | Notes |
|-------|-------|
| Homeserver | e.g. `https://matrix.org` or your own server |
| Verify SSL certificate | turn off only for a self-signed certificate |
| Order rooms by | recent activity, alphabetical, or invites and rooms |
| List encrypted rooms | they are shown with a note rather than hidden |
| Mark a room read when it is opened | the behaviour most chat clients have |
| Messages per page | used by the initial sync and by *Load earlier messages* |
| Notify when I am mentioned / on every message | balloon notifications |
| Sync timeout | seconds the sync request waits on the server; longer is cheaper, shorter reconnects faster on a flaky link |

**Sign Out** invalidates the token on the server, not just locally.

## Project layout

| Path | Contents |
|------|----------|
| `api/` | `MatrixApi` - login, sync, rooms, messages, read markers, profiles; each method names the spec route it maps to |
| `model/` | rooms, messages, members, snapshot |
| `service/` | `MatrixService` (session and sync loop), `SyncMapper` (incremental merge) |
| `settings/` | preferences, the settings UI and the sign-in dialog |
| `toolwindow/` | panel, room list, timeline |
| `actions/` | session, room and clipboard actions |

## Notes on behaviour

* **Sync is incremental**, so a response says what changed rather than what is. `SyncMapper` merges
  onto the previous snapshot: a room absent from a response keeps its timeline, an event id seen
  twice replaces rather than duplicates, and an edit is read from its `m.new_content`. That merge
  is the part with the most tests behind it.
* **The timeline is one text area**, not a list of components. A busy room holds hundreds of
  messages, and one text area scrolls and selects across the whole conversation - which is what a
  person wants when copying a snippet out of a chat.
* **The view follows new messages only when you are already at the bottom.** Reading back through
  history is not interrupted by an arrival.
* **The master password is never stored**; only the device access token reaches the password safe.
* **No message editing or reactions.** The plugin sends and redacts; editing an existing message
  and reacting to one are not implemented.
