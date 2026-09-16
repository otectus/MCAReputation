# MCA: Reputation 0.6.0 — verification report

Compiled 2026-09-16 from the verifier and builder reports for the 0.6.0 profile layer. Reference
plans: `docs/MCA_Reputation_0.6.0_Execution_Plan.md`,
`docs/MCA_Reputation_0.6.0_Phase_1_Implementation_Plan.md`.

**Status.** Build and automated tests are green on both loaders, on the default task path with no
`-P` overrides. The Forge runtime checks are **blocked** by the MCA dev-runtime constraint already
documented in `PRODUCTION_TESTS.md`; no `mcareputation` fault was observed in any attempt. On
NeoForge the dev runtime **passed** twice: Reputation alone (server and client) and a four-mod suite
handshake with all three companions. The NeoForge work was rebased onto the owner's pushed 0.5.0
port; the canonical NeoForge working copies are the clones under
`/home/otectus/Projects/1.21.1 Ports/`. Nothing has been pushed and no release has been published.

## 1. Scope

| Repository / worktree | Branch | Commit range | Version | Tests |
|---|---|---|---|---|
| `/home/otectus/Projects/MCAReputation` | `feature/0.6.0-profiles` | `d1f5dec`..`cbb23a1` | 0.6.0 | 819 / 0 / 1 skip |
| `.../1.21.1 Ports/MCAReputation_1.21.1` (= worktree `MCAReputation-neoforge`) | `master` / `neoforge/1.21.1` | `b70f320`..`fc85759` | 0.6.0 | 896 / 0 / 1 skip |
| `/home/otectus/Projects/MCAQuests` | `feature/reputation-0.6.0` | `5348af1`..`64038ce` | 1.6.5 → 1.6.6 | 1305 / 0 / 16 skips |
| `/home/otectus/Projects/MCACrime` | `feature/reputation-0.6.0` | `0079641`..`05953b0` | 0.7.2 → 0.7.3 | 1586 / 0 / 7 skips |
| `/home/otectus/Projects/MCAConversations` | `feature/reputation-0.6.0` | `375fbba`..`a76a3f0` | 1.7.1 → 1.7.2 | 1589 / 0 / 6 skips |
| `.../1.21.1 Ports/MCAQuests_1.21.1` | `neoforge/1.21.1` | `93787a6`..`1929c23` | 1.6.5 → 1.6.6 | 1374 / 0 / 19 skips |
| `.../1.21.1 Ports/MCACrime_1.21.1` | `neoforge/1.21.1` | `b495e5f`..`fd8bb4c` | 0.7.2 → 0.7.3 | 1663 / 0 / 1 skip |
| `.../1.21.1 Ports/MCAConversations_1.21.1` | `neoforge/1.21.1` | `5b67c5b`..`6186b22` | 1.7.1 → 1.7.2 | 1632 / 0 / 6 skips |

Version numbers live in each repository's `gradle.properties`; the column above records the release
step each branch performs, not a second source of truth. No branch in this table was pushed. The
retired worktrees `MCAQuests-neoforge` and `MCACrime-neoforge` and their NeoForge-only version steps
1.6.5 / 0.6.1 are gone; see §5.

## 2. MCA: Reputation — Forge 1.20.1

Commits on top of 0.5.0 (`d1f5dec`), in order:

| Commit | Work package | Tests |
|---|---|---|
| `29b1329` | P1 seam + `ReputationContext` SPI | 475 |
| `e9ef76c` | P2 schema / math / content | 588 |
| `de34f20` | P3 evidence persistence, format 3, resumable enrichment | 623 |
| `ae5738d` | P4 reconciliation / aging / retention | 664 |
| `b3ae066` | P5 API, capabilities, event, api-jar fixtures | 718 |
| `7ae9a40` | P6 observer interpretation | 753 |
| `80bf136` | P7 protocol 5, screen, config, debug commands | 814 |
| `367a8a0` | `mod_version` 0.6.0 | — |
| `630bd63` | P9 docs + `MODMAP.md` | — |
| `81490b8` | doc review corrections | — |
| `cbb23a1` | collapse profile pane on open | 819 |

