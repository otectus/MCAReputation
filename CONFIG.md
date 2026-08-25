# MCA: Reputation — configuration

> **Unchanged by the Minecraft 1.21.1 / NeoForge port.** Both filenames, every key, every default and
> every range are exactly what the Forge 1.20.1 build used, so an existing server's or client's TOML
> keeps working untouched — there is nothing to edit or migrate. `ConfigParityTest` pins both key sets
> and both filenames on every build, so a key that ever moves fails CI rather than silently resetting
> somebody's setting.

Two files, written on first run:

- `config/mcareputation-common.toml` — **server-authoritative**. On a dedicated server the server's copy
  is the one that matters; a client's copy of these values is never consulted for anything.
- `config/mcareputation-client.toml` — **presentation only**. Hiding a number changes what you see and
  nothing about the server's arithmetic.

Two rules hold throughout:

1. **Disabling a subsystem never deletes anything.** Turn decay off and contributions freeze where they
   are; turn it back on and they resume. Turn the whole mod off and every score, title, and deed stays
   in the save untouched.
2. **Config may tighten a bound, never loosen one.** Every option that feeds a stored collection or a
   score is clamped against a hard limit in the source, so hand-editing the TOML cannot produce an
   unbounded witness list or a score that overflows.

---

## `[general]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `enableReputation` | `true` | — | Master switch. When off, no incident is recorded and no score changes. Existing standing, titles, and history remain in the save. |
| `debugLogging` | `false` | — | Verbose DEBUG for MCA access failures, witness selection, dedupe refusals, and score arithmetic. Never one line per tick. |

## `[scoring]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `minimumScore` | `-1000` | `-1000000 … 0` | Lower clamp on standing with one community. |
| `maximumScore` | `1000` | `0 … 1000000` | Upper clamp. Must exceed the minimum; the accessors normalise an inverted pair rather than producing an impossible window. |
| `defaultVillageSearchRadius` | `128` | `16 … 512` | Blocks searched for a village when an action has no obvious home community. Never used to invent one: if nothing is found, nothing is recorded. |
| `enableScoreDecay` | `true` | — | Whether contributions fade per their datapack decay policy. **Off freezes them where they are; it does not restore decay that already happened.** |
| `enableTierTitles` | `true` | — | Whether crossing a tier threshold grants that tier's title. |

## `[core_events]`

The narrow set of MCA actions detected automatically. See DATAPACK.md for what each incident is worth.

| Option | Default | Range | What it does |
|---|---|---|---|
| `enableCoreAssaultIncidents` | `true` | — | Record an incident when a player harms an MCA villager. |
| `enableCoreKillingIncidents` | `true` | — | Record an incident when a player kills one. |
| `minimumIncidentDamage` | `1.0` | `0.0 … 1024.0` | Damage below this is ignored, so chip damage and thorns do not create incidents. Measured *after* armour and absorption. |
| `attributeTamedDamage` | `true` | — | Attribute damage from a player's tamed animal to that player. Arrows and thrown potions are always attributed to whoever fired or threw them, independently of this. |
| `selfDefenseWindowTicks` | `100` | `0 … 6000` | If the villager damaged the player within this many ticks first, the retaliation counts as self-defence. `0` disables the concept. |
| `selfDefenseMultiplier` | `0.25` | `0.0 … 1.0` | Penalty multiplier for a self-defence assault, rounded toward zero. Reduced rather than waived: a brawl in the square is still a brawl. |
| `assaultCoalesceTicks` | `200` | `1 … 24000` | Repeated hits on the same villager inside this window are one incident, so a sustained beating is not charged once per damage tick. |

## `[witnesses]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `witnessRadius` | `24` | `1 … 128` | Block radius scanned for villagers who saw an incident. Scanned **only** when an incident happens, never on a tick. |
| `maxWitnesses` | `32` | `1 … 32` | Maximum witnesses stored per incident. Candidates are sorted by distance then UUID before the cap, so the same scene always yields the same set. |
| `requireWitnessLineOfSight` | `true` | — | Require unobstructed sight. A villager on the far side of a wall did not see it. |
| `minRumorDelayTicks` | `6000` | `0 … 1000000` | Shortest deterministic delay before a non-witness resident hears a rumour. |
| `maxRumorDelayTicks` | `48000` | `0 … 10000000` | Longest such delay. Normalised upward if set below the minimum. |

