# MCA: Reputation

> Your deeds become stories, and those stories shape how the village treats you.

An add-on for [Minecraft Comes Alive: Reborn](https://modrinth.com/mod/minecraft-comes-alive-reborn) that
gives every MCA village a memory. Help people and word gets around. Hurt someone where others can see,
and word gets around about that too.

- **Minecraft** 1.21.1 · **NeoForge** 21.1.249+ · **Java** 21
- **Requires** MCA Reborn 7.7.x (built and verified against `7.7.36-beta.3+1.21.1`)
- **Optional companions** MCA: Quests, MCA: Conversations, MCA: Crime
- **Licence** GPL-3.0-only

---

## What it does

MCA already models how one villager feels about you — that is what hearts are. This mod models
something different: what a **village** thinks of you, and *why*.

- **Standing.** A number and a named tier, from Infamous through Stranger to Revered, kept **per player
  and per village**. Your reputation in one settlement says nothing about the next one.
- **Deeds.** Standing is not a bare number; it is explained by a ledger of the things you actually did.
  The screen shows what the village remembers and how much each thing still counts for.
- **Witnesses.** Villagers only know what they saw, or what they have since heard. A crime with nobody
  watching does not change your standing — though the world remembers it happened.
- **Rumour.** News spreads through a village gradually, at a different pace for each resident, without
  a single tick of background processing.
- **Amends.** A deed can be apologised for, atoned for, or forgiven. The penalty softens; the record
  stays. That is the point.

## What it deliberately does not do

No hearts replacement. No per-villager *stored* reputation (villagers have personal opinions derived
from what they witnessed or heard, never a separate score). No guards, fines, exile, or bounties. No
trade-price rewriting. No global fame. No AI text generation, no telemetry, no network calls. Routine
trading, gifts, and repeated conversation clicks earn nothing at all — those are farmable, and they
belong to systems that already own them.

## Installing

Drop the jar in `mods/` alongside MCA Reborn. That is the whole installation; the mod works standalone.

**Architectury is not required by this mod.** MCA 7.6 pulls it in itself and MCA 7.7 dropped it; this
mod contains no Architectury reference, so a 7.7 user who has removed it is not blocked.

## With the rest of the suite

Each add-on works alone, and any combination works. Installing more of them closes the loop:

| Installed | What you get |
|---|---|
| **Reputation** alone | Standing, the deeds ledger, witnesses and rumour, the Standing screen, commands, datapacks |
| **+ MCA: Quests** | Quests, projects, and situations move standing per participant; the Journal shows the same numbers; restitution quests can resolve a deed |
| **+ MCA: Conversations** | Villagers factor standing into trust and respect checks, and tell each other about your deeds in their own voice |
| **All three** | A promise in conversation becomes a quest, the quest becomes a public deed, the deed becomes a story, and the story unlocks the next piece of work |

Quests and Conversations each ship their own optional bridge. Nothing here depends on either at compile
time, and removing any one of the three leaves the others working.

## Seeing your standing

- A **Standing** button appears on MCA's villager interaction screen.
- An **Open Standing** keybind is registered, **unbound by default** — bind it in Controls if you want it.
- With MCA: Quests installed, the Journal links to the same screen.
- With MCA: Conversations installed, you can simply ask a villager what people think of you.
- **Optional:** A scoreboard objective showing your standing with the current village, and a tab-list
  suffix showing your tier. Both are off by default; enable them in the server config and set the
  scoreboard display slot with vanilla's `/scoreboard objectives setdisplay` if you want them visible.

## Commands

`/mcareputation` (alias `/mcarep`). Looking at your own standing needs no permission; changing anything
needs permission level 2, and every change is written to the server log with who did it and why.

```
/mcareputation get [community|here]          your standing here
/mcareputation list [player]                 every village you have standing with
/mcareputation history [player] [community] [limit]
/mcareputation add|set <player> <amount> [community] [reason]
/mcareputation incident add|list|resolve|pin …
/mcareputation title grant|revoke|list …
/mcareputation tiers [ladder]                the loaded tier ladder
/mcareputation validate                      check every loaded datapack definition
/mcareputation migrate status|run …          legacy MCA: Quests standing import
/mcareputation export [player]               export standing data as JSON
/mcareputation top <community> [limit]       the top players in a village
/mcareputation community <community> decay   enable, disable, or check decay immunity
/mcareputation debug community|witnesses
```

Communities are written `<dimension>/<villageId>`, e.g. `minecraft:overworld/3`, or the literal `here`.
A bare village id is deliberately not accepted: MCA numbers villages per dimension, so an unqualified
id is ambiguous.

## Configuration

`config/mcareputation-common.toml` (server-authoritative) and `config/mcareputation-client.toml`
(presentation only). Every option, its default, its range, and what switching it off actually does is
in **[CONFIG.md](CONFIG.md)**.

Turning a subsystem off changes behaviour only. Nothing in this mod deletes a saved record.

## For pack authors

Incident types, tier ladders, and titles are all datapack-driven:

```
data/<namespace>/mcareputation/incidents/**/*.json
data/<namespace>/mcareputation/reputation_tiers/**/*.json
data/<namespace>/mcareputation/titles/**/*.json
```

Existing `mcaquests/reputation_tiers` and `mcaquests/titles` paths keep working. You can also gate
loot and advancement criteria on standing using the `mcareputation:standing` loot condition and the
`mcareputation:tier_reached` advancement trigger. Full schemas and worked examples are in
**[DATAPACK.md](DATAPACK.md)**.

## For mod authors

A stable, server-authoritative Java API plus five NeoForge events. See **[API.md](API.md)**.
The API generation is `2`. Add-ons built against the Forge 1.20.1 artifact or the first NeoForge port
must be recompiled: the public event types now extend NeoForge's Event class instead of Forge's,
which breaks their event bus imports and linkage.

## Upgrading an existing world

If you already play with MCA: Quests, your village reputation carries over — once, per player, as a
starting balance rather than as invented history. The policy, its honest limitations, and how to roll
it back are in **[MIGRATION.md](MIGRATION.md)**.

## Documentation

| File | What is in it |
|---|---|
| [CONFIG.md](CONFIG.md) | every config option, default, range, and disabled behaviour |
| [DATAPACK.md](DATAPACK.md) | incident, tier, and title schemas with examples |
| [API.md](API.md) | the public Java API, the NeoForge events, threading and failure contracts |
| [MIGRATION.md](MIGRATION.md) | legacy Quests import, removal, and rollback |
| [CHANGELOG.md](CHANGELOG.md) | release notes |
| [PRODUCTION_TESTS.md](PRODUCTION_TESTS.md) | the verification matrix and its current status |
| [IMPLEMENTATION_NOTES.md](IMPLEMENTATION_NOTES.md) | the Phase 0 audit and the reconciled design decisions |

## Building

Minecraft 1.21.1 needs a JDK 21. `JAVA_HOME` does not have to point at one — the foojay toolchain
resolver provisions it if the system JDK is older.

```bash
./gradlew build
```

`build/libs/mcareputation-<version>.jar` is the distributable artifact directly; NeoForge runs
official Mojang names in dev and in production, so there is no reobfuscation step. The build also
runs `checkJarContents`, which fails if a companion mod's classes are shaded in, if any class still
names `net.minecraftforge` or MCA's old `forge.net.mca` root, if the Forge-era `mods.toml` or
`pack.mcmeta` reappears, or if anything was compiled against the wrong Java release.

To build the whole suite, build this repository **first** — the two companions compile against its
class output:

```bash
cd MCAReputation   && ./gradlew classes
cd ../MCAQuests    && ./gradlew build
cd ../MCAConversations && ./gradlew build
```

## Licence

GPL-3.0-only, because this mod links against MCA Reborn's internals. See [LICENSE.md](LICENSE.md).