### 2.1 Invariants

| Invariant | Value | Source |
|---|---|---|
| Network protocol | `"5"` | `network/ReputationNetwork.java:73` |
| Save format | `3` | `state/ReputationSavedData.java:64` |
| Public API version | `1` | `api/McaReputationApi.java:69` |
| Mod version | `0.6.0` | `gradle.properties` |
| API jar shape | 69 classes, `apiExports` unchanged, `IncidentDefinition` not exported | `build.gradle` / `verifyApiJar` |

### 2.2 Commands and results

Independent verifier at `367a8a0` (before the doc corrections and the client fix):

| Command | Result | Log |
|---|---|---|
| `check --rerun-tasks` | 815 tests: 814 passed, 0 failed, 1 intentional skip (`GoldenSavedDataTest.regenerateTheFixture`) | `scratchpad/gradle-MCAReputation-check-20260916-142734.log` |
| `build` | PASS, including `checkJarContents`, `verifyApiJar` (69 classes), `verifyApiJarLinks` ("sample consumer compiles against `mcareputation-0.6.0-api.jar` alone (2 classes)"), `verifyApiJarLinkage` ("29 0.5.0 call sites linked") | `scratchpad/gradle-MCAReputation-build-20260916-142811.log` |
| `check_mod.py MCAReputation` | 0 missing models / 0 lang / 0 textures | — |

Final builder run at `cbb23a1`:

| Command | Result | Log |
|---|---|---|
| `check` | 819 passed, 0 failed, 1 skip | `/tmp/gradle-MCAReputation-check-20260916-152107.log` |
| `build` | PASS | `/tmp/gradle-MCAReputation-build-20260916-152315.log` |

### 2.3 Artifacts at `cbb23a1`

| Artifact | SHA-256 |
|---|---|
| `build/libs/mcareputation-0.6.0.jar` | `81ed9c32d0e7ab872db5f1c94de8391229f3dcbccda873c2fb8e500637104b7c` |
| `build/libs/mcareputation-0.6.0-api.jar` | `d17bb8c279f91da8702de76861c8eb9212eeca62351924d0ce9c714854672d6e` |

The API jar is 126681 bytes and unchanged since `367a8a0`.

### 2.4 Runtime attempts — blocked

All three attempts were authorized and run. Each failed before any `mcareputation` code ran, for
reasons inside MCA or the dev classpath; no `mcareputation` fault was observed.

| Attempt | Outcome | Log |
|---|---|---|
| `runServer`, `mca_version=7.7.1-alpha.2` | MCA's own `MixinTranslatableText` fails to apply in the official-mappings dev runtime: `could not find any targets matching ...TranslatableContents;m_237524_()V`. Crash before any mod construct; zero lines mention `mcareputation`. | `scratchpad/server-7.7.1-latest.log` |
| `runServer -Pmca_version=7.6.20` | MCA 7.6 requires Architectury `[9.0.8,)`, which the dev classpath lacks. | `scratchpad/server-7.6.20-latest.log` |
| `runClient` (`DISPLAY` present) | Same mixin failure as the 7.7.1 server. | `scratchpad/client-7.7.1-latest.log` |

Caveat on the 7.6.20 run: Gradle exited 0 despite the crash, so the wrapper's PASS line is
untrustworthy for `run*` tasks — read the log, not the exit code.

`PRODUCTION_TESTS.md` already documents that Forge dev runs cannot exercise MCA. Every Forge runtime
gate therefore remains manual / production-only.

### 2.5 Doc review

`doc-checker` found 3 wrong facts and 4 overstatements in `630bd63`. All were corrected in `81490b8`
and spot-rechecked. One code/doc disagreement (the profile pane collapsing on open) was resolved by
changing the code in `cbb23a1`, which added 4 tests including a source assertion that the
constructor, not `init()`, performs the collapse.

## 3. MCA: Reputation — NeoForge 1.21.1

The canonical working copy is the clone `/home/otectus/Projects/1.21.1 Ports/MCAReputation_1.21.1`
(branch `master`); the worktree `/home/otectus/Projects/MCAReputation-neoforge` (branch
`neoforge/1.21.1`) holds the same head.

