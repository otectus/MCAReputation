# Changelog

All notable changes to MCA: Reputation.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.6.0] — unreleased

**Carries the upstream Forge 0.6.0 feature set to Minecraft 1.21.1 / NeoForge.** Standing answers how
much a village likes you. This release adds the other half: what it knows you **for**, and how widely.
The two are separate quantities on separate clocks, authored in separate datapack files, and stored in
separate channels — because a player can be famous for the wrong things, or quietly well-regarded and
recognised by nobody, and one number cannot say both.

The second theme is that a service repeated is worth less than the first. Wrongdoing is not: a second
assault costs exactly what the first one did, structurally rather than by a caller remembering to check.

| Mod | Version |
|---|---|
| Minecraft | `1.21.1` (metadata range `[1.21.1,1.21.2)`) |
| NeoForge | `21.1.249+` (metadata range `[21.1.249,21.2)`) |
| Java | `21` |
| MCA Reborn | `7.7.x` (metadata range `[7.7,8)`) — built and verified against `7.7.36-beta.3+1.21.1` |
| MCA: Quests | `1.1.0+` (optional, requires API version 2) |
| MCA: Conversations | `2.0.0+` (optional, requires API version 2) |
| MCA: Crime | `0.1.0+` (optional) |

**Numbers that moved:** the network protocol goes `"5"` → `"6"`, because the snapshot reply now carries
a profile subpayload and a request stamp; a client and server must run matching versions of this mod.
The Forge line goes `"4"` → `"5"` for the same release — **the two channels are separate lineages and
have never been wire-compatible, so the numbers are not a comparison.** The save format goes `2` → `3`,
the same number and the same bytes as the Forge build, and a world still crosses between the two
loaders at the same format number. `McaReputationApi.getApiVersion()` is unchanged at `2`: everything
below is additive, including the whole `api.profile` surface, and the companions' NeoForge branches
gate on `2`. Forge's API version is `1` for this same surface, for the reason the 0.5.0 entry gives —
the `api.event` types here extend `net.neoforged.bus.api.Event`.

### Added

- **Public profiles.** A village now tracks two things beside your score: **recognition** — how widely
  known you are there, on its own `0 … 1000` ladder from unknown through noticed, recognized,
  well_known and renowned to famous — and **facets**, the traits it knows you for. Seven ship:
  bravery, compassion, generosity, lawfulness, mercy, reliability, violence. Both fade on lifetimes
  the datapack authors per contribution (56 in-game days for recognition, 28 for a facet, as shipped)
  and both are independent of standing's own decay policy, so a deed can stop counting against your
  score long before it stops being what you are known for.

- **The evidence is frozen when the deed is accepted.** Every accepted deed carries an immutable
  payload: the authored quantities, the credit percentage it was actually awarded at, the lifetime,
  the decay step, the resolution mode and its multipliers, and a fingerprint of the rules used. A
  datapack edit changes what *future* deeds are worth and never rewrites what a player already did.
  Values are stored in fixed point — one authored point is 10 000 subunits — so a contribution
  credited at 25% survives as a quarter point rather than truncating to a public zero before it is
  summed.

- **Resolution modes, per channel.** Recognition and historical evidence ignore an apology, an
  atonement, and forgiveness entirely: being known for something is not undone by apologising for it,
  and the violence a killing demonstrated still happened. Only evaluative evidence settles, at the
  multipliers frozen onto the deed. A disproven deed is zero in every mode. The progression is
  monotonic by construction, so a rewound clock cannot resurrect anything either.

- **Repeat credit.** A `credit_policies` datapack file states, as explicit percentages, what the
  first, second, third … qualifying service in a window is worth. The six shipped policies run over
  14 in-game days with a zero tail, so no shipped deed pays forever; a rescue pays
  100% / 100% / 50% / 25% / 0%, with a second ceiling per beneficiary so rotating who you help lowers
  the ceiling rather than resetting the allowance. Only a profile authored `commendable` may carry a
  policy at all: adverse and mixed deeds get full accountability. The counters are bounded (64 groups
  and 128 subjects per player per community) and at capacity they refuse conservatively and evict nothing —
  dropping anti-farm state and then granting full credit is the exploit itself.

