# MCA: Reputation 0.6.0 — verification report

Compiled 2026-09-16 from the verifier and builder reports for the 0.6.0 profile layer. Reference
plans: `docs/MCA_Reputation_0.6.0_Execution_Plan.md`,
`docs/MCA_Reputation_0.6.0_Phase_1_Implementation_Plan.md`.

**Status.** Build and automated tests are green on both loaders. The Forge runtime checks are
**blocked** by the MCA dev-runtime constraint already documented in `PRODUCTION_TESTS.md`; no
`mcareputation` fault was observed in any attempt. The NeoForge dev runtime **passed** as a dev run
(server and client). Nothing has been pushed and no release has been published.

## 1. Scope

| Repository / worktree | Branch | Commit range | Version | Tests |
|---|---|---|---|---|
| `/home/otectus/Projects/MCAReputation` | `feature/0.6.0-profiles` | `d1f5dec`..`cbb23a1` | 0.6.0 | 819 / 0 / 1 skip |
| `/home/otectus/Projects/MCAReputation-neoforge` | `neoforge/1.21.1` | `b5c09ca`..`d656613` | 0.6.0 | 892 / 0 / 1 skip |
| `/home/otectus/Projects/MCAQuests` | `feature/reputation-0.6.0` | `5348af1`..`64038ce` | 1.6.5 → 1.6.6 | 1305 / 0 / 16 skips |
| `/home/otectus/Projects/MCACrime` | `feature/reputation-0.6.0` | `0079641`..`05953b0` | 0.7.2 → 0.7.3 | 1586 / 0 / 7 skips |
| `/home/otectus/Projects/MCAConversations` | `feature/reputation-0.6.0` | `375fbba`..`a76a3f0` | 1.7.1 → 1.7.2 | 1589 / 0 / 6 skips |
| `/home/otectus/Projects/MCAQuests-neoforge` | `neoforge/1.21.1` | `6b7ef46`..`f9af55a` | 1.6.4 → 1.6.5 | 1251 / 0 / 19 skips |
| `/home/otectus/Projects/MCACrime-neoforge` | `neoforge/1.21.1` | `b2d8970`..`4c96c5f` | 0.6.0 → 0.6.1 | 906 / 0 / 1 skip |

Version numbers live in each repository's `gradle.properties`; the column above records the release
step each branch performs, not a second source of truth. No branch in this table was pushed.

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

Worktree `/home/otectus/Projects/MCAReputation-neoforge`, new local branch `neoforge/1.21.1` from
`origin` `b5c09ca`.

| Commit | Content | Tests |
|---|---|---|
| `ba51f25` | Port 0.5.0: format 1→2; protocol `"4"`→`"5"` per the Forge 0.5.0 changelog; API 2 kept; `gradlew` exec bit fixed | 531 |
| `06b33cb` | Port 0.6.0: protocol `"5"`→`"6"`; format 2→3 with a byte-identical fixture; API 2; profession getter re-audited with `javap` against the pinned `mca-neoforge-7.7.36-beta.3` jar and added as `AUDITED_OPTIONAL_MEMBERS`; `verifyApiJarLinks`/`verifyApiJarLinkage` ported with a `ba51f25` baseline | 888 |
| `33627e5` | doc review corrections (6 findings) | — |
| `d656613` | collapse profile pane on open | 892 |

### 3.1 Commands and results

| Command | Result | Log |
|---|---|---|
| `check` | 892 passed, 0 failed, 1 skip | `/tmp/gradle-MCAReputation-neoforge-check-20260916-152122.log` |
| `build` | PASS. `verifyApiJar`: 78 entries, api jar 68 classes — one fewer than Forge, from a pre-existing branch shape difference; every export present. `checkJarContents`: 367 entries, all Java 21. | `/tmp/gradle-MCAReputation-neoforge-build-20260916-152334.log` |

### 3.2 Artifacts at `d656613`

| Artifact | SHA-256 |
|---|---|
| `build/libs/mcareputation-0.6.0.jar` | `a7057c96b3f4769249638812dda3628ae75d2ec963a1a2f4146b121bde5e386b` |
| `build/libs/mcareputation-0.6.0-api.jar` | `4379d6d8a3a5828d0e57018ac952cf6c1d62440f0e646e370d93943fb37e901f` |

### 3.3 Runtime — PASS as a dev run

`runServer` (ModDevGradle, `run-server/`; RCON used because `JavaExec` stdin is not wired):

- Mod list: MCA: Reputation 0.6.0 + MCA 7.7.36-beta.3+1.21.1 + NeoForge 21.1.249.
- No mixin apply failure — only a benign refmap `WARN`.
- `mcareputation ready (API v2)`.
- `MCA integration active: mca 7.7.36-beta.3+1.21.1 (package root net.conczin.mca)` with **no**
  reduced-interpretation `WARN`, i.e. every optional member including the profession getter
  resolved.