### 3.1 The rebase

An earlier session worked from stale remote-tracking refs and produced its own 0.5.0 port
(`ba51f25`), which duplicated the owner's already-pushed port `b70f320` "Ship 0.5.0 on NeoForge
1.21.1" (`origin/neoforge/1.21.1`). `ba51f25` was **dropped** and the 0.6.0 commits rebased onto
`b70f320`. Conflicts were resolved with `b70f320` winning for 0.5.0 code — `ReputationClient.ModBus`
is kept and there is no `ReputationClientRegistration` — with two exceptions: `PRODUCTION_TESTS.md`
§6 "0.5.0 reliability track" was restored, because `b70f320` had deleted it and Forge 0.6.0 still
carries it, and `GoldenSavedDataTest` was kept, because `b70f320` left dangling references to it.
The golden format-2 raw-byte test was weakened to "version == 3 plus identical `players` and
`decayImmune` subtrees", exactly as Forge's `GoldenSavedDataTest` does. `apiBaselineRef` in
`build.gradle` is now `b70f320`.

History on top of `b70f320` (merge-base with `origin/neoforge/1.21.1`), in order:

| Commit | Content | Tests |
|---|---|---|
| `f396f29` | Port 0.6.0 to NeoForge 1.21.1 | — |
| `9adef17` | doc review corrections | — |
| `e1c8f8e` | collapse the profile pane on every open | — |
| `2a881e9` | dev-run evidence + companion gate note | — |
| `596b4fc` | artifact hashes, test total, regenerated `MODMAP.md` | — |
| `fc85759` | test-total correction | 896 |

Nothing is pushed; the clone reports **ahead 6**.

### 3.2 Invariants

| Invariant | Value | Source |
|---|---|---|
| Network protocol | `"6"` | `network/ReputationNetwork.java:116` |
| Save format | `3` | `state/ReputationSavedData.java:75` |
| Public API version | `2` | `api/McaReputationApi.java:73` |
| Mod version | `0.6.0` | `gradle.properties` |
| MCA runtime pin | `7.7.36-beta.3+1.21.1` | `gradle.properties` |

### 3.3 Commands and results

Final independent verifier run in the clone, default task path (no `-P` overrides):

| Command | Result | Log |
|---|---|---|
| `build` | PASS, 896 tests / 0 failures / 1 intentional skip. `verifyApiJar`: 78 entries, every export present. `verifyApiJarLinks`: sample consumer compiles against the api jar alone (2 classes). `apiLinkage`: 29 0.5.0 call sites. `checkJarContents`: 367 entries, all Java 21. | `scratchpad/gradle-MCAReputation_1.21.1-build-20260916-165829.log` |

### 3.4 Artifacts at `fc85759`

| Artifact | SHA-256 |
|---|---|
| `build/libs/mcareputation-0.6.0.jar` (clone build) | `3ac51afc2001f12da28fbde0eecbccad0dea8db19e19edd14e98c560d9cd9a1b` |
| `build/libs/mcareputation-0.6.0-api.jar` | `3b84ffe2cb224e980db8207c5088588642b27dafd0e3a7a719c11e9c1c3c0681` |

Jar timestamps make the **mod** jar hash non-reproducible between builds: the worktree build at the
same commit produced `8d0208d143d2f188998a740c57c89a554ceadb8878d2f21e839d76a754d6da24`. The API jar
is reproducible — 126733 bytes, 68 classes, same hash from both trees.

### 3.5 Runtime, Reputation alone — PASS as a dev run

`runServer` in the worktree (ModDevGradle, `run-server/`; RCON used because `JavaExec` stdin is not
wired):

- Mod list: `mcareputation` 0.6.0 + MCA 7.7.36-beta.3+1.21.1 + NeoForge 21.1.249; no mixin apply
  failure.
- `mcareputation ready (API v2)`.
- `MCA integration active: mca 7.7.36-beta.3+1.21.1 (package root net.conczin.mca)` with **no**
  reduced-interpretation `WARN`, i.e. every optional member including the profession getter
  resolved.
