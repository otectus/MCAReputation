# DIAGNOSIS — "25 more to acquaintance" and deliveries that never register

Report, verbatim:

> "I'm having trouble with the 'checking' of stuff like quest items and reputation, no matter what I
> do I have '25 more to acquaintance' and my rank is 'stranger', additionally delivery quests don't
> seem to be registering."

- **S1** — standing never moves; the tier is the floor tier and the "N more to X" figure is frozen.
- **S2** — delivery-objective progress never increments on a successful delivery.

## Verdict up front

**Two bugs, not one, and they live in two different repositories.** They are not a shared choke
point: the two pipelines share no hop between them except the MCA binding, and the MCA binding is
verified working (see *Ruled out*, §4.1). What they share is a *defect class* — a question asked in
several places, answered at several different times, with only some of the answers written down.

| | Root cause | Repo | Hop |
|---|---|---|---|
| **S1a** | Only 10 of the 262 bundled quests award any standing, and `QuestManager.grantQuestReputation` returns silently for the other 252. Nothing uses the documented `reputation` block at all. | **MCA: Quests** | Trigger / mutation |
| **S1b** | The standing screen's unprompted selection preferred *the village at the player's feet* over *a village the player has a record with*, and `buildSnapshot` synthesises a floor-tier detail (score 0 → Stranger → "25 more to Acquaintance") for a community with no record. With one real community the selector drew no arrows, so there was no way to navigate to it. | **MCA: Reputation** | Sync / display (store drift: read key ≠ write key) |
| **S2** | `VillagerTarget.matches` re-evaluates the *selection* predicate (`require`) at credit time. For the four bundled `require: "nearby"` deliveries that predicate is `loaded && within 12 blocks **of the quest giver**` — which is false precisely when the player has walked to the recipient to hand the goods over. It only bites when the accept-time binding did not take, and since 1.4.3 offers are cached, so the gap between the gate and the accept is now arbitrarily long. | **MCA: Quests** | Target/identity match |

S1a is sufficient on its own to produce the reported sentence for a quest-focused player. S1b is a
second, independent way to produce the *same* sentence, and it is the one that would have kept the
number invisible even after S1a was fixed. Both are fixed.

---

## 1. Step 1 — is the server value wrong, or is the client display stale?

Answered before anything else, per the brief. Result: **(c) store drift, plus (b) for the quest
source specifically.** Neither is a stale-client bug.

There was no way to answer this from inside the game, which is why the report needed a user to find
it. `/mcareputation debug standing` (new, see §6) now prints the raw stored value, the tier and next
tier derived from it, the exact remaining amount, *which community the screen would open on*, and
whether the player has a record there — the last two being exactly the pair whose disagreement is
S1b.

Method used here, in the absence of a runnable game in this environment:

- **S1, server value:** `ReputationService.recordWith` was driven directly against the in-memory
  store through the existing `ServiceContext` seam (`StandingPipelineTest`). Given a request with a
  non-zero delta, the stored value moves, crosses the tier boundary at the documented threshold, and
  survives a `roundTripForTest()` save/load. The transaction is **not** broken.
- **S1, whether a request is ever made:** traced from `QuestManager.completeQuest` →
  `grantQuestReputation`. It returns before building a request whenever the quest declares no
  outcome. Then, by inspection of the shipped datapack: 252 of the 262 quests declare none.
- **S1, read key vs write key:** traced `RequestSnapshotC2S.resolveSelection` →
  `ReputationNetwork.buildSnapshot` → `emptyDetail`. The read key is the nearest village to the
  player's feet; the write key is the home village of the villager the deed was about. When they
  differ and the player has no record at the read key, the reply is a synthesised score-0 detail.
- **S2:** traced the whole `EntityInteract` → credit path (below). The mutation, persistence and sync
  hops are all sound; the failure is at the predicate/identity hop.

---

## 2. Step 2 — pipeline traces

### S1 — a deed becomes a number on the screen

