# Fleet backup

Back to [README](../README.md).

**Tick what matters. Vaier does the rest.** That is the whole of backing up a machine in Vaier, and it is the only path you need to learn. Everything below the first section is reference: how it works under the hood, and the recovery and adoption machinery for the day a NAS has no root to give or a repository already exists.

## Tick what matters

Open a machine's files in the **Explorer**, tick the folders that matter — the ones you would want back if the machine died — and press **Back up**. That is the one decision that is yours. Vaier makes every other one: it creates a place for that machine's archives on the backup server with a generated passphrase, prepares the machine on its first backup (trusting its key on the server, installing the client), keeps the list of paths minimal (a folder you tick absorbs anything already ticked beneath it), runs every night, and emails the admins only when a run fails or came back with holes. **Back up more** takes you back to the files; **Stop backing up** removes a path and its descendants, and — when the last one goes — the machine's job with it, leaving the archives already made untouched.

The machine's `backup` entry shows what is protected, when it last ran and how it went, and the two verbs above. Anything else about it — backing up other users' files, stopping altogether — sits under a fold, because it is not a step, it is a setting.

*How it works, in one paragraph:* the archives are [borg](https://www.borgbackup.org/) repositories on the machine you designated as the fleet's **backup server**, one repository per machine, reached over SSH with a key trusted for exactly that repository; the passphrases live in Vaier and, encrypted under one passphrase of yours, in the **survival kit** copied onto machines Vaier does not run on. You never have to name a repository, choose retention or compression, or run borg yourself. The sections below are for when you want to see, adopt or recover that machinery by hand.

---

## Under the hood: recovery and adoption reference

Nothing here is a step on the way to a backup. It is what Vaier does for you, exposed for the hosts where it cannot (a Synology it has no root on), for adopting a borg server or repository that already exists, and for recovering with the borg CLI when Vaier is the thing that is gone.

---

## Backup server

A machine running a borg server that holds your repositories. The fleet has **at most one**. You designate which machine plays the role from its Inspector in the **Explorer** — a **Make this the fleet's backup server** action, offered on any machine but only while none is designated yet, with the coordinates form prefilled from that machine's own address. Adopt an existing borg server or have Vaier provision one from scratch (often a LAN server such as your NAS), and Vaier stands up a **pinned** borg-server container there.

The server's `backup` entry in the Explorer then carries its coordinates under a plain **Server details** fold and the manual operations — **Provision**, **Authorize a host**, **Setup script**, **Edit coordinates**, **Remove designation** — under a warning fold named for what it does, *Provision, authorize or remove this backup server*, alongside its repositories and jobs. Where Vaier can drive docker over SSH it runs the setup for you (**Provision**); where it can't — a Synology NAS, for instance, doesn't expose a usable docker CLI over SSH — it **stages** an idempotent **setup.sh** on the host over SSH and hands you the one command to run (`sudo bash <path>`). The setup script is served behind admin login, so it is never curled onto the host; if Vaier can't reach the host at all, download setup.sh from the UI and copy it over yourself. Either way that's guidance, not a failure.

**Authorize a host** trusts a client machine's SSH key on the server exactly once, so backup jobs authenticate — borg runs on the client as the SSH user, not root, and that key has to be trusted server-side. Authorizing also **pins the server's host key** on that client, so borg's non-interactive SSH can verify the server with no trust-on-first-use — Vaier obtains the key over its own authenticated channel and installs it in the client's `known_hosts`. If the backup server goes quiet, Vaier emails every admin (and again, once, when it recovers).

When you authorize a host, Vaier writes a **restricted** `authorized_keys` entry on the backup server, not a bare key: the key is forced to `borg serve` and confined (`--restrict-to-path`) to exactly the repositories that host backs up to on that server, with no shell, pty or forwarding. So one compromised client can never read or delete another host's repositories. Because the restriction is derived from the host's jobs, **adding a repository for a machine means re-authorizing that host** to widen its access to the new repository — until you do, backups to the new repository are refused. If you authorize before creating any job, the key is confined to the repository root as a safe placeholder, and Vaier tells you to re-authorize once a job exists.

Authorizing also **pins the backup server's SSH host key** on the client, so borg never has to trust-on-first-use. A freshly provisioned borg server generates brand-new host keys, which either clashes with a client's stale `known_hosts` pin (`REMOTE HOST IDENTIFICATION HAS CHANGED`) or leaves a new client with no pin at all — and borg's non-interactive SSH fails rather than prompting. Because Vaier is the trusted broker (it reaches the server's machine with its own vault credential and pinned host key), it reads the server's public host keys authoritatively and installs them in the client's `known_hosts` — no `ssh-keyscan`, no `accept-new`. The setup script publishes those keys when it stands the server up, so a **provisioned** server is ready to pin; an **adopted** server (registered, never provisioned by Vaier) has no published host-key file, so authorizing still trusts the client key but reports that the host key wasn't pinned — run the setup script once on the server, or pin it manually, then authorize again.

