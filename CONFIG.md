# MCA: Reputation — configuration

> **Largely unchanged by the Minecraft 1.21.1 / NeoForge port.** Both filenames and the same keys,
> defaults and ranges the Forge 1.20.1 build used carry over, so an existing server's or client's TOML
> keeps working untouched, plus the NeoForge-only `integration.enableCrimeIntegration` — there is
> nothing else to edit or migrate. `ConfigParityTest` pins both key sets and both filenames on every
> build, so a key that ever moves fails CI rather than silently resetting somebody's setting.

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
| `enableScoreDecay` | `true` | — | Whether contributions fade per their datapack decay policy. **Off freezes every ledger: the clock advances but nothing ages, and turning it back on applies no catch-up decay for the communities that were actually reconciled while it was off.** The same freeze applies while `enableReputation` is off, and to one community via `/mcareputation community <c> decay off`. The one honest limit: a community nobody reconciled during the pause — mainly an offline player's — is not frozen either, since decay runs off the world clock rather than a per-community pause counter, so it simply ages in one jump the next time anyone asks. |
| `enableTierTitles` | `true` | — | Whether crossing a tier threshold grants that tier's title. |

## `[core_events]`

The narrow set of MCA actions detected automatically. See DATAPACK.md for what each incident is worth.

| Option | Default | Range | What it does |
|---|---|---|---|
| `enableCoreAssaultIncidents` | `true` | — | Record an incident when a player harms an MCA villager. |
| `enableCoreKillingIncidents` | `true` | — | Record an incident when a player kills one. |
| `enableCoreRescueIncidents` | `true` | — | Record an incident when a player kills the mob attacking an MCA villager. |
| `enableCoreCureIncidents` | `true` | — | Record an incident when a player cures a zombie MCA villager. |
| `enableCoreRaidIncidents` | `true` | — | Record an incident when a player is credited with a village surviving a raid. |
| `enableCorePvpIncidents` | `false` | — | Record an incident when a player kills another player where a village can see. Off by default: on most servers duelling is not the village's business. |
| `minimumIncidentDamage` | `1.0` | `0.0 … 1024.0` | Damage below this is ignored, so chip damage and thorns do not create incidents. Measured *after* armour and absorption. |
| `attributeTamedDamage` | `true` | — | Attribute damage from a player's tamed animal to that player. Arrows and thrown potions are always attributed to whoever fired or threw them, independently of this. |
| `selfDefenseWindowTicks` | `100` | `0 … 6000` | If the villager damaged the player within this many ticks first, the retaliation counts as self-defence. `0` disables the concept. |
| `selfDefenseMultiplier` | `0.25` | `0.0 … 1.0` | Penalty multiplier for a self-defence assault, rounded toward zero. Reduced rather than waived: a brawl in the square is still a brawl. |
| `assaultCoalesceTicks` | `200` | `1 … 24000` | Repeated hits on the same villager inside this window are one incident, so a sustained beating is not charged once per damage tick. |
| `rescueThreatWindowTicks` | `100` | `1 … 1200` | How recently a mob must have struck a villager for killing it to still count as a rescue, when the mob is no longer targeting them. |
| `rescueCoalesceTicks` | `6000` | `0 … 72000` | Rescues of the same villager by the same player within one bucket of this length count once, so a kited mob farm earns nothing. `0` disables bucketing. |

## `[witnesses]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `witnessRadius` | `24` | `1 … 128` | Block radius scanned for villagers who saw an incident. Scanned **only** when an incident happens, never on a tick. |
| `maxWitnesses` | `32` | `1 … 32` | Maximum witnesses stored per incident. Candidates are sorted by distance then UUID before the cap, so the same scene always yields the same set. |
| `requireWitnessLineOfSight` | `true` | — | Require unobstructed sight. A villager on the far side of a wall did not see it. |
| `minRumorDelayTicks` | `6000` | `0 … 1000000` | Shortest deterministic delay before a non-witness resident hears a rumour. |
| `maxRumorDelayTicks` | `48000` | `0 … 10000000` | Longest such delay. Normalised upward if set below the minimum. |
| `enableVillagerOpinion` | `true` | — | Derive what one villager personally makes of a player from the deeds that villager knows about. Nothing extra is saved; turning this off only stops the question being answered. |
| `opinionHearsayPercent` | `50` | `0 … 100` | Weight, in percent, of a deed a villager only heard about — and of the community baseline, which reaches them the same way. |
| `opinionInvolvedPercent` | `150` | `100 … 300` | Weight, in percent, of a deed the villager was a subject of. Above 100 because what was done to you counts for more than what you watched. |

Each (incident, villager, community) triple hashes to its own fixed delay inside the rumour window, so a
village learns gradually rather than all at once — with no stored per-villager knowledge and no tick
cost. Once a villager knows something they cannot un-know it, not even by winding the clock back.

## `[limits]`