| # | Hop | Where | Status |
|---|---|---|---|
| 1 | **Trigger** — quest turn-in | `QuestManager.completeQuest` → `grantQuestReputation` (MCA: Quests) | ✅ fires |
| 2 | **Predicate** — is there an outcome to record? | `def.reputation().completeOutcome()`, else the sum of `mcaquests:village_reputation` rewards; `if (legacyAmount == 0) return;` | ❌ **BROKEN** — empty for 252 of 262 bundled quests, and the return is silent |
| 2b | Alternative triggers | `ProjectReputation`, `SituationManager`, `RecordIncidentReward`, core assault/kill in `ReputationGameplayEvents` | ✅ these *do* author outcomes (20 project files carry `reputation`) |
| 3 | **Backend selection** | `ReputationBridge.init()` from `McaQuests.onCommonSetup`; `CanonicalReputationBackend` when `mcareputation` is loaded, else `LegacyReputationBackend` | ✅ verified; logs its choice at INFO |
| 4 | **API entry** | `CanonicalReputationBackend.award` → `McaReputationApi.record` → `integrationEnabled("mcaquests")` → `ReputationService.record` | ✅ toggle defaults `true` |
| 5 | **Mutation** | `ReputationService.recordInternal` steps 7–8, on the live `CommunityReputationRecord` | ✅ covered by `StandingPipelineTest` and the pre-existing `ReputationServiceTest` |
| 6 | **Persistence** | `data.setDirty()` (step 11); `ReputationSavedData.roundTripForTest()` | ✅ covered |
| 7 | **Sync** | `RequestSnapshotC2S` → `buildSnapshot` → `SnapshotS2C` → `ClientReputationData.acceptSnapshot`; every screen-open path requests first | ✅ not stale — `ReputationClient` keybind, the MCA-screen button and `ClientReputationData.openScreen` all send a request |
| 7b | **Sync — *which* community** | `resolveSelection` positional fallback + `emptyDetail` synthesis | ❌ **BROKEN** — read key can be a community with no record while a real one exists |
| 8 | **Display** | `ReputationScreen.renderProgress` reads `detail.score()`, `detail.tierThreshold()`, `detail.nextThreshold()` — all from the one server-sent `SelectedDetail` | ✅ one source; the arithmetic is `Math.max(0, nextThreshold - score)`, no scale or integer-division defect |
| 8b | **Display — reachability** | `if (communities.size() > 1)` gated the selector arrows | ❌ **BROKEN** — an off-list selection plus one real community drew no arrows |

The literal on-screen strings are `mcareputation.screen.tier` (`"%s"`) and
`mcareputation.screen.progress` (`"%s more to %s"`), both in
`assets/mcareputation/lang/en_us.json`, both filled from `SelectedDetail`. The threshold table is
`data/mcareputation/mcareputation/reputation_tiers/default.json`: `stranger` at 0, `acquaintance` at
25 — which is where the reported "25" comes from, and it is *correct arithmetic over a score of 0*.

### S2 — a hand-over becomes progress

| # | Hop | Where | Status |
|---|---|---|---|
| 1 | **Trigger** | `QuestProgressEvents.onTalkToVillager` on `PlayerInteractEvent.EntityInteract`, `@Mod.EventBusSubscriber(modid)` — Forge bus, both sides guarded | ✅ registered on the right bus; server-side guard and MAIN_HAND guard both correct |
| 2 | **Predicate — is it an MCA villager** | `McaCompat.isMcaVillager` → `McaHandles.isVillager` → `McaBinding` | ✅ `McaBindingProbeTest` replays the manifest against 7.6.20, 7.7.0-beta.2 and 7.7.1-alpha.2 |
| 3 | **Objective sweep** | `forActiveObjectives(player, DeliverToVillagerObjective.class, …)` over the live `PlayerQuestData` | ✅ live objects, not copies (`PlayerQuestData.active()` and `ActiveQuest.progress(int)` both return the stored instances) |
| 4 | **Suspension gate** | `objective.unavailableReason(...)` → `ObjectiveSupport.boundTargetLost` | ✅ returns empty while the recipient is loaded, which they are at hand-over |
| 5 | **Target/identity match** | `ObjectiveSupport.matchesLocked` → `VillagerTarget.matches(candidate, …, bound)` | ❌ **BROKEN for `mode: family` when unbound** — see below |
| 6 | **Payload check** | `ObjectiveSupport.countMatching(player, item) >= itemCount`; `ItemTarget.matches` | ✅ counts the whole inventory, exact item or tag |
| 7 | **Mutation** | `progress.setCount(1)` on the live `ObjectiveProgress` | ✅ |
| 8 | **Persistence** | `ActiveQuest` is serialised with the `PlayerQuestData` capability | ✅ |
| 9 | **Sync** | `QuestProgressEvents.onPlayerTick` → `QuestManager.checkReadyTransitions` + `syncLog` once per second | ✅ — but the interact path does not `settleProgress()` the way `creditTalk` does, so credit lands up to a second late (cosmetic, noted, not the bug) |
| 10 | **Display** | `QuestManager.objectiveLines` sends `objective.current(player, progress)` as a number in `CardObjective` | ✅ reads the same progress the credit writes |

