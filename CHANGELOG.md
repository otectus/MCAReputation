# Changelog

All notable changes to MCA: Reputation.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.4.0] — unreleased

Four new automatic deeds (villagers rescued from threats, villagers cured from zombification, raid
victories, and player kills — the last off by default), per-villager opinion derived from what each
villager knew, standing display options (scoreboard objective and tab-list tier visibility), and
three new admin commands for auditing and control. The network protocol bumps to version 4; the save
format is unchanged and forward-compatible.

**Carries the upstream Forge 0.4.0 feature set to Minecraft 1.21.1 / NeoForge.** The platform moved;
the feature contract did not. See *Platform* below for what that changed and what it deliberately did not.

| Mod | Version |
|---|---|
| Minecraft | `1.21.1` (metadata range `[1.21.1,1.21.2)`) |
| NeoForge | `21.1.249+` (metadata range `[21.1.249,21.2)`) |
| Java | `21` |
| MCA Reborn | `7.7.x` (metadata range `[7.7,8)`) — built and verified against `7.7.36-beta.3+1.21.1` |
| MCA: Quests | `1.1.0+` (optional, requires API version 2) |
| MCA: Conversations | `2.0.0+` (optional, requires API version 2) |
| MCA: Crime | `0.1.0+` (optional) |

The optional companions and the API version move: each must be a 1.21.1 NeoForge build targeting API version 2; a Forge 1.20.1 companion cannot load on this platform at all.

### Added

