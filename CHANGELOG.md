# Changelog

All notable changes to MCA: Reputation.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.3.0] — unreleased

Three things: the standing screen now looks like part of the game, it now shows the standing you
actually have, and MCA: Crime can finally be installed alongside this mod without the two of them
charging the same punch twice.

The screen was drawn entirely with flat `fill()` rectangles in a violet scheme of its own — a poor fit
for a screen the player reaches one click from MCA's own interaction screen, and not what §28.2 asks
for when it says to use MCA's visual language rather than a visually unrelated menu. Nothing about
what it *says* changed with the redraw: the same server-authoritative snapshot, the same fields, the
same empty states.

What it said was wrong for a different reason, and a player found it before we did: *"no matter what I
do I have '25 more to acquaintance' and my rank is 'stranger'."* The number was not stuck — the screen
was reading a different village from the one their deeds were written to, and answering honestly about
a place they had never been. That is the Fixed section below, and `DIAGNOSIS.md` carries the full
pipeline trace, the alternatives that were ruled out, and the evidence for each.

The compatibility half is the core-incident authority handshake below. It is a small API and a large
consequence: it is the difference between the two mods being usable together and not.

### Changed

- **The standing screen is drawn from textures, in vanilla's container idiom.** A new sheet at
  `assets/mcareputation/textures/gui/reputation.png` supplies the panel frame, the sunken well the
  deed ledger sits in, the progress track and its fill, the scroller channel and thumb, the section
  rule, and the selector arrow faces. The frame is pixel-identical to vanilla's own container GUI —
  every colour was sampled from the 1.20.1 client jar rather than eyeballed, including the detail
  that the four corners are not alike: where the light and dark bevels meet, vanilla steps the
  outline out by one pixel and leaves a single face-coloured pixel in the notch. The generator that
  produces the sheet, `tools/GenerateGuiTexture.java`, is committed alongside it, so the art can be
  re-derived and reviewed rather than edited blind.
- **Every sprite is nine-sliced, so the panel still shrinks to fit.** §28.2's small-GUI-scale rules
  rule out a fixed-size container texture, so corners are copied and edges repeat instead. When the
  panel gets short enough that the ledger's heading would be written over the tier line, the heading
  and its rule are dropped rather than overlapped.
- **The community selector uses arrow sprites instead of the literal characters `<` and `>`,** which
  is the one part of a Minecraft screen that never looks native. The new `SpriteButton` overrides
  only the label step of vanilla's button rendering, so the frame, the hover, focus and disabled
  states, the click sound and resource-pack compatibility remain vanilla's own. Each button keeps a
  translatable message for narration and reuses it as a tooltip, since it no longer has a caption.
- **Two text colours, both vanilla's.** Fifteen inline hex literals became `0x404040` and
  `0x7F7F7F`, the pair vanilla labels its container screens with. That also settles §28.4 outright:
  with a neutral palette there is no polarity left to encode in colour, so the sign, status and
  wording on each deed's meta line carry it alone rather than merely reinforcing a hue.
- **The header and the deed ledger are wrapped once when the screen opens,** rather than
  re-measured on every frame. It is cheaper, but the point is that the drawn height and the height
  the scrollbar is scaled against were previously two separate walks over the same data and could
  drift apart; now there is one.
- The scroller channel is reserved beside the ledger whether or not it overflows. Reserving it only
  when needed is circular — the wrap width would depend on the wrap — and put the measured and drawn
  heights in disagreement for exactly the lists sitting on the boundary.

### Fixed

- **The standing screen showed "Stranger — 25 more to Acquaintance" for players who had earned
  standing.** Asked for a snapshot with no village named, the server picked whichever village was
  nearest the player's feet and detailed that one; a village with no record is answered with a
  synthesised floor-tier detail, so a player standing anywhere within the 128-block search radius of
  a village they had never dealt with was shown a score of zero however much standing they had
  elsewhere. The write key (the home village of the villager a deed was about) and the read key (the
  nearest village to the player) are answers to two different questions.

  Where you are now wins only when you have a history there; otherwise the reply details the standing
  you actually have; and "you are a stranger here" is reserved for a village you explicitly asked
  about, or for a player who genuinely has no standing anywhere. The decision has a name and a home
  of its own, `SnapshotSelection`, so it can be tested and printed rather than being three lines
  inside a packet handler. No packet shape changed and the protocol version is unmoved.

