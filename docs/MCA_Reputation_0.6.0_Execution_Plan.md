# MCA: Reputation 0.6.0 — execution plan (Forge 1.20.1 `main` + NeoForge 1.21.1 branch)

Produced 2026-09-16 by the planner agent from repository evidence. Planning only: no files were
modified and no Gradle was run while producing it. Reference spec:
`docs/MCA_Reputation_0.6.0_Phase_1_Implementation_Plan.md`.

## 1. Verified Forge baseline (`main` @ `d1f5dec`)

- `gradle.properties`: MC 1.20.1, Forge 47.4.10, `mod_version=0.5.0`, `mca_version=7.7.1-alpha.2+1.20.1`, range `[7.6,8)`. Matches spec §2.1.
- Save format: `state/ReputationSavedData.java:54` `FORMAT_VERSION = 2`; future-format read-only guard `:234`; `migrateFormat` `:310-338`.
- Protocol: `network/ReputationNetwork.java:57` `PROTOCOL_VERSION = "4"`.
- API version: `api/McaReputationApi.java:59` `API_VERSION = 1`, surfaced `:66` and in the capability snapshot `:848`.
- Capabilities: `api/ReputationCapabilities.java:20-24` record; 13 additive `FEATURE_*` constants `:27-63`. New profile features are pure constant additions.
- Transaction seam: `reputation/ReputationService.java:194` `deliverInternal`, `:251` `replay`, `:357` `recordInternal`, `:396` `private record Commit`, `:407` `commit(...)`, `:605` `supersedeInternal`, `:1041` `recordBoundReceipt`. 1850 lines; the spec §11.1 "stage before mutation" refactor rewrites `Commit`/`commit`.
- Admission/eviction: `state/CommunityReputationRecord.java:292` `evictable`, `:307` `canAdmit` (no live-value folding; refuse instead).
- Reconciliation gate: `reputation/ReconciliationService.java:34` `enum Intent`, `:71/:79` entry points, `:93` INSPECT short-circuit, `:124` `isFrozen`.
- Incident schema: `incident/IncidentDefinition.java:31-43` 13-component record, 13-field codec `:59-88`, `flatXmap` validation `:89`. Adding `social_profile` makes 14 (under DFU's 16 limit); needs a delegating 13-arg constructor. Not in the API jar export list, so not an ABI change.
- Incident state: `incident/IncidentRecord.java:57-75` fields, `:201-229` settledDelta/currentContribution/decayElapsedTicks, `:264-278` supersession + storyRevision. Attachment point for `IncidentProfileEvidence` and the second (profile) elapsed clock.
- Reload: `data/ReputationReloadListener.java:55` `SimplePreparableReloadListener`, directory constants `:59-63`, `Prepared` record `:67`, cross-validation `:87`, publish `:116-118`.
- Shipped data: `src/main/resources/data/mcareputation/mcareputation/{incidents,reputation_tiers}` and `data/mcaquests/mcaquests/titles` (note the doubled namespace segment). New content goes to `data/mcareputation/mcareputation/{facets,recognition_tiers,incident_profiles,credit_policies}`.
- Diagnostics: `command/ReputationCommand.java` already has a `debug` subtree (`authorities`, `community`, `quarantine`, `receipts` ~`:962-976`, `supersede`, `witnesses`, `standing`) plus `migrate --dry-run`. Spec §21.1's four subcommands are additions.
- Build: `build.gradle:178-192` `apiExports`, `:195` `apiJar`, `:221` `verifyApiJar`, `:250-252` `build` depends on `checkJarContents` + `verifyApiJar`.
- Tests: 52 files under `src/test/java` incl. `OptionalClassloadTest`, `reputation/TestServiceContext`, `reputation/TestDeliverySeam`, `state/GoldenSavedDataTest`, `state/SavedDataMigrationTest`; fixtures `src/test/resources/fixtures/mcareputation-format-{1,2}-1.20.1.nbt`.
- Seam constraint: `reputation/ServiceContext.java:25` is package-private and every `*With(ServiceContext ...)` overload in `ReputationService` is package-private. A new top-level `profile/` or `credit/` package cannot see it (decision D6).

Companions: Quests is at the pinned `5348af1` (1.6.5). Conversations is ahead (`375fbba`, 1.7.1). Crime is ahead (`b7726fa`, 0.7.2). Spec audit findings still reproduce: `MCACrime/.../CrimeReputationCompat.java:147` returns `Optional<UUID>`, `:166` uses `record(...)`, `:199` sends a synthetic probe through `record(...)`; `MCAConversations/.../ConversationsReputationCompat.java:281-297` keys the signal by villager+player+decisionId; `MCAQuests/.../CanonicalReputationBackend.java:118` ignores the requested ladder, `:143` unconditionally calls `.delta(award.delta())`. `MCAAddonCore` is an empty directory.

## 2. Verified NeoForge baseline (`origin/neoforge/1.21.1` @ `b5c09ca`)

No local branch or worktree (one stale prunable entry for unrelated `claude/modest-knuth-3ecfcf`).

The divergence is exactly one release. The set of `src/**` files present on `main` but absent on the NeoForge branch is identical to the set added by the 0.5.0 commit (plus loader-specific `mods.toml`, `pack.mcmeta`, old `reputation.png`). So NeoForge ≡ Forge 0.4.1 (`588b051`) ported, missing precisely: `api/{ChangeCause,DeliveryOutcome,GossipStory,IncidentDelivery,OpinionResult,ReceiptOutcome,ReceiptView,ReputationCapabilities,SpeakerContext,StandingChange,SupersedeSpec,TitleSnapshot}`, `reputation/{ReconciliationService,ReputationPolicy,StandingAvailability}`, `state/{OperationReceipt,OperationReceipts,SaveQuarantine}`, `network/SnapshotPaging`, `event/{ReputationConfigLifecycle,ScoreboardOwnership}`, `client/ReputationClientRegistration`, and 21 tests/fixtures.

NeoForge facts shaping the port:
- `gradle.properties`: MC 1.21.1, `neoforge_version=21.1.249`, Parchment 2024.11.17, `mod_version=0.4.1`, `mca_version=7.7.36-beta.3+1.21.1` with `mca_jar_sha256` pin, range `[7.7,8)`.
- Build: ModDevGradle 2.0.146, Java 21 toolchain, `neoForge.unitTest`, extra tasks `mcaJarInfo`, `checkJarContents` (constant-pool scan), `apiJar`/`verifyApiJar` same shape.
- `state/ReputationSavedData.java:61` `FORMAT_VERSION = 1` (never received v1→v2). Javadoc `:48-53` documents loader-independent on-disk bytes and format number; `GoldenSavedDataCompatibilityTest` asserts a Forge format-1 fixture loads under 1.21.1. Save/load are `HolderLookup.Provider`-aware adapters over provider-neutral `savePayload`/`loadPayload` (`:215,:240,:245`).
- `network/ReputationNetwork.java:85` `PROTOCOL_VERSION = "4"` over `CustomPacketPayload` + `PayloadRegistrar` + `RegistryFriendlyByteBuf`/`ComponentSerialization.STREAM_CODEC`, with bounded `readBoundedList` decode already present (preserve).
- NF-only guards: `NeoForgePortLintTest` (bans `net.minecraftforge`, `SimpleChannel`, `ForgeConfigSpec`, `new ResourceLocation(`, `ExtraCodecs.COMPONENT`, `LivingHurtEvent`, `DistExecutor`, `javax.annotation.Nullable`, `reobf`, …), `McaBinaryAbiTest`, `DedicatedServerClassloadTest`, `ConfigParityTest`, `NeoForgeMetadataTest`, `GuiSpriteMetadataTest`, `network/ClientPacketSinkTest`, `event/DamageHookTest`.
- Branch has no `CLAUDE.md`, `MODMAP.md`, or `DIAGNOSIS.md`.
- Precedent: `8e0ac5e "Port 0.4.0 to NeoForge 1.21.1"` was a hand-written semantic port in one commit (47 files), source and docs together, not a cherry-pick. Repeat that shape.

## 3. Branch / worktree strategy

1. `git worktree prune` (clears the stale entry) — optional, ask first (D10).
2. Forge: branch `feature/0.6.0-profiles` off `main`; one commit per work package P0…P9. Leave the uncommitted `gradlew` change and untracked `.claude/`, `docs/…` alone.
3. NeoForge in a separate worktree so the loaders never share a Gradle/JDK context:
   `git worktree add /home/otectus/Projects/MCAReputation-neoforge -b neoforge/1.21.1 origin/neoforge/1.21.1`
   (`main` needs JDK 17 as the Gradle JVM; NF needs a 21 toolchain.)
4. Stage B (NeoForge 0.5.0 parity) is its own commit and can run in parallel with Forge 0.6.0 because its source (`d1f5dec`) is frozen. Do not `git cherry-pick d1f5dec` — it is a 93-file squashed release commit against Forge-only APIs the NF lint test bans by name; port semantically.
5. Stage C (NeoForge 0.6.0) starts only after Forge P7 is green.

## 4. Stage A — Forge `main`, ordered work packages

Sequential unless noted. Spec §22's P0–P9 is the skeleton.

**P0 — Pin and characterize** (no functional change)
- Record `d1f5dec` and companion HEADs (two are ahead of the pins, see D2) in the new `docs/` verification document.
- Run baseline `check`/`build` and record pre-existing failures before touching code.

**P1 — Reliable acceptance seam** (spec §3.2, §11), no new feature payload
- `reputation/ReputationService.java`: replace `Commit` (`:396`) with a staged operation record (identity, policy snapshot, evaluation time, pending incident, pending profile payload slot, credit reservation slot, permitted evictions, precursor snapshot, receipt, old/new read models); reorder `commit()` (`:407`) so reconciliation/admission preflight runs before the capacity decision; move receipt append + publication strictly after the canonical mutation in `deliverInternal` (`:194`) / `recordInternal` (`:357`); extend `supersedeInternal` (`:605`) snapshot/restore to every field it will later roll back.
- `state/CommunityReputationRecord.java:292,307`: policy-aware, non-growing preflight at a single evaluation time.
- `state/ReputationSavedData.java:234`: extend future-format/read-only refusal to every write path P1 touches.
- Tests: extend `reputation/TestDeliverySeam`, `ReputationServiceTest`, `SupersedeLifecycleTest`, `state/ReceiptTest` with re-entrant replay, failure injection at each boundary, read-only refusal, admission-after-aging.
- Exit gate: all 52 test files pass; new characterization tests pass; zero schema change.

**P2 — Pure schema and math** (merge after P1)
- New `profile/`: `FacetDefinition`, `RecognitionTierSet`, `IncidentProfileDefinition`, `ProfileRegistryBundle`, `ProfileMath` (fixed-point, 10 000 subunits/point, saturating long, quantized `decay_step_ticks` default 24000).
- New `credit/`: `CreditPolicy`, `CreditDecision`, `CreditResolver` (non-increasing basis-point schedule, group ∧ subject minimum).
- `incident/IncidentDefinition.java`: 14th component `Optional<ResourceLocation> socialProfile`, one `StrictCodecs.strictOptional` field at `:59-88`, delegating 13-arg constructor, bounds in `validate` (`:89`).
- `data/ReputationReloadListener.java`: four new directory constants beside `:59-63`, four new `Prepared` fields (`:67`), strict/lenient publication per spec §9.6 (lenient keeps a valid scalar incident whose optional profile is malformed).
- `data/ReputationContentValidator.java`: cross-registry reference validation + every numeric hard bound from spec §9.6.
- Shipped content: `facets/*.json` (7), `recognition_tiers/default.json` (6 tiers), `incident_profiles/*.json`, `credit_policies/*.json`, `social_profile` additions to existing incident JSONs per spec §17; `assets/mcareputation/lang/en_us.json` keys for every facet/tier label (`data/LangParityTest` enforces).
- Exit gate: strict-codec rejection tests (duplicate keys, inverted ranges, non-monotonic thresholds), exact-subunit arithmetic, stable cross-JVM ordering, `ContentValidationTest` green on shipped files.

**P3 — Snapshot persistence** (depends on P2)
- `incident/IncidentRecord.java`: `IncidentProfileEvidence` payload (origin `LIVE|LEGACY_ENRICHED|LEGACY_UNENRICHED|DISABLED_AT_OCCURRENCE`, rule fingerprint, authored/credited units, lifecycle snapshot, credit decision, current contribution, profile revision) + NBT read/write with bounds before allocation.
- `state/CommunityReputationRecord.java`: bounded credit/window trackers (64 group, 128 subject) with monotonic watermarks and a persisted conservative overflow decision — never LRU eviction.
- `state/ReputationSavedData.java:54` `FORMAT_VERSION = 2 → 3`; extend `migrateFormat` (`:310`) with v2→v3 after v1→v2; resumable/budgeted enrichment cursor; `state/SaveQuarantine` quarantines a malformed profile payload without dropping its scalar incident.
- Fixtures: add `src/test/resources/fixtures/mcareputation-format-3-1.20.1.nbt` generated by this build; keep format-1/2 fixtures untouched.
- Exit gate: v2→v3 round trip preserves every scalar value/identity at the same evaluation point; enrichment idempotent across two runs and across interruption; tracker capacity behaviour; future-format (v4) fixture reports read-only.

**P4 — Reconciliation, aging, retention** (depends on P3)
- `reputation/ReconciliationService.java`: profile-only change detection; second bounded profile elapsed clock advanced only through the gate; `ReputationPolicy` (`:6`, `fromConfig()` `:47`, builder `:129`) gains profile/credit policy fields.
- `incident/IncidentRecord` lifecycle: resolution modes `recognition|historical|evaluative`, monotonic non-increasing settlement, `DISPROVEN` → zero.
- Both cap paths (`CommunityReputationRecord`, `PlayerReputationRecord`) refuse pruning while live profile subunits exist.
- Exit gate: no catch-up across a whole disabled interval with no intervening read; immunity/freeze boundaries; display-clamped facet still protects its evidence.

**P5 — API and capabilities** (depends on P4)
- New `api/profile/`: `ProfileSnapshot`, `FacetValue`, `RecognitionValue`, `VillagerProfileSnapshot`, `ProfileQueryResult`, `ProfileAvailability`, `ProfileCoverage`, `ProfileQuery`, `ProfileCapabilities`, `ProfiledDelivery`. Immutable, explicitly sorted, no internal types in signatures.
- `api/McaReputationApi.java`: keep `API_VERSION = 1`; add the eight §14.3 operations; `capabilities(...)` shape unchanged.
- `api/ReputationCapabilities.java`: five new `FEATURE_*` constants after `:63` only.
- `api/event/ReputationProfileChangedEvent` (new, post-commit, immutable); `ReputationChangedEvent`/`StandingChange` untouched.
- `build.gradle:178`: `api/**` already covers the new package; add exports only if a signature-closure check proves a gap; add an external-style compile fixture against the produced `apiJar` alone, plus a linkage fixture compiled against the 0.5.0 api jar and run against the new implementation.
- Exit gate: `verifyApiJar` + both fixtures; predicate semantics tests (unknown IDs fail closed, unobserved ≠ negative evidence, speaker query never falls back).

**P6 — Observer interpretation** (depends on P5)
- `reputation/OpinionResolver.java` + new `profile/VillagerProfileResolver`: filter each incident through `incident/AwarenessResolver` first, then aggregate; one trait resolver shared by entity and UUID entry points; combined external bias within the existing ±8.
- `compat/McaCompat`/`McaReflect`: personality/profession normalization only; verify every symbol with `.mcmod-tools/find_api.py`; no new static MCA linkage.

**P7 — Client and network** (depends on P5; P6 for the observer pane)
- `network/ReputationNetwork.java:57` `"4" → "5"`; bounded profile subpayload (≤3 dominant traits, ≤8 detail entries, ~16 KiB) with length validation before allocation, request/generation identity to reject stale replies, unchanged throttling; `network/SnapshotPaging`, `network/ClientPacketHandler`.
- `client/ClientReputationData`, `client/ReputationScreen`: one compact profile expansion; honest unknown/unavailable/partial-legacy/read-only states; no colour-only meaning; keep 0.5.0 paging and stale-opinion clearing.
- `McaReputationConfig.java`: new `profiles` section (spec §20's eight settings) with guarded accessors beside `:391-644`, wired into `ReputationPolicy.fromConfig()`; `event/ReputationConfigLifecycle` if snapshot invalidation is needed.
- `command/ReputationCommand.java`: four new `debug` children (`profile`, `credit`, `profileincident`, `profilemigration`) using `Intent.INSPECT`; lang keys.

**P8 — Consumer adoption** — out of this repo. Default: exact patch requirements + executable consumer fixtures only (spec §16.4 "core complete / suite adoption pending"). See D2.

**P9 — Certification and docs**
- `CHANGELOG.md` (0.6.0), `API.md`, `DATAPACK.md`, `CONFIG.md`, `MIGRATION.md`, `README.md`, `PRODUCTION_TESTS.md`, `CURSEFORGE.md` if released; new `docs/MCA_Reputation_0.6.0_Verification.md` with exact commands, artifact names + SHA-256, tests run/skipped.
- Regenerate `MODMAP.md` with `.mcmod-tools/modmap.py`; preserve everything below `AUTO:END`.
- Correct the stale comments the spec calls out. Read `DIAGNOSIS.md` before touching `buildSnapshot`/`resolveSelection`.

## 5. Stage B — NeoForge 0.5.0 parity port (parallel, independent)

Single commit shaped like `8e0ac5e`, ported from frozen `d1f5dec`.
- N1.1 Add the 22 missing `src/main/java` files, semantically translated: `ForgeConfigSpec`→`ModConfigSpec`, `ExtraCodecs.COMPONENT`→`ComponentSerialization.CODEC`, `new ResourceLocation(...)`→`fromNamespaceAndPath`/`parse`, `javax.annotation.Nullable`→JetBrains, `MinecraftForge.EVENT_BUS`→`NeoForge.EVENT_BUS`, `@Mod.EventBusSubscriber`→top-level `@EventBusSubscriber`, mod-bus registration through the injected `ModContainer`. `client/ReputationClientRegistration` maps onto the NF client-mod-bus idiom rather than `DistExecutor`.
- N1.2 Apply the 0.5.0 modifications to the ~70 shared files by reading the Forge diff per file, not by patch application.
- N1.3 Save format: `FORMAT_VERSION 1 → 2` plus the v1→v2 `migrateFormat` step; copy `mcareputation-format-2-1.20.1.nbt` into NF test resources and extend `GoldenSavedDataCompatibilityTest`. Keep the provider-neutral `savePayload`/`loadPayload` split.
- N1.4 Network: 0.5.0's paged snapshot/richer deed lines as `StreamCodec`s on the existing payloads; keep `readBoundedList`. Protocol string per D4.
- N1.5 Tests: port the 21 added test files; keep every NF-only guard green.
- N1.6 Metadata/docs: `mod_version=0.5.0`; `neoforge.mods.toml` untouched except via `processResources`; update NF `CHANGELOG.md`, `API.md`, `CONFIG.md`, `MIGRATION.md`, `PRODUCTION_TESTS.md`; add `CLAUDE.md`, `MODMAP.md`, `DIAGNOSIS.md`.

## 6. Stage C — NeoForge 0.6.0 port (after Forge P7)

Port P2–P7 in the same order with these deltas:
- Config: `ModConfigSpec` + `ModConfigEvent` lifecycle; `ConfigParityTest` fails until `CONFIG.md` documents every new key.
- Networking: profile subpayload as `StreamCodec`s over `RegistryFriendlyByteBuf`; `ComponentSerialization.STREAM_CODEC` for authored labels; handler exceptions must stay wrapped.
- Saved data: `FORMAT_VERSION 2 → 3` with byte-identical layout to Forge so the cross-loader golden fixture keeps passing (D3).
- Datapack/codecs: `Codec`/`RecordCodecBuilder` identical in 1.21.1; `ExtraCodecs.COMPONENT` must not appear.
- Registries: `BuiltInRegistries`/`DeferredRegister` for loot condition + command argument types; no `ForgeRegistries`.
- MCA compat: NF pins `7.7.36-beta.3` with audited `mca_jar_sha256`; any new reflection in P6 must be re-audited and `McaBinaryAbiTest` updated; resolve by name with neutral fallback.
- API jar: NF `apiJar`/`verifyApiJar` same shape; keeps mojmap + Parchment names, no reobf.
- Client: NF uses 1.21 GUI sprites (`textures/gui/sprites/reputation/*`) with `.mcmeta` guards; new widgets ship sprite + mcmeta.
- Version: `mod_version=0.6.0` (D3/D5).

## 7. Verification checks

Forge (`/home/otectus/Projects/MCAReputation`, JDK 17):
```
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/MCAReputation compileJava     # per edit, P1..P7
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/MCAReputation check           # end of each P
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/MCAReputation build           # P3, P5, P7, P9
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/MCAReputation "apiJar verifyApiJar checkJarContents"   # P5, P9
python3 /home/otectus/Projects/.mcmod-tools/check_mod.py MCAReputation                                    # after P2 content, after P7 lang keys
python3 /home/otectus/Projects/.mcmod-tools/modmap.py  MCAReputation                                      # P9 only
```
NeoForge (`/home/otectus/Projects/MCAReputation-neoforge`, JDK 21):
```
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/MCAReputation-neoforge compileJava
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/MCAReputation-neoforge check
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/MCAReputation-neoforge build
/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh /home/otectus/Projects/MCAReputation-neoforge "mcaJarInfo apiJar verifyApiJar checkJarContents"
```
Release-level: Forge = protocol 5, format 3, `API_VERSION` still 1, both API-jar fixtures link, all tests green, `check_mod.py` clean, `MODMAP.md` regenerated. Runtime (`runServer`/`runClient` with pinned MCA) is required for "release certified" and needs explicit authorization.

## 8. Risks

1. Scope: ~1180-line spec, 9 packages touching the 1850-line service, storage schema, protocol, API, UI and datapack schema, doubled for a loader one release behind. Likeliest failure is a half-landed P1/P3 refactor. Keep each P independently green and committed.
2. `commit()` rewrite regressing 0.5.0 reliability; `DIAGNOSIS.md` records two historical sync bugs here. P1 lands with no schema change so regressions are attributable.
3. Save migration v2→v3 plus resumable enrichment where `setDirty` is not a durable commit. Double-run re-award or interrupted-but-complete coverage is data loss.
4. ABI: any new API signature mentioning an internal type silently forces `apiExports` to grow. `verifyApiJar` checks exports/resources, not signature closure; the external compile fixture is the real guard.
5. Reconciliation-gate bypass via the second (profile) time channel. Every profile read/write goes through `ReconciliationService` with an explicit `Intent`.
6. Seam visibility (`ServiceContext` package-private) may force `profile`/`credit` into `reputation` or widen the seam (D6).
7. Cross-loader drift: two loaders, two MCA pins, two protocol lineages, two GUI asset systems.
8. Companions: local Crime (0.7.2) and Conversations (1.7.1) are ahead of the spec's pins; audit findings still reproduce.
9. Toolchain: `gradlew` already modified; JDK 17 vs 21; NF relies on foojay-provisioned 21 plus network access to the Conczin Maven (absent → `McaBinaryAbiTest` self-disables).

## 9. Unresolved decisions

- D1 Release shape: ship Forge 0.6.0 as "core complete / suite adoption pending" (spec §16.4), or block on Quests/Conversations/Crime adoption? Decides whether P8 is in scope.
- D2 Companion write access: may changes be made inside `MCAQuests`, `MCAConversations`, `MCACrime`? Default no. Re-pin companion review commits to local HEADs?
- D3 NeoForge save-format numbering: recommend adopting Forge's 1→2→3 exactly to preserve cross-loader byte identity. Spec §4.3 warns against mechanical copying; confirm.
- D4 NeoForge protocol number: NF is at "4" with incompatible framing. Recommend "5" plus a doc note that loader protocol numbers are not comparable; alternative "6".
- D5 NeoForge versioning path: separate 0.5.0 parity commit before 0.6.0 (recommended), or jump 0.4.1→0.6.0? Releasing the intermediate is a separate question.
- D6 Package layout vs seam: promote a minimal `ServiceContext` read/now/policy view to public/internal-SPI so `profile/` and `credit/` can be top-level (recommended), or nest inside `reputation/`?
- D7 Tuning defaults: accept spec §17 recognition/facet numbers, §7.2 six tiers, 28/56-day lifetimes, `maxFacetOpinionAdjustment=25`, all-zero `tail_bp` as unplaytested starting values?
- D8 Legacy enrichment: ship §19.2 conservative historical enrichment in 0.6.0, or structural migration with everything `LEGACY_UNENRICHED` and defer enrichment?
- D9 Runtime certification: authorize `runServer`/`runClient` and the §24.1 16-combination matrix, or is delivery "builds + automated tests green"?
- D10 Housekeeping: OK to `git worktree prune` and create the local `neoforge/1.21.1` branch/worktree?
