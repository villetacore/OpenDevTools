# Git Forge Tools

IntelliJ IDEA plugin (Kotlin) that puts every git service you use in one tool window: **GitHub**,
**GitHub Enterprise**, **GitLab**, **Gitea** and **Forgejo** - hosted or self-hosted, several
accounts at once.

## Multiple accounts is the point

Most git integrations assume one account per service. This one does not: a work GitHub next to a
personal one, two GitLab instances, a self-hosted Gitea and a Forgejo, all configured together and
all listed in the same tree. An account is identified by its own id, not by its kind, so nothing
stops you from adding the same forge twice.

Each account's token lives in the IDE password safe under that account's id. Renaming an account
keeps its token; removing one deletes it.

## Features

### Accounts

* Add, edit and remove accounts in a table under *Settings | Tools | Git Forges*.
* **Test Connection** per account, which also fills in the user name shown next to it.
* Self-hosted instances need the server root only - `/api/v3`, `/api/v4` or `/api/v1` is appended
  according to the kind.
* SSL verification can be turned off per account, for an instance behind a self-signed certificate.

### Repositories

* Tree of accounts and their repositories, with a filter across names and descriptions.
* Forks and private repositories can each be hidden.
* **The repository of the open project is detected and highlighted**, and is selected automatically
  on first load. The match comes from the project's own `.git/config` - both the `owner/name` and
  the host have to agree, because two forges can host a repository of the same name.

### Per repository

One request round loads every tab, so switching between them is instant:

| Tab | Contents |
|-----|----------|
| Summary | visibility, default branch, counts, clone URLs |
| Issues | number, title, state, author, labels, comments |
| Pull requests | number, title, status, author, source and target branch |
| CI | workflow runs / pipelines with their status, branch and commit |
| Releases | tag, name, release or prerelease or draft |
| Branches | name, default, protected, head commit |

### Actions

* Create an issue; comment on an issue or a pull request; close and reopen an issue.
* Open a pull or merge request between two branches, optionally as a draft.
* Merge a pull request, with only the methods that forge actually supports offered.
* Open anything in the browser; copy the HTTPS or SSH clone URL.
* Show what the plugin reads out of the project's `.git/config`, for when a match is not found.

## Requirements

* JDK 21 for the build (`JAVA_HOME` must point at it).
* IntelliJ IDEA 2025.3 or newer, Community or Ultimate.
* An access token per account.

The plugin does **not** depend on the bundled Git plugin. It reads `.git/config` itself, so it
still loads in an IDE where Git integration is disabled.

## Build and run

From the repository root:

```bash
./gradlew :git-forge-tools:buildPlugin   # build/distributions/git-forge-tools-0.2.0.zip
./gradlew :git-forge-tools:runIde        # sandbox IDE with the plugin installed
```

## Tokens

| Forge | Where to create one | Scope |
|-------|---------------------|-------|
| GitHub | *Settings \| Developer settings \| Personal access tokens* | `repo` for private repositories; `read:org` to see organization ones |
| GitHub Enterprise | the same, on your server | as above |
| GitLab | *Preferences \| Access tokens* | `api` |
| Gitea | *Settings \| Applications \| Generate new token* | repository read/write, issue read/write |
| Forgejo | the same as Gitea | as above |

A token that cannot see something simply shows less; the tool window reports what the server
refused rather than failing the whole view.

## Project layout

| Path | Contents |
|------|----------|
| `api/` | `ForgeClient` - the concepts all three dialects share - and `BaseForgeClient` for the shared HTTP, paging and error handling |
| `api/provider/` | `GitHubClient`, `GitLabClient`, `GiteaClient` (which also serves Forgejo) |
| `model/` | accounts, repositories, issues, pull requests, pipelines, releases, branches |
| `service/` | `ForgeService` (clients and loading), `GitRemoteDetector` (project matching) |
| `settings/` | the account table, the account dialog and the preferences |
| `toolwindow/` | the tree and the tabbed details pane |
| `dialogs/` | new issue, new pull request, merge, comment |
| `actions/` | navigation, issue and pull request actions |

## Notes on behaviour

* **One account failing does not hide the others.** A bad token or an unreachable server is
  reported next to that account in the tree and as a notification; every other account still lists
  its repositories.
* **A repository loads all its sections at once**, and each section is wrapped individually: a
  repository with issues disabled, or a Gitea without Actions, still shows the sections it has.
* **Gitea Actions is optional.** It arrived in Gitea 1.19 and is absent or disabled on plenty of
  instances, so a 404 from the CI endpoint means "this server has no CI", not a failure.
* **Dialect quirks are handled where they belong.** GitHub returns pull requests through its issues
  endpoint, so the issue list filters them out; GitLab addresses a project by id or URL-encoded
  path and numbers issues by `iid`; GitLab marks a draft with a title prefix rather than a flag.
* **Paging stops at ten pages** per collection. An account with thousands of repositories should
  not be able to stall the tool window.
* **No code review.** Diffs, review comments and approvals are not implemented; the plugin links
  to the pull request in the browser for that.
