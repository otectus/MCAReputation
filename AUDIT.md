# AUDIT — MCA: Reputation, 2026-09-30

Scope: the Forge 1.20.1 tree at `MCAReputation/` (branch `feature/0.6.0-profiles`), with the fixes
carried to the NeoForge 1.21.1 port at `1.21.1 Ports/MCAReputation_1.21.1` in the same job (§7a).
MCA: Quests and MCA: Conversations were read for their call sites and not edited. Everything below is from source,
the test suite and two dev launches; no in-game session was driven (see *Not verified*).

## 1. What the mod does (as understood from code, config, lang and data)

- **Tracked against:** one player × one *community*, a dimension-aware MCA village
  (`community/CommunityKey` = dimension + MCA village id). Not per villager and not per faction. A
  villager's personal opinion is derived from the community ledger, filtered to what that villager
  witnessed, was involved in or heard as rumour (`reputation/OpinionResolver`).
- **Store:** one `SavedData` on the overworld (`state/ReputationSavedData`), keyed by player UUID, so
  there is no capability and nothing to copy on `PlayerEvent.Clone`. Score =
  `clamp(baseline + Σ current incident contributions, -1000..1000)`; the baseline carries admin
  `set`/`add` and legacy imports.
- **Sources:** six core deeds (`event/ReputationGameplayEvents`, `event/ReputationDeedEvents`):
  assault −8 and killing −40 (witnessed; a killing absorbs the assault before it), rescue +6 and cure
  +15 (witnessed), raid victory +20 (village-wide), PvP killing −12 (off by default). Companion
  deliveries through the API: MCA: Quests (quest completion at the authored amount, or 2/4/7 by
  difficulty; projects; situations; promises), MCA: Conversations (apology, gossip-driven deeds),
  MCA: Crime (its own crimes, via core-incident authority claims and exemptions). Admin commands.
- **Tiers** (`data/mcareputation/mcareputation/reputation_tiers/default.json`, inclusive thresholds,
  floor catches everything below): infamous −300, hated −150, distrusted −75, wary −25, stranger 0,
  acquaintance 25, friend 75, honored 150 (grants `mcaquests:honored_of_village`), revered 300 (grants
  `mcaquests:revered_of_village`).
- **Decay:** per incident, `linear_to_zero` after a delay: assault and rescue 2/day after 2 days, raid
  1/day after 14 days, PvP 2/day after 2 days, apology 1/day after 1 day. Quest completion, cure and
  killing do not decay. Negative incidents can also be softened by resolution (apology, atonement,
  forgiveness).
- **What tiers unlock:** tier titles, a ±8-capped trust/respect check bias in MCA: Conversations, quest
  gates in MCA: Quests (through `matches` and the tier reads), the `mcareputation:standing` loot
  condition, the `mcareputation:tier_reached` advancement trigger, and per-villager opinion.
- **Relation to MCA hearts:** independent by design. Hearts stay MCA's private affection economy;
  this mod never writes them (the `McaReputation` class doc, spec §4).

## 2. The known issue

> Standing doesn't visibly update: the tier display stays frozen at the floor tier, and the "N more
> to next tier" counter never changes. Delivery quest progress in MCA: Quests may be failing for a
> related reason.

**Not reproducible in the current code.** This is the report `DIAGNOSIS.md` investigated on
2026-09-02. Its three root causes are fixed, and each fix is still present in today's trees:

| Cause (DIAGNOSIS.md) | Fix, verified in current source |
|---|---|
| S1a: 252 of 262 bundled quests awarded no standing | MCA: Quests `QuestManager.grantQuestReputation` (`QuestManager.java:1460`) now applies a per-difficulty default, `easy/medium/hard QuestReputation` = 2/4/7 (`McaQuestsConfig.java:304-314`). Reputation's `quest_completed` accepts overrides up to ±100 (`max_override_abs`). |
| S1b: the screen opened on the village at the player's feet, which had no record, instead of the one that did | `network/SnapshotSelection.unprompted`, covered by `StandingPipelineTest` |
| S2: delivery credit re-ran the giver-relative `nearby` selection at hand-over | MCA: Quests `VillagerTarget.matches` FAMILY branch now tests identity only (`VillagerTarget.java:332-336`) |