- **A villager's opinion now reads what it knows you for.** The facets a specific villager has
  actually learned contribute one bounded term to that villager's opinion of you — the pack's own
  `opinion_weight_bp` first, then the operator's `maxFacetOpinionAdjustment` ceiling (25 by default)
  over their sum. A pack cannot out-author the operator, and the operator cannot make a facet matter
  that its pack weighted at zero. What leaves this mod for a Conversations Trust/Respect check is
  still bounded at ±8, unchanged: that is a different quantity in different units, and a saturated
  facet term can move a villager to a different rung but never add a second bias beside that rung's
  own. An MCA personality this build can read adjusts the interpretation; one it cannot read uses the
  authored default, which is what makes the fallback neutral rather than flattering.

- **Knowledge filtering, per villager.** A speaker-scoped profile answer is filtered *per incident*,
  before anything is summed, by the same awareness rules the ledger already used — never as a
  coefficient over the village's total, because the shape of that total is itself evidence of events
  the speaker may not have learned. A villager who knows nothing has an empty profile, which is a
  valid answer and is never replaced by the village's wider view.

- **Standing screen: two profile lines and a Details expansion.** How well known you are in the
  selected village, and what for, above the ledger; a `Details` button — a real, keyboard-reachable,
  narrated button — expands up to eight facet rows with the evidence counts for and against each,
  inside the scrollable list rather than the fixed header. It is collapsed every time the screen
  opens, and expanding it sends nothing: the details arrived with the standing. Opened from a
  villager, the screen also says what *that villager* knows you for. Five states render distinctly —
  unreadable store, read-only store, migrating, incomplete legacy history, and genuine stranger — and
  an incomplete import reads as "recognition history is incomplete", never as "nobody knows you". No
  colour-only meaning: a facet's direction is the pack's own word plus an explicit "in your favour" /
  "against you" phrase. No new texture and no new GUI sprite.

- **Eight new config keys**, four server and four client, documented in
  [CONFIG.md](CONFIG.md): `[profiles] enableProfiles`, `enableRepeatCredit`, `enableFacetOpinion`,
  `maxFacetOpinionAdjustment` on the server, and `showRecognition`, `showKnownFor`,
  `showObserverProfile`, `showExactProfileValues` on the client. The split is deliberate: a client
  preference must never be able to change what the server records.

- **Four new datapack directories** — `facets`, `recognition_tiers`, `incident_profiles`,
  `credit_policies` — plus a `social_profile` field on an incident. See [DATAPACK.md](DATAPACK.md).
  These four are read by a duplicate-key-rejecting parser, unlike the three older directories which
  keep Gson's lenient behaviour so packs that load today keep loading: `{"points": 8, "points": 80}`
  resolves to `80` without a word in ordinary Gson, and for a quantity that is frozen onto player
  records forever there is nothing left to recover after the fact.

- **The public profile API** — `api.profile`, twelve immutable types and nine profile methods on
  `McaReputationApi` (seven operations plus a capability probe, two of them overloaded), plus `ReputationProfileChangedEvent` on the game bus. An unavailable answer is a
  real answer and says which kind it is: disabled, unpublished content, unresolvable target, migrating
  store, read-only store, or incomplete legacy history. An authored predicate fails **closed** on an
  unknown facet or tier id, treats unobserved as *not* negative evidence by default, and answers
  `UNRESOLVED` rather than `false` for an invalid query so a pack's own fallback runs instead of a
  silently closed gate.

- **Four new debug subcommands**, all at permission level 2:
  `/mcareputation debug profile <player> <community>` (capabilities, availability, raw subunits beside
  the public integer, per-facet evidence, payload origins, suppressed credit decisions, and the
  bounded credit explanation), `debug credit <player> <community>` (the windows, peeked and not
  consumed), `debug profileincident <player> <community> <incident>` (all four channel quantities, so
  a faded deed and a discounted one are distinguishable), and `debug profilemigration [run <budget>]`
  (coverage and quarantine; the one mutating branch is the explicit `run`). `debug profile` enters the
  reconciliation gate with an inspecting intent, so printing a diagnostic cannot age the evidence it
  is printing.

### Changed

- **Network protocol version bumped to 6.** A 0.5.x client cannot join a 0.6.0 server or vice versa.
  The payload registrar compares the version string by equality, which is what makes appending fields
  safe *within* a version and a bump mandatory across one.