- `profile generation 1: 7 facet(s), 1 recognition ladder(s), 9 incident profile(s), 6 credit polic(y/ies)`.
- `Done (1.621s)`; clean stop.

| Command | Reply |
|---|---|
| `mcareputation debug integrations` | api version 2, 0 authorities, all six kinds detected natively |
| `mcareputation debug profilemigration` | coverage complete, format v3 |

Logs: `scratchpad/final-runServer-reponly.log`, replies `scratchpad/rcon-replies-reponly.txt`.

`runClient` (Reputation only): title screen, full reload including `mod/mcareputation` and all
atlases, 90 s soak, no crash report and no `mcareputation` exception; the only `ERROR` is the host's
missing `libflite.so` narrator native. **No in-screen check was performed.** Log:
`scratchpad/final-runClient-reponly.log`.

### 3.6 Runtime, suite handshake — PASS as a dev run

`runServer` in the worktree with the three companion jars dropped into untracked `run-server/mods/`
(removed afterwards).

- Mod list: MCA: Conversations 1.7.2+1.21.1, MCA: Crime 0.7.3, MCA: Quests 1.6.6, MCA: Reputation
  0.6.0, MCA 7.7.36-beta.3+1.21.1, NeoForge 21.1.249. No dependency errors, no exceptions, no extra
  library mods; `Done (1.797s)`.
- Binding lines: `MCA: Crime — MCA: Reputation detected (API v2)`;
  `[MCA: Quests] MCA: Reputation detected; village standing, tiers, and titles now delegate to it`;
  `[MCA: Conversations] MCA: Reputation detected; villagers now take public standing into account`;
  `MCA: Crime — MCA: Reputation capabilities: api v2 enabled=true delivery=true receipts=true supersede=true bound_resolution=true profiles=profile_snapshot_v1,speaker_profile_v1,repeat_credit_v1,profiled_delivery_v1,profile_change_v1`;
  `'MCA: Crime' registered as a core incident authority`; mirrors `MCA: Crime village standing` and
  `mcaquests:fallback-store`; import provider `mcaquests:legacy-reputation`; on stop
  `'MCA: Crime' withdrew its core incident authority`.
- Data: `loaded 27 incident type(s), 2 tier ladder(s), 9 title(s)`; `profile generation 1: 7
  facet(s), 1 recognition ladder(s), 18 incident profile(s), 8 credit polic(y/ies)`.

| Command | Reply |
|---|---|
| `mcareputation debug integrations` | api version 2; `quests=true conversations=true crime=true`; 1 authority (MCA: Crime); `MCA_VILLAGER_ASSAULT` / `MCA_VILLAGER_KILL` claimed by MCA: Crime with native detection **off**, the other four native |
| `mcareputation debug authorities` | both kinds `claimed by MCA: Crime (declared, canDeliver=true)` |
| `crime debug integrations` | reputation `installed=true enabled=true state=ready` (authority held); api v2 with all five profile strings; mca quests `state=ready`; outbox `pending=0 dead=0` |
| `mcaquests compat status` | MCA fully available (`net.conczin.mca`) |
| `conversations chat status` | reply truncated by the minimal RCON client (test-tool limitation); the binding is evidenced by the log line above |

Logs: `scratchpad/final-runServer-suite.log`, replies `scratchpad/rcon-replies-suite.txt`.

### 3.7 Doc review

6 findings in the 0.6.0 port, corrected in `9adef17`. The NeoForge `CLAUDE.md` claim that the
companions' NeoForge branches all gate on API version 2 was wrong and was corrected; see §5.

## 4. Companions — Forge 1.20.1

Each on a new `feature/reputation-0.6.0` branch, none pushed.

### 4.1 MCA: Quests — `64038ce`