---

## Backup repository

You never create one by hand: **Back up** creates one per machine behind the verb, named for the machine, with a generated passphrase, its path deriving as `base/<name>`. Each repository is still an entry of its own under the backup server's `backup` entry, and adopting an existing, oddly-named repository is done there, from its own entry. Each repository is an entry of its own in the tree — open it to see its path, append-only setting, whether a passphrase is stored, and the archives inside it, and to **Edit** or **Delete** it (Delete forgets the repository in Vaier; it does not erase the borg store or its archives).

A repository or server **name is a safe identifier** (letters, digits, `_` and `-`) because it becomes a shell/path token in every borg command — type "NUC 02" and it's slugged to `NUC-02` as you go, and spaces or shell metacharacters are refused outright. As defense in depth Vaier also **single-quotes every borg path** (the repo URL, each `--restrict-to-path`) so a hand-edited config file can never inject a command. On create, Vaier generates a strong, shell-safe **passphrase** for you, shown once with a copy button — save it, since it's stored encrypted at rest and never shown back.

Guided provisioning does the rest — **Check host readiness** reports whether borg is installed on a machine (and its version, and whether it's supported), whether the machine can reach the server, whether the **client's key is actually trusted** on the server (proved by running `borg info` for the repository from the client — reaching borg proves the key authenticated, even before the repository is initialised), and whether the client and server borg **versions are compatible** (borg 1.x and 2.x use incompatible repo formats, so their majors must match). Vaier knows a **provisioned** server's borg version from its pinned image; for an **adopted** server the version is unknown, so compatibility fails closed rather than guessing. A green borg and open port no longer read as ready when the key isn't trusted — authorize the host right there and re-check.

When borg isn't installed on the machine, **Prepare client** installs it: Vaier detects the host's package manager (apt/dnf/yum/apk/pacman/zypper — Arch's package is `borg`, the rest ship `borgbackup`) and runs an idempotent install, itself over SSH where the SSH user has passwordless sudo, or by staging the script and handing you the one `sudo bash <path>` command where it doesn't. Prepare client also grants the SSH user **passwordless sudo for the borg binary alone**, which is what a job that backs up **as root** needs — so it does useful work on a machine that already has borg, and re-running it on a prepared host is how you add that grant. The rule is validated before it is installed, and it names borg's paths and nothing else (never a shell or an env wrapper — either would hand out root on the host).

The install runs detached on the host (it can outlast the SSH exec cap); the **backend** watches it — and, the same way, a **server provision** and an **on-demand run** — and the browser learns each one finished over a single live server-sent-events stream, never by polling. And a run that would otherwise die with `borg: not found` is now refused up front — Vaier probes for borg before launching and records a clear "borg is not installed on _machine_ — run Prepare client" instead. There is no manual initialise step: a **backup run** creates its repository with `borg init` when it is absent, so a repository is never a hidden prerequisite.

---

## Backup jobs

Give each machine a job: which machine (by name), which backup repository, the source paths to back up, exclude patterns, retention (`keepDaily` / `keepWeekly` / `keepMonthly`), compression (default `zstd,6`), whether the job is enabled, and whether it backs up **as root**. You create, edit, delete, run and enable/disable a job in the **Explorer**, on the machine's `backup` entry — there is no separate Backups page.

The **Tick what matters** path above creates and maintains this job for you; the form is for reading how a machine is being backed up today and for adopting a job written by hand.

### Back up as root

borg runs on the machine as the SSH user (e.g. `ubuntu`), not root, so **every file in the job's source paths that this user cannot read is skipped**: the run still writes an archive, but that archive has holes in it. Container volumes are the usual victims — a mosquitto database owned `1883:1883` mode `0600`, a pihole file owned `root:root` — and chmod'ing files one by one is whack-a-mole, since every new container volume is a fresh silent hole. What the setting means in operator terms is on the in-app **Concepts** page (*Back up as root*); this section is the mechanics.

**You are asked only when it has cost you something.** A run that could not read a source file settles **incomplete**, which is a failure and emails the admins (see *Running* below), and the machine's pane then raises a single suggestion — *This backup is missing N files*, naming them. Saying yes is **one** action: Vaier installs the grant where it is missing and turns the job's setting on, but only once the machine really grants it. The flag never moves ahead of the grant — a job asking for root on a machine that has not granted it doesn't back up badly, it doesn't back up at all, since every run dies on `sudo -n` before borg starts. Where the grant has to be installed, that install runs detached and Vaier finishes the action itself when it lands; where Vaier can't gain root at all, it hands you the one `sudo bash <path>` command instead. The grant is a sudoers rule naming the borg binary and nothing else (never a shell or `env`) — the same one **Prepare client** installs.

The setting is still a checkbox on the machine's `backup` entry in the **Explorer** (*Back up files owned by other users*), under **Advanced**: it is where you read how a machine is being backed up today, and where you turn it back **off** — off is a plain job save, needing nothing installed anywhere. It is **opt-in** either way; a job never escalates itself.

Be clear-eyed about what you are granting: **a passwordless `sudo borg` is root-equivalent**, and scoping the rule to the borg binary does not change that — a borg running as root can read and write any file on the machine, and its `--rsh` flag runs a command of the caller's choosing as root. The narrow rule keeps the grant auditable and honest about its purpose; it is not a sandbox. The real control is that it is per-job and opt-in: turning it on makes Vaier's stored SSH credential for that machine as powerful as root, so grant it only where that is acceptable. **Check host readiness** shows a *borg can run as root* row for any job with it on — and only for those, since a job that runs as the SSH user doesn't need the grant — with a **Prepare client** button right on that row when it's missing.

### Running

Run any job on demand with **Run now** from its machine's `backup` entry in the **Explorer**, or let the **nightly schedule** run every enabled job once a day. The schedule hour (0–23, default 2) is set on the ungated **Settings** surface, alongside the disk-pressure threshold.

Each execution is a **backup run** with a status (running, success, warnings, **incomplete**, failed, or unknown); a failed run emails every admin, reusing the same SMTP configuration as the other alerts. Two outcomes share borg's exit 1, and Vaier tells them apart by reading the run's own output. A run that **could not read some of its source files** settles **incomplete** — the archive was written but is missing data, which is the worst way a backup can fail (it looks fine until you need it), so it counts as a **failure**: it reads red in the Explorer and emails every admin with a subject that says *Backup incomplete*, naming how many files were lost, a sample of their paths, and the **Back up as root** setting that would have read them. A run that merely grumbled without losing anything (a file changed while borg read it) still settles to **warnings** — its archive is complete, so it is not a failure and does not page anyone.

A run that ended in warnings, an incomplete archive, failure, or an unknown outcome carries its **run diagnostics** — the lines borg actually reported, such as `/home/ubuntu/mqtt/data/mosquitto.db: open: [Errno 13] Permission denied` — so *which* files were skipped and why is answered in the UI instead of by reading a YAML file on the server. borg's machine-readable statistics are stripped out, so what you read is only the part meant for a human; a clean run has no diagnostics.

A run starts detached on the host and its outcome is **pushed to the browser over an SSE stream** the moment the backend sweep sees it settle, never by polling. Vaier keeps its small per-host working state — the borg passphrase file and each run's result/log — in `~/.vaier-backup` on the target machine (the SSH user's own home, so it works whether or not that user is root), falling back to `/tmp/vaier-backup` if the home directory can't be determined.

### Archives

Open a **backup repository** entry in the Explorer to browse the point-in-time archives it holds, each with its name and time (borg's `list` runs on a job's host, so a repository no job targets shows an empty archive list rather than an error). **Restore from the UI is not yet available** — recover with the borg CLI against the repository for now. (The Explorer's file browser does let you browse and copy files out of an archive via its time rail — see [`docs/EXPLORER.md`](EXPLORER.md) — this note is about a dedicated restore *flow*.)

---

## Survival kit

Your repository passphrases live inside Vaier, and Vaier's own backup is encrypted with one of them — so losing the Vaier server would leave you holding archives that nothing left standing can open. Nothing warns about it, because nothing is broken until everything is.

The **survival kit** breaks that circle: every repository's `ssh://` address and passphrase, the borg commands that read them, and Vaier's own **config key**, encrypted under **one passphrase you choose** and copied onto machines Vaier does not run on. Set the passphrase in **Settings → Reading your backups without Vaier** (typed twice, because a mistyped one looks saved until the day you need it) and press **Write the kit now**.

**Vaier picks the hosts and says why** — a server it can reach over SSH, never a laptop or a phone, never itself, and never two behind the same relay; where the fleet has more sites than copies, it keeps the ones furthest apart on the map, since two relays in one building burn together. It reports where each copy went and the reason, which hosts refused, and the only thing that really matters: whether anything written now outlives this server. A fleet with fewer sites than copies gets fewer copies and is told so.

The kit lands as `vaier-survival-kit.txt` in each host's SSH-user home (`0600`), plus one beside Vaier's own config for the likelier day when Vaier will not start on a host whose disk is fine — that copy never counts toward the three, because a copy that dies with Vaier is not redundancy. **Opening it needs no Vaier**: the one `openssl enc -aes-256-cbc -pbkdf2 -d` command is printed in the clear at the top of every copy, along with the date and repository count, so a stale kit is recognisable before you type anything.

**Vaier keeps them current itself** — it fingerprints what a kit would say now and compares it with what the fleet was last written, every ten minutes, so adding a repository, rotating a passphrase, re-pointing a job or renaming a machine rewrites every copy without being asked. Changing the **kit passphrase** rewrites them too (the words are identical, but every copy out there still opens with the old one), and a host that was asleep for a write is tried again until it has one, across restarts.