| Option | Default | Range | What it does |
|---|---|---|---|
| `maxIncidentsPerCommunity` | `64` | `1 … 64` | The state budget for one player in one community, not just a retained-count cap. A record that still contributes, is pinned, or is an active (non-superseded) negative deed younger than `receiptRetentionTicks` is never evicted to make room; when the cap is reached and nothing is left that may be evicted, the next deed is refused rather than the ledger silently overflowing. |
| `maxIncidentsPerPlayer` | `512` | `1 … 512` | Incidents retained across all of one player's communities, under the same eviction rule as above. |
| `receiptRetentionTicks` | `336000` | `0 … 100000000` | How long a delivery receipt stays answerable, in ticks. A companion that replays an operation older than this gets no memory of it and has to recover explicitly. Receipts are also capped at 512 per player and evicted oldest-first regardless of this setting. Default is 14 in-game days (336000 ticks). |
| `reconcileOnlineIntervalTicks` | `1200` | `20 … 72000` | How often decay is reconciled for **online players only**. This is not a world scan; an idle server with nobody connected does nothing. |
| `strictJsonValidation` | `false` | — | Treat any datapack validation error as a failed reload. Either way the previously loaded definitions stay live — strict mode simply refuses to swap them. |

When a cap is reached, history is discarded in the order it stops mattering: expired entries, then
resolved ones, then unremarkable ones, then the oldest. Only records that no longer contribute are
eligible — a record still carrying weight is never one of them — so **pruning never changes your
score**, only the explanation for it; a pruned zero-weight record still has its bookkeeping folded
into a non-decaying baseline first. Pinned incidents, anything still contributing, and a recent open
negative deed (one a receipt may still be checked against) are never dropped this way. If every record in a full
ledger falls into one of those three, the *next* deed is refused instead — `/mcareputation debug
receipts <player> [community]` shows the count against the cap, how many records are currently
evictable, and whether the next deed would be refused.

## `[integration]`

Each of these is a no-op when the mod in question is absent.

| Option | Default | What it does |
|---|---|---|
| `enableQuestsIntegration` | `true` | Accept writes from MCA: Quests. With this off, quest- and project-sourced deeds and resolutions are refused as `DISABLED`; Quests' reads (scores, tiers, titles) still answer, so its UI stays truthful. |
| `enableConversationsIntegration` | `true` | Serve MCA: Conversations. With this off, the check bias and villager opinion bias read `0` and dialogue-sourced deeds are refused as `DISABLED`. It **no longer** gates the generic standing predicate: the `mcareputation:standing` loot condition and Reputation's own condition read the same effective standing whether this is on or off, because a dialogue mod's switch has no business disabling a loot table. |
| `enableCrimeIntegration` | `true` | NeoForge-only. Accept incidents and resolutions from MCA: Crime, and let it claim ownership of villager assault/death detection so one attack is not counted by both mods. Turning this off makes Crime's authority claim fail, and native detection resumes for the next event — there is never a window where neither mod is watching. |
| `coreAuthorityUndeclaredKinds` | `ASSAULT_KILL_ONLY` | How much to trust a companion that registers as a core incident authority without declaring which kinds it detects. `TRUST_LEGACY` lets it claim any kind, as an undeclared authority could before this option existed. `ASSAULT_KILL_ONLY` — the default — only honours it for villager assault and villager killing, the two kinds that existed when an authority could be undeclared; rescue, cure, raid, and PvP stay natively detected. `IGNORE` honours an undeclared authority for nothing. A companion that declares its kinds is unaffected either way. |
| `mirrorQuestsFallbackState` | `true` | After each commit, mirror score/tier/title into Quests' own fallback store, so removing this mod later leaves Quests with sensible standing instead of resetting everyone. |
| `migrateLegacyQuestsData` | `true` | Import a pre-Reputation world's shared Quests village scores into per-player baselines, exactly once per player. See MIGRATION.md. |

## `[visibility]`

Display of standing outside the standing screen. Both are off by default: they are always-on surfaces,
and a server that wants one will say so. Both react to a live config reload: turning a display off and
reloading takes down only the adornments this mod put up.

| Option | Default | Range | What it does |
|---|---|---|---|
| `enableScoreboardObjective` | `false` | — | Keep a dummy scoreboard objective holding each online player's standing with the village the standing screen would open on. An objective is only ever **adopted** if one already exists under this name with `dummy` criteria *and* this mod's own display-name marker; a foreign objective of the same name is never written to or removed, and the refusal is logged once. Turning the feature off removes only an objective this mod owns. |
| `scoreboardObjectiveName` | `mcareputation` | non-blank | Name of that objective. |
| `enableTabListTier` | `false` | — | Append the tier name to each player's tab-list entry. Appended to whatever name other mods produced, so it stacks rather than overwrites. |
| `displayRefreshIntervalTicks` | `100` | `20 … 1200` | How often the displays are swept for online players. Standing changes and dimension changes update immediately; this sweep exists because walking into another village raises no event. |

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
| `showVillagerOpinion` | `true` | When the screen was opened from a villager, show what that villager personally makes of you beneath the village's own view. |

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