1.6.5 → 1.6.6, branched from `release/1.6.3` @ `5348af1`. The adoption switches the backend to
`deliver()` / `deliverProfiled` with an `OptionalInt` delta, makes `highWaterTierId` ladder-aware,
dedupes bindings through `resolveBound`, passes a real `SpeakerContext`, negotiates through
`capabilities(server)` (the reflective probe is removed), adds the `mcareputation:profile` condition
and `incident_profile` on outcomes and `record_incident`, ships two profiles
(`mcaquests:quest_commission`, `mcaquests:quest_commitment_broken`) and sample-pack quests.
`REQUIRED_API_VERSION` stays 1 because the Forge API is 1, and `QuestNetwork`'s protocol is
unchanged.

| Check | Result |
|---|---|
| `compileJava`, `test`, `build` | PASS (`/tmp/gradle-MCAQuests-*-20260916-1510*.log`) |
| Tests | 1305 / 0 / 16 pre-existing skips |
| `check_mod.py` | 0 / 0 / 0 |
| Artifacts | `build/libs/mcaquests-1.6.6.jar`, `build/libs/mcaquests-1.6.6-api.jar` |

Scoped down: situation outcomes cannot carry `incident_profile` (schema limit); the vertical slice
lives in the sample pack, not the production pack; no `ReputationProfileChangedEvent` listener, as
there is no consumer.

### 4.2 MCA: Crime — `05953b0`

0.7.2 → 0.7.3, branched from `main` @ `0079641`. Typed `ReputationDelivery` outcomes replace
`Optional<UUID>`; the synthetic `record()` probe is replaced by a `findReceipt` lookup; capability
negotiation added; `CrimeAuthorityPolicy` now declares only `MCA_VILLAGER_ASSAULT` and
`MCA_VILLAGER_KILL`, with `owns` / `canDeliver` respecting the kind; assault→killing supersession
added; 7 incident profiles, 2 credit policies and `social_profile` on all 8 incidents; an
NPC-offender attribution bug fixed; new config key
`integrations.reputation.supersedeWindowTicks`.

| Check | Result |
|---|---|
| `compileJava`, `check`, `build` with `-PrequireReputation=true` | PASS (`/tmp/gradle-MCACrime-*-20260916-151[34]*.log`) |
| Tests | 1586 / 0 / 7 pre-existing skips |
| `check_mod.py` | 1 pre-existing error (`CrimeKeybinds.java:50`), untouched |
| Artifact | `build/libs/mcacrime-0.7.3.jar` sha256 `e7735f7e33ceda6a8679a6dd2d7068248681500b86e6d80f6e25a70e02f2ab2a` |

`docs/0.7.3/BASELINE.md` and `docs/0.7.3/VERIFICATION.md` were written in that repository. The
adapter is compile-verified only: the test classpath excludes Reputation by design.

### 4.3 MCA: Conversations — `a76a3f0`

1.7.1 → 1.7.2, branched from `main` @ `375fbba`. The vendored api jar was refreshed to
`mcareputation-0.6.0-api.jar` from Reputation `367a8a0` (sha `d17bb8c...`), still valid because the
jar has not changed since. Adds the `deliverProfiled` / `recordSuperseding` / `deliver` ladder with
`SpeakerContext` and witness, a capability snapshot, the 0.6.0 `getOpinionBias` term, the
`conversations_reputation_profile` condition, a signal action that binds and supersedes, three
`standing.*` context fields, a `ReputationProfileChangedEvent` listener, and two scenes
(`known_for_courage`, `known_for_violence`) in `en` + `pt` with generated output committed.
`gossipStory` is used for corrections only, because the API has no per-incident story lookup.

| Check | Result |
|---|---|
| `compileJava`, `check`, `verifyGeneratedConversationContent`, `verifyVoiceOverlays`, `build` | PASS (`/tmp/gradle-MCAConversations-*-20260916-15*.log`) |
| Tests | 1589 / 0 / 6 pre-existing skips |
| `check_mod.py` | 0 / 0 / 0 |
| Artifact | `build/libs/mcaconversations-1.7.2.jar` |

`docs/RELEASE-1.7.2-LEDGER.md` records the NeoForge mirror as pending. Scoped down: template
variables for tier and traits were not added; 2 of the 6 spec content variants ship.

## 5. Companions — NeoForge 1.21.1