- **With one village on record, there was no way to reach it from an unknown one.** The selector
  arrows were drawn only when the community *list* held more than one entry, but the detailed
  community need not be in that list at all. One real village plus the village you are standing in is
  two destinations; the arrows now appear, and cycling forward from an off-list selection enters the
  list at the front instead of skipping its first entry. `SelectorMath` holds both decisions as pure
  functions.

### Added

- **`/mcareputation debug standing [<player>] [<community>]`** — everything the standing pipeline
  believes about one player, in one screenful: the raw stored score and baseline, the incident count,
  the active tier and its threshold, the next tier and the exact remaining amount, which store is
  being read, which community the screen would open on and whether the player has a record there,
  the registered mirrors, the integration toggles, and the MCA binding status.

  This is the deliverable half of the bug above. Two opposite faults — a stored number that never
  moves, and a stored number the screen is not reading — produce the same sentence out of a player,
  and until now telling them apart needed a source checkout. The "screen would select" line beside
  the "standing in / has record" line is the pair whose disagreement *is* the fault.

- **Full compatibility with MCA: Crime**, through a core-incident authority handshake.

  This mod detects two deeds entirely by itself — harming an MCA villager and killing one — and MCA:
  Crime detects the same two. Two mods watching the same `LivingHurtEvent` and both filing an assault
  do not produce a disagreement, they produce two penalties for one punch, and neither mod can prevent
  that from its own side. There was no supported way for either to stand down, so the integration was
  unbuildable: MCA: Crime's adapter has been written against
  `McaReputationApi.registerCoreIncidentAuthority` for a version, and until now that method did not
  exist. Installing both mods meant either double-counted villager harm or no integration at all.

  A companion now claims one or more `CoreIncidentKind`s and this mod stands down from detecting them,
  while continuing to own everything downstream — the claimant files the same incident type through
  `record`, so the ledger, scores, decay, gossip and witnesses are byte-for-byte what they would have
  been. `McaReputationApi.hasExternalAuthority` reports whether a claim is currently held, and
  `/mcareputation debug authorities` answers the question an operator actually has, which is "why have
  villager assaults stopped appearing in the ledger".

  Three decisions in it are worth stating, because each has a silent failure mode on the other side:

  - **Ownership is asked per event, not read once at registration.** A companion's detection is
    normally conditional on its own config; a one-time flag would let an operator switch that config
    off and silently disable villager detection in *both* mods, with nothing in either log to connect
    it to. Asking every time means detection returns here on the very next event, no restart.
  - **A claimant that throws is treated as not claiming,** so detection stays with this mod. The risk
    of that direction is a duplicate, which is visible in the ledger the moment it happens; the risk
    of the other is a deed recorded by nobody. A silent loss is worse than a loud duplicate.
  - **Withdrawal is by handle, not by `unregister(authority)`.** Companions commonly register an
    anonymous implementation, and an equality-based removal works right up until a config reload leaks
    a claim nothing can withdraw — which would suppress this mod's detection permanently.

  Additive to API version 1: `getApiVersion()` deliberately does **not** move, because a bridge
  written against the original version neither calls this nor is affected by it, and bumping it would
  make every existing bridge refuse an API it is still fully compatible with.

- **The scroller can be dragged,** and clicking the bare channel takes it to the pointer, as
  vanilla's own lists do. The mapping from a pointer position back to a scroll offset lives in
  `ScrollMath` next to the one that paints the thumb, so the two cannot part company.

### Fixed

- **The scroller no longer creeps away from the pointer as it is dragged.** `ScrollMath.thumbY`
  truncated, so wherever the division landed just under an integer the thumb repainted one pixel
  above where it had been grabbed. It rounds now. This surfaced from asserting the round trip
  through the new drag mapping, not from the re-skin itself.
- **The scroller thumb can no longer be taller than the track it runs in.** Its sixteen-pixel floor,
  there to keep it grabbable, could exceed the available track at punishing GUI scales and produce a
  negative offset.

### Notes

- 244 automated tests, including a round-trip assertion between the scrollbar's paint and drag
  mappings.
- The frame was verified against the one vanilla actually ships by regenerating it at
  `container/generic_54.png`'s own dimensions and diffing, but the screen has not yet been looked at
  in a running client. [PRODUCTION_TESTS.md](PRODUCTION_TESTS.md) still governs a tag.

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
