# MCA: Reputation

**Public standing with MCA villages: your deeds become stories, and those stories shape how the
village treats you.**

Hearts are personal. *MCA: Reputation* is public. MCA Reborn already tracks how much one villager
likes you — this mod tracks what the whole **village** knows about you: who you helped, who you
hurt, who *saw* it, and who has heard about it since. Kill a farmer alone in a dark field and the
village greets you tomorrow like nothing happened. Do it in the square at noon and count the days
until every door in town knows your name.

It's a lightweight add-on: no AI, no text generation, no servers phoned home. Just a
server-authoritative civic memory wired into MCA's own villages — and hooks for the rest of the
MCA add-on suite to turn that memory into dialogue, gossip, and work.

---

## What it does

### 🏘️ A reputation with the village, not a villager
Every player has a separate public standing with every MCA village — a score and a named tier,
per player, per village, dimension-aware and multiplayer-safe. Being Revered in one village buys
you nothing in the next, and your standing is *yours*: on a server, the hero and the menace can
walk the same streets.

### 📜 They remember what you did — specifically
The score isn't a mystery meter. Every point traces back to a recorded **deed**: what you did,
when, to whom, and who found out. Open the Standing screen and read *"What they remember"* —
`Kept a promise to Coralia`, `Attacked Brigid`, `Saw the new well through` — each with its age,
its status, and (if you want) its exact cost or reward.

### 👁️ Nobody knows what nobody saw
Public deeds need **witnesses**. When something happens, nearby villagers with line of sight
become the ones who know — nobody else. A victim always knows their own beating, even with no
one around; but a killing with no surviving witness stays a secret, held in the ledger as hidden
history with **no penalty at all**, because the village genuinely doesn't know. No magic
detection, no omniscient villagers.

### 🗣️ Word gets around
Witnessed deeds don't stay with the witnesses. Each other resident learns the story after their
own delay — somewhere between a quarter-day and a couple of in-game days — so rumor *spreads*
through town rather than teleporting. (It's deterministic under the hood: no tick simulation, no
save bloat, no server cost.) Village-scale deeds like a finished quest are known to everyone at
once.

### ⚖️ Nine ways a village can see you
From **Infamous** (*"Your name is a warning here."*) through **Stranger** (*"Nobody here has an
opinion of you yet."*) up to **Revered** (*"Children here will grow up knowing your name."*).
Climbing into a tier for the first time earns a toast; slipping down gets a quiet, subdued line
instead — the village doesn't celebrate your fall, it just… cools.

### 🎖️ Titles are earned, not rented
Reaching Honored or Revered grants a permanent **title**. Titles are badges for what you *did*,
not a live meter of what you *are* — if your standing later collapses, the title stays. Titles
can be village-scoped or global, and datapacks can add their own.

### 🤝 You can make it right
Nothing is ever deleted — but deeds can be **resolved**. An incident can become *apologised for*,
*atoned*, *forgiven*, or *disproven*, each reducing its remaining penalty by that deed's own
rules. A public apology is a small good deed in its own right, but it never erases the original —
being sorry isn't the same as making it right. Some wounds also fade on their own (an assault
starts decaying after a couple of days); a killing never does.

### 🛡️ Deliberately hard to farm
The mod automatically watches a *narrow, reliable* set of actions: attacking villagers (one
incident per beating, not per hit — and genuine self-defense costs a quarter of the penalty) and
killing them (which upgrades the beating rather than stacking on top of it). There is
deliberately **no** reputation from trading, gifts, or chat spam — those overlap MCA's own hearts
and are trivially farmable. Everything else comes from authored content: quests, projects,
situations, conversation choices, datapacks, and commands.

### 🖥️ The Standing screen
A **Standing** button on MCA's own villager interaction screen (plus an optional keybind, unbound
by default) opens a clean summary: the village, your tier and progress toward the next, your
titles, and the scrollable list of what they remember. It is drawn from a texture sheet in
vanilla's own container idiom — the same panel frame, the same two text colours, a real draggable
scrollbar — so it reads as part of the game rather than as a menu bolted on beside it. Prefer
mystery? A client toggle hides exact numbers and shows only tiers and qualitative progress.

### 🔒 Multiplayer-safe by construction
Everything is server-authoritative — clients can't submit scores, deeds, or witnesses. Storage is
bounded, sync is rate-limited, there are no per-tick village scans, and turning any feature off
changes behavior only: no saved record is ever deleted by a config change.

---

## Requirements

**Language:** English.