**Hop 5 in detail.** Four of the twenty-one bundled `deliver_to_villager` quests bind
`"recipient": {"mode": "family", …, "require": "nearby"}` — *a meal for mother*, *a warm meal*,
*a child's first toy*, *teach them to fish*. `RelativeCandidate.matches("nearby")` is
`isAlive() && nearby`, and `nearby` is computed in `McaCompat.describeRelative` as:

```java
loaded && entity.distanceToSqr(giver) <= INTERACT_RANGE_SQR   // 12 blocks — from the GIVER
```

`VillagerTarget.matches`'s `FAMILY` branch, when `progress.targetUuid()` is null, re-runs
`candidates(giver, level)` — which re-applies that filter **at the moment of the hand-over**. So the
credit test is "is the recipient standing within 12 blocks of the quest giver right now?", asked at
the one moment the player is guaranteed to be somewhere else. Every other mode compares identities
(`SELF` → `active.villagerUuid()`, `UUID` → the declared id, `SITUATION_FOCUS` → the focal id);
`FAMILY` alone re-runs a *selection query* as an *identity test*. That asymmetry is the bug.

It bites only when the accept-time binding did not take, and 1.4.3 made that far more likely: offers
are now drawn once and remembered for `offerRefreshTicks`, so the gate
(`VillagerTargeted.unofferableReason`) can pass minutes or hours before `bindVillagerTargets` runs at
accept and re-asks the same transient question. When the answer has changed, `selectRelative` returns
empty, **nothing is bound, nothing is logged**, and the objective sits at 0/1 for the rest of its
life with no reason line.

Three evaluations of one time-varying predicate at three different times, and only the middle one
records its answer. `RelativeCandidate`'s own javadoc claims the opposite — "the gate, the
offer-time resolvability check, the accept-time binder, `matches` and the display name all filter the
*same* candidate list with the *same* predicate, so they cannot disagree". Same predicate, different
instants: the code does not provide the guarantee the comment asserts, so per ground rule 3 the
comment is corrected in the same commit.

---

## 3. Step 3 — ruled in / ruled out, with evidence

### 3.1 Two-backend drift — **ruled out**

