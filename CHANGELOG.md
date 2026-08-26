# Changelog

All notable changes to MCA: Reputation.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.2.1] — unreleased

A crash fix. MCA Reborn `7.7.1-alpha.2` renamed its base package from `net.mca` to `net.conczin.mca`,
which moved the Forgix-relocated Forge classes from `forge.net.mca.*` to `forge.net.conczin.mca.*`.
MCA kept the mod id `mca`, so Forge accepted the pairing and the server started — then died on the
first `LivingHurtEvent`.

### Fixed

- **MCA: Reputation no longer crashes servers running MCA 7.7.1 or newer.** 0.2.0 linked against
  `forge.net.mca.*` at compile time, so on 7.7.1 the first villager-or-mob damage event threw
  `NoClassDefFoundError: forge/net/mca/entity/VillagerEntityMCA` out of `McaCompat` and took the
  server tick loop with it — reproducible with `/kill` on any mob. MCA is now resolved by name at
  runtime, and one jar supports MCA 7.6 through 7.7.1+.
- **The compat layer actually fails safe now.** Every method already claimed to, but the
  `instanceof` guard sat *outside* the `try/catch`, and class resolution happens at exactly that
  instruction — which is why a package rename became a crash rather than a degrade. The type test is
  now inside the guarded region, and `LinkageError` is caught ahead of `Throwable` throughout.
- **An unsupported MCA now degrades instead of dying.** A linkage failure trips a one-shot latch that
  disables MCA integration for the session and logs a single error naming the detected MCA build and
  the supported package roots — rather than re-throwing once per damage tick. Standing already in
  the save stays intact and readable with `/mcareputation`.

### Added

- **A startup self-test.** Common setup resolves MCA once and logs one line — either
  `MCA integration active: mca <version> (package root <root>)`, or an error naming exactly which
  members failed to resolve. It checks against the MCA actually installed, which compiling against a
  pinned version cannot do, and it is the first thing to look for in a bug report.

### Changed

- MCA is no longer a compile-time dependency (`runtimeOnly` in `build.gradle`), so reintroducing an
  MCA `import` is now a compile error rather than a test failure. `OptionalClassloadTest` asserts the
  stronger rule that replaced spec §11's old one: *nothing* imports MCA, not even `compat`.

## [0.2.0] — unreleased

A full-tree review before first release: score-integrity fixes, command and interface repairs,
config that does what it says, and the transaction finally under test. 0.1.0 was never published;
0.2.0 is the version that ships.

Compatibility is unchanged from the table below (MC 1.20.1, Forge 47.x, MCA `7.6`–`7.7`), except the
optional companions: the Journal's **[View Deeds]** link needs **MCA: Quests 1.2.0+**, and villager
gossip about deeds plus the standing topic need **MCA: Conversations 1.2.0+**. Older companions keep
working; those features simply are not offered.

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

- `McaReputationApi.registerImportProvider` / `unregisterImportProvider` — the supported §32.2
  registration path, so companions stay off internal packages — and
  `McaReputationApi.openReputationScreen`, which backs MCA: Quests' Journal **[View Deeds]** link
  (§29.7) with a fresh snapshot ahead of the push.
- The transaction test seam and suites: `ReputationServiceTest` (ordering, dedupe, containment,
  clamp exactness, imports), `CommandTreeTest`, `RequestThrottleTest`, `FeedbackMergeTest`,
  `FeedbackPresentationTest`, `ScrollMathTest`, and a two-way `LangParityTest` — 241 tests in all.

## [0.1.0] — unpublished

The initial development build: a public memory and civic consequence layer for MCA Reborn villages.
Superseded by 0.2.0 before any release was tagged.

### Compatible versions

| Mod | Version |
|---|---|
| Minecraft | `1.20.1` |
| Forge | `47.4.10+` (metadata range `[47,)`) |
| MCA Reborn | `7.6`–`7.7` — built against `7.7.0-beta.2`, verified against `7.6.20` |
| MCA: Quests | `1.1.0+` (optional) |
| MCA: Conversations | `1.1.0+` (optional) |

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
- A stable public Java API and five Forge events, plus a `ReputationMirror` sink and a
  `LegacyImportProvider` seam for add-ons.

### Compatibility

- **Works standalone.** MCA Reborn is the only requirement.
- **No Architectury dependency.** MCA 7.6 declares it itself and 7.7 dropped it; this mod contains no
  Architectury reference, so a 7.7 user who removed it is not blocked.
- **No mixins.** The one place a mixin was a candidate — the Standing button on MCA's interaction
  screen — uses Forge's screen-init event instead, so there is no MCA-internal signature to drift
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
