# Proxmox Tools

IntelliJ IDEA plugin (Kotlin) for working with a [Proxmox VE](https://www.proxmox.com/) server or
cluster directly from the IDE.

## Features

### Tool window

* Tree of the whole cluster: nodes, QEMU virtual machines, LXC containers, storages and pools,
  with live CPU, memory and uptime on every row.
* Four groupings, the same ones the web UI offers: **Server View**, **Pool View**, **Tag View**
  and a flat list. Plus a text filter and a "running only" toggle.
* Templates, locked guests and HA-managed guests are marked as such.

### Details tabs

The right-hand pane changes with the selection and loads each tab on demand:

| Selection | Tabs |
|-----------|------|
| Datacenter | Summary, Tasks, HA, Backup Jobs, Firewall, Access |
| Node | Summary, Statistics, Network, Services, Disks, Updates, Replication, Tasks, Syslog |
| Guest | Summary, Configuration, Snapshots, Backups, Network, Firewall, Statistics, Tasks |
| Storage | Summary, Content |

*Statistics* draws the RRD graphs Proxmox keeps - CPU, memory, disk I/O and network - over any of
the five timeframes the API supports.

### Actions

* **Power**: start, shut down, stop, reboot, reset, suspend, resume; start/stop every guest on a node.
* **Snapshots**: take (with or without RAM), roll back, delete, edit the description. The snapshot
  chain is drawn as a tree.
* **Lifecycle**: clone (full or linked), migrate between nodes, convert to template, destroy,
  resize a disk, and edit name, CPU, memory, boot behaviour, tags and notes.
* **Create**: wizards for a QEMU virtual machine and for an LXC container, pre-filled from the
  server's own inventory of storages, bridges, ISOs and templates.
* **Backup**: run `vzdump` with the options of the web UI's *Backup Now*, restore an archive into
  a new or an existing guest, protect an archive from pruning, delete a volume.
* **Console**: noVNC, xterm.js serial or SPICE for a guest, the shell for a node, or the guest's
  page in the web UI.
* **Tasks**: the cluster-wide and per-node task history, with a log viewer that tails a running
  task and is one click away from a failure notification.
* **Node**: start, stop and restart the systemd units Proxmox manages.
* **Firewall**: guest rules and options, with enable/disable and delete.
* **Datacenter**: high-availability resources, scheduled backup jobs, the cluster firewall and the
  users and permissions of the server - all read-only.

Every action that starts a Proxmox task waits for it and reports the exit status; a failure links
straight to the task log.

## Requirements

* JDK 21 for the build (`JAVA_HOME` must point at it).
* IntelliJ IDEA 2025.3 or newer (`sinceBuild = 253`), Community or Ultimate.
* A reachable Proxmox VE 7 or 8 server.

## Build and run

From the repository root:

```bash
./gradlew :proxmox-tools:buildPlugin   # build/distributions/proxmox-tools-0.2.0.zip
./gradlew :proxmox-tools:runIde        # sandbox IDE with the plugin installed
./gradlew :proxmox-tools:verifyPlugin  # JetBrains plugin verifier
```

Install the resulting ZIP through *Settings | Plugins | Install Plugin from Disk*.

## Configuration

*Settings | Tools | Proxmox*

| Field | Notes |
|-------|-------|
| Host / Port | e.g. `192.168.1.10` and `8006` |
| Verify SSL certificate | turn off for the default self-signed certificate |
| Authentication | API token (recommended) or user name + password |
| User | including the realm: `root@pam`, `automation@pve`, ... |
| Token name | the part after `!` in the token ID, only for token authentication |
| Secret | token value or password, kept in the password safe |
| Group guests by | Server / Pool / Tag view, or a flat list |
| Double click opens | noVNC, xterm.js or SPICE |
| Auto-refresh | seconds between automatic reloads, `0` disables it |
| Task history size | how many task rows the Tasks tab requests |
| Confirm destructive actions | stop, reboot, rollback, restore and delete ask first |

Use **Test Connection** to verify the settings; it reports the Proxmox VE version, how many nodes
are visible and how many root privileges the account has.

### Creating an API token

```bash
pveum user token add root@pam idea --privsep 0
```

Copy the printed value into the *Secret* field and put `idea` into *Token name*.
For a least-privilege setup create a dedicated user and grant it `PVEVMAdmin` on `/vms`.

Some features need more than `PVEVMAdmin`: the node tabs need `Sys.Audit`, backup and restore need
`VM.Backup` plus `Datastore.AllocateSpace`, and the create wizards need `VM.Allocate`. A token
without them still shows the tree - the affected tab simply reports what the server refused.

## Project layout

| Path | Contents |
|------|----------|
| `api/` | `ProxmoxTransport` (auth, envelope, errors) and the endpoint groups: cluster, nodes, guests, storages, access |
| `model/` | plain data classes: cluster, guests, storage, tasks, snapshots, backups, infrastructure, RRD |
| `service/` | `ProxmoxService` (client and model mapping), `ProxmoxTaskTracker` (UPID polling), `mapper/` |
| `settings/` | persistent settings, password safe access and the settings UI |
| `toolwindow/` | the panel, `tree/` (items, builder, renderer), `details/` (the tabs), `tasks/` (log viewer) |
| `dialogs/` | snapshot, clone, migrate, backup, restore, edit, resize and the two create wizards |
| `actions/` | `power/`, `snapshot/`, `lifecycle/`, `backup/`, `misc/` |

## Notes on behaviour

* **Multi-guest actions run one after another**, not concurrently: Proxmox locks a guest for the
  duration of a task, so parallel calls against the same guest would simply fail.
* **A linked clone is only offered for a template**, which is the rule the server enforces.
* **Configuration edits send only the fields you changed**, together with the config digest, so a
  concurrent change from the web UI is rejected rather than silently overwritten.
* **Device edits (disks, NICs) are left to the web UI** on purpose: they carry far more validation
  than a form here could reproduce faithfully.