MCA: Quests has two: `LegacyReputationBackend` (its own store) and `CanonicalReputationBackend`
(delegating to this mod). `ReputationBridge.init()` *is* called — `McaQuests.onCommonSetup` line 69,
`event.enqueueWork(ReputationBridge::init)` — and it logs its choice at INFO either way
("MCA: Reputation detected; village standing, tiers, and titles now delegate to it"). The version
handshake matches: `REQUIRED_API_VERSION = 1` and `McaReputationApi.getApiVersion()` returns 1 (0.3.0
deliberately did not bump it; see `registerCoreIncidentAuthority`'s javadoc). Writes and reads both
go through `QuestReputation` → `ReputationBridge.backend()`, so they cannot land on different
backends. Not the cause.

### 3.2 Two-store drift generally — **RULED IN (S1b)**

Not a client cache problem: `ClientReputationData` is populated by `SnapshotS2C`, and every path that
opens the screen sends a `RequestSnapshotC2S` first (`ReputationClient.onClientTick` keybind,
`openFromInteraction`, `ClientReputationData.openScreen`, and the server-pushed
`openScreenWithSnapshot`). The screen also rebuilds on `acceptSnapshot`. The drift is **server-side
and one level up**: the *key* the reply is built for. See §2 hop 7b.

### 3.3 Zero-source problem — **partially ruled in (S1a)**

Not *all* sources are dead, so the choke point is not downstream of all of them:

| Source | Ships content that awards standing? |
|---|---|
| Quests (262 files) | ❌ **10 of 262** — all via the legacy `village_reputation` reward, 8–12 points each; **0** use the documented `reputation` block |
| Projects (20 files) | ✅ yes |
| Situations | ✅ yes (`SituationManager` line 326) |
| Core assault / kill | ✅ yes (`villager_assaulted` −8, `villager_killed` −40) |
| `record_incident` reward | ✅ available to packs |

And the shipped pack barely reaches its own ladder. **Seven bundled quests are gated on village
standing**, and *six of those seven are themselves among the ten that grant it* — the Townstead
capstones gate on a threshold higher than they pay. That leaves roughly four ungated quests worth
about 42 points between them, against Acquaintance at 25, Friend at 75, Honored at 150 and Revered
at 300. A player working through ordinary village quests earns nothing from 96% of them, which is
exactly the shape of "no matter what I do". It is also the same defect class 1.5.0 just fixed for
`whyNothingIsOffered` ("None did. Not one of the 262 bundled quests").

### 3.4 Threshold / format bug — **ruled out**

`ReputationTierSet.tierFor` and `nextTier` are pure and covered by `TierTest`;
`ReputationSnapshot.pointsToNextTier()` is `max(0, nextThreshold − score)` over the same `score` the
tier was read from; the screen recomputes the same expression from the same `SelectedDetail`. No
integer division, no scale factor, no comparison against a constant. `ReputationTiers.getDefault()`
falls back to `BUILTIN_DEFAULT`, whose thresholds equal the shipped `default.json`, so a failed
datapack load cannot substitute different numbers.
`StandingPipelineTest.theDistanceToTheNextTierDerivesFromTheSameScoreTheTierDoes` sweeps the ladder
to keep it that way. The reported "25" is *correct output for a score of 0*.

### 3.5 Identity comparison — **RULED IN (S2)**

See §2 hop 5. Not UUID-vs-entity-id and not a stale bound UUID: the bound UUID path is correct. The
defect is a target that was **never bound**, falling through to a re-run of a transient selection
query.

### 3.6 Dead config key / dead datapack field — **ruled out as a cause, but this is the class**

Checked every gate on the traced paths:

- `McaReputationConfig.enableQuestsIntegration` / `enableReputationTiers` / `enableReputation` — all
  default `true`, all read.
- `requireOriginalVillagerForTurnIn` was dead until MCA: Quests `9f4557f` and is now read by
  `TurnInSpec.mode()`; at its shipped default of `true` it answers `ORIGINAL_GIVER`, which is what
  the mod hardcoded before. Verified in code, not taken from the commit message.
- `defaultVillageSearchRadius` (128) is read by `CommunityResolver.resolveNearest` — and is precisely
  what makes S1b reachable, since it will find a village 128 blocks away that the player has never
  dealt with.

The *dead field* here is not a config key but a **datapack field with no shipped users**: the
`reputation` block on a quest, documented at length in DATAPACK.md and used by exactly zero of the
262 bundled quests.

### 3.7 Sided registration — **ruled out**

`ReputationGameplayEvents`, `QuestEventHandlers`, `QuestProgressEvents` and `ProjectProgressEvents`
all carry `@Mod.EventBusSubscriber(modid = …)` on the Forge event bus with no `Dist` restriction, and
each bails on a client level. `ReputationNetwork.register` and `ReputationBridge.init` are both
enqueued from `FMLCommonSetupEvent`. `ReputationClient` is `Dist.CLIENT` and is only reached through
`DistExecutor`, which `OptionalClassloadTest.serverSideClassesDoNotReferenceTheClientPackage`
enforces.

### 3.8 Silent catch — **ruled in as an *observability* defect, not as the cause**

The traced paths are lined with `catch (Throwable)` safe defaults, and every one of them was checked:

- `McaCompat` (both mods) logs at DEBUG behind `debugLogging`, and one ERROR per JVM on a
  `LinkageError`. **Verified not firing** for the reported setup: every one of the 21 members
  `McaReflect` resolves was checked with `javap` against the actual MCA 7.7.1-alpha.2 jar
  (`forge/net/conczin/mca/**`) and all 21 resolve, so `McaReflect.AVAILABLE` is true. Same for
  MCA: Quests, whose `McaBindingProbeTest` replays its manifest against three MCA builds in CI.
- `ReputationService.recordWith`'s outer `catch (Throwable)` logs at ERROR with player and incident
  type. Not silent.
- `QuestReputation.award`'s `catch (Throwable)` logs at ERROR. Not silent.

The genuinely silent paths are plain `return` statements, not catches, and they are the ones that hid
both bugs: `grantQuestReputation`'s "nothing authored" return, `bindVillagerTargets`' failed bind, and
`DeliverToVillagerObjective.onInteract`'s "target did not match" return. All three now log once, with
identifying context, behind `debugLogging` where they are per-interaction.

---

## 4. What was checked and found sound

### 4.1 The MCA binding — the only hop both symptoms share

If `McaReflect` had failed to resolve, MCA: Reputation would stop detecting deeds *and* stop resolving
communities, and MCA: Quests would stop recognising villagers — a genuine single choke point for both
symptoms, and the live hypothesis the brief asks to test. It is not what happened. Checked by
extracting `minecraft-comes-alive-reborn-7.7.1-alpha.2+1.20.1.jar` and running `javap` over
`forge/net/conczin/mca/**`: the package root probe matches, and all eight classes and all thirteen
methods `McaReflect` names resolve with the exact signatures it asks for, including the two
overload-sensitive ones (`Village#getResidents(ServerLevel)` and
`VillageManager#findNearestVillage(BlockPos, int)`). `AVAILABLE` is therefore true and no
`LinkageError` latch trips. Note also that a dead binding could **not** have produced S1's frozen
display: `resolveNearest` would return empty and the selection would fall through to the player's
best-known community — the correct answer.

### 4.2 Invariants preserved

- **Server authority.** No client-supplied value gained trust; `resolveSelection` still validates a
  requested community against the store and a context entity against dimension, type and distance.
- **Compat-layer isolation.** `OptionalClassloadTest` (4 checks: no MCA imports, no companion-mod
  references in compiled classes, both companions optional in `mods.toml`, no client references from
  server classes) still passes. The new `SnapshotSelection` and `SelectorMath` name no MCA type.
- **Fail-closed defaults.** Nothing was loosened. `SnapshotSelection.unprompted` still answers "you
  are a stranger here" when that is genuinely true; it just no longer answers it when it is not.
- **No protocol change.** `PROTOCOL_VERSION` stays `"2"`; no packet shape moved. The diagnosis did
  not require it.

---

## 5. Step 5 — proof

### Regression tests (fail on the pre-fix tree, pass after)

MCA: Reputation, `src/test/java/dev/otectus/mcareputation/`:

- `reputation/StandingPipelineTest` — an award of N moves the stored value by exactly N; the ladder
  crosses at the documented threshold of 25 inclusive; standing survives a save/load round trip; the
  "distance to next tier" figure is swept against the tier lookup over the whole ladder so the two
  can never be read from different numbers; and four cases pinning the read key against the write key
  (`theScreenNeverPrefersAVillageWithNoRecordOverOneWithAHistory` is the store-drift guard, and fails
  on the old ordering).
- `client/SelectorMathTest` — the selector's reachability and index arithmetic, including
  `oneCommunityIsStillReachableFromAnOffListSelection`, which fails on the old
  `communities.size() > 1`.

All loader-independent, in the style of `ScrollMathTest` and `ReputationMathTest`: no server, no
level, no registries.

### Manual script

See `PRODUCTION_TESTS.md` → *"Standing that does not move, and deliveries that do not register"*,
including the dedicated-server run with a separate client, since S1b's symptom is identical in
singleplayer and multiplayer but only a second client proves the sync half.