- **Save format bumped to 3, migrating automatically on first load.** Every retained public incident
  whose definition names a profile gets an unenriched stub and starts its profile clock; nothing else
  moves. A ledger with no profile content still serializes to the byte-identical player subtrees
  format 2 produced, which is what lets an existing world load unchanged. `migrateFormat()` now runs
  its steps in order from the version each upgrades, so a format-1 file still goes through v1 → v2's
  receipt recovery and supersession adoption on its way to v3 — in one load. See
  [MIGRATION.md](MIGRATION.md).

- **Legacy enrichment ships, and it is deliberately narrow.** The stubs are filled in by a resumable,
  budgeted pass — eight players at a time from the existing periodic sweep, deferring entirely while
  the datapack registries are unpublished — because the content it needs comes from the reload, which
  may not have happened at load time. Only the built-in deed types are reconstructed, named by a
  frozen manifest, so a pack that repointed `villager_rescued` at a lavish custom profile cannot
  retroactively award the difference; and only the recognition and historical channels, so an old
  killing contributes the recognition and violence it factually demonstrated and nothing about
  culpability or remorse. Credit is 100%, the counters stay empty, and no standing, title, receipt or
  revision moves. Coverage is reported honestly afterwards: a finished conservative pass is *partial*
  history, not complete history, and an absence gate keeps respecting that.

- **Profile aging has its own clock, and only the reconciliation gate moves it.** Profiles can be
  frozen while standing keeps ageing, so one clock would pay a disabled interval out as a burst of
  catch-up fading. Per-community decay immunity is persisted state the gate can see at any later read
  and is skipped exactly as the scalar channel skips it. The global switches are the half no record
  can observe for itself, so they are recorded as bounded freeze epochs at the transition — which is
  what makes an interval nobody looked at and an interval somebody looked at halfway through produce
  the same answer.

- **Retention protects the evidence, not the integer.** Neither cap path will prune a record still
  holding live profile subunits. A ledger with nothing else evictable refuses the next deed instead;
  every authored lifetime is finite, so the pressure is temporary and the same ledger prunes and
  admits again once the evidence is spent.

- **`ReputationPolicy` now reads the profile switches from the config** rather than carrying their
  documented defaults, so an operator switching profiles off changes behaviour rather than only
  CONFIG.md. `recognitionCap`, `facetPointCap` and `protectProfileEvidence` stay constants on purpose:
  the first two are the units the stored subunits are interpreted in, and lowering either would
  reinterpret evidence a player already earned.

- **Overlapping core-incident authority claims are reported once per kind, at registration** rather
  than from inside the damage event, which fires for every point of damage dealt anywhere.

### Platform

The Forge 0.6.0 sources were re-expressed for this loader rather than applied as a patch; the
behaviour is the same and the on-disk save bytes are identical, but the following are written
differently here.

- The new `[profiles]` config blocks are `ModConfigSpec`, and `ReputationConfigLifecycle` reports the
  profile-policy transition from `ModConfigEvent.Reloading` on the injected mod bus. `ConfigParityTest`
  pins all eight new keys.
- The profile subpayload travels as `StreamCodec`s over `RegistryFriendlyByteBuf`, with
  `ComponentSerialization.STREAM_CODEC` for the authored labels rather than 1.20.1's
  `FriendlyByteBuf.writeComponent`. Every list — including the community, title and incident lists
  that predate this release — goes through this branch's own bounded reader, which refuses a claimed
  length *before* allocating. The byte-budget measurement in `network/SnapshotCodec` uses a scratch
  `RegistryFriendlyByteBuf` over the destination's own `RegistryAccess`, so what is measured is the
  bytes that will actually travel; a plain scratch buffer would throw on the first component instead
  of measuring it.
- `SnapshotCodec` exists on the Forge side because `ReputationNetwork`'s static initialiser built a
  `SimpleChannel`; that hazard does not exist here, where registration happens inside
  `ReputationNetwork.register` and the outer class holds no channel. The file is kept for the byte
  budget's own sake and to keep the two branches file-for-file comparable, and its javadoc says so
  rather than repeating a reason that is not true here.
- Forge's P7 reached the client packet path through `DistExecutor`, which NeoForge removed. The client
  profile state goes through this branch's existing `ClientPacketHandler.Sink` seam;
  `DedicatedServerClassloadTest` and `ClientPacketSinkTest` still assert that nothing under
  `network/` names a client type.