Each adoption lives in the canonical clone under `/home/otectus/Projects/1.21.1 Ports/` on the local
branch `neoforge/1.21.1`; **nothing is pushed**.

### 5.1 Compatibility verification at the origin heads

Run first in temporary detached worktrees at the origin heads (removed afterwards) against the
Reputation NeoForge 0.6.0 classes and api jar:

| Mod | Commit | Result | How |
|---|---|---|---|
| MCA: Quests | `6b7ef46` | `compileJava` + `check` PASS, 1251 / 0 / 19 skips | — |
| MCA: Conversations | `1a2ce66` | PASS, 1299 / 0 / 6 skips | `-PmcaReputationApiPath`, SHA pin untouched |
| MCA: Crime | `b2d8970` | PASS, 906 / 0 / 1 skip | hardcoded `../MCAReputation_1.21.1` path, symlinked |

### 5.2 The API-gate finding

**Finding.** The Quests and Crime NeoForge branches hardcoded `REQUIRED_API_VERSION = 1`, so their
Reputation integration has been silently **off** against every NeoForge Reputation release since
0.4.1, which moved the API to 2. The Conversations NeoForge branch already gates on 2.

**Resolution.** The two standalone gate-fix worktrees from the earlier session
(`MCAQuests-neoforge` @ `f9af55a` as 1.6.5, `MCACrime-neoforge` @ `4c96c5f` as 0.6.1) sat on stale
bases; both worktrees and their branches were **deleted**, and the NeoForge-only version numbers
1.6.5 / 0.6.1 are **not** used. The gate fix is folded into each mod's 0.6.0 adoption below.

### 5.3 The 0.6.0 adoptions

| Mod | Version | Commit(s) | Base | Mirrors Forge | Tests |
|---|---|---|---|---|---|
| MCA: Quests | 1.6.6 | `1929c23` | `93787a6` (`origin/1.21.1` = 1.6.5) | `64038ce` | 1374 / 0 / 19 |
| MCA: Crime | 0.7.3 | `fd8bb4c` | `b495e5f` (`origin/neoforge/1.21.1` = 0.7.2) | `05953b0` | 1663 / 0 / 1 |
| MCA: Conversations | 1.7.2 | `3655d86` + `6186b22` | `5b67c5b` (1.7.1 slice 7) | `a76a3f0` | 1632 / 0 / 6 |

**MCA: Quests 1.6.6** (`MCAQuests_1.21.1`). `REQUIRED_API_VERSION` 1 → 2; the reflective opinion
probe is removed, so `mcareputation:villager_opinion` is live on this loader for the first time; 1.21
`MapCodec` dispatch codecs; `QuestNetwork` protocol unchanged (17); `apiExports` unchanged (16
exports). Default-path `build` (no `-P`): PASS, 1374 / 0 / 19; archive verifier, `jarSmokeCheck` and
`verifyApiJar` green. Artifacts `mcaquests-1.6.6.jar` sha256
`77b7d4fb259d15a6bd5cc7d076c9b4cab462237db308b5c30722e567f906d892`, `mcaquests-1.6.6-api.jar` sha256
`89549f43e098d8961865c8f37cbfd5dfd752afbcbdf9cae96c785b0a39967497`. Log
`scratchpad/gradle-MCAQuests_1.21.1-build-20260916-165936.log`.

**MCA: Crime 0.7.3** (`MCACrime_1.21.1`). `REQUIRED_API_VERSION` 1 → 2, `MINIMUM_COMPANION_VERSION`
0.4.1; `build.gradle` gains `-PmcaReputationClasses` and `-PmcaQuestsClasses` overrides; `gradlew`
mode fixed to `100755`; JetBrains `Nullable`, `ModConfigSpec`, `ServerTickEvent.Post`. There is no
`MODMAP.md` on this line — the tool is Forge-shaped; the inventory is recorded in
`docs/0.7.3/VERIFICATION.md`. Default-path `build` with `-PrequireReputation=true
-PrequireQuests=true`: PASS, 1663 / 0 / 1; both adapters compiled (`compat/reputation` 4 classes,
`compat/mcaquests` 2 classes); `checkJarContents` 1103 entries clean. Artifact `mcacrime-0.7.3.jar`
sha256 `8c87dbb3d76b3fd3882a4dad824946ed212b2fc47f6464772393e64ebc4fc5e4`. Log
`scratchpad/gradle-MCACrime_1.21.1-build-20260916-170020.log`.