- Reload: `loaded 19 incident type(s), 1 tier ladder(s), and 2 title(s)` and
  `profile generation 1: 7 facet(s), 1 recognition ladder(s), 9 incident profile(s), 6 credit polic(y/ies)`.
- `Done (4.177s)`; zero `ERROR`, `FATAL` or exceptions.
- `save-all` and `stop` clean.

Command replies over RCON (`scratchpad/rcon-replies.txt`; server log
`scratchpad/neoforge-runServer-latest.log`):

| Command | Reply |
|---|---|
| `mcareputation debug profilemigration` | `manifest v1, coverage complete, 0 stubbed... format v3 (this build writes v3)` |
| `mcareputation debug profile Steve here` | `No player was found` (clean) |
| `mcareputation debug profile <uuid> here` | clean Brigadier refusal |
| `mcareputation debug authorities` | no authorities registered; six kinds detected natively |
| `mcareputation debug integrations` | api version 2; quests / conversations / crime = `true` |

`runClient` (`scratchpad/neoforge-runClient-latest.log`): title screen reached (LWJGL 3.3.3, OpenAL,
15 atlases); both reloads published the profile registries cleanly; clean quit after ~21s caused by
stray desktop input. The only `ERROR`s are the missing `libflite.so` (narrator, a system issue).
**No in-screen check was performed.**

### 3.4 Doc review

6 findings in `06b33cb`, corrected in `33627e5`. One wrong claim in the NeoForge `CLAUDE.md` — that
the companions' NeoForge branches all gate on API version 2 — was corrected separately; see §5.

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

### 5.1 Compatibility verification

Run in temporary detached worktrees at the origin heads (removed afterwards) against the Reputation
NeoForge 0.6.0 classes and api jar:

| Mod | Commit | Result | How |
|---|---|---|---|
| MCA: Quests | `6b7ef46` | `compileJava` + `check` PASS, 1251 / 0 / 19 skips | — |
| MCA: Conversations | `1a2ce66` | PASS, 1299 / 0 / 6 skips | `-PmcaReputationApiPath`, SHA pin untouched |
| MCA: Crime | `b2d8970` | PASS, 906 / 0 / 1 skip | hardcoded `../MCAReputation_1.21.1` path, symlinked |

### 5.2 The API-gate finding and fix

**Finding.** The Quests and Crime NeoForge branches hardcoded `REQUIRED_API_VERSION = 1`, so their
Reputation integration has been silently **off** against every NeoForge Reputation release since
0.4.1, which moved the API to 2. The Conversations NeoForge branch already gates on 2.

**Fix**, on new local `neoforge/1.21.1` branches in new worktrees, neither pushed:

| Repository | Commit | Version step | Change |
|---|---|---|---|
| `/home/otectus/Projects/MCAQuests-neoforge` | `f9af55a` | 1.6.4 → 1.6.5 (NeoForge lineage only) | gate 2 |
| `/home/otectus/Projects/MCACrime-neoforge` | `4c96c5f` | 0.6.0 → 0.6.1 | gate 2, `MINIMUM_COMPANION_VERSION` 0.4.1, `gradlew` +x, new `-PmcaReputationClasses` override |

Both pass `compileJava` + `check` with the override: 1251 / 0 / 19 and 906 / 0 / 1.

### 5.3 Full 0.6.0 adoption on the NeoForge companion branches is not done

These branches only accept API 2; they do not adopt the 0.6.0 feature set. They lag their Forge
lines by unrelated releases — Quests 1.6.4 vs 1.6.5, Conversations 1.6.3 vs 1.7.1, Crime 0.6.0 vs
0.7.2 — and those ports must land first.

## 6. Not established

- **All Forge runtime gates** (every `PRODUCTION_TESTS.md` row) — blocked by MCA's Forge mixin
  refmap issue in dev runs. A production-style instance is required.
- **NeoForge in-screen behaviour**, the protocol-6 handshake refusal against a 0.5.0 client, and a
  real format-2 world upgrade with enrichment — not exercised; the dev server had no players and no
  world history.
- **Companion runtime handshake against a live Reputation**, on either loader — not exercised.
- **Nothing pushed; no release published.**

## 7. How to reproduce

Use the quiet wrapper rather than a bare `./gradlew`, and read the log rather than the exit code for
`run*` tasks.

```bash
# Reputation, Forge 1.20.1
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/MCAReputation check
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/MCAReputation build
python3 /home/otectus/Projects/.mcmod-tools/check_mod.py MCAReputation

# Reputation, NeoForge 1.21.1 (separate worktree; Java 21 toolchain)
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/MCAReputation-neoforge check
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/MCAReputation-neoforge build

# Companions, Forge 1.20.1
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/MCAQuests build
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/MCACrime build -PrequireReputation=true
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/MCAConversations build

# Companions, NeoForge 1.21.1 (need the Reputation NeoForge classes / api jar)
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/MCAQuests-neoforge check
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/MCACrime-neoforge check
```
