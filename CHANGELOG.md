# Changelog

All notable changes to MCA: Reputation.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.2.0] — unreleased

A full-tree review before first release: score-integrity fixes, command and interface repairs,
config that does what it says, and the transaction finally under test. 0.1.0 was never published;
0.2.0 is the version that ships.

**This release targets Minecraft 1.21.1 on NeoForge.** The platform moved; the feature contract did
not. See *Ported* below for what that changed and what it deliberately did not.

| Mod | Version |
|---|---|
| Minecraft | `1.21.1` (metadata range `[1.21.1,1.21.2)`) |
| NeoForge | `21.1.248+` (metadata range `[21.1.248,21.2)`) |
| Java | `21` |
| MCA Reborn | `7.7.x` (metadata range `[7.7,8)`) — built and verified against `7.7.36-beta.3+1.21.1` |
| MCA: Quests | `1.1.0+` (optional) |
| MCA: Conversations | `2.0.0+` (optional) |
| MCA: Crime | `0.1.0+` (optional) |

The optional companions: the Journal's **[View Deeds]** link needs **MCA: Quests**, and villager
gossip about deeds plus the standing topic need **MCA: Conversations**. Each must be a 1.21.1 NeoForge
build; a Forge 1.20.1 companion cannot load on this platform at all. Without them those features
simply are not offered.

### Ported

**Minecraft 1.21.1 / NeoForge 21.1.248, from Minecraft 1.20.1 / Forge 47.4.10.**

- The build moved from ForgeGradle 6 to ModDevGradle 2.0.141 on Gradle 8.12 and Java 21. There is no
  reobfuscation step any more: NeoForge runs official Mojang names in dev and in production, so
  `build/libs/mcareputation-0.2.0.jar` is the distributable artifact directly.
- MCA Reborn's classes moved from the Forgix-relocated `forge.net.mca.*` root to `net.conczin.mca.*`.
  Every signature this mod consumes was verified present and compatible against the pinned artifact;
  the change is an import swap confined to the two `compat` classes, exactly as designed.
- Networking was rewritten from a `SimpleChannel` with numeric discriminators to five named
  `CustomPacketPayload`s on a `PayloadRegistrar`, protocol version `3`. Decoding is now bounded as
  well as encoding: an oversized collection count is rejected before anything is allocated, where the
  Forge build read whatever the sender claimed.
- `LivingHurtEvent` became `LivingDamageEvent.Post`, and the damage threshold now reads
  `getNewDamage()` — the health actually lost after armour, enchantments and absorption. This is what
  keeps the chip-damage threshold and the assault/death coalescing meaning what they always meant.
- The client dispatch seam no longer uses `DistExecutor`, which NeoForge removed. Common packet code
  now calls an installable sink expressed only in this mod's own payload records, and the client
  installs a real implementation during client setup. A dedicated server still resolves no client
  class — now checked directly against the compiled bytecode rather than trusted to an idiom.

**Deliberately unchanged.** The saved-data format is still version `1` and the file is still
`mcareputation.dat` in the overworld's data storage: a world from the 1.20.1 build loads here with
every score, incident, witness, title, dedupe entry and high-water mark intact, and there is a
checked-in golden fixture written by the old serializer that proves it. Every config key, default and
filename is unchanged. Every datapack path is unchanged, including the legacy `mcaquests` ones. The
public API generation is still `1` — though add-ons compiled against the Forge artifact must be
rebuilt, because their loader and event-bus imports moved with the platform.

**No downgrade.** Opening a world in 1.21.1 is not a supported path back to 1.20.1. Vanilla's world
upgrade is one-way regardless of this mod.

### Fixed

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

### Added

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
| NeoForge | `21.1.248+` (metadata range `[21.1.248,21.2)`) |
| MCA Reborn | `7.7.x` (metadata range `[7.7,8)`) — built against `7.7.36-beta.3+1.21.1` |
| MCA: Quests | `1.1.0+` (optional) |
| MCA: Conversations | `2.0.0+` (optional) |
| MCA: Crime | `0.1.0+` (optional) |

*Historical note: 0.2.0 was originally developed against Minecraft 1.20.1 / Forge 47.4.10 with MCA
`7.6`–`7.7`. It was ported to the platform above before first release; see* Ported *above.*

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