- The screen changes land on this branch's 1.21 GUI-sprite-based panel (`GuiTextures.well`,
  `GuiPalette`) rather than Forge's blit-based one. As on Forge, no new texture or sprite was needed —
  text and an existing `Button` only — so `GuiSpriteMetadataTest` needed nothing new.
- MCA's `VillagerEntityMCA#getProfessionId()` is resolved on `McaReflect`'s existing descriptor-driven
  audited surface, as a second **optional** tier: `AUDITED_OPTIONAL_MEMBERS`, reported by
  `missingOptional()` and one startup WARN, with availability still decided by the required list
  alone. It was audited with `javap` against the artifact `mca_jar_sha256` pins — `public
  net.minecraft.resources.ResourceLocation getProfessionId()` — and `McaBinaryAbiTest` now re-checks
  the optional tier against that jar on every `check`, while `McaTraitFallbackTest` resolves it at
  runtime against the real MCA on the test classpath.
- `verifyApiJarLinks` and `verifyApiJarLinkage` were ported to ModDevGradle on the Java 21 toolchain.
  The linkage baseline is **`b70f320`, this line's own 0.5.0 port** — not the Forge release the
  feature set came from, because a Forge-era api jar does not link here at all and would fail the
  fixture for a reason that has nothing to do with 0.6.0 compatibility. Nothing is reobfuscated:
  ModDevGradle compiles against Mojang names with Parchment parameters and its jar output is already
  the distributable artifact, so the baseline classes and today's name the same Minecraft members.
- `check_mod.py` remains unusable on this branch (it is Forge-1.20.1-shaped); `LangParityTest`,
  `GuiSpriteMetadataTest` and `ContentValidationTest` cover what it would have checked, and
  `ProfileContentValidationTest` adds the same for the four new directories.
- The golden `mcareputation-format-3-1.20.1.nbt` fixture is a **byte-for-byte copy of the Forge
  file**, not a local regeneration, and `GoldenSavedDataCompatibilityTest` pins its SHA-256 so it
  cannot quietly become one. The provider-neutral `savePayload`/`loadPayload` split is what lets it be
  read directly.

## [0.5.0] — unreleased

There was no 0.4.1 release; the API-jar work below shipped as part of this one instead, alongside a
reliability pass carried over from the Forge 0.5.0 review of how a deed is detected, delivered, retained,
and displayed across the whole suite.

**Splitting the claim, as that review asks:** everything below is something this mod now does correctly
*on its own* — recording, deduplicating, reconciling, superseding, and displaying a deed the same way
regardless of who else is installed. It is not a claim that the
four-addon interaction is now fully correct: MCA: Crime's typed delivery outcomes, pre-link resolution
queueing, NPC-offender filtering, and assault-to-killing parity through its own detection path; MCA:
Quests' optional-delta preservation and bound restitution targets; and MCA: Conversations' incident-bound
amends and status-aware acknowledgment all still need their own patch to adopt the seams this release adds.
Runtime and built-jar certification against those companions is out of scope for this entry.

**Numbers that moved:** the network protocol goes `"4"` → `"5"` — a client and server must run matching
versions of this mod. The save format goes `1` → `2`; an existing world migrates automatically, once, the
first time it loads, and the migration is idempotent. `McaReputationApi.getApiVersion()` stays at `2`;
the new `capabilities()` query below is the additive alternative to a version bump.

### Added

- **`apiJar` Gradle task** produces `build/libs/mcareputation-<version>-api.jar`, a compile-only
  artifact containing the `api` package plus a named read-model slice of classes intended for
  sibling add-ons such as MCA: Conversations. It is for sibling add-ons to compile against and must
  never be shipped inside another mod — MCA: Reputation is the only thing that supplies these
  classes at runtime, and a bundled copy is a duplicate-class error, not a fallback. Classes
  outside `api/` are a read model of the current shape of these types, not a stability promise.
  A new `verifyApiJar` check is wired into `build`.

- **Capability negotiation.** `McaReputationApi.capabilities(server)` returns a `ReputationCapabilities`
  snapshot: the API version, whether the mod/decay/opinion features are enabled, a set of feature strings,
  which `CoreIncidentKind`s this mod is still detecting itself, and who claims the rest.