- **Four new automatic incidents:** `villager_rescued` (+6, witnessed, decays), `villager_cured`
  (+15, witnessed, no decay), `raid_repelled` (+20, village-wide, decays), and `player_killed_in_village`
  (−12, witnessed, decays; off by default because it is not always the village's business).
  Hooked in `ReputationDeedEvents` with anti-farm dedupe keys in `DeedKeys`; config keys
  `enableCoreRescueIncidents`, `enableCoreCureIncidents`, `enableCoreRaidIncidents`,
  `enableCorePvpIncidents`, `rescueThreatWindowTicks` (100 ticks), `rescueCoalesceTicks` (6000
  ticks). New `CoreIncidentKind` constants for compatibility claims.

- **Per-villager opinion:** A villager's personal standing with a player, derived from incidents that
  villager knows about (through witnessing or hearing) weighted by how they found out. Stored never,
  computed from the village ledger on demand; configured by `enableVillagerOpinion` (true),
  `opinionHearsayPercent` (50), `opinionInvolvedPercent` (150). The standing screen shows one extra
  line when opened from a villager, toggled by client config `showVillagerOpinion`. API:
  `McaReputationApi.getVillagerOpinion(...)` and `getOpinionBias(...)`.

- **Standing visibility:** A scoreboard objective and a tab-list tier suffix, both off by default.
  Configured by `enableScoreboardObjective`, `scoreboardObjectiveName` (default "mcareputation"),
  and `enableTabListTier`; refreshed every `displayRefreshIntervalTicks` (100 ticks). Shows the
  standing in the player's current village, or their best-known one using the same selection rule as
  the standing screen.

- **Admin commands:** `/mcareputation export [player]` (permission 3; writes a timestamped JSON
  snapshot), `/mcareputation top <community> [limit]` (permission 2, default 10, max 50), and
  `/mcareputation community <community> decay <on|off|status>` (permission 3 for on/off, 2 for
  status; toggled communities never decay).

- **Datapack condition and advancement trigger:** `mcareputation:standing` loot condition for gating
  loot and advancement criteria; `mcareputation:tier_reached` advancement trigger for noticing
  standing milestones. Both documented in `DATAPACK.md`.

### Changed

- **Network protocol version bumped to 4.** 0.3.0 clients cannot join 0.4.0 servers and vice versa.

### Notes

- Save format unchanged (FORMAT_VERSION still 1); 0.3.0 saves load without migration, and a 0.4.0
  world's optional `decayImmune` list is dropped silently by 0.3.0 on its next save.

## [0.3.0] — unreleased

Three things: the standing screen now looks like part of the game, it now shows the standing you
actually have, and MCA: Crime can finally be installed alongside this mod without the two of them
charging the same punch twice.

The screen was drawn entirely with flat `fill()` rectangles in a violet scheme of its own — a poor fit
for a screen the player reaches one click from MCA's own interaction screen, and not what the design
asks for when it says to use MCA's visual language rather than a visually unrelated menu. Nothing about
what it *says* changed with the redraw: the same server-authoritative snapshot, the same fields, the
same empty states.

What it said was wrong for a different reason, and a player found it before we did: *"no matter what I
do I have '25 more to acquaintance' and my rank is 'stranger'."* The number was not stuck — the screen
was reading a different village from the one their deeds were written to, and answering honestly about
a place they had never been. The standing screen now shows the standing you actually have.

The compatibility half is the core-incident authority handshake. It is a small API and a large
consequence: it is the difference between Reputation and MCA: Crime being usable together and not.

**Carries the upstream Forge 0.3.0 feature set to Minecraft 1.21.1 / NeoForge.** The platform moved;
the feature contract did not. See *Platform* below for what that changed and what it deliberately did not.

| Mod | Version |
|---|---|
| Minecraft | `1.21.1` (metadata range `[1.21.1,1.21.2)`) |
| NeoForge | `21.1.249+` (metadata range `[21.1.249,21.2)`) |
| Java | `21` |
| MCA Reborn | `7.7.x` (metadata range `[7.7,8)`) — built and verified against `7.7.36-beta.3+1.21.1` |
| MCA: Quests | `1.1.0+` (optional, requires API version 2) |
| MCA: Conversations | `2.0.0+` (optional, requires API version 2) |
| MCA: Crime | `0.1.0+` (optional) |

The optional companions: the Journal's **[View Deeds]** link needs **MCA: Quests**, and villager
gossip about deeds plus the standing topic need **MCA: Conversations**. Each must be a 1.21.1 NeoForge
build targeting API version 2; a Forge 1.20.1 companion cannot load on this platform at all. Without
them those features simply are not offered. Bridges built against API version 1 must be re-targeted and
recompiled.

### Changed

- **The standing screen is drawn from textures, in vanilla's container idiom.** Nine GUI sprites under
  `assets/mcareputation/textures/gui/sprites/reputation/` supply the panel frame, the sunken well the
  deed ledger sits in, the progress track and its fill, the scroller channel and thumb, the section
  rule, and the selector arrow faces. Seven sprites are nine-sliced; the two selector arrows are
  fixed-size 8×8. The frame is pixel-identical to vanilla's own container GUI. The generator that
  produces the sprites, `tools/GenerateGuiTexture.java`, is committed alongside them, so the art can
  be re-derived and reviewed rather than edited blind.
- **The header and the deed ledger are wrapped once when the screen opens,** rather than re-measured on
  every frame. It is cheaper, but the point is that the drawn height and the height the scrollbar is
  scaled against are now guaranteed not to drift apart.
- **The community selector uses arrow sprites instead of literal `<` and `>` characters.** The new
  `SpriteButton` overrides only the label step of vanilla's button rendering, so the frame, hover,
  focus and disabled states, the click sound and resource-pack compatibility remain vanilla's own.
- **Two text colours, both vanilla's.** Hundreds of inline hex literals became `0x404040` and
  `0x7F7F7F`, the pair vanilla labels its container screens with.
- **The scroller can be dragged,** and clicking the bare channel takes it to the pointer, as
  vanilla's own lists do. The mapping from a pointer position back to a scroll offset lives in
  `ScrollMath` next to the one that paints the thumb, so the two cannot part company.

### Added

- **`/mcareputation debug standing [<player>] [<community>]`** — everything the standing pipeline
  believes about one player, in one screenful: the raw stored score and baseline, the incident count,
  the active tier and its threshold, the next tier and the exact remaining amount, which store is
  being read, which community the screen would open on and whether the player has a record there,
  the registered mirrors, the integration toggles, and the MCA binding status.
- **`/mcareputation debug authorities`** — a list of every registered core-incident authority and
  which kinds it currently claims.
- **Full compatibility with MCA: Crime**, through a core-incident authority handshake. The two mods
  both detect villager assault and death; without an agreement, installing both would file two
  penalties for one punch. Reputation now exposes `McaReputationApi.registerCoreIncidentAuthority`
  and `hasExternalAuthority`. A companion claims one or more `CoreIncidentKind`s and Reputation stands
  down from detecting them, while continuing to own everything downstream — the claimant files the
  same incident type through `record`, so the ledger, scores, decay, gossip and witnesses are
  byte-for-byte what they would have been.
- **API version 2** identifies the NeoForge generation. The public event types now extend
  `net.neoforged.bus.api.Event` instead of the Forge equivalents. Bridges built against API version 1
  must be re-targeted and recompiled: their event linkage no longer holds and their loader imports
  moved with the platform.

### Fixed

- **The standing screen showed "Stranger — 25 more to Acquaintance" for players who had earned
  standing.** Asked for a snapshot with no village named, the server picked whichever village was
  nearest the player's feet; a village with no record is answered with a synthesised floor-tier detail,
  so a player standing within 128 blocks of a village they had never dealt with was shown a score of
  zero however much standing they had elsewhere. The screen now shows the standing you actually have:
  where you are now wins only when you have a history there; otherwise the reply details the standing
  you actually have; and "stranger" is reserved for a village you explicitly asked about, or for a
  player who genuinely has no standing anywhere.
- **With one village on record, there was no way to reach it from an unknown one.** The selector arrows
  were drawn only when the community *list* held more than one entry, but the detailed community need
  not be in that list at all. The arrows now appear, and cycling forward from an off-list selection
  enters the list at the front.
- **The scroller no longer creeps away from the pointer as it is dragged.** `ScrollMath.thumbY` truncated,
  so wherever the division landed just under an integer the thumb repainted one pixel above where it had
  been grabbed. It rounds now.
- **The scroller thumb can no longer be taller than the track it runs in.** Its sixteen-pixel floor
  could exceed the available track at punishing GUI scales and produce a negative offset.

### Notes

- 343 automated tests, including round-trip assertions on the scrollbar's paint and drag mappings, the
  snapshot-selection logic, and the core-incident authority claim truth table.
- The standing screen frame was verified against vanilla's own container by regenerating it at that
  screen's dimensions and diffing pixel-for-pixel.
- The frame keeps the vanilla `toast/advancement` sprite rather than moving to `toast/system`, retaining
  pixel parity with the Forge 0.3.0 visual.
- NBT format 1 is unchanged; 1.20.1 worlds carry over without a conversion step.

### Platform

**Minecraft 1.21.1 / NeoForge 21.1.249, Gradle 9.2.1, ModDevGradle 2.0.146, foojay 1.0.0, from
Minecraft 1.20.1 / Forge 47.4.10.**

- The build moved from ForgeGradle 6 to ModDevGradle 2.0.146 on Gradle 9.2.1 and Java 21. There is no
  reobfuscation step any more: NeoForge runs official Mojang names in dev and in production, so
  `build/libs/mcareputation-0.3.0.jar` is the distributable artifact directly.
- MCA Reborn's classes are resolved by name at runtime through a reflection-only binding in `McaReflect`,
  bound to the single unrelocated `net.conczin.mca` root. A missing MCA member logs one startup error
  and degrades the feature instead of failing at classload. `McaBinaryAbiTest` audits every reflected
  member against the pinned MCA jar SHA-256. Every other class is forbidden from importing MCA by
  `checkJarContents` and `OptionalClassloadTest`, which now also forbid any Forge or relocated-MCA
  bytecode.
- Networking was rewritten from a `SimpleChannel` with numeric discriminators to five named
  `CustomPacketPayload`s on a `PayloadRegistrar`, protocol version `3`. Decoding is now bounded as
  well as encoding: an oversized collection count is rejected before anything is allocated.
- `LivingHurtEvent` became `LivingDamageEvent.Post`, and the damage threshold now reads
  `getNewDamage()` — the health actually lost after armour, enchantments and absorption. This is what
  keeps the chip-damage threshold and the assault/death coalescing meaning what they always meant.
- The client dispatch seam no longer uses `DistExecutor`, which NeoForge removed. Common packet code
  now calls an installable sink expressed only in this mod's own payload records, and the client
  installs a real implementation during client setup. A dedicated server still resolves no client class.

**Deliberately unchanged.** The saved-data format is still version `1` and the file is still
`mcareputation.dat` in the overworld's data storage: a 1.20.1 world loads here with every score,
incident, witness, title, dedupe entry and high-water mark intact. Every config key, default and
filename is unchanged. Every datapack path is unchanged, including the legacy `mcaquests` ones.

**No downgrade.** Opening a world in 1.21.1 is not a supported path back to 1.20.1. Vanilla's world
upgrade is one-way regardless of this mod.

### Folded in: [0.2.0] — the full-tree review before first release

A full-tree review: score-integrity fixes, command and interface repairs, config that does what it
says, and the transaction finally under test.

#### Fixed

**Score integrity**

- Pruning near the score ceiling can no longer silently change a score: the baseline now holds fold
  overflow beyond the visible clamp, and survives a save/load cycle without being re-clamped.
- `/mcareputation set` lands exactly on its target whatever the ledger sums to, using the ledger's
  true unclamped contribution instead of "score minus baseline".
- An unwitnessed villager killing no longer *refunds* a witnessed assault's penalty — the
  assault fold rolls back whenever the killing carries no public weight (unwitnessed-retained,
  duplicate, or refused).
- A throwing add-on listener can no longer make a committed transaction report `ERROR`; every event
  post inside the commit — including tier-title grants — is contained.
- Decay respects `enableScoreDecay` on the resolve and administrative paths, and every read path
  (community list, deed list, dedupe refusals) reconciles decay before reporting a number.
- The tier high-water mark seeds from the tier a player already stood in, so dipping below your
  starting tier and climbing back is not a fake first-time milestone.
- A dry-run legacy import writes nothing — previously it permanently marked the player migrated and
  made the real import impossible. Real imports now post `ReputationChangedEvent` and tier
  transitions (with imported high-water suppressing re-celebration) and write the documented
  `legacy_balance` ledger line.
- The `EXPIRED` incident status is actually assigned by reconciliation, and no longer blocks a
  later genuine apology.
- Cap enforcement can no longer stall on one all-pinned community, and its pruning marks the save
  dirty. The load path enforces the same ledger/dedupe/high-water bounds as the write path.

**Commands**

- Community arguments are a real Brigadier argument type. An unquoted `minecraft:overworld/3` was
  previously unparseable, and the string-typed argument swallowed player names — making
  `/mcareputation get <player>` unreachable. `here` and player forms now disambiguate at parse time.
- The `/mcarep` alias redirects to the registered tree instead of an orphan node, so clients get
  tab-completion; bare `/mcareputation` and `/mcarep` print usage.
- `history` and `incident list` accept community and limit on the self forms (§24: the player
  argument is optional).
- `title grant … global` grants globally instead of silently doing nothing and reporting
  "unchanged"; the bare form resolves the executor's village.

**Interface**

- The standing screen can no longer wedge on "Asking around…": requests are paced client-side to
  match the server's rate limit (the newest wish parks and flushes), and an unanswered request times
  out into the retryable empty state. Fast community cycling can no longer desynchronise the header
  from the footer.
- Titles and tier descriptions cross the wire as resolved text, so dedicated-server clients render
  "Honored" instead of a raw id — network protocol bumped to 2.
- Feedback is buffered per community: one village's tier label can no longer be computed from
  another village's score. A downward tier crossing shows the numeric change alongside the subdued
  message, and a non-milestone climb gets a quiet acknowledgement (`feedback.tier_up`).
- A truncated deed list says "showing N of M"; scrollbar and mouse wheel agree at the boundaries;
  all per-world static state is cleared on server stop.

**Content and config**

- `villager_killed` no longer ships pinned — pinned shipped content made the storage caps
  permanently unenforceable, and pruning folds weight so the score survives either way.
- The two `mcaquests:*` tier titles now ship with definitions here, so a standalone install renders
  their names.
- `enableQuestsIntegration`, `enableConversationsIntegration`, and `mergeChangeNotifications` had
  zero call sites; all three are wired and their documentation matches their behaviour.
- Validation distinguishes errors from advice: strict reloads refuse only genuine errors, an
  over-limit tier bias is an error (the runtime clamps it), and malformed tags or gossip variables
  are caught with the exact file and field.

#### Added

- **Core-incident authority** — `McaReputationApi.registerCoreIncidentAuthority` and
  `hasExternalAuthority`, with the public `CoreIncidentKind`, `CoreIncidentAuthority`, and
  `CoreIncidentAuthorityRegistration` types. Reputation and MCA: Crime both watch for villager
  assault and death; without an agreement one swing produces two deeds. A companion now claims the
  kinds it produces, and Reputation's native detector stands down for exactly those — checked per
  event, so a bridge that disables itself hands detection straight back. Ownership is only accepted
  when a single healthy authority claims a kind: zero claims or an ambiguous two-way claim leaves
  Reputation producing, because a visible duplicate can be fixed and a deed that silently never
  existed cannot. A throwing `owns()` reads as unclaimed. `/mcareputation debug integrations` reports
  who currently owns what.
- **Recoverable duplicates** — a `DUPLICATE` result now carries the id of the incident the dedupe key
  already produced. A companion that crashed between our commit and its own link write can replay the
  key and repair the link, rather than losing it or recording a second incident just to obtain an id.
  Nothing else changes: the refusal still writes nothing and reports a zero delta.
- `enableCrimeIntegration` in `[integration]`, gating `mcacrime:*`-sourced writes the same way the
  Quests and Conversations toggles already gate theirs. Turning it off makes Crime's authority claim
  fail and native detection resume.
- `McaReputationApi.registerImportProvider` / `unregisterImportProvider` — the supported §32.2
  registration path, so companions stay off internal packages — and
  `McaReputationApi.openReputationScreen`, which backs MCA: Quests' Journal **[View Deeds]** link
  (§29.7) with a fresh snapshot ahead of the push.
- The transaction test seam and suites: `ReputationServiceTest` (ordering, dedupe, containment,
  clamp exactness, imports), `CommandTreeTest`, `RequestThrottleTest`, `FeedbackMergeTest`,
  `FeedbackPresentationTest`, `ScrollMathTest`, and a two-way `LangParityTest`; plus
  `CoreIncidentAuthorityTest` covering the full ownership truth table — 255 tests in all.

## [0.1.0] — unpublished

The initial development build: a public memory and civic consequence layer for MCA Reborn villages.
Superseded by 0.2.0 before any release was tagged.

### Compatible versions

| Mod | Version |
|---|---|
| Minecraft | `1.21.1` |
| NeoForge | `21.1.249+` (metadata range `[21.1.249,21.2)`) |
| MCA Reborn | `7.7.x` (metadata range `[7.7,8)`) — built against `7.7.36-beta.3+1.21.1` |
| MCA: Quests | `1.1.0+` (optional) |
| MCA: Conversations | `2.0.0+` (optional) |
| MCA: Crime | `0.1.0+` (optional) |

*Historical note: 0.1.0 and 0.2.0 were never published; their change set is folded into 0.3.0.
This section shows the feature foundation that preceded the first public release.*

### Added

**Standing**

- Per-player, per-village public standing, keyed by a dimension-aware `CommunityKey`. Two players in
  one village have separate reputations; the same village id in two dimensions never collides.
- A nine-rung default tier ladder from Infamous to Revered. The positive half is exactly the thresholds
  MCA: Quests already shipped (0 / 25 / 75 / 150 / 300), so no existing world changes meaning; the
  negative half is additive below zero.
- Earned titles, per village or global. Tier titles stay earned if standing later falls — a title
  records something you did, not where you currently stand.
- A celebratory toast the first time you reach a new best tier with a village, tracked by a high-water
  mark so oscillating around a threshold does not replay it. Falling to a lower tier gets a subdued
  message instead.

**Deeds**

- A structured, bounded incident ledger that explains the score rather than duplicating it. Score is
  always recomputable from a baseline plus the retained contributions, and a corrupted cached value is
  repaired on load rather than trusted.
- Fifteen shipped incident types covering assault, killing, quests, projects, situations, promises,
  apology, and restitution.
- Decay: a deed can fade toward zero over days. Computed from a monotonic age counter, so `/time set`
  into the past never returns contribution a player already lost.
- Resolution: a deed can be apologised for, atoned for, forgiven, or disproven. The penalty softens and
  the record stays. Only a strictly stronger resolution takes effect, so a repeatable restitution quest
  cannot pay twice.
- Pruning that discards history in the order it stops mattering, folding any remaining weight into the
  baseline first — so trimming a full ledger never changes the player's score.

**Witnesses and rumour**

- Witness resolution on the event itself, over a bounded box of loaded entities, sorted
  deterministically before the cap so one scene always yields the same set.
- A crime nobody saw has no public consequence. The shipped killing definition keeps it as hidden,
  zero-contribution history; the world remembers even when the village does not.
- Rumour spread by hashing each (incident, villager, community) triple into its own fixed delay. No
  stored pairwise knowledge, no save growth, no tick cost — and once a villager knows something they
  cannot un-know it.

**Automatic detection**

- Villager assault and killing, attributed through direct hits, projectiles, thrown potions, and
  optionally tamed animals. Repeated hits coalesce into one deed; a killing absorbs the assault that
  preceded it so the pair totals the killing's figure rather than stacking.
- Self-defence reduces the penalty rather than waiving it, when the villager demonstrably struck first.
- Nothing else is inferred. Trades, gifts, entering a village, curing, generic mob kills, sleeping,
  marriage, and block placement all earn nothing, deliberately.

**Interface**

- A standalone Standing screen: community, tier, progress, titles, and a scrollable list of what the
  village remembers. Reached from a Standing button on MCA's interaction screen, from an unbound
  keybind, or from MCA: Quests' Journal.
- Merged action-bar feedback, so a quest granting three rewards produces one line rather than three
  that overwrite each other.
- `en_us` localization throughout. Polarity is never conveyed by colour alone.

**Server surfaces**

- The `/mcareputation` command tree (alias `/mcarep`): query, history, adjust, incidents, titles, tiers,
  validation, migration, and debug. Self-queries need no permission; every mutation is audit-logged.
- A dedicated network channel at protocol version 1. Clients cannot send a score, delta, title,
  incident, witness, or village id that the server trusts; snapshot requests are rate limited and every
  payload is bounded before encoding.
- Datapack-driven incidents, tier ladders, and titles, with atomic reload and cross-definition
  validation that reports every problem at once with the exact file and field.
- A stable public Java API and five loader events, plus a `ReputationMirror` sink and a
  `LegacyImportProvider` seam for add-ons.

### Compatibility

- **Works standalone.** MCA Reborn is the only requirement.
- **No Architectury dependency.** MCA 7.6 declares it itself and 7.7 dropped it; this mod contains no
  Architectury reference, so a 7.7 user who removed it is not blocked.
- **No mixins.** The one place a mixin was a candidate — the Standing button on MCA's interaction
  screen — uses the loader's screen-init event instead, so there is no MCA-internal signature to drift
  against.
- **One binary for MCA 7.6 and 7.7.** Every consumed signature was verified byte-identical across
  `7.6.20` and `7.7.0-beta.2`; the one known drift is consumed through `Object#toString()`.
- Legacy `mcaquests` tier and title datapack paths are still loaded, and `mcaquests:default` is aliased
  to the canonical ladder.
- Pre-Reputation MCA: Quests standing is imported once per eligible player as a non-decaying baseline.
  See [MIGRATION.md](MIGRATION.md) for the policy and its honest limitations.

### Notes

- 163 automated tests cover the pure domain: community keys, score arithmetic, decay, awareness,
  resolution, pruning, dedupe, persistence and its corruption containment, packet bounds, the shipped
  content, and the optional-classloading seam.
- Not yet production-verified. [PRODUCTION_TESTS.md](PRODUCTION_TESTS.md) records the matrix that must
  pass before a release is tagged; compilation and unit tests are explicitly not sufficient.

[0.2.0]: https://github.com/otectus/MCAReputation/releases/tag/v0.2.0