**MCA: Conversations 1.7.2** (`MCAConversations_1.21.1`). `3655d86` lands the whole Forge adoption
`a76a3f0` as one slice; `6186b22` pins the final api jar. The 1.7.0 / 1.7.1 NeoForge commits are
**unpushed** on the owner's clone — `origin/neoforge/1.21.1` is still `958a0bb`. Vendored
`libs/api/mcareputation-0.6.0-api.jar` is the NeoForge jar from Reputation `fc85759` (sha
`3b84ffe2...`); the Quests pin stays at 1.6.4 as on Forge; generated content is byte-identical to
Forge including `pt_br`. `docs/parity-1.7.2-adaptations.json` records 172 adaptations (release 1.7.2,
protocol 4) alongside `PARITY-1.7.2.md` and `RELEASE-1.7.2-LEDGER.md`; the same manifest was
committed on the Forge branch as `e3c0415` so `verify_release_parity.py` passes from either copy:
`{"identical": 1883, "reviewed_adaptations": 172}`, "Release parity verified". `check` (forced
rerun) 1632 / 0 / 6; `build` PASS including `verifySiblingApis` with no override, plus
`verifyGeneratedConversationContent` and `verifyVoiceOverlays`. Artifact
`mcaconversations-neoforge-1.7.2+1.21.1.jar` sha256
`c69ff08d39e07b521935572ae6907e9f02d07efb580783e7e4ddc3656af0bf26`, with 0 `mcareputation` entries.
Logs `/tmp/gradle-MCAConversations_1.21.1-*-20260916-16*.log`.

All three jars were then exercised together against the live NeoForge Reputation server; see §3.6.

## 6. Not established

- **All Forge runtime gates** (every `PRODUCTION_TESTS.md` row) — blocked by MCA's Forge mixin
  refmap issue in dev runs. A production-style instance is required; nothing on the Forge line's
  runtime is established.
- **NeoForge in-screen behaviour** beyond reaching the title screen.
- **The protocol-6 handshake refusal** against a 0.5.0 client.
- **A real format-2 world upgrade with enrichment** — the dev servers had no players and no world
  history.
- **A player actually earning recognition or facets, and a companion condition firing in-world** —
  the suite run in §3.6 establishes the handshake and the negotiated capabilities, not gameplay.
- **Nothing pushed anywhere; no release published.** Conversations' 1.7.0 / 1.7.1 NeoForge commits
  also remain unpushed on the owner's clone.

## 7. How to reproduce

Use the quiet wrapper rather than a bare `./gradlew`, and read the log rather than the exit code for
`run*` tasks.

```bash
# Reputation, Forge 1.20.1
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/MCAReputation check
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/MCAReputation build
python3 /home/otectus/Projects/.mcmod-tools/check_mod.py MCAReputation

# Reputation, NeoForge 1.21.1 (canonical clone; Java 21 toolchain)
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh "/home/otectus/Projects/1.21.1 Ports/MCAReputation_1.21.1" build

# Companions, Forge 1.20.1
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/MCAQuests build
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/MCACrime build -PrequireReputation=true
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/MCAConversations build

# Companions, NeoForge 1.21.1 (build Reputation first; the clones sit side by side)
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh "/home/otectus/Projects/1.21.1 Ports/MCAQuests_1.21.1" build
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh "/home/otectus/Projects/1.21.1 Ports/MCACrime_1.21.1" build -PrequireReputation=true -PrequireQuests=true
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh "/home/otectus/Projects/1.21.1 Ports/MCAConversations_1.21.1" build
```

The NeoForge dev runs use the worktree `/home/otectus/Projects/MCAReputation-neoforge`
(`runServer` / `runClient`), with commands driven over RCON; for the suite run, the three companion
jars are copied into untracked `run-server/mods/`.
