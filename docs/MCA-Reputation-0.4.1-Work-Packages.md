# MCA: Reputation 0.4.1 — Reputation-side work packages

Derived from `docs/MCA-Reputation-0.4.1-Implementation-Plan.md` (the four-repo review) by scouting
Forge `main` at `edb8ebd` and the NeoForge checkout at `b5c09ca` on 2026-09-08.
**Status: proposed, awaiting approval. Nothing in either repo has been changed.**

Path shorthand used in every table below:

- `<SRC>` = `C:\Projects\MCAReputation\src\main\java\dev\otectus\mcareputation`
- `<TEST>` = `C:\Projects\MCAReputation\src\test\java\dev\otectus\mcareputation`
- `<NEO>` = `C:\Projects\1.21.1 Ports\MCAReputation_1.21.1\src\main\java\dev\otectus\mcareputation`

---

## 1. Release framing

**The plan doc's boundary is right, but its release theme is not deliverable by this repo.** §1 promises "a deed has the same public meaning whether Reputation runs alone or alongside Quests, Conversations, and Crime." Reputation alone cannot deliver that: F01 (P0), F03, F04, F05, F06, F11, F12, F13 and F15 all need a companion patch, and companions are out of implementation scope. What Reputation can ship is the *seam set* those patches bind to, plus the six items it genuinely owns end-to-end: F02, F07, F08, F09, F10, F14, F16.

The plan doc anticipates this at §1: "If a companion patch cannot be shipped together, split the release claims explicitly: a Reputation core fix does not by itself mean the four-addon interaction has been fixed." Adopt that verbatim as the release-note framing.

**One P0 exception worth calling out.** F01 is the only blocker Reputation *can* fix unilaterally, and this plan recommends it does. `CoreIncidentAuthority` shipped in 0.3.0 with two kinds; the four positive kinds (`MCA_VILLAGER_RESCUE`, `MCA_VILLAGER_CURE`, `MCA_RAID_REPELLED`, `PLAYER_KILL_IN_VILLAGE` at `api/CoreIncidentKind.java:45/53/61/69`) arrived in 0.4.0 (`9abc5e2`). Honouring an *undeclared* legacy authority only for the two kinds that existed when it was written is not a reinterpretation of its contract; it is the contract. That restores rescue/cure/raid before Crime ships anything. Details in WP2.

### Version, API and protocol numbers

| Number | Forge `main` now | NeoForge now | Recommendation | Why |
|---|---|---|---|---|
| `mod_version` | 0.4.1 (unreleased, API-jar entry in CHANGELOG) | 0.4.1 | Keep 0.4.1 as the container per the request; **but see decision D1**. This carries a save-format bump and a wire bump, which is minor-release shaped. | Only `gradle.properties` changes either way; cost of deciding later is one line. |
| `McaReputationApi.API_VERSION` | `1` | `2` | **Do not bump Forge.** Stay at `1`; add a capability query instead (§6 row 1). Leave the NeoForge/Forge divergence alone. | `DIAGNOSIS.md:139-141` records that Quests pins `REQUIRED_API_VERSION = 1` and that 0.3.0 deliberately did not bump. The comparison operator in all three companions is unverified (Risk R1); if any is `==`, a bump silently disables every integration. §6 also says "Keep the inspected API version 1 compatible where possible." |
| `PROTOCOL_VERSION` | `"3"` (`<SRC>\network\ReputationNetwork.java:55`) | `"4"` (its `:85`) | Bump **only in WP7**, Forge `"3"→"4"`, NeoForge `"4"→"5"`. Divergence is harmless; the two loaders never handshake with each other. | F16 changes `IncidentSummary` and adds pagination to `RequestSnapshotC2S`/`SnapshotS2C`. `CLAUDE.md:11` says `"2"` and is stale today; `DIAGNOSIS.md:260` also says `"2"`. Both need a documenter pass. |
| `ReputationSavedData.FORMAT_VERSION` | `1` (`<SRC>\state\ReputationSavedData.java:51`) | `1` (its `:61`) | Bump to `2` in WP5. See decision D2. | Receipts, the freeze clock, and the supersede flag all need persistence. |

---

## 2. Ordered work packages

Each package compiles and passes `check` on its own. WP1→WP5 are strictly ordered; WP6 needs WP3; WP7 needs WP4+WP5; WP8 is the port.

---

### WP1 — Policy snapshot and test seams (plan step A)

**Covers:** step A ("written freeze, retention, title, and replay semantics" + fixtures). No behaviour change. This exists because `TestServiceContext` has no config stub and every later package is config-driven; without this seam the F07/F09/F14 tests cannot vary policy in a loader-independent JUnit run.

| File | Change |
|---|---|
| `<SRC>\reputation\ReputationPolicy.java` **(new)** | Immutable snapshot record of policy-relevant config: master enabled, decay enabled, min/max score, rumor delay min/max, opinion percents, per-community/per-player incident caps, receipt retention ticks, undeclared-authority mode. One factory `ReputationPolicy.fromConfig()`. |
| `<SRC>\McaReputationConfig.java` | Add `snapshot()` returning `ReputationPolicy`, built **only** from the existing guarded accessors (`enabled()` `:375`, `scoreDecayEnabled()` `:535`, etc.). No new direct `.get()` calls. |
| `<SRC>\reputation\ServiceContext.java` | Add `ReputationPolicy policy()`; default implementation returns `ReputationPolicy.fromConfig()`. Existing `postSafely` (`:62`) untouched. |
| `<TEST>\reputation\TestServiceContext.java` | Accept an injected `ReputationPolicy` and an explicit clock supplier. Keep the existing in-memory `ReputationSavedData` + event-capture behaviour. |

**Tests:** no new invariants; the existing suite must stay green, proving the snapshot reproduces today's defaults. Add `ReputationPolicyTest` asserting `fromConfig()` returns documented defaults when the spec is unloaded (the CLAUDE.md guarded-accessor rule).

**Acceptance:** `./gradlew compileJava` then `check`, zero test changes needed elsewhere.

**NeoForge:** copy `ReputationPolicy` verbatim; `McaReputationConfig` and `ServiceContext` are loader-touched (config diff is 128 lines); re-apply by hand and extend `ConfigParityTest` with the new key.

---

### WP2 — F01 authority declaration + F02 duplicate identity (plan step B)

**Covers:** F01, F02. §6 contracts: *Effective capabilities* (partial), *Incident delivery* (duplicate half).