- **Per-kind detection authority.** `CoreIncidentAuthority` gains `declaredKinds()`, `canDeliver(kind)`,
  and `onServerStopped()`. An authority that claims detection without declaring which kinds it detects
  falls under the new `coreAuthorityUndeclaredKinds` config (`TRUST_LEGACY` / `ASSAULT_KILL_ONLY` /
  `IGNORE`), default **`ASSAULT_KILL_ONLY`** — honouring only villager assault and killing, the two kinds
  that existed before per-kind declaration was possible. `/mcareputation debug authorities` reports the
  effective per-kind claim.

- **Recoverable delivery.** `McaReputationApi.deliver(IncidentDelivery)` returns a `DeliveryOutcome`
  carrying a typed `ReceiptOutcome`. Read-only lookups — `findReceipt`, `findIncident`, `receiptFloor` —
  never create a record. Receipts are kept per player (capped at 512, retained for
  `receiptRetentionTicks`, 14 in-game days (336000 ticks) by default). A ledger with nothing left to evict refuses a
  new deed with `Reason.CAPACITY` instead of dropping older history to make room.

- **One accounting seam for superseding.** `McaReputationApi.recordSuperseding(request, SupersedeSpec)`.

- **Speaker-aware queries and bound resolution.** `selectIncidents`/`resolveBySelector` overloads take a
  `SpeakerContext`; the speaker-less overloads now fail closed on `knownToSpeaker` instead of ignoring it.
  `resolveBound(...)` resolves one exact incident idempotently, keyed by operation.

- **Title consistency.** Village-scoped grants and revocations now reach registered mirrors
  (`ReputationMirror.mirrorTitleRevoked`, `mirrorTitleState`, `TitleSnapshot`). `globalTitles(server,
  player)` reads global titles without a community record. `highWaterTierId(...)` takes the ladder as a
  parameter. A multi-tier jump grants each newly-crossed milestone once, with one notification.

- **Gossip with a sense of what changed.** `McaReputationApi.gossipStory(...)` returns a `GossipStory`
  carrying a semantic revision that moves only on resolution or supersession, never ordinary decay. The
  existing `gossipCandidate(...)` path is unchanged.

- **Standing screen: pagination and richer deed lines.** The community list pages at up to 64 per page,
  with the true total community count carried alongside so the screen can say how many pages there are.
  Each deed line now carries a visibility label (private / witnessed / village-known) and its original
  value versus what it currently counts for.

- **Debug subcommands**, all permission level 2: `/mcareputation debug receipts <player> [community]`,
  `/mcareputation debug supersede <player> <community>`, `/mcareputation debug quarantine`.

### Changed

- **Network protocol version bumped to 5.** A 0.4.x client cannot join a 0.5.0 server and vice versa.

- **Save format bumped to 2, migrating automatically on first load.** Retained incidents carrying a
  dedupe key get a synthesised receipt returning their own ID on replay; existing `superseded_by` links
  are marked terminal. A decay-immune community needs no stored freeze clock — the reconciliation gate
  already advances its clock on every pass without ageing it. Migration is idempotent, and a save from a
  future format is preserved untouched and opened read-only rather than rewritten.

- **Decay immunity and the master/decay toggles hold on every path** — score reads, snapshots, opinion
  queries, incident selection, and admin commands all route through one policy-aware reconciliation gate.

- **Standing predicates no longer depend on `enableConversationsIntegration`.**
  `getVillagerOpinionDetailed(...)` distinguishes a genuine zero opinion from `DISABLED`, `UNSUPPORTED`,
  or `UNRESOLVED`; the existing `getVillagerOpinion` overloads delegate to it unchanged.

- **Scoreboard objective ownership is proven before it is touched**: adopted only when its criteria is
  `dummy` and its display name carries this mod's own marker; disabling the feature removes only the
  objective this mod created.

### Fixed

- **A superseded (folded) incident can no longer regain a contribution** across fold → resolve →
  reconcile → reload → reconcile again.

- **A backdated deed is aged before it is announced**, instead of showing its full un-aged value for a
  moment.

- **Every semantic standing change publishes exactly one `StandingChange` envelope**; decay and reload are
  marked quiet, so they reach mirrors and the displayed tier without a toast and never register as a
  first-time tier celebration.

### Notes

- Recoverable duplicate delivery (`ReputationResult.duplicate(...)` returning the original incident ID)
  already shipped here in 0.3.0 and is unchanged by this release.

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