The questions the brief asks to settle first:

- **Singleplayer, dedicated server or both?** The historical S1b symptom was identical in both. Of the
  display faults found in this audit, M1 is specific to dedicated servers (it needs jitter or TPS
  below 20) and M2 happens in both.
- **Does any source work?** Yes. All six core deeds and every API delivery reach the one transaction
  (`ReputationService.commit`). `StandingPipelineTest` and `ReputationServiceTest` show the stored
  value moves by exactly the delta, crosses tiers at the documented thresholds, and survives a
  save/load round trip.
- **Is the server value wrong, or only the display?** The server value is right. The remaining
  faults were in sync and display (M1, M2) and in *which* value other readers saw (M3, M4).
  `/mcareputation debug standing` now also prints what the client was last sent, so on a live server
  the two cases can be told apart in one command.

## 3. Pipeline traces (trigger → predicate → identity → mutation → persistence → sync → display)

| Source | First hop that could break | Status |
|---|---|---|
| Assault | predicate: `minimumIncidentDamage`, attribution, self-defence window; claimed by MCA: Crime when installed | sound |
| Killing | identity: folds the assault in the coalesce window via `recordSuperseding` | sound |
| Rescue / cure | predicate: the mob must be targeting a villager, or have struck one within 5 s, inside witness range; the surviving villager is always its own witness (`WitnessResolver.java:58-60`) | sound |
| Raid | identity: `level.getRaidAt(player position)` needs the hero within 96 blocks | sound as authored (see Q3) |
| PvP | off by default | sound |
| Quests / Conversations / Crime deliveries | receipt and dedupe before the ledger; integration toggles per namespace | sound |
| Decay | persistence → sync: **reads absorbed decay without publishing it** | **M3, fixed** |
| All sources | display: API score reads **skipped decay**; request **dropped under lag**; push-open **lost its village** | **M4, M1, M2, fixed** |

Mutation, persistence (`setDirty` on every mutating path), tier bookkeeping (high-water mark, titles
granted exactly once) and the snapshot encoding were all traced and are sound.

## 4. Findings

| # | Severity | Location (pre-fix) | Issue | Fixed |
|---|---|---|---|---|
| M1 | Major | `network/ReputationNetwork.java:188-191`; `client/RequestThrottle.java:24-25,66-73` | Server silently dropped snapshot requests inside a 10-tick window measured on a different clock than the client's pacing; on a laggy or jittery dedicated server the screen stayed on the previous village | Y |
| M2 | Major | `client/ClientReputationData.java:90-95`; `client/ReputationScreen.java:306` | Journal "View Deeds" push-open re-requested unnamed; the reply replaced the named village with the unprompted one | Y |
| M3 | Major | `reputation/StandingAvailability.java:97`; `reputation/ProfileService.java:172,234`; `api/McaReputationApi` `matches` and the profile reads; `command/ReputationCommand.java:801` | Reads aged the ledger and persisted the result without publishing it: mirrors, the standing outbox (MCA: Crime / Ultima Kingdoms consumers) and the scoreboard never saw that decay step, and the next sweep had nothing left to report | Y |
| M4 | Major | `reputation/ReputationService.java:1929` via `McaReputationApi.getScore`/`getScoreOrZero`/`getTierId`/`getCheckBias` | Returned the stored, un-aged score; MCA: Quests' Journal and tier gates and MCA: Conversations' check biases could disagree with the screen (§15.1 promises otherwise) | Y |
| M5 | Major (dev only) | `build.gradle` run configs | No `mixin.env.remapRefMap`: MCA's own mixin failed to apply in userdev, so `runClient`/`runServer` crashed before loading this mod | Y |
| m1 | Minor | `reputation/ReputationService.reconcileCommunity` | Aged the store from any thread (no server-thread guard, unlike every other aging read) | Y |
| m2 | Minor | `command/ReputationCommand.debugStanding` | Could not show what the client was last sent | Y |
| m3 | Minor | `state/ReputationSavedData.reconcilePlayer` javadoc; `RequestThrottle`/`ClientReputationData` docs; `DIAGNOSIS.md` §2 hop 7 and §3.2 | Claimed things that were or became untrue (the "only entry point"; "server drops"; "openScreen requests first") | Y |
| m4 | Minor | `StandingAvailability.of`, `ProfileService` QUERY reads | Still age a record from whatever thread calls them directly; every API path now reaches them on the server thread after the publishing reconcile | N (documented) |
| m5 | Minor | MCA: Quests `JournalService.java:55,101` | Journal tier and "next" come from Quests' own legacy ladder (no negative rungs), not the canonical one | N (sibling, Q2) |