Each (incident, villager, community) triple hashes to its own fixed delay inside this window, so a
village learns gradually rather than all at once — with no stored per-villager knowledge and no tick
cost. Once a villager knows something they cannot un-know it, not even by winding the clock back.

## `[limits]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `maxIncidentsPerCommunity` | `64` | `1 … 64` | Incidents retained for one player in one community. |
| `maxIncidentsPerPlayer` | `512` | `1 … 512` | Incidents retained across all of one player's communities. |
| `reconcileOnlineIntervalTicks` | `1200` | `20 … 72000` | How often decay is reconciled for **online players only**. This is not a world scan; an idle server with nobody connected does nothing. |
| `strictJsonValidation` | `false` | — | Treat any datapack validation error as a failed reload. Either way the previously loaded definitions stay live — strict mode simply refuses to swap them. |

When a cap is reached, history is discarded in the order it stops mattering: expired entries, then
resolved ones, then unremarkable ones, then the oldest. Anything still carrying weight has that weight
folded into a non-decaying baseline first, so **pruning never changes your score** — only the
explanation for it. Pinned incidents are never dropped.

## `[integration]`

Each of these is a no-op when the mod in question is absent.

| Option | Default | What it does |
|---|---|---|
| `enableQuestsIntegration` | `true` | Accept writes from MCA: Quests. With this off, quest- and project-sourced deeds and resolutions are refused as `DISABLED`; Quests' reads (scores, tiers, titles) still answer, so its UI stays truthful. |
| `enableConversationsIntegration` | `true` | Serve MCA: Conversations. With this off, the check bias reads 0, standing conditions never match (authored fallbacks fire), no gossip candidates are offered, and dialogue-sourced deeds are refused as `DISABLED`. |
| `mirrorQuestsFallbackState` | `true` | After each commit, mirror score/tier/title into Quests' own fallback store, so removing this mod later leaves Quests with sensible standing instead of resetting everyone. |
| `migrateLegacyQuestsData` | `true` | Import a pre-Reputation world's shared Quests village scores into per-player baselines, exactly once per player. See MIGRATION.md. |

---

## Client — `[display]`

None of these change what the server records. They change what you are shown.

| Option | Default | What it does |
|---|---|---|
| `showReputationButton` | `true` | Add the Standing button to MCA's villager interaction screen. |
| `showChangeActionBar` | `true` | Show routine standing changes as a single merged action-bar line. |
| `showTierToasts` | `true` | Toast the first time you reach a new best tier with a village. Re-entering a tier you have held before does not toast. |
| `showNegativeTierMessages` | `true` | Show a subdued message when your standing falls to a lower tier. |
| `mergeChangeNotifications` | `true` | When several villages' standing changes arrive in the same tick, combine them into one action-bar line (deltas summed, newest village's name as the label). With this off, only the newest change shows. Tier messages go to chat and are never merged away. |
| `showExactScore` | `true` | Show the number. With this off, standing is described by tier name and qualitative progress — the server's arithmetic is identical either way. |
| `showIncidentDeltas` | `true` | Show each deed's numeric contribution in the Standing screen. |

## Playing with it turned down

A few combinations worth knowing:

- **No automatic consequences, quests only.** `enableCoreAssaultIncidents=false`,
  `enableCoreKillingIncidents=false`. Standing then moves only through authored quest, project, and
  situation outcomes.
- **Nothing fades.** `enableScoreDecay=false`. Every deed counts for as long as it is retained.
- **A harsher world.** Lower `selfDefenseMultiplier` toward `0.0`, raise `witnessRadius`, and lower
  `minRumorDelayTicks` so news travels fast.
- **A quieter interface.** `showChangeActionBar=false`, `showTierToasts=false`. Standing still moves;
  you simply have to go and look.
