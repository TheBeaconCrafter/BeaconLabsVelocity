# BeaconLabsVelocity

**A feature-rich Velocity proxy plugin** for Minecraft networks. It provides maintenance mode, whitelist, punishments, player reporting, private messaging, chat filtering, server guard, playtime tracking, optional Feather integration (server list background + Discord Rich Presence), and optional cross-proxy sync via Redis.

---

## Requirements

| Requirement | Notes |
|-------------|--------|
| **Velocity** | Latest 3.x recommended |
| **Java 17+** | Required to run the plugin |
| **MariaDB/MySQL** | Optional. Required for punishments, reports, whitelist, playtime, and IP history. |
| **Redis** | Optional. Required only for [Cross-Proxy](#cross-proxy-redis) features. |
| **Feather Server API (Velocity)** | Optional. Required only for [Feather](#feather-integration) (server list background, Discord RPC). |

---

## Installation

1. Download the latest `beaconlabsvelocity-*.jar` from [Releases](https://github.com/bcnlab/BeaconLabsVelocity/releases) (or build from source).
2. Place the JAR in your Velocity proxy’s `plugins/` folder.
3. Start the proxy once to generate `plugins/BeaconLabsVelocity/config.yml`.
4. Edit `config.yml` (and optionally `punishments.yml`, `badwords.yml`, `servers.yml` in the same folder).
5. If using the database: set `database.enabled: true` and fill in `database.*`. Restart the proxy.

**Optional – Feather:**  
Install the [Feather Server API](https://github.com/p-dobosz/feather-server-api) Velocity plugin in `plugins/`. Then enable and configure the `feather` section in `config.yml`.

**Optional – Cross-Proxy:**  
Enable and configure the `redis` section in `config.yml` on every Velocity proxy that should participate.

---

## Features

### Core & MOTD

- **Custom MOTD** – Server list title and subtitle with [MiniMessage](https://docs.adventure.kyori.net/minimessage/format.html) (gradients, hover, click, colors). Separate maintenance MOTD when maintenance mode is on.
- **Configurable max players and version line** – Shown on the server list.
- **Lobby server** – Default server for new joins; configurable in `config.yml` (`lobby-server`).
- **Plugin info** – `/labsvelocity` shows plugin version.

---

### Maintenance & Whitelist

- **Maintenance mode** – When enabled, only players with `beaconlabs.maintenance.bypass` can join. Others see a configurable kick message. MOTD can switch to a “maintenance” version.
- **Proxy whitelist** – When enabled, only whitelisted players (and those with `beaconlabs.whitelist.bypass`) can join. Stored in the database; managed with `/proxywhitelist` (aliases: `/pwhitelist`, `/pw`).

Both support custom kick messages and bypass permissions.

---

### Punishments

Full punishment system backed by the database (requires DB enabled):

- **Mute** – Blocks chat (and optionally other actions) for a duration or permanently. Configurable messages and behaviour in `punishments.yml`.
- **Ban** – Prevents joining. Supports temporary and permanent bans, with configurable kick message and optional ban broadcast.
- **Kick** – Disconnects a player with a message.
- **Warn** – Records a warning; can be used for escalation (e.g. auto-mute/ban after N warns if you implement it elsewhere).
- **History** – `/punishments` / `/banlog` to view a player’s punishment history.
- **Unmute / Unban** – Remove active mute or ban.
- **Clear punishments** – `/cpunish` to clear specific or all punishment types for a player.

Punishment messages and defaults are in `punishments.yml`. Permissions are separate for each action (e.g. `beaconlabs.punish.mute`, `beaconlabs.punish.ban`).

---

### Player Reporting

- **Report** – Players use `/report <player> [reason]` to report others. Reports are stored in the database.
- **Reports list** – Staff use `/reports` to view, assign, and resolve reports. Configurable cooldown and notify permission (`beaconlabs.reports.notify`).

Requires database.

---

### Messaging & Chat

- **Private messages** – `/msg`, `/tell`, `/w`, `/whisper`, `/m` to message a player; `/r` or `/reply` to reply to the last sender.
- **Team chat** – `/teamchat` or `/tc`: message visible only to players with `beaconlabs.teamchat`.
- **Broadcast** – `/broadcast` or `/bc` to send a message to all players (permission: `beaconlabs.broadcast`).
- **Chat report** – `/chatreport [player]`: generates a chat log (from proxy-side log) and can upload to a paste service. Useful for evidence. With Cross-Proxy, can request a report for a player on another proxy.

---

### Chat Filter

- **Bad-word filter** – Words listed in `badwords.yml` are detected in chat. Messages containing them can be blocked and/or alerts sent to staff with `beaconlabs.chatfilter.alert`.
- **Interactive alerts** – Staff can get clickable alerts (e.g. chatreport, warn) from the filter.

Config file: `plugins/BeaconLabsVelocity/badwords.yml`.

---

### Server Guard

- **Per-server access** – Control who can join which backend server via permissions.
- **Configuration** – In `servers.yml`: set `default-action` (ALLOW or BLOCK), list `always-allowed` servers (e.g. lobby), and map server names to permissions (e.g. `survival: beaconlabs.server.survival`).
- **Admin bypass** – Players with `beaconlabs.admin` can join any server.
- **Reload** – `/serverguard reload` or `/sg reload` to reload `servers.yml`.

---

### Navigation & Sending

- **Lobby** – `/lobby`, `/l`, `/hub` send the player to the configured lobby server.
- **JoinMe** – `/joinme` lets other players click to join the same server as the sender. Configurable cooldown and permissions in `config.yml` (`joinme.*`).
- **Send** – `/send <player|all|current> <server>` (aliases: `/proxysend`, `/psend`) to move players. With Cross-Proxy, can send to players on other proxies.
- **Goto** – `/goto <player>` to move yourself to the server the target player is on.

---

### Player Stats & Info

- **Playtime** – Tracks session time in the database. `/playtime` or `/pt` for own playtime; optional `/playtime top` and viewing others’ playtime with permissions.
- **IP history** – Stores and retrieves IPs per player (for staff). Shown in `/info` when permitted.
- **Info** – `/info <player>` shows detailed player info (punishments, server, playtime, IPs if permitted).
- **Plist** – `/plist` lists players on this proxy (and with Cross-Proxy, can show players on all proxies).
- **Ping** – `/ping [player]` to see latency.
- **Proxies** – `/proxies` lists known proxies and player distribution (Cross-Proxy). Optional `/proxies debug` with permission.

Requires database for playtime and IP history.

---

### Staff & Admin

- **Staff list** – `/staff` or `/team` lists online staff (permissions: `beaconlabs.visual.staff`, `beaconlabs.visual.mod`, `beaconlabs.visual.admin`).
- **Server metrics** – `/servermetrics` or `/sm` for proxy/server metrics (permission: `beaconlabs.admin.servermetrics`).
- **Skin** – `/skin <username> [target]` to set skin; optional permission to set others’ skin.
- **Feather debug** – Console-only `/featherdebug on|off` to toggle Feather join event logging (permission: `beaconlabs.command.feather.debug`).
- **IPs** – `/ips <player>` to view IP history (permission: `beaconlabs.admin.ips`).

---

### Feather Integration

Optional integration with the [Feather Server API (Velocity)](https://github.com/p-dobosz/feather-server-api). Requires the Feather Velocity plugin in `plugins/` and `feather.enabled: true` in `config.yml`.

- **Server list background** – Custom PNG shown on the server list (place image in `plugins/BeaconLabsVelocity/feather/`, set `feather.server-list-background` to the filename). Sizes: recommended 909×102 px, max 1009×202 px, 512 KB.
- **Discord Rich Presence** – When `feather.discord.enabled` is true, players’ Discord status can show custom text and image. Placeholders in `state`, `details`, and `image-text`: `%players%`, `%max_players%`, `%server%`, `%player%`, `%dimension%` (dimension may show `?` on proxy if Feather doesn’t provide it). Player count and placeholders update when players join or leave.
- **Feather debug** – Use `/featherdebug on` in console to log Feather client join (name, UUID, platform, mods). No compile-time dependency on Feather; uses reflection.

---

### Cross-Proxy (Redis)

When `redis.enabled` is true and Redis is configured, multiple Velocity proxies share state over Redis Pub/Sub (with a shared secret):

- **Duplicate session** – By default, the same player cannot be online on two proxies at once; the second join can be kicked. Optional `allow-double-join` to allow it.
- **Perform actions across proxies** – All commands that interact with players on the proxy are compatible with cross proxy sync. Players on Proxy A can still /msg players on Proxy B, admin commands etc all of course work
- **Player list** – `/plist` and `/info` can reflect players on all proxies; `/proxies` shows proxy list and counts.
- **Team chat & reports** – Team chat and report notifications can be delivered to staff on all proxies.

All proxies must use the same Redis instance and the same `shared-secret`. Each proxy should have a unique `proxy-id` (e.g. `na`, `eu`).

---

## Commands

| Command | Description | Permission |
|--------|-------------|------------|
| **Core** | | |
| `/labsvelocity` | Plugin version | — |
| `/staff`, `/team` | List online staff | `beaconlabs.visual.staff` |
| `/joinme` | Share link to join your server | `beaconlabs.command.joinme` |
| **Server** | | |
| `/lobby`, `/l`, `/hub` | Go to lobby | — |
| **Messaging** | | |
| `/msg`, `/tell`, `/w`, `/whisper`, `/m` | Private message | `beaconlabs.message` |
| `/r`, `/reply` | Reply to last message | `beaconlabs.message` |
| `/teamchat`, `/tc` | Staff-only team chat | `beaconlabs.teamchat` |
| `/broadcast`, `/bc` | Broadcast message | `beaconlabs.broadcast` |
| `/chatreport [player]` | Generate chat log / paste | `beaconlabs.chat.chatreport` |
| **Punishments** | | |
| `/mute <player> [duration] [reason]` | Mute | `beaconlabs.punish.mute` |
| `/unmute <player>` | Unmute | `beaconlabs.punish.unmute` |
| `/ban <player> [duration] [reason]` | Ban | `beaconlabs.punish.ban` |
| `/unban <player>` | Unban | `beaconlabs.punish.unban` |
| `/kick <player> [reason]` | Kick | `beaconlabs.punish.kick` |
| `/warn <player> [reason]` | Warn | `beaconlabs.punish.warn` |
| `/punishments`, `/banlog` | Punishment history | `beaconlabs.punish.history` |
| `/cpunish <player> [type]` | Clear punishments | `beaconlabs.punish.clear` |
| **Reports** | | |
| `/report <player> [reason]` | Report a player | `beaconlabs.command.report` |
| `/reports` | View/manage reports | `beaconlabs.command.reports` |
| **Utility** | | |
| `/ping [player]` | Show ping | `beaconlabs.command.ping` / `.ping.others` |
| `/playtime [player\|top]` | Playtime | `beaconlabs.command.playtime` (+ `.others`, `.top`) |
| `/skin <name> [target]` | Set skin | — / `beaconlabs.command.skin.other` |
| **Admin** | | |
| `/goto <player>` | Go to player’s server | `beaconlabs.command.goto` |
| `/send`, `/proxysend`, `/psend` | Send player(s) to server | `beaconlabs.command.send` |
| `/plist` | Player list | `beaconlabs.command.plist` |
| `/proxies [debug]` | Cross-proxy list / debug | `beaconlabs.command.proxies` / `.command.proxies.debug` |
| `/info <player>` | Player info | `beaconlabs.punish.info` |
| `/ips <player>` | IP history | `beaconlabs.admin.ips` |
| `/maintenance [on\|off]` | Maintenance mode | `beaconlabs.command.maintenance` |
| `/proxywhitelist`, `/pwhitelist`, `/pw` | Whitelist management | `beaconlabs.command.whitelist` |
| `/serverguard`, `/sg` | Server guard reload | `beaconlabs.command.serverguard` |
| `/servermetrics`, `/sm` | Server metrics | `beaconlabs.admin.servermetrics` |
| `/featherdebug on\|off` | Feather debug (console) | `beaconlabs.command.feather.debug` |

---

## Permissions (overview)

| Permission | Purpose |
|------------|--------|
| `beaconlabs.maintenance.bypass` | Join during maintenance |
| `beaconlabs.whitelist.bypass` | Join when whitelist is on |
| `beaconlabs.message` | Use /msg and /reply |
| `beaconlabs.teamchat` | Use and see team chat |
| `beaconlabs.broadcast` | Use /broadcast |
| `beaconlabs.chat.chatreport` | Use /chatreport |
| `beaconlabs.chatfilter.alert` | Receive chat filter alerts |
| `beaconlabs.reports.notify` | Receive report notifications |
| `beaconlabs.punish.*` | mute, unmute, ban, unban, kick, warn, history, clear, info, notify, ban.bypass |
| `beaconlabs.command.report` | Use /report |
| `beaconlabs.command.reports` | Use /reports |
| `beaconlabs.command.joinme` | Use /joinme |
| `beaconlabs.command.joinme.bypass` | Bypass joinme cooldown |
| `beaconlabs.command.ping` | Use /ping (self) |
| `beaconlabs.command.ping.others` | Use /ping on others |
| `beaconlabs.command.playtime` | Use /playtime (self) |
| `beaconlabs.command.playtime.others` | View others’ playtime |
| `beaconlabs.command.playtime.top` | Use /playtime top |
| `beaconlabs.command.goto` | Use /goto |
| `beaconlabs.command.send` | Use /send |
| `beaconlabs.command.plist` | Use /plist |
| `beaconlabs.command.proxies` | Use /proxies |
| `beaconlabs.command.proxies.debug` | Use /proxies debug |
| `beaconlabs.command.maintenance` | Use /maintenance |
| `beaconlabs.command.whitelist` | Use /proxywhitelist |
| `beaconlabs.command.serverguard` | Use /serverguard |
| `beaconlabs.command.feather.debug` | Use /featherdebug |
| `beaconlabs.visual.staff` | Appear in /staff |
| `beaconlabs.visual.mod` | Shown as Mod in /staff |
| `beaconlabs.visual.admin` | Shown as Admin in /staff |
| `beaconlabs.admin` | Bypass server guard; high-level access |
| `beaconlabs.admin.ips` | View IPs in /info |
| `beaconlabs.admin.viewips` | View IP section in /info |
| `beaconlabs.admin.servermetrics` | Use /servermetrics |

---

## Configuration

Main file: `plugins/BeaconLabsVelocity/config.yml`

- **prefix** – Chat/command prefix (e.g. `&6BeaconLabs &8» `).
- **lobby-server** – Default server name for new joins and `/lobby`.
- **motd** – Server list lines (MiniMessage), max-players, version-name, version-protocol.
- **database** – Host, port, database, user, password; enable with `enabled: true`.
- **maintenance** – enabled, kick-message, motd overrides, bypass permission.
- **whitelist** – enabled, kick-message (bypass: `beaconlabs.whitelist.bypass`).
- **reports** – cooldown-seconds, notify-permission.
- **joinme** – cooldown, use and bypass-cooldown permissions.
- **feather** – enabled, server-list-background, discord (enabled, image, image-text, state, details).
- **redis** – enabled, host, port, password, shared-secret, proxy-id, allow-double-join, timeouts.

Other files in the same folder:

- **punishments.yml** – Punishment messages, defaults, and behaviour.
- **badwords.yml** – List of bad words for the chat filter.
- **servers.yml** – Server guard: default-action, always-allowed servers, server → permission mapping.

---

## Building

```bash
mvn clean package
```

Output: `target/beaconlabsvelocity-1.2.jar`. Use the JAR without `-sources` for the proxy.

---

## License

See the project repository for license information.