No compile errors, side-safety violations, wrong-bus handlers, unread config keys (61 of 61 read),
missing or orphaned lang keys, TODO/FIXME markers or version-trap APIs were found.
`check_mod.py` reports 0/0/0. `OptionalClassloadTest` still enforces client/server and MCA isolation.

## 5. Root causes and fixes (Major findings)

**M1.** The client paces requests to 10 ticks on `Minecraft.level.getGameTime()`, and the server
enforced 10 ticks on `server.overworld().getGameTime()` and *discarded* anything inside that window.
Network jitter packs two requests closer together than they were sent, and a server below 20 TPS
counts fewer ticks than the client. Either way the request vanished, the client timed out after 60
ticks, and `selectionStale` kept the previous selection's header, tier and progress on screen.
*Fix:* `network/RequestPacing` keeps the one-answer-per-10-ticks bound but defers the newest request
inside the window instead of dropping it. `ReputationFeedback`'s existing end-of-tick flush answers it
when the window closes. Nothing is polled; a request that arrived is answered late, never not at all.
The deferred answer is wrapped in its own try/catch. It runs on the server tick, where an escaping
exception would stop the tick loop; the immediate path is already contained by `enqueueWork`'s future.

**M2.** `openScreenWithSnapshot` sends the named village's snapshot and then `OpenScreenS2C`, in order,
on one channel. `ClientReputationData.openScreen()` then sent `request(0, empty)`, which the server
resolves "unprompted" (the village at the player's feet or their best village), and that reply
replaced the one just pushed. *Fix:* the push path sends no request. The screen's one-shot
"nothing cached" request now also requires that no selection is cached, since a push for a village
with no record arrives as a selection with an empty list.

**M3.** `ReconciliationService.reconcile` with `QUERY` runs `record.reconcile` → `refreshScoreOnly` →
`bumpRevision` and persists the result (`ReconciliationService.java:163-205`), but publishes nothing;
publishing is `ReputationService.publishReconcile`'s job. `StandingAvailability.of` and `ProfileService`
called the gate directly. *Evidence*, from a throwaway test run through the pre-fix calls: an
assault worth −30 fully decayed after one day; `StandingAvailability.of` returned score 0 with **0**
events posted and **0** outbox entries, and the following `reconcileWith` sweep returned `false`.
The Distrusted → Stranger step was never published anywhere. *Fix:* the API calls
`ReputationService.effectiveStanding` for `matches` and `reconcileForRead` (the publishing, guarded
`reconcileCommunity`) before every profile read. This copies the pattern `getVillagerOpinionDetailed`
already used. `/mcareputation top` now uses `ReputationService.reconcile`.

**M4.** `ReputationService.score` reads the stored field; `snapshot`, `knownCommunities` and the screen
reconcile first. *Evidence:* same test, raw stored −30 against a reconciled 0. *Fix:*
`McaReputationApi.getScore` → `ReputationService.currentScore`, which reconciles through the
publishing gate on the server thread and then reads. `getScoreOrZero`, `getTierId` and
`getCheckBias` inherit it. The signatures are unchanged. The behaviour now keeps the §15.1 promise
quoted in `ReputationService.knownCommunitiesWith` ("no read path shows a stale, un-decayed
number"), which the snapshot reads already kept. Affected call sites, none needing a change:
MCA: Quests `CanonicalReputationBackend.java:205,229` (`score`, `villageScores`, feeding the Journal,
`VillageReputationCondition` and `ReputationTierCondition`); MCA: Conversations' `getScoreOrZero` ×2,
`getTierId` and `getCheckBias`; MCA: Crime's `getScore`.

**M5.** MCA's refmap names SRG members (`m_237524_`); userdev runs on official names. MCA: Quests and
MCA: Conversations already set the two remap properties; this repo did not.

## 6. Changes by file

- `network/RequestPacing.java` (new): lossless per-player pacing, pure and unit-tested.
- `network/ReputationNetwork.java`: pacing replaces the drop map; `answer`, `flushDeferredRequests`
  (each deferred answer contained), `SentSnapshot`/`lastSent`, `sendSnapshot`; `forget`/`clearAll`
  clear both maps.
- `network/ReputationFeedback.java`: end-of-tick flush calls `flushDeferredRequests` first.
- `client/ClientReputationData.java`: `openScreen` sends no request; class doc corrected.
- `client/ReputationScreen.java`: one-shot request only when no selection is cached.
- `client/RequestThrottle.java`: docs corrected.
- `reputation/ReputationService.java`: `reconcileCommunityWith` (server-thread guard), `currentScore`,
  `effectiveStanding` (+ `...With` seams).
- `api/McaReputationApi.java`: `getScore` → `currentScore`; `matches` → `effectiveStanding`;
  `reconcileForRead` before `getProfileDetailed`, `getVillagerProfileDetailed` (both), `matchesProfile`,
  `matchesSpeakerProfile` (both).
- `command/ReputationCommand.java`: `top` reconciles through the service; `debug standing` prints
  "client last sent".
- `state/ReputationSavedData.java`: `reconcilePlayer` javadoc says what it is (raw, unpublished).
- `build.gradle`: refmap remap properties on every run config.
- Tests: `network/RequestPacingTest` (10), `reputation/ReadPublicationTest` (6); wording in
  `client/RequestThrottleTest`.
- Docs: `API.md` (live reads reconcile and publish), `DIAGNOSIS.md` (2026-09-30 addendum),
  `MODMAP.md` (regenerated; Decisions and Known issues).

No registry id, NBT key, config key, packet id, protocol version (`"5"`) or save format (`4`) changed.
No public API signature changed.

## 7. Verification

| Check | Command | Result |
|---|---|---|
| Baseline tests | `gradlew-quiet.sh MCAReputation check` | PASS, 842 tests, 0 failures, 1 skipped |
| Compile | `gradlew-quiet.sh MCAReputation compileJava` | PASS |
| Tests after fixes | `gradlew-quiet.sh MCAReputation check` | PASS, 858 tests, 0 failures, 1 skipped (`GoldenSavedDataTest.regenerateTheFixture`, disabled by design) |
| Full build (final, after the deferred-answer containment) | `gradlew-quiet.sh MCAReputation build` | PASS, tests re-run (858, 0 failures) → `build/libs/mcareputation-0.6.1.jar`, `mcareputation-0.6.1-api.jar` |
| Jar shape | `gradlew-quiet.sh MCAReputation verifyApiJar checkJarContents` | PASS (incl. `verifyApiJarLinks`, `verifyApiJarLinkage`) |
| Resources | `check_mod.py MCAReputation` | 0 errors, 0 warnings, 0 notes |
| Datagen | — | N/A: no datagen providers; all JSON is hand-written |
| Dedicated server | `gradlew-quiet.sh MCAReputation runServer` | Before M5: crash in MCA's mixin. After: `Done (15.856s)!`; "ready (API v1)", "MCA integration active: mca 7.7.1-alpha.2+1.20.1 (package root forge.net.conczin.mca)", 19 incident types / 1 ladder / 2 titles; no exception from this mod; stopped with SIGTERM → "All dimensions are saved" |
| Client | `gradlew-quiet.sh MCAReputation runClient` | Loaded to resource-reload completion; the same init lines; only ERROR is vanilla's narrator (`libflite.so` missing); no crash report |

Logs: `/tmp/gradle-MCAReputation-*-20260930-*.log`, `run/logs/latest.log`, `run/logs/debug.log`.
The client launch above, and the first server launch, ran before the deferred-answer try/catch was
added. The dedicated server was relaunched on the final tree: `Done (4.211s)!`, "ready (API v1)", MCA
binding active, 0 ERROR lines, no exception naming this mod, clean save on SIGTERM. The client was not
relaunched; the try/catch is server-side code.

## 7a. NeoForge 1.21.1 port

Ported to `1.21.1 Ports/MCAReputation_1.21.1` (local `master`), which had all four defects at the same
sites. The older worktree `/home/otectus/Projects/MCAReputation-neoforge` was not touched.

- Same changes, re-expressed where the platform differs: `RequestPacing` and its test copied verbatim;
  `ReputationNetwork` (payload handler, `answer`, `flushDeferredRequests`, `SentSnapshot`),
  `ReputationFeedback` (`ServerTickEvent.Post`), `ClientReputationData`, `ReputationScreen`,
  `RequestThrottle`, `ReputationService`, `McaReputationApi`, `ReputationCommand`,
  `ReputationSavedData`, `RequestThrottleTest`, `ReadPublicationTest`; `API.md`, `DIAGNOSIS.md`
  addendum, `MODMAP.md` Decisions and Known issues.
- The behavioural difference is one the port already had: it has no standing outbox, so a decay step a
  read observes is published to mirrors and the event bus only. Its `ReadPublicationTest` asserts those
  two and says so.
- Parity pass: the one item the port's `docs/PORT_PARITY.md` still listed as pending from the
  2026-09-28 comparison was carried across as well. Milestone titles are now granted by
  `publishStandingChange` after the change is recorded, not inside the tier transition. The ledger
  records that and every change above. A line-by-line comparison of what each tree added today leaves
  only loader idioms and the journal-only test assertions.
- No protocol bump: no wire format changed. The port's rule is to bump when Forge's moves, and it
  did not. No `build.gradle` change was needed (NeoForge 1.21.1 runs on official names, so MCA's mixins
  need no refmap remap).
- Two untracked `.rej` files in that tree (`api/McaReputationApi.java.rej`, `client/ReputationClient.java.rej`,
  dated 2026-09-27) predate this work and were left as they were.

| Check | Command | Result |
|---|---|---|
| Compile | `gradlew-quiet.sh "1.21.1 Ports/MCAReputation_1.21.1" compileJava` | PASS |
| Tests | `... check` | PASS, 919 tests, 0 failures, 1 skipped (was 903 before the 16 new tests); includes `NeoForgePortLintTest`, `DedicatedServerClassloadTest`, `ClientPacketSinkTest` |
| Build | `... build` | PASS (incl. `verifyApiJar`, `checkJarContents`) → `build/libs/mcareputation-0.6.0.jar` |
| Dedicated server | `... runServer` | `Done (4.443s)!`; "ready (API v2)", "MCA integration active: mca 7.7.36-beta.3+1.21.1", 19 incident types / 1 ladder / 2 titles; only ERROR is the first-run missing `server.properties`; stopped with SIGTERM → "All dimensions are saved". The `run-server/` directory created for this was removed afterwards (it is not git-ignored); logs kept at `/tmp/mcarep-port-runserver-logs/` |
| Client | — | Not launched for the port |

## 8. Open questions

- **Q1. The older NeoForge worktree.** `/home/otectus/Projects/MCAReputation-neoforge`
  (`neoforge/1.21.1`, behind the `1.21.1 Ports` copy) still has M1–M4. Is it still used, or can it go?
- **Q2. MCA: Quests Journal ladder (sibling change).** `JournalService.villageEntry` should derive tier,
  next tier and threshold from `dev.otectus.mcareputation.reputation.ReputationTiers.getDefault()`
  when `ReputationBridge.isCanonical()` (as `CanonicalReputationBackend.tierId` already does), falling
  back to its own ladder only for the legacy backend. Today the Journal places a player at −10 in
  Stranger with Acquaintance (25) next, because Quests' ladder floor is Stranger; the standing screen
  says Wary, 10 more to Stranger.
- **Q3. Raid heroes far from the raid.** Credit requires the hero within 96 blocks of the raid at the
  moment of victory (`ServerLevel.getRaidAt` passes a squared distance of 9216, i.e. 96 blocks). Vanilla gives Hero
  of the Village to every hero entity still loaded, wherever it stands, so a hero who has wandered off
  gets the effect and no standing. Intended, or should the raid be found by id instead?
- **Q4. Known-issue section.** Unless you still see the frozen tier on a current build, it can be
  retired. If you do, `/mcareputation debug standing <player>` output from that server is the fastest
  way to place it.

## 9. Not verified

- No in-game session: I could not drive a client, so the screen, the Journal link, toasts and the
  pacing under real lag are verified by code trace and unit tests only.
- MCA's zombie-villager cure firing Forge's `LivingConversionEvent.Post` was not checked against MCA's
  source (it is obfuscated in the deobf jar here). The cure hook depends on it.
- `find_api.py` cannot see vanilla classes in this environment (it finds only Forge sources jars); the
  one vanilla SRG name used reflectively (`Screen#addRenderableWidget` = `m_142416_`) was checked
  against `srg_to_official_1.20.1.tsrg` instead.
- Two MCA versions outside the dev run (`7.6.x`) were covered only by `McaReflectProbeTest`, not
  launched.

## 10. In-game checklist

Run each item in singleplayer **and** on a dedicated server with a separate client. After each, run
`/mcareputation debug standing <player>` and check that "score", "tier", "next" and "client last sent"
agree.

Sources:
1. Hit a villager → −8 (the villager is its own witness); tier Wary, since Wary covers −25..−1; the action bar shows −8.
2. Kill a villager within 10 s of hitting it → total −40 (not −48); one change in the ledger.
3. Kill a zombie that is targeting a villager, within 24 blocks of it → +6; rescuing the same villager again inside the same 5-minute bucket pays nothing.
4. Cure a zombie villager → +15; the same player curing the same villager again → nothing.
5. Win a raid in an MCA village → +20 once, and again only for a different raid.
6. `enableCorePvpIncidents=true`, kill a player inside a village with a witness → −12.
7. Complete an MCA: Quests quest with no authored reputation → +4 (medium), +2/+7 for easy/hard.
8. Complete a delivery quest to a family member after walking away from the giver → progress credits.

Tier boundaries (use `/mcareputation set <player> <community> <n>` to jump):
9. 24 → Stranger "1 more to Acquaintance"; 25 → Acquaintance, toast once; drop to 24 and back → no second toast.
10. 74/75 (Friend), 149/150 (Honored + title), 299/300 (Revered + title; the caption reads "As well regarded as you can be here", no "N more").
11. −1 → Wary "1 more to Stranger"; −25/−26, −75/−76, −150/−151, −300/−301 (Infamous; −1000 floor).

Sync:
12. Cycle villages rapidly with the arrows on a laggy server → the header always ends on the village
    the selector points at.
13. Journal "View Deeds" for a village other than your best → the screen opens on that village.
14. Let an assault decay across a day boundary, then trigger a quest gate or dialogue condition before
    the minute sweep → the scoreboard and any MCA: Crime reaction follow the decayed tier.
15. Log out and back in, die and respawn, change dimension → screen, tab-list suffix and scoreboard
    all show the current value.