| File | Change |
|---|---|
| `<SRC>\api\ReputationResult.java` | Add static factory `duplicate(UUID existingIncidentId, CommunityKey community, int score, String tierId)`: direct backport of NeoForge `ReputationResult.java:78-82`. `applied=false`, delta `0`, `incidentId` present, reason `DUPLICATE`. |
| `<SRC>\reputation\ReputationService.java:191-192` | Dedupe branch returns `duplicate(existing.get().id(), …)` instead of `notApplied(...)`. Mirrors NeoForge `:194`. |
| `<SRC>\api\CoreIncidentAuthority.java` | Three additive **default** methods (Crime's anonymous impl at `CrimeReputationCompat.java:295` must keep compiling): `default Optional<Set<CoreIncidentKind>> declaredKinds()` → `Optional.empty()` meaning *undeclared/legacy*; `default boolean canDeliver(CoreIncidentKind kind)` → `true`; `default void onServerStopped()` → no-op. |
| `<SRC>\api\CoreIncidentAuthorityRegistration.java` | Additive `default Optional<String> unavailableReason()` → `Optional.empty()`, for the F01-point-5 diagnostic. |
| `<SRC>\event\CoreIncidentAuthorities.java:64,84` | `isClaimed(kind)` becomes *effective* claim: registration active **AND** `owns(kind)` **AND** `canDeliver(kind)` **AND** the declared-kinds gate. New `clearServerScoped()` next to `clear()` (`:106`) that drops memoized effective claims but **keeps registrations** (see decision DD10). Re-document `clear()`. |
| `<SRC>\McaReputationConfig.java` | New COMMON enum key `coreAuthorityUndeclaredKinds` ∈ `{TRUST_LEGACY, ASSAULT_KILL_ONLY, IGNORE}`, default **`ASSAULT_KILL_ONLY`**. Feeds `ReputationPolicy`. |
| `<SRC>\command\ReputationCommand.java` | New `debug authority` subcommand (permission 2, next to the existing `debug` group) printing, per `CoreIncidentKind`: claimed/native, claimant id, declared-or-legacy, `canDeliver`, unavailable reason. |
| `<SRC>\McaReputationMod.java:116-123` | `onServerStopped` also calls `CoreIncidentAuthorities.clearServerScoped()`. |

**No change needed** at the six early-return sites (`ReputationDeedEvents.java:93/183/270/328`, `ReputationGameplayEvents.java:93/200`); they call `isClaimed`, which now returns the right answer.

**Tests** (`<TEST>\event\CoreAuthorityTest.java`, new):

| T-id | Invariant |
|---|---|
| T01 | Iterating **all six** `CoreIncidentKind` members against a legacy broad authority (returns `true` for every kind, declares nothing): only `MCA_VILLAGER_ASSAULT` and `MCA_VILLAGER_KILL` are effectively claimed under the default mode. A future 7th enum member is unclaimed by default; this is the regression guard §5 F01 asks for. |
| T02 | `declaredKinds` present → exactly that set claimed. `canDeliver=false` → not claimed even while registered. `TRUST_LEGACY` restores 0.4.0 behaviour. |
| T03 (part) | Recording the same dedupe key twice returns `applied=false`, `delta=0`, **same `incidentId`**, and the ledger gains no second record. |

**Acceptance (§5 F01/F02):** rescue/cure/raid originate in Reputation with a legacy Crime adapter present; duplicate delivery returns the original UUID.

**NeoForge:** `ReputationResult.duplicate` already exists; port only the call-site parity check. `CoreIncidentAuthority*` and `CoreIncidentAuthorities` are loader-only → straight copy. Config key needs a `ConfigParityTest` entry.

---

### WP3 — F07 reconciliation gate, F14 truth table, standing-change envelope (step C, part 1)

**Covers:** F07, F14. §6 contracts: *Effective capabilities*, *Standing change*.

| File | Change |
|---|---|
| `<SRC>\reputation\ReconciliationService.java` **(new)** | The single policy-aware entry point. `ReconcileOutcome reconcile(ServiceContext ctx, UUID player, CommunityKey community, long gameTime, ChangeCause cause, Intent intent)` where `Intent ∈ {MUTATE, QUERY, INSPECT}`. `INSPECT` never mutates and never creates a record (F07 "keep a pure inspect operation for diagnostics"). Returns old/new score, old/new tier, contribution delta, `frozen` flag, revision, cause. |
| `<SRC>\reputation\ReputationService.java`, 7 bypass sites | `:184` (dedupe refusal) → gate, cause `DECAY`, quiet. `:200` (pre-write) → gate, quiet, before creation. `:334` (resolve) → gate with **freeze-respecting** semantics: when frozen, run clock-skip only, then resolve (§5 F07 "a permitted explicit resolution can still soften an incident, but must not run hidden decay first"). `:465`, `:566`, `:662` → `Intent.QUERY`. `:514` (loop over communities) → **targeted**: reconcile only the community being asked about (§5 F07 "rather than reconciling all of a player's communities for every nearby villager's opinion query"). |
| `<SRC>\state\ReputationSavedData.java:134-146` | `reconcilePlayer` keeps its signature but delegates the immunity decision to the same gate, so there is one policy implementation, not two. |
| `<SRC>\state\CommunityReputationRecord.java` | Add `freezeTo(long gameTime)`: advances every incident's `lastReconciledGameTime` **without** advancing `decayElapsedTicks`. Add `refreshScoreOnly(int min, int max)` exposing the private `recomputeScore` (`:158-165`) to the service package. `reconcile(...)` at `:212` is unchanged. |
| `<SRC>\incident\IncidentRecord.java` | Add `skipDecayTo(long gameTime)`: the per-record half of the freeze clock. Sets `lastReconciledGameTime` forward only; leaves `decayElapsedTicks`, `settledDelta`, `currentContribution`, `status` untouched. |
| `<SRC>\api\StandingChange.java` + `<SRC>\api\ChangeCause.java` **(new)** | `StandingChange(UUID player, CommunityKey community, int oldScore, int newScore, String oldTierId, String newTierId, ResourceLocation ladder, String highWaterTierId, long revision, ChangeCause cause, boolean quiet)`. `ChangeCause ∈ {DEED, RESOLUTION, SUPERSEDE, DECAY, ADMIN, IMPORT, RELOAD, MIGRATION}`. |
| `<SRC>\reputation\ReputationService.java`, dispatch | One `publishStandingChange(StandingChange)` used by every site that currently emits separately: `:268` `notifyMirrors`, `:270-275`, `:356-361`, `:684-686`, `:870-874`, plus the newly-routed reconciliation. Decay and reload set `quiet=true`; `ReputationTierChangedEvent.firstTime` is never `true` for a quiet cause. |
| `<SRC>\api\ReputationMirror.java` | Additive `default void mirrorStanding(StandingChange change)` whose default body calls the existing `mirrorScore(...)` (`:42`) with the extracted fields, so Quests' existing mirror keeps receiving updates unchanged. |
| `<SRC>\reputation\StandingAvailability.java` **(new)** | The §5 F14 truth table in one place: `EffectiveStanding of(server, player, community)` → `{state ∈ AVAILABLE / NEUTRAL_NEW / UNAVAILABLE_COMMUNITY / MASTER_DISABLED / FEATURE_DISABLED, score, tierId}`. Recommended default from §5 F14: numeric predicates evaluate the effective neutral value; action/incident requirements fail closed. |
| `<SRC>\api\McaReputationApi.java:194-208` | `matches` **drops** the `conversationsIntegrationEnabled()` gate at `:197` and routes through `StandingAvailability`. The `.orElseGet(() -> query.isEmpty())` at `:203` is replaced by evaluation against effective neutral standing, creating nothing. `data/StandingCondition.java:93` needs no edit and gains the fix. |
| `<SRC>\api\McaReputationApi.java`, new | `ReputationCapabilities capabilities(MinecraftServer)` (§6 row 1: supported operation features, feature enabled state, effective detector kinds, readiness reason). New record `<SRC>\api\ReputationCapabilities.java`. |
| `<SRC>\api\OpinionResult.java` **(new)** + `McaReputationApi` | `getVillagerOpinionDetailed(...)` returning `OpinionResult(OpinionAvailability availability, Optional<VillagerOpinion> opinion)` with `availability ∈ {AVAILABLE, DISABLED, UNSUPPORTED, UNRESOLVED}`. The existing two `getVillagerOpinion` overloads (`:324`, `:361`) keep their exact signatures and delegate. This is the T31 seam: Conversations must be able to tell a genuine zero from a disabled feature. |

**Tests:**

| T-id | File | Invariant |
|---|---|---|
| T16 | `<TEST>\reputation\DecayImmunityTest.java` (new) | For each of the seven former bypass paths (record, duplicate, resolve, snapshot, opinion, recent-history, admin), an immune community's `currentContribution` and score are byte-identical before and after, across a large elapsed interval. |
| T17 | same | Freeze → elapse 5 in-game days → unfreeze → reconcile: **no catch-up decay**. Separately: global decay disable → elapse → re-enable, documented policy holds for state touched during the pause. |
| T18 | `<TEST>\reputation\ChangeEnvelopeTest.java` (new) | Decay crossing a tier boundary publishes exactly one `StandingChange` with `cause=DECAY, quiet=true`; no `ReputationTierChangedEvent.firstTime=true`; the mirror sees it once. |
| T30 | `<TEST>\api\StandingPredicateTest.java` (new) | `matches` returns identical results with `enableConversationsIntegration` true and false. A `min:0` predicate against a valid community with no stored record evaluates the same before and after a record exists, and no record is created. |
| T31 | same | Valid zero opinion → `AVAILABLE`; feature off → `DISABLED`; unresolved villager → `UNRESOLVED`. Only the non-`AVAILABLE` cases license a public-standing fallback. |

**Acceptance (§5 F07/F14):** score, snapshot, opinion, incident selection, commands and loot conditions agree after the same elapsed time; opening a screen does not alter protected standing; a Conversations toggle does not disable a loot condition.

**NeoForge:** `ReconciliationService`, `StandingAvailability`, `StandingChange`, `ChangeCause`, `ReputationCapabilities`, `OpinionResult`, and the `IncidentRecord`/`CommunityReputationRecord` additions are all provider-neutral → straight copy. `ReputationService` and `McaReputationApi` are re-application (Nullable import, `Event` base). `ReputationSavedData` is loader-structural (`Factory<>` + `savePayload`/`loadPayload` at NeoForge `:78-79/220/245`).

---

### WP4 — F08 superseded lifecycle, F05 supersede seam, F10 clocks and ordering (step C, part 2)

**Covers:** F08, F10, and the Reputation half of F05. §6 contract: *Superseding a precursor*.

| File | Change |
|---|---|
| `<SRC>\incident\IncidentRecord.java` | **(a)** New terminal accounting state: `boolean superseded` + typed `UUID supersededBy` (decision DD3, *not* an `IncidentStatus` member). `contributes()` false when set; `reconcile(...)` (`:304`) returns `0` immediately; `resolve(...)` (`:332`) returns `Optional.empty()` immediately; never an amends candidate. **(b)** `foldInto(successor, gameTime)` (`:368`) sets the flag and the typed field **and keeps writing** the `superseded_by` context key (`BuiltinIncidents.java:99`) for back-compat. **(c)** New `absorbIntoBaseline(long gameTime)` for the pruning fold at `CommunityReputationRecord.java:280-284`: same zeroing, *no* supersede semantics. **(d)** `restoreContribution(...)` (`:386`) clears the flag and the link. **(e)** `createdGameTime` (`:60`) is re-specified as **occurrence** time; new persisted field `appliedGameTime` records when it entered the ledger (decision DD4). **(f)** NBT `save`/`load` (`:428-466` / `:474-528`) write/read `superseded`, `supersededBy`, `appliedGameTime`; absent on load → `superseded=false`, `appliedGameTime=createdGameTime`. |
| `<SRC>\reputation\ReputationService.java`, record path | Backdated delivery: when the request's occurrence time is older than now, create the record with `createdGameTime = occurredAt`, seed `decayElapsedTicks = now − occurredAt` and `lastReconciledGameTime = now`, so the arriving contribution is **already aged before** `:268-275` fires mirrors/events/toasts. Clamp a future occurrence time to now; keep honest historical past times. Community `lastReconciledGameTime` is never moved backward (guarded already at `IncidentRecord.java:305` and `CommunityReputationRecord.java:228-230`). |
| `<SRC>\reputation\ReputationService.java`, new | `ReputationResult recordSuperseding(ReputationRequest successor, SupersedeSpec spec)`. `SupersedeSpec(UUID precursorIncidentId, long maxWindowTicks, boolean requireSharedSubject)`. Validates: same player, same community, shared subject, precursor within window by occurrence time, precursor not already superseded or `DISPROVEN`. Sequence: fold precursor → record successor → if the successor carries **no public weight**, restore the precursor. Publishes **one** `StandingChange` with `cause=SUPERSEDE` (§5 F05 "Emit one final score/tier transition, not transient 'assault refunded' and 'murder charged' promotions"). |
| `<SRC>\api\McaReputationApi.java` | Additive `recordSuperseding(ReputationRequest, SupersedeSpec)` and `SupersedeSpec` in `api`. This is the shared seam for the native path and a future Crime path. |
| `<SRC>\event\ReputationGameplayEvents.java:219-268` | Rewritten to call `recordSuperseding`. Deletes the hand-rolled find (`:219-221`) / fold (`:245-251`) / link (`:260-262`) / rollback (`:263-267`) sequence. `assaultCoalesceTicks` (`McaReputationConfig:465-466`, default 200) becomes the `maxWindowTicks` argument. |
| `<SRC>\state\CommunityReputationRecord.java:102-105` | `incidentsNewestFirst()` sorts by occurrence descending with incident UUID as the stable tie-break, instead of reversing insertion order. `IncidentQuery.select`'s explicit sort (`api/IncidentQuery.java:78-79`) is left alone and becomes correct for free under DD4. |

**Tests:**

| T-id | File | Invariant |
|---|---|---|
| T19 | `<TEST>\incident\SupersedeLifecycleTest.java` (new) | fold → resolve → reconcile → NBT round trip → reconcile again: contribution is `0` at every step and after reload. This is the exact combination `ResolutionTest`/`DecayTest` never cover. |
| T12/T13/T14 | same | Same-victim assault→kill within window folds and totals the kill's target. Unseen kill → precursor restored, not refunded (`:263-267` behaviour preserved through the seam). Different victim, different community, or outside the window → **no** fold. |
| T23 | `<TEST>\reputation\BackdatedDeliveryTest.java` (new) | An old decaying deed delivered after a newer one contributes its *present* (aged) value at commit time; no toast/tier event fires for a value it no longer earns; history reads chronologically by occurrence; the community clock never regresses. |
| — | Update `<TEST>\state\PruningTest.java` | Baseline absorption still holds via `absorbIntoBaseline` (existing assertions at `:68-71` must still pass). |

**Acceptance (§5 F05/F08/F10):** superseded record permanently non-contributing across resolve+reconcile+reload; fatal-encounter parity; present-contribution-before-effects.

**NeoForge:** `IncidentRecord` is byte-identical today; it stops being so; copy the new version wholesale. `SupersedeSpec`/`recordSuperseding` copy. `ReputationGameplayEvents` is the one file that **must be re-implemented, not copied**: NeoForge uses `LivingDamageEvent.Post` with post-mitigation damage plus `isRecordableAssault`/`isEffectiveHit`. The NeoForge `event/DamageHookTest` must keep passing.

---

### WP5 — F03 receipts, F09 retention, save format v2 (step D)

**Covers:** F03, F09, and the Reputation half of F11. §6 contracts: *Incident delivery*, *Receipt lookup*, *Bound resolution*.

| File | Change |
|---|---|
| `<SRC>\state\OperationReceipt.java` **(new)** | `OperationReceipt(String producerNamespace, UUID player, CommunityKey community, String operationKey, ReceiptOutcome outcome, Optional<UUID> incidentId, long occurredGameTime, long recordedGameTime)`. `ReceiptOutcome ∈ {APPLIED, DUPLICATE, ACCEPTED_NO_PUBLIC_INCIDENT, REFUSED_DISABLED, REFUSED_INVALID, REFUSED_CAPACITY}`: the §5 F03 delivery-contract rows, one-to-one. |
| `<SRC>\state\OperationReceipts.java` **(new)** | Per-player insertion-ordered store. Identity = namespace + player + community + operation key (§6). Lookup also accepts a **legacy unnamespaced** form (operation key alone within player+community) so existing dedupe keys (`DeedKeys.java:40-60` and the inline assault/kill keys at `ReputationGameplayEvents.java:232`) keep resolving. Exposes `receiptFloor()` = oldest retained occurrence time. |
| `<SRC>\state\PlayerReputationRecord.java` | Hosts the receipt store; saves/loads it alongside `dedupeIndex` (`:49`, rebuild at `:235-242`). The existing `findByDedupeKey` (`:199-213`) collision semantics are **unchanged**; receipts are an additional index, not a replacement. |
| `<SRC>\reputation\ReputationBounds.java` | New `MAX_RECEIPTS_PER_PLAYER` (recommend 512, matching `MAX_DEDUPE_INDEX_PER_PLAYER` at `:68`). Eviction strictly oldest-first by occurrence, plus a time horizon. |
| `<SRC>\McaReputationConfig.java` | New COMMON key `receiptRetentionTicks` (recommend 336000 = 14 in-game days), `defineInRange` clamped to the hard cap. Feeds `ReputationPolicy`. |
| `<SRC>\state\CommunityReputationRecord.java:250-308` | Retention rules per §5 F09: no pass may evict a record that still contributes, that a non-terminal receipt references, or that is a live amends candidate. Pinned exemption at `:277` stays. When nothing is evictable the request is **refused** (below) rather than silently over-cap. |
| `<SRC>\api\ReputationResult.java` | Add `Reason.CAPACITY` and factory use in the refusal path (F09/T11 backpressure). See decision D5. |
| `<SRC>\state\ReputationSavedData.java` | `FORMAT_VERSION` → `2`. `save` (`:198-215`) writes receipts and the freeze clock. `load` (`:217-262`) reads them; `migrateFormat()` (`:269-276`) gains the real v1→v2 body (below). **Load stops truncating**: `CommunityReputationRecord.java:363-365` reads everything, then applies the *same* admission policy as runtime; the overflow is retained as raw NBT for quarantine, never dropped (§5 F09 "Do not silently truncate and call the resulting score a repair"). |
| `<SRC>\state\ReputationSavedData.java:220-225` | Future-format branch stops being "warn and continue". It latches read-only: the raw tag is retained, `setDirty()` is suppressed, `save` writes the retained tag back verbatim, and an actionable error is logged. §9: "Preserve untouched and refuse destructive conversion." |
| `<SRC>\state\SaveQuarantine.java` **(new)** | Holds over-budget/unreadable raw subtrees in memory (count-bounded) and writes them once from a `ServerStartedEvent` handler to the world folder. Exposed by a `/mcareputation debug quarantine` report. |
| `<SRC>\api\IncidentDelivery.java` + `<SRC>\api\DeliveryOutcome.java` **(new)** | §6 row 2 "detailed request/result". `IncidentDelivery` wraps an existing `ReputationRequest` plus operation identity, occurrence time, and producer revision; **it does not add components to `ReputationRequest`** (decision DD5). `DeliveryOutcome` carries the `ReceiptOutcome`, the `ReputationResult`, and the receipt. |
| `<SRC>\api\McaReputationApi.java` | Additive: `DeliveryOutcome deliver(IncidentDelivery)`; `Optional<OperationReceipt> findReceipt(MinecraftServer, String namespace, UUID player, CommunityKey, String operationKey)`; `Optional<ReputationIncidentView> findIncident(MinecraftServer, UUID player, CommunityKey, UUID incidentId)`; `long receiptFloor(MinecraftServer, UUID player)`. All lookups are **strictly read-only**: no `getOrCreatePlayer` (`ReputationSavedData.java:82-84`), no reconcile (`Intent.INSPECT`), no community creation. Existing `record(ReputationRequest)` (`:406`) is retained and implemented over `deliver`. |

**v1 → v2 migration (`migrateFormat`)**, per §9:

1. For every retained incident carrying a dedupe key, synthesise an `APPLIED` receipt returning that incident's ID (§9 "Retained incident with dedupe key"). Namespace derived from the incident's `source`.
2. For every community in `decayImmune` (`ReputationSavedData.java:58`), write `frozenSince = UNKNOWN`; the first policy-aware reconcile after upgrade sets it to *now* and skips aging rather than catching up (§9 "Preserve the current contribution at upgrade and initialize the new freeze clock there").
3. Every record already carrying a `superseded_by` context entry is marked terminal (`superseded=true`, typed link parsed; a missing successor is tolerated, per §5 F08 "validate links without requiring that a pruned successor still exists").
4. `appliedGameTime` defaults to `createdGameTime`.
5. Already-pruned history invents nothing (§9 "Do not invent old receipt identities").
6. Migration is idempotent (running it twice is a no-op) and emits no events, toasts, or rewards.

**Tests:**

| T-id | File | Invariant |
|---|---|---|
| T04 | `<TEST>\state\ReceiptTest.java` (new) | Deliver, drop the acknowledgment, save/load, replay the same operation → same incident ID, no second incident, `DUPLICATE` receipt outcome. |
| T10 | same | `findReceipt`/`findIncident` under a datapack making assault public-without-witnesses: ledger size, score, receipt count, event count and player-store cardinality all unchanged. This is the direct replacement for Crime's write-capable `findIncident` probe. |
| T11 | same | Full receipt budget and a community whose ledger cannot evict → explicit `Reason.CAPACITY` refusal with **no** partial commit. |
| T20 | Modify `<TEST>\state\DedupeTest.java` (~`:95`) | The existing assertion that a pruned key *disappears* must be **inverted** (§10 says so explicitly). After pruning, replaying the key is still refused because the receipt survives. |
| T21 | `<TEST>\state\CompactionTest.java` (new) | Before/after compaction: future decay trajectory, per-villager opinion, and resolvability are equivalent. |
| T22 | `<TEST>\state\SavedDataTest.java` (extend) | Pinned overflow survives reload with no score loss. A v2 file written by a "v3" writer is preserved byte-for-byte and not rewritten. Malformed neighbours are skipped while valid ones load (existing behaviour at `:243-246`). |
| T37 | same | Migrate twice → no duplication or loss. Legacy-import markers survive. |

**Acceptance (§5 F03/F09):** a pruned/display-hidden deed cannot repay; a pending case remains resolvable; pinned overflow survives reload; a future-format file is preserved; folded→resolved→reconciled remains zero.

**NeoForge:** `OperationReceipt`, `OperationReceipts`, `SaveQuarantine`, `IncidentDelivery`, `DeliveryOutcome`, `PlayerReputationRecord`, `CommunityReputationRecord` copy. `ReputationSavedData` is **re-implementation**: the v2 body goes into `savePayload`/`loadPayload` (NeoForge `:220/245`) and the `SavedData.Factory<>` (`:78-79`) is untouched. NeoForge's `state/GoldenSavedDataCompatibilityTest` hard-asserts `FORMAT_VERSION == 1` at its `:83-84`; that assertion must be **rewritten**, not deleted: keep the existing `mcareputation-format-1-1.20.1.nbt` fixture, assert it loads and migrates to v2 with the documented totals, and add a v2 fixture. See decision D2.

---

### WP6 — F12 speaker-aware queries, F13 title/high-water consistency (step C/F seams)

**Covers:** F12, F13 (Reputation half). §6 contracts: *Speaker-aware query*, *Bound resolution*, *Title synchronization*.

| File | Change |
|---|---|
| `<SRC>\api\SpeakerContext.java` **(new)** | `SpeakerContext(UUID speakerId, boolean resident)`. |
| `<SRC>\api\McaReputationApi.java:222-234` | Additive overload `selectIncidents(server, player, community, IncidentQuery, SpeakerContext)` that filters through `AwarenessResolver.knows(...)` (`incident/AwarenessResolver.java:40`) with the configured rumor delays. The existing 4-arg overload keeps its signature but **fails closed**: if `query.knownToSpeaker()` is set and no speaker was given, return empty instead of silently ignoring it (`api/IncidentQuery.java:41-43,55-69`). Intentional behaviour change; it only affects callers currently receiving over-broad answers. |
| `<SRC>\api\McaReputationApi.java:423-431` | Same treatment for `resolveBySelector`; plus a **bound** form `resolveBound(server, player, community, UUID incidentId, IncidentStatus, ResourceLocation source, String operationKey)` that resolves an exact incident with receipt-based idempotency (§6 *Bound resolution*; the Quests-side F12 fix binds to it). |
| `<SRC>\reputation\TitleService.java:45-64` | `grantVillage` notifies mirrors (today only `grantGlobal` does, at `:93-95`). Add revoke mirroring at `:127-135`. |
| `<SRC>\api\ReputationMirror.java` | Two additive defaults: `default void mirrorTitleRevoked(UUID player, Optional<CommunityKey> community, ResourceLocation title) {}` and `default void mirrorTitleState(UUID player, TitleSnapshot state, long revision) {}` (§6 "a grants-only stream cannot repair a revoked title"). Quests' existing mirror keeps compiling untouched. |
| `<SRC>\reputation\ReputationService.java:922-930,964` | `tierHighWater`/`setTierHighWater` take the ladder as a parameter instead of hard-coding `ReputationTiers.DEFAULT_ID`. Additive `McaReputationApi.highWaterTierId(server, player, community, ResourceLocation ladder)`. |
| `<SRC>\reputation\TitleService.java` + `<SRC>\api\McaReputationApi.java` | Direct global-title enumeration for a player with **no** community record (today only per-player at `TitleService.java:174`, and API enumeration walks community snapshots). No fake community is created. |
| `<SRC>\reputation\ReputationService.java`, milestone policy | A jump from tier 0 to tier 300 grants **each** newly crossed positive milestone title once, while publishing **one** `StandingChange`/tier notification for the actual destination tier (§5 F13 recommendation). |
| `<SRC>\api\GossipStory.java` **(new)** + `McaReputationApi.gossipStory(...)` | §6 *Gossip story*: incident ID, status, semantic revision, current and historical contribution, supersede link, permitted facts. `ExternalGossipCandidate` (`api/ExternalGossipCandidate.java:25-35`) and `gossipCandidate(...)` (`:246`) are left **untouched** so old Conversations adapters keep their baseline behaviour. |

**Tests:** T27 (informed / uninformed / missing / moved speaker; missing speaker with `knownToSpeaker` fails closed), T28 (grant/revoke visible to canonical and mirror; global-only holder enumerated without a community), T29 (custom-ladder high-water returns that ladder's data; multi-tier jump grants each milestone once and notifies once). New files `<TEST>\api\SpeakerQueryTest.java` and `<TEST>\reputation\TitleConsistencyTest.java`.

**NeoForge:** all copy except `McaReputationApi` (Nullable/`Event` re-application) and `TitleService` (loader-only; straight copy after re-applying imports).

---

### WP7 — F16 Standing screen, display state, diagnostics (step H)

**Covers:** F16. Protocol bump lives here and only here.

| File | Change |
|---|---|
| `<SRC>\network\ReputationNetwork.java:55` | `PROTOCOL_VERSION` `"3"` → `"4"`. |
| `<SRC>\network\ReputationNetwork.java:226-227` | `IncidentSummary` gains `visibility`, `baseDelta` (the original deed), `decays` (whether ordinary fading applies), `superseded`. Answers §5 F16 rows 1 and 2. |
| `<SRC>\network\ReputationNetwork.java`, snapshot/pagination | `RequestSnapshotC2S` gains a page index; `SnapshotS2C`/`SelectedDetail` gain `page`, `pageCount`, `totalCommunities`. `MAX_SYNCED_COMMUNITIES=64` (`ReputationBounds.java:62`) becomes a **per-page** cap with the true total carried separately. Server-side validation at `:146-150` (cooldown), `:165-172` (ownership) and `:173-192` (context entity) is preserved and extended to clamp the page index. |
| `<SRC>\network\ReputationNetwork.java:441-449` `buildSnapshot` / `RequestSnapshotC2S.resolveSelection:163` | **Read `DIAGNOSIS.md` §2 hop 7b and §4.2 before editing.** Selection semantics must not regress: `SnapshotSelection.unprompted` (`network/SnapshotSelection.java:42-48`) keeps current-location-if-known → best-known-anywhere → unrecorded, and pagination must not become a fourth way to lose the selected community. §5 F16 row "Preserve navigation" says the same thing. |
| `<SRC>\client\ReputationScreen.java` | Page controls next to the existing selector cycling (`:251-254`), preserving `SelectorMath`/`ScrollMath` behaviour and the re-find-by-key refresh at `:218-243`. Visibility label and "original vs current" contribution rendering. Opinion line (`:317-327`) gains an explicit relationship label to the selected community, and clears when the selection changes (§5 F16 row 4). |
| `<SRC>\client\ClientReputationData.java` | Page state alongside `communities`/`selected` (`:33-34`). |
| `<SRC>\event\StandingDisplay.java:237-241` | Scoreboard ownership validation: before `addObjective`, an existing same-named objective is adopted only if its criteria is `ObjectiveCriteria.DUMMY` **and** its display name is our own translatable marker. Otherwise log once and disable writing for that name; never repurpose, never remove a foreign objective. On feature disable, remove only objectives we own; today there is no removal path at all. |
| `<SRC>\event\ReputationConfigLifecycle.java` **(new)** | `ModConfigEvent.Loading` / `.Reloading` on the **MOD bus**, filtered to `mcareputation`'s own `ModConfig`. Invalidates `StandingDisplay`'s `SHOWN` cache (`:61`) and clears adornments for a feature that just turned off; invalidates derived opinion caches; re-evaluates effective authority claims (F01 point 5); performs the decay off→on clock skip. All server-touching work is scheduled onto the server thread. |
| `<SRC>\McaReputationMod.java:82` | Register `ReputationConfigLifecycle` on the MOD bus (`FMLJavaModLoadingContext` bus), **not** `MinecraftForge.EVENT_BUS`. |
| `<SRC>\command\ReputationCommand.java` | Diagnostics per §11: `debug receipts` (coverage + floor), `debug supersede`, `debug quarantine`, extended `debug standing` showing occurrence vs application vs effective decay time. Permission 2, matching the existing `debug`/`top` level (`:754-755`). |
| `src\main\resources\assets\mcareputation\lang\en_us.json` | New keys under `screen.` (page/visibility/original-delta/relationship), `incident.` (superseded), `command.` (new debug subcommands), `scoreboard.` (ownership marker). `LangParityTest` (`:34-44`) enforces two-way parity: every new `Component.translatable` literal must land here. |

**Tests:** T34 (selector/pagination math, loader-independent, in the `SelectorMathTest`/`ScrollMathTest` style), T35 (ownership decision table as a pure function: adopt / refuse / remove), packet round-trip tests for the new DTO fields, `LangParityTest` extension.

**NeoForge:** `ReputationNetwork` is a **structural rewrite** (`PayloadRegistrar`, stream codecs): re-implement, bump `"4"→"5"`, and keep the NeoForge `ClientPacketSinkTest` green. `ReputationScreen`/`GuiTextures` differ (per-part sprites + `blitSprite`). `StandingDisplay` is loader-only (26-line diff) → copy. NeoForge has **no** config-event listener today, so `ReputationConfigLifecycle` is a fresh NeoForge implementation against its own config-event types.

---

### WP8 — NeoForge parity (step I)

Target: `C:\Projects\1.21.1 Ports\MCAReputation_1.21.1`, branch `neoforge/1.21.1`, tip `b5c09ca`, clean, `mod_version=0.4.1`, `apiJar`/`verifyApiJar` already present including `IncidentSeverity`. No git ancestry with `main`; every port is a file-level re-application.

| Classification | Files |
|---|---|
| **Straight copy** | `IncidentRecord`, `CommunityReputationRecord`, `PlayerReputationRecord`, `OpinionResolver`, `AwarenessResolver`, `IncidentQuery`, `ReputationRequest`, `TitleService`, `StandingDisplay`, and every new provider-neutral type (`ReputationPolicy`, `ReconciliationService`, `StandingAvailability`, `StandingChange`, `ChangeCause`, `ReputationCapabilities`, `OpinionResult`, `SpeakerContext`, `SupersedeSpec`, `OperationReceipt(s)`, `IncidentDelivery`, `DeliveryOutcome`, `GossipStory`, `SaveQuarantine`). |
| **Re-apply imports/base types** | `ReputationService`, `McaReputationApi`, `ReputationResult`, `ReputationMirror`, `CoreIncidentAuthority*`, `CoreIncidentAuthorities`, `ReputationDeedEvents`, `McaReputationConfig`, `StandingCondition`, `ReputationCommand`. NeoForge `api.event` types extend NeoForge's `Event`; `javax.annotation.Nullable` → `org.jetbrains.annotations.Nullable`. |
| **Re-implement (loader-specific)** | `ReputationSavedData` (v2 into `savePayload`/`loadPayload`), `ReputationNetwork` (payload registrar + stream codecs, protocol `"5"`), `ReputationGameplayEvents` (`LivingDamageEvent.Post`, post-mitigation damage), `ReputationConfigLifecycle` (new), client screen/sprites. |
| **NeoForge-only tests to update** | `GoldenSavedDataCompatibilityTest` (rewrite the `FORMAT_VERSION == 1` assertion at `:83-84`), `ConfigParityTest` (three new keys), `NeoForgePortLintTest`, `DamageHookTest`, `ClientPacketSinkTest`, `DedicatedServerClassloadTest`. |

**Do not** touch `<NEO>\compat\McaReflect.java`: its 220-line divergence from main is not purely mechanical and nothing in this plan changes reflection behaviour.

---

### Reputation-side seams for steps E, F, G (companion work, not implemented here)

| Step | Companion need | Reputation supplies (package) |
|---|---|---|
| E — Crime delivery | Explicit per-kind ownership | `declaredKinds()` / `canDeliver()` defaults + effective-claim logic (WP2) |
| E | Typed delivery outcomes replacing `Optional<UUID>` | `IncidentDelivery` / `DeliveryOutcome` / `ReceiptOutcome` (WP5) |
| E | Read-only recovery lookup replacing the write-capable `findIncident` probe | `findReceipt` / `findIncident` (WP5) |
| E | Resolution queued before the create link exists | `resolveBound(..., operationKey)` + receipts carrying `ACCEPTED_NO_PUBLIC_INCIDENT` (WP5/WP6) |
| E | Assault→killing parity through the producer path | `recordSuperseding(request, SupersedeSpec)` (WP4) |
| E | NPC-offender filtering | **None.** Crime-side only; Reputation must not guess actor kind. Optionally an audit command listing player-store rows whose UUID never matched a known profile: report only, never delete (§9). |
| F — Quests rewards | Omitted vs explicit-zero delta | Already present: `ReputationRequest.deltaOverride` is an `OptionalInt` (`api/ReputationRequest.java:41`). Carried through `IncidentDelivery`. Documentation fix, not a code fix. |
| F | Stable reward identity / replay safety | Receipt store keyed by namespace + player + community + operation key (WP5) |
| F | Exact restitution target | `resolveBound(...)` (WP6) |
| F | `known_to_giver` | `selectIncidents(..., SpeakerContext)` (WP6) |
| F | Title/high-water consistency | Ladder-parameterised high-water, revoke + full-state mirror defaults, offline/global enumeration (WP6) |
| G — Conversations | Distinguish zero opinion from disabled | `OpinionResult` + `OpinionAvailability` (WP3) |
| G | Generic predicates independent of the Conversations switch | `matches` degated (WP3) |
| G | One apology per offense | `resolveBound` + receipt idempotency (WP5/WP6) |
| G | Correction stories | `GossipStory` with status + semantic revision + supersede link (WP6) |

---

## 3. Design decisions made in this plan

| # | Decision | Alternative rejected |
|---|---|---|
| DD1 | **One `ReconciliationService` gate**; all seven `ReputationService` bypass sites (`:184, :200, :334, :465, :514, :566, :662`) route through it with an explicit `Intent`; `ReputationSavedData.reconcilePlayer:134` delegates to the same gate rather than owning a second copy of the policy. | Pushing immunity into `CommunityReputationRecord.reconcile`: rejected, that class is a pure state object with no config or immunity-set access (the set lives at `ReputationSavedData.java:58`) and is currently byte-identical across loaders. |
| DD2 | **Freeze = advance `lastReconciledGameTime`, do not advance `decayElapsedTicks`.** Persisted community immunity gets a `frozenSince` clock and a hard no-catch-up guarantee. Global master/decay disable gets the same rule for state touched during the pause, and an honestly documented limit for state that was not (an offline player's clock is stale regardless; decay is world-clock based by design). | Skipping the community entirely while frozen (today's `:146` behaviour): rejected, it guarantees a catch-up decay burst the moment immunity is lifted, which is the opposite of §5 F07's recommendation. |
| DD3 | **Superseded is a flag on `IncidentRecord`, not an `IncidentStatus` member** (`boolean superseded` + typed `UUID supersededBy`), with the `superseded_by` context key still written for back-compat. | Adding `IncidentStatus.SUPERSEDED`: rejected on three counts. §5 F08 asks for a state "separate from apologized/atoned/forgiven/disproven"; all three companions import `IncidentStatus` and an exhaustive switch over it would fail at *runtime*, not compile time; and the enum is serialised by name into both NBT and `IncidentSummary`, so a new constant is an unnecessary wire and save hazard. |
| DD4 | **`createdGameTime` is re-specified as occurrence time; the new field is `appliedGameTime`.** For non-backdated deeds the two are equal, so existing data is unaffected. Backdated deliveries seed `decayElapsedTicks` at creation so the contribution is aged before effects fire. | Adding `occurredGameTime` to `IncidentRecord` *and* `ReputationIncidentView`: rejected, `ReputationIncidentView` is a public record read by Conversations, and §6 forbids "changing existing public record constructors". This inversion makes `IncidentQuery.select`'s existing sort on `createdGameTime` (`api/IncidentQuery.java:78-79`) correct with zero API change. |
| DD5 | **`IncidentDelivery` wraps `ReputationRequest`; no components are added to `ReputationRequest`.** | Appending components to the `ReputationRequest` record: rejected, it moves the canonical constructor descriptor, which §6 explicitly forbids, and Conversations compiles against a *vendored* API jar pinned at `edb8ebd` (`gradle/sibling-apis.properties:16`), so it would run against a shape it never saw. |
| DD6 | **Receipts live per player inside `PlayerReputationRecord`**, keyed by producer namespace + player + community + operation key, budget `MAX_RECEIPTS_PER_PLAYER=512` plus a `receiptRetentionTicks` horizon, evicted oldest-first by occurrence. `receiptFloor()` is published so a producer can tell when an operation predates the horizon and needs explicit recovery (§5 F09). Legacy unnamespaced keys are matched by operation key alone within player+community, and the existing `findByDedupeKey` collision semantics are untouched. | A global receipt table on `ReputationSavedData`: rejected, it would not save, load, prune, or bound with the player it belongs to, and would need its own eviction policy for offline players. |
| DD7 | **Read-only lookups use `Intent.INSPECT`**: no `getOrCreatePlayer`, no reconcile, no community creation, no events. T10 asserts this rather than trusting it. | Reusing the query path with reconciliation suppressed by a boolean: rejected, that is how the seven bypasses happened in the first place. |
| DD8 | **`recordSuperseding(ReputationRequest, SupersedeSpec)` is the single public-accounting seam**, and the native kill path at `ReputationGameplayEvents.java:219-268` is rewritten onto it so there is exactly one implementation. | Leaving the native fold in place and adding a second Crime-facing API: rejected, §5 F05 requires "Native and Crime-owned events should call the same public-accounting seam," and two implementations is precisely the F05 defect. |
| DD9 | **`matches` loses its Conversations gate; Conversations-specific gating stays on `getOpinionBias` and `getCheckBias`.** `StandingAvailability` is the one place the §5 F14 truth table exists. | A config escape hatch to restore the old coupling: rejected, nobody could want a loot condition to depend on a dialogue mod's switch, and an escape hatch would preserve the ambiguity the truth table is meant to remove. |
| DD10 | **Server stop clears server-scoped derived state, not authority registrations.** Add `CoreIncidentAuthority.onServerStopped()` as an additive default so a future companion patch can opt into per-server lifecycle. | Releasing registration handles on stop, as §5 F01 point 5 literally suggests: rejected, companions register once from `FMLCommonSetupEvent` (per JVM), so releasing handles would leave Crime silently unregistered in a second world in the same process. This is a T38 trap that only bites with two worlds. |
| DD11 | **`ModConfigEvent.Reloading`/`.Loading` on the MOD bus**, filtered to this mod's own `ModConfig`, with server-touching work scheduled onto the server thread. It invalidates display caches, opinion caches, effective authority claims, and performs the decay off→on clock skip. | A per-tick config poll in `ReputationGameplayEvents.onServerTick:360`: rejected, polling cannot distinguish "changed" from "always was", and the event is the documented seam. |
| DD12 | **Enriched gossip is a new `GossipStory` type**; `ExternalGossipCandidate` and `gossipCandidate` are untouched. | Adding components to `ExternalGossipCandidate`: rejected, §6 says "old adapters retain their safe baseline behavior," and Conversations reads it from a pinned vendored jar. |
| DD13 | **Scoreboard ownership is proven by criteria + our own display-name marker**, adopted only on both matches; a foreign objective is neither written nor removed, and the refusal is logged once. | Tracking ownership in saved data: rejected, it would not survive an admin deleting the objective out from under us, and the marker is self-describing on inspection. |

---

## 4. Decisions for the user

| # | Decision | Recommendation |
|---|---|---|
| **D1** | Ship this as **0.4.1** or **0.5.0**? | **0.5.0.** A save-format bump, a wire bump, a new API surface, and a gameplay-visible change to `matches` are not patch-shaped. Cost is one line in `gradle.properties` plus moving the existing unreleased CHANGELOG entry. If 0.4.1 is kept, nothing in this plan changes, but the migration note has to work harder. |
| **D2** | **Save format v2 with migration**, or additive optional fields inside v1? | **v2.** §5 F09 and §9 both call for it ("The inspected format is 1; format 2 is a proposed target"), and receipts + the freeze clock + terminal supersession are not cosmetic additions. The NeoForge golden fixture is the only real cost: `GoldenSavedDataCompatibilityTest:83-84` hard-asserts `FORMAT_VERSION == 1` and has no regenerate path. Handle it by **keeping** the v1 fixture as a migration fixture and adding a v2 one; do not delete the assertion, rewrite it. Additive-in-v1 is cheaper today and leaves no place for the future-format read-only latch to be tested. |
| **D3** | Bump `PROTOCOL_VERSION`? | **Yes, but only inside WP7.** Forge `"3"→"4"`, NeoForge `"4"→"5"`. If WP7 is cut for schedule, no bump is needed and WP1–WP6 ship on protocol 3. |
| **D4** | Bump Forge `API_VERSION` 1→2 to match NeoForge? | **No.** Stay at `1`; add `capabilities()` for negotiation. Bumping risks disabling all three integrations if any companion compares with `==` (unverified, Risk R1), and `DIAGNOSIS.md:139-141` records the deliberate precedent for not bumping. |
| **D5** | F09 backpressure: **refuse** a deed with `Reason.CAPACITY` when nothing is evictable, or keep today's over-cap-and-log? | **Refuse.** Silently losing live contribution to satisfy a display cap is the defect §5 F09 names. But it is a gameplay-visible change (in a pathological world a deed genuinely does not record), so it needs an explicit operator diagnostic and a CONFIG/MIGRATION doc line. Log-and-exceed for one more release is the fallback. |
| **D6** | Does **F15's bound-amends action** and the **§7 content pass** (acknowledgment lines, amends templates) ship now? | **No; defer the content, ship the seams.** Reputation ships `resolveBound`, receipt idempotency, and `GossipStory` in WP5/WP6; the dialogue, templates and acknowledgment variations are Conversations work and §4 itself says F15's dialogue and F16 polish "can follow after the underlying contracts pass." |
| **D7** | NeoForge port **per package** or **after all Forge packages**? | **Two batches.** One port pass after WP5 (everything that touches the save format, so the golden fixture is regenerated exactly once), one after WP7. Per-package porting multiplies the re-application cost across two trees with no shared ancestor; a single end-of-line port makes the diff too large to review. |
| **D8** | WP7 touches `client/ReputationClient.java` and `compat/McaScreenCompat.java`, which have **uncommitted edits in the worktree** `.claude\worktrees\modest-knuth-3ecfcf` (plus an untracked `client/ReputationClientRegistration.java`). Land, discard, or work around? | **Resolve before WP7 starts.** That worktree is 3 behind main; its changes are unreviewed and will conflict. The stale `phase-r1-core-authority` / `phase-r1-on-hotfix` / `hotfix/mca-7.7.1-package-rename` branches are superseded by main and can be deleted. |

---

## 5. Out of scope, and what no unit test can settle

**Out of scope for this deliverable:** all four companion repositories (steps E, F, G, and F17's Crime build gates, F04, F06, F11's Quests-side fix); MCA runtime behaviours; the amends dialogue and acknowledgment content (§7.1, §7.2); the addon combination matrix (§10, 16 combinations); the eight built-jar runtime gates (§10); performance measurement and release budgets (§10); rollback tooling beyond the quarantine file and the coordinated-backup procedure (§9); MCAAddonCore (an empty directory).

**Runtime-only; cannot be settled by loader-independent JUnit:**

| Item | Why |
|---|---|
| T01/T02 with the **real** Crime adapter loaded | Needs the packaged Crime jar; the unit test uses a fake legacy authority. |
| T05, T06, T11 crash semantics | Two independently saved mod files are not one transaction (§5 F03 "Crash semantics"). Needs a real server kill between saves. |
| T12–T15 in-game | Damage events, witness line of sight, and NPC-vs-player actor kind. |
| T24–T26, T32, T33 | Quests/Conversations turn-in and dialogue flows. |
| T34/T35 rendering | GUI scale, long names, translated text, foreign scoreboard objectives. |
| T36 datapack reload | Needs a real reload listener + registries. |
| T38 world A → world B | Two servers in one JVM; the DD10 authority-lifecycle trap lives here. |
| Protocol mismatch | An old client against a new server; only a real handshake proves it. |
| §7.3 in full | Sleeping witnesses, cure identity across the MCA conversion chain, raid victory timing, rescue attribution, criminal evidence semantics: all explicitly runtime in the plan doc. |
| MCA binding | `McaReflect` against each supported MCA line, in production remapping. |

These belong in `PRODUCTION_TESTS.md` as scripts, not in `src/test`.

---

## 6. Documents needing updates (documenter agent, later)

| Document | Facts that change |
|---|---|
| `CLAUDE.md` | Line 11 says protocol `"2"`; it is `"3"` today and `"4"` after WP7. Add the new packages, the `ReputationPolicy`/`ServiceContext` test seam, and the DD10 authority-lifecycle rule. |
| `DIAGNOSIS.md` | §4.2 "No protocol change. `PROTOCOL_VERSION` stays `"2"`" is doubly stale. Add a note that `resolveSelection`/`buildSnapshot` gained pagination in WP7 and that the S1b guarantee is preserved. |
| `API.md` | New: `capabilities()`, `deliver`/`IncidentDelivery`/`DeliveryOutcome`, `findReceipt`/`findIncident`/`receiptFloor`, `recordSuperseding`/`SupersedeSpec`, speaker-aware `selectIncidents`/`resolveBySelector`, `resolveBound`, `highWaterTierId(ladder)`, `getVillagerOpinionDetailed`/`OpinionResult`, `gossipStory`/`GossipStory`, `StandingChange`/`ChangeCause`, the three new `ReputationMirror` defaults, the three new `CoreIncidentAuthority` defaults. **API_VERSION stays 1.** The 4-arg `selectIncidents` now fails closed on `knownToSpeaker`. Receipt horizon and the honest non-guarantee of exactly-once delivery (§5 F03 "Acknowledge this limit in API documentation"). |
| `CONFIG.md` | `coreAuthorityUndeclaredKinds`, `receiptRetentionTicks`, and any new bounds. The effect of each integration switch after the F14 degating. |
| `DATAPACK.md` | Occurrence time vs application time vs effective decay time; visibility labels now surfaced in the UI. |
| `MIGRATION.md` | v1→v2: receipt synthesis from retained dedupe keys, freeze-clock initialisation, `superseded_by` → terminal, `appliedGameTime` default, quarantine path, future-format read-only refusal, backup-before-upgrade, rollback limits, idempotent re-migration. |
| `PRODUCTION_TESTS.md` | The runtime-only table in §5 above, plus the addon combination matrix. |
| `CHANGELOG.md` | Keep the existing unreleased API-jar entry; add the reliability track. Per §1, **split the claims**: what Reputation fixed alone vs what needs a companion patch. |
| `README.md` | Capability negotiation, the producer for each native kind, current tier vs high-water milestone. |
| `MODMAP.md` | Regenerate with `.mcmod-tools/modmap.py` after WP7 (rewrites everything above `AUTO:END`). |

---

## 7. Risks and unknowns

| # | Risk | Status |
|---|---|---|
| R1 | Do the three companions compare `getApiVersion()` with `==` or `>=`? `DIAGNOSIS.md:139-141` implies equality for Quests. | **Unconfirmed.** Drives D4. Cheap to settle: grep `REQUIRED_API_VERSION` in the three companion trees. Until settled, do not bump. |
| R2 | Does any companion have an **exhaustive switch expression** over `IncidentStatus` or `ReputationResult.Reason`? A new constant would be a runtime `MatchException`, not a compile error. | **Unconfirmed.** DD3 sidesteps it for `IncidentStatus`; `Reason.CAPACITY` (D5) still needs the check. |
| R3 | Does anything outside Reputation **construct** `ReputationIncidentView` or `ExternalGossipCandidate`? | **Unconfirmed.** DD4 and DD12 both avoid touching their constructors, so this is only a fallback concern. |
| R4 | `LevelResource.ROOT` / `MinecraftServer.getWorldPath` for the quarantine write; `Objective#getDisplayName()` for the ownership marker. | **Unconfirmed by `find_api.py` this session.** Both are standard 1.20.1, but confirm before use. The tool returned false negatives for `SavedData.save`, `DimensionDataStorage.computeIfAbsent`, `CompoundTag.getAllKeys` and `MinecraftServer.overworld` this session, so a "not found" is not evidence of absence for a symbol already compiling in this project. |
| R5 | Does `ModConfigEvent.Loading` fire before a `MinecraftServer` exists? Almost certainly yes. | The listener must tolerate a null server and defer server-touching work. Design already assumes this. |
| R6 | The plan doc's Crime anchors are stale: `CrimeDetector.commitNpc` is actually `IncidentService.java:96`; the doc's reviewed Quests commit `724b07f` is **not an ancestor** of Quests' current HEAD `af372b6`. | Known. Re-check any companion claim against current HEAD before writing a companion patch; does not affect Reputation-side work. |
| R7 | One scout read the NeoForge branch at a stale remote ref (`8e0ac5e`) and reported "no apiJar" and protocol `"2"`. Both wrong. | Superseded by the follow-up read of the real checkout (`b5c09ca`, clean, apiJar present, protocol `"4"`). |
| R8 | `McaReflect` diverges 220 lines between trees and is not purely mechanical. | Nothing in this plan changes reflection behaviour. Do not port it. |
| R9 | The scale of WP3–WP5 against a "patch release" label. | This is the honest reason behind D1. Steps C and D are rated "Large" each in §8, and they are the two packages nobody can safely defer once the format moves. |
| R10 | `TestServiceContext` has no config stub today, so several planned invariants are untestable until WP1 lands. | Mitigated by making WP1 the first package. If WP1 is skipped, WP3/WP5 tests either become loader-dependent (violating CLAUDE.md) or silently assert defaults only. |

**Verification command after every package** (from `C:\Projects\MCAReputation`):

```
C:\Projects\.mcmod-tools\gradlew-quiet.ps1 -Project MCAReputation -Task compileJava
C:\Projects\.mcmod-tools\gradlew-quiet.ps1 -Project MCAReputation -Task check
```

`check_mod.py MCAReputation` after WP7 only (it is the package that adds lang keys). `checkJarContents` before any release candidate.