| | |
|---|---|
| **Minecraft** | 1.20.1 |
| **Mod loader** | Forge 47.x (built with 47.4.10) |
| **Required** | [MCA Reborn](https://www.curseforge.com/minecraft/mc-mods/minecraft-comes-alive-reborn) `7.6` – `7.7` |
| **Only if MCA needs it** | [Architectury API](https://www.curseforge.com/minecraft/mc-mods/architectury-api) — required by MCA 7.6, dropped by MCA 7.7. This mod never asks for it |
| **Optional** | MCA: Quests — quests, projects, and situations become named public deeds |
| **Optional** | MCA: Conversations — villagers gossip about your actual deeds and treat you accordingly |
| **Optional** | MCA: Crime — safe to install alongside; the two mods agree on who detects villager harm |

> This is an **add-on** — MCA Reborn must be installed for it to do anything.

**One jar, every supported MCA.** MCA renamed its base package in 7.7.1, the kind of change that
normally forces a version-specific build. This mod resolves MCA by name at runtime instead, so the
same file works on 7.6 and on 7.7.1+, and an MCA it genuinely cannot read switches its own
integration off and logs why rather than taking the server down. Runtime-tested against MCA
`7.6.20`, `7.7.0-beta.2`, and `7.7.1-alpha.2`; at startup the log states which MCA build was
detected and whether integration came up — that line is the first thing to include in a bug report.

---

## Optional: MCA: Quests integration

With **MCA: Quests** installed, Reputation becomes the single source of truth for village
standing. Quest completions, failures, and abandons, project phases, and situation outcomes all
become *named* public deeds — `Finished "The Missing Shipment"`, not an anonymous `+12`. The
Journal shows the same canonical standing, and restitution quests can formally resolve a bad deed
as *atoned*.

Your existing Quests reputation, tier progress, and titles **migrate automatically** into the new
per-player system — nothing you earned is lost, and Quests alone keeps working exactly as before
if Reputation isn't installed.

*Quests `1.1.0+` integrates; the Journal's **[View Deeds]** link needs `1.2.0+`.*

## Optional: MCA: Conversations integration

With **MCA: Conversations** installed, the ledger comes alive. Villagers who know a deed tell it
as gossip — by name, in their own personality's voice, once per teller. Your public standing
gently colors trust and respect in conversation checks (it flavors outcomes; it can never
single-handedly decide them). And you can simply ask: *"What do people think of me around here?"*
— the answer names your tier, you can press for the deed people actually talk about, and if
something genuinely hangs over you, an amends path lets you apologise in public and have it
recorded for what an apology is worth.

*Conversations `1.1.0+` integrates; deed gossip and the standing topic need `1.2.0+`.*

All three add-ons together close the loop: a promise made in conversation becomes a quest, the
finished quest becomes a public deed, and the deed becomes a story the village tells about you.

## Optional: MCA: Crime compatibility

Both mods watch for villagers being harmed, so with no agreement between them, installing both
would file two penalties for one punch — and neither side could prevent that alone. Since 0.3.0
there is a public handshake: MCA: Crime claims villager assault and killing, this mod stands down
from detecting them, and Crime files the *same* deed through the same API. The ledger, scores,
decay, gossip and witnesses come out identical whichever mod saw it, and giving the claim up
restores detection here on the very next event, no restart. `/mcareputation debug authorities`
says who currently holds what.

---

## Configuration

Everything is toggleable. `config/mcareputation-common.toml` (server-authoritative) covers the
core detection (assault/killing, self-defense window and multiplier, minimum damage), witnessing
(radius, line of sight, rumor spread timing), score bounds and decay, storage limits, and each
integration and the legacy migration independently. `config/mcareputation-client.toml` covers
presentation only: the Standing button, toasts, action-bar feedback, and whether exact scores and
deltas are shown.

## Commands

`/mcareputation` (alias `/mcarep`) lets any player check their own standing (`get`, `list`,
`history`), while operators get the full tree: adjust scores, record or resolve incidents, pin
notable deeds, grant and revoke titles, inspect tier ladders, validate datapack content, and run
or inspect the legacy migration. A `debug` branch answers support questions directly:
`debug standing` prints everything the standing pipeline believes about one player — stored score,
active tier, the exact amount remaining to the next one, which community the screen would open on
and whether they have a record there, the integration toggles and the MCA binding — and
`debug community`, `debug witnesses` and `debug authorities` cover the rest. Every mutation is
audit-logged with who did it and why.

## For datapack & modpack authors

Deeds, tier ladders, and titles are **data**, not code — JSON under
`data/<namespace>/mcareputation/…`. A custom incident type defines its own score delta,
visibility (private / witnessed / village), severity, decay, resolution multipliers, and gossip
line, and pack-defined tier ladders can reshape the whole social climb. Legacy `mcaquests` tier
and title packs keep loading unchanged. `/mcareputation validate` reports every content problem
with the exact file and field.

## For mod developers

A stable, documented Java API (`dev.otectus.mcareputation.api`) and five Forge events
(reputation changed, tier changed, incident created, incident resolved, title granted) let other
mods record deeds, query standing, and react to changes. Every write goes through one atomic,
idempotent transaction — dedupe keys guarantee a deed lands exactly once, no matter how many
systems report it. A mod that does its own villager-harm detection can claim it through
`registerCoreIncidentAuthority` so the two never double-count.

## Status & license

**0.3.0** — alpha, first public release, actively developed; feedback and bug reports welcome.
Licensed **GPL-3.0-only**, matching MCA Reborn.
