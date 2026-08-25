# MCA: Reputation — Phase 0 inspection and written reconciliation

> Required by spec §39 Phase 0. Records what the checked-out source actually says, where it differs
> from the specification, and the final class/file plan. Written before any implementation code.

**Audit date:** 2026-08-14 · **Target:** `mcareputation` 0.1.0 · **Spec:** `MCA-Reputation-Initial-Version-Spec.md`

---

## 1. Audited revisions

| Project | Spec's audited revision | Revision actually checked out | Match |
|---|---|---|---|
| MCA: Quests | `5b1db389ae0afcdbafdbb4a4192d685e2287bbe6` | `5b1db38` — *"Fold the abandon and card-overflow work into the 1.0.0 changelog"* | ✅ exact |
| MCA: Conversations | `c13ae585c9317463b6f3cbc987fa80e86ad0b6c3` | `c13ae58` — *"Phase 5: the personal topics — life story, dreams, hopes, regrets, secrets"* | ✅ exact |

The §2 audit therefore holds verbatim; no re-audit amendment is required. Both siblings declare
`mod_version=1.1.0` in `gradle.properties` (working toward an unreleased 1.1.0), so the companion
releases produced here are `1.1.0`.

## 2. MCA API confirmation (spec §39 items 4 and 6)

Verified with `javap` against the **deobf-mapped** jars ForgeGradle already has cached for both
supported MCA versions:

```
~/.gradle/caches/forge_gradle/deobf_dependencies/maven/modrinth/minecraft-comes-alive-reborn/
    7.6.20+1.20.1_mapped_official_1.20.1/…jar
    7.7.0-beta.2+1.20.1_mapped_official_1.20.1/…jar
```

### 2.1 Village identity and residents — identical on 7.6.20 and 7.7.0-beta.2

| Signature | Used for |
|---|---|
| `VillageManager.get(ServerLevel)` | entry point |
| `VillageManager#getOrEmpty(int) : Optional<Village>` | community lookup by id |
| `VillageManager#findNearestVillage(BlockPos, int) : Optional<Village>` | fallback-radius resolution |
| `Village#getId() : int` | `CommunityKey.villageId` |
| `Village#getName() : String` | cached community metadata |
| `Village#getCenter() : Vec3i` | cached community metadata |
| `Village#isWithinBorder(BlockPos, int) : boolean` | residency/anchor checks |
| `Village#getResidents(ServerLevel) : List<VillagerEntityMCA>` | loaded residents (awareness) |
| `Village#getResidentsUUIDs() : Stream<UUID>` | full residency, load-independent |
| `Village#getResidentNames() : Map<UUID,String>` | subject/witness display names |
| `VillagerEntityMCA#getResidency().getHomeVillage() : Optional<Village>` | primary community resolution |

Every one of these is byte-identical across the two versions. **One binary supports both; no
reflection and no version branch is needed.** This resolves the §39 "STOP" gate in the
non-blocking direction.

### 2.2 Known 7.6 → 7.7 drift

`forge.net.mca.entity.ai.relationship.Personality` is an `enum` on 7.6 and a registry-backed class on
7.7, so neither `name()` nor `getPersonalityId()` exists on both. Conversations already solved this by
going through `Object#toString()` and normalising (`"ODD"` / `"mca:odd"` → `"odd"`). Reputation
consumes personality only as an opaque display/gossip hint, so it uses the same `toString()` path.

### 2.3 Village ID uniqueness → `CommunityKey` still keeps the dimension

`VillageManager` is a `SavedData` obtained per `ServerLevel`, so village IDs are allocated **per
dimension** and two dimensions can absolutely hold the same numeric ID. Even if they could not, §39
directs us to retain the dimension. `CommunityKey(ResourceLocation dimension, int villageId)` is final.

### 2.4 Interaction-screen injection point (§39 item 6) — **no mixin needed**

`forge.net.mca.client.gui.InteractScreen` extends `AbstractDynamicScreen` → `ExtendedScreen` →
`net.minecraft.client.gui.screens.Screen`, and its `render`/`mouseClicked` overrides both call
`super`, so vanilla `renderables`/`children` are rendered and clickable. Its villager field is
`private final VillagerLike<?> villager` with **no getter** on either version.

**Decision — deviation from §11's "MCA menu injection mixin/accessor":** the mod ships **zero
mixins**. The Standing button is added through Forge's `ScreenEvent.Init.Post` (checking
`screen instanceof InteractScreen`), and the villager identity comes from a client-side record of the
last entity the player interacted with (`PlayerInteractEvent.EntityInteract`), **not** from reading
MCA's private field. The claimed entity ID is then validated server-side exactly as §27.2 already
demands (existence, same dimension, living MCA villager, ≤ 12 blocks). This is strictly *more*
robust than an accessor mixin — an accessor cannot be made optional, so an MCA field rename would
hard-fail mixin application at startup, whereas this path degrades to "button does nothing useful".
§11 calls mixins a last resort; there is no last resort here.

Consequence: `mods.toml` has no `[[mixins]]` block and there is no mixin JSON to lint, which
discharges the §36.5 mixin checks vacuously.

## 3. Quests reputation surface — complete enumeration (spec §39 item 3)

Every direct read/write of reputation, tier, or title state in Quests at `5b1db38`. Column 3 is the
Phase 5 disposition.

### 3.1 Writes (all already funnel through `ReputationService.award`)

| Site | Current call | Phase 5 |
|---|---|---|
| `quest/QuestManager.java:480` | `ReputationService.award(…)` for `village_reputation` rewards | route via bridge; per-player |
| `project/ProjectManager.java:350,363,638,648` → `:412 addReputation` | `award(data, server, state.identity(), delta, null)` — **anonymous, no player** | per-recipient incidents |
| `project/ProjectRewardDistributor.java:63,119` | `award(server, "v:"+villageId, amount, player)` | route via bridge |
| `quest/situation/SituationManager.java:298-299` | `award(server, "v:"+instance.villageId(), outcome.reputation(), player)` | route via bridge |
| `compat/ftbq/McaVillageReputationReward.java:62` | `award(…)`; `:67` banks via `addBankedReward` | route via bridge |
| `command/McaQuestsCommand.java:394,404` | `award(…)` for `set`/`add` | route via bridge |
| `quest/reputation/ReputationService.java:38-53` | the funnel itself: `data.reputation/addReputation/tierHighWater/setTierHighWater`, `ReputationTierReachedEvent` | becomes the **legacy backend**, only reachable when the bridge is unavailable |

### 3.2 Direct `ProjectSavedData` reads that bypass the funnel (must be re-routed)

| Site | Current call |
|---|---|
| `quest/condition/leaf/VillageReputationCondition.java:46` | `ProjectSavedData.get(...).reputation("v:"+id)` |
| `quest/condition/leaf/ReputationTierCondition.java:60` | `ProjectSavedData.get(...).reputation("v:"+id)` |
| `quest/JournalService.java:78,90` | `saved.reputationKeys()`, `saved.reputation("v:"+id)` |
| `project/ProjectManager.java:831` | `ProjectSavedData.get(server).reputation(identity)` |
| `command/McaQuestsCommand.java:377,393` | `ProjectSavedData.get(server).reputation(...)` |
| `quest/reputation/ReputationService.java:104-105,117-120,139` | `villageReputation`, `allVillageReputations`, `currentTier` |

An `rg`-style assertion test (§29.1) will fail the Quests build if a `ProjectSavedData` reputation
call reappears outside the legacy backend and the migration reader.

### 3.3 Title and tier reads

`TitleService.grantGlobal/grantVillage/grant` (`command/McaQuestsCommand.java:192,203`,
`compat/ftbq/McaGrantTitleReward.java:80,92`, `project/ProjectRewardDistributor.java:186,193`,
`quest/reward/GrantTitleReward.java:39`, `quest/reputation/ReputationService.java:162`);
`ReputationTiers.get/getDefault/ids` (`command/McaQuestsCommand.java:378,411`,
`compat/ftbq/FtbqBridgeImpl.java:293`, `compat/ftbq/McaReputationTierTask.java:69`,
`network/FtbqEditorIdsSync.java:69-71`, `quest/JournalService.java:55`,
`data/ReputationTierLoader.java`, `data/TitleLoader.java`).

### 3.4 Legacy save shapes that migration must read

`ProjectSavedData` (`<world>/data/mcaquests_projects.dat`) — `reputation: CompoundTag<String,int>`
keyed `"v:<villageId>"` (world-shared, **not** per player), and `repTierHW: CompoundTag<String,String>`
identity → tier id. `PlayerTitles` (in player NBT) — `global: ListTag<String>` and
`villages: CompoundTag<"<villageId>", ListTag<String>>`.

## 4. Reconciled deviations from the specification

These are the only places the implementation knowingly differs. Behavioural contracts, persistence
rules, compatibility behaviour, and test requirements are unchanged.

1. **Tier IDs are `String`, not `ResourceLocation`** (affects §18 `ReputationResult`, §26 events).
   Quests' shipped `ReputationTier.CODEC` already uses `Codec.STRING.fieldOf("id")` and its
   `repTierHW` NBT stores bare strings such as `"honored"`. Ladder IDs stay `ResourceLocation`
   (`mcaquests:default`). Forcing tier IDs to `ResourceLocation` would break every existing Quests
   tier datapack and high-water tag for no gain. **Ladder** ID stays a `ResourceLocation` everywhere.
2. **Tier `name` accepts a plain string or a text component.** §22.1 already requires accepting the
   legacy plain-string form; the implementation makes it a true either-or codec so `mcaquests`
   ladders and `mcareputation` ladders both load. Title `name`/`description` behave the same way.
3. **No mixins at all.** See §2.4 above.
4. **`GLOBAL_RESERVED` visibility is spelled `GLOBAL_RESERVED` in code and accepts `"global"` in
   JSON**, since §7/§14 use both spellings for the same value.
5. **The `assaultCoalesceTicks` bucket is a sliding window keyed on the first qualifying hit**, not a
   world-time modulus. A modulus bucket would let a player split a beating across a bucket boundary
   for a second full penalty; §20.1's wording ("coalesce by player + victim + 200-tick bucket") is
   satisfied more strictly by the sliding window. The window lives in
   `ReputationService.findRecent`; the incident's *dedupe key* uses the exact creation tick (not a
   `gameTime / coalesce` bucket), because a new record always starts a fresh window and the key only
   has to absorb the same damage event firing twice in one tick.
6. **`org.gradle.java.home` is not set** in this repo's `gradle.properties`. Both siblings hardcode
   an absolute Linux JDK path there, which makes them unbuildable elsewhere — §36.5 explicitly bans
   "accidental absolute JDK path requirement" in the distributed build, so the new repo relies on
   `JAVA_HOME` / the foojay toolchain resolver instead.
7. **`McaReputationApi.resolve` drops the spec's `Optional<String> dedupeKey` parameter** (§25's
   sketch). Resolution idempotency is intrinsic — the status ratchet refuses anything not strictly
   stronger — so a resolution dedupe key would have nothing to protect and could only disagree with
   the ratchet.
8. **`/mcareputation tiers` carries no permission requirement.** The ladder is public information
   every player already sees in the standing screen; gating the text listing would protect nothing.
9. **Per-ladder scoring is not implemented in this version.** `ReputationSnapshot.ladder` is always
   the default ladder id and every scoring path evaluates `mcareputation:default` (with the
   `mcaquests:default` alias). Datapacks may *define* additional ladders — `/mcareputation tiers
   <ladder>` lists them — but nothing scores against them yet; wiring a ladder id through the
   transaction is future work, deliberately not half-built into the save format.
10. **The tier high-water mark seeds from the tier a player already stood in** the first time any
   transition is evaluated for a community (including records loaded from a pre-seed save). Without
   the seed, dipping below your starting tier and climbing back would read as a first-time milestone
   and fire the celebration for a tier you began the game in.
11. **`TitleDefinition.revocable`, `TitleDefinition.icon`, and the `BuiltinIncidents.SOURCE_*`
   constants are reserved surface** — parsed, documented, and carried, but nothing consumes them in
   this version. Datapacks may set them without effect; a later version wires them without a format
   change.

## 5. Sibling optional-compile workflow (spec §39 item 7)

Confirmed from `MCAConversations/build.gradle`: the established pattern is a `compileOnly
files("${projectDir}/../<Sibling>/build/classes/java/main")` guarded by `file.exists()`, with a
`logger.warn` when absent. Reputation adopts nothing in this direction (§9.2: it must have **no**
compile-time dependency on either sibling). Quests and Conversations each add one such guarded block
pointing at `../MCAReputation/build/classes/java/main`.

Build order for a full-suite build: **MCAReputation → MCAQuests → MCAConversations**
(Conversations already compiles optionally against Quests as well).

## 6. Forge event availability (spec §39 item 5)

Confirmed present in Forge 47.4.10 for 1.20.1 and used as follows:

- `LivingHurtEvent` — assault attribution (fires after armour/absorption, carries the final amount,
  is cancellable, and is server-side-checkable via `entity.level().isClientSide`).
- `LivingDeathEvent` — killing attribution and the assault→killing upgrade.
- `PlayerEvent.PlayerLoggedInEvent` — lazy decay reconciliation and legacy migration trigger.
- `TickEvent.ServerTickEvent` — the bounded, rate-limited online-player reconciliation only
  (`reconcileOnlineIntervalTicks`, default 1200); never a world/village/incident scan.
- `AddReloadListenerEvent` — datapack registries.
- `RegisterCommandsEvent`, `ServerStoppingEvent` — commands and shutdown flush.
- `ScreenEvent.Init.Post`, `PlayerInteractEvent.EntityInteract` (client) — Standing button.

Damage attribution reads `DamageSource#getEntity` (owner: shooter/thrower/tamer) and
`DamageSource#getDirectEntity` (the projectile/potion itself), which covers the direct, projectile,
thrown-potion, and tamed-pet cases in §20.1 without any per-source special casing.

## 7. Final file plan

> **Drift note (2026-08-17).** This section is the *pre-implementation* plan and is preserved as
> written. The shipped tree differs in the expected ways: the entry point split into
> `McaReputationMod`, several planned loader/validator classes merged into
> `data/ReputationReloadListener` + `data/ReputationContentValidator`, the planned
> `command/CommunityArgument` now exists (it was initially skipped and restored in review),
> `state/LegacyQuestsImporter` moved into Quests as its `QuestsLegacyImportProvider`, and
> `util/EnumCodecs`, `util/StrictCodecs`, `network/ClientPacketHandler`, `network/ReputationFeedback`,
> `incident/BuiltinIncidents`, `incident/IncidentDisplay`, `incident/ResolutionPolicy`,
> `reputation/Titles`, and `reputation/ServiceContext` were added. The parenthetical below on
> `McaCompat` is also out of date: `compat/McaScreenCompat` imports `forge.net.mca.*` too — the real
> rule, asserted by `OptionalClassloadTest`, is that only the `compat` *package* may.

Follows §11 exactly, with these concrete additions:

```
dev.otectus.mcareputation
├── McaReputation.java                  mod entry point, server lifecycle, LOGGER
├── McaReputationConfig.java            COMMON + CLIENT ForgeConfigSpec, clamped accessors
├── api/  McaReputationApi, ReputationRequest, ReputationResult, ReputationSnapshot,
│         ReputationIncidentView, ResolutionResult, ImportResult, LegacyImportRequest,
│         ReputationMirror, ExternalGossipCandidate, ReputationQuery
│   └── event/  ReputationChangedEvent, ReputationTierChangedEvent,
│               ReputationIncidentCreatedEvent, ReputationIncidentResolvedEvent,
│               ReputationTitleGrantedEvent
├── compat/  McaCompat (ONLY class importing forge.net.mca.*)
├── community/  CommunityKey, CommunityMetadata, CommunityResolver
├── incident/  IncidentDefinition, IncidentRegistry, IncidentLoader, IncidentValidator,
│              IncidentRecord, IncidentStatus, IncidentVisibility, IncidentSeverity,
│              IncidentSubject, SubjectKind, DecayPolicy, GossipSpec,
│              WitnessResolver, AwarenessResolver, IncidentSelector
├── reputation/  ReputationService, ReputationTier, ReputationTierSet, ReputationTiers,
│                TitleDefinition, TitleScope, TitleService, ReputationMath, ReputationBounds
├── state/  ReputationSavedData, PlayerReputationRecord, CommunityReputationRecord,
│           MigrationState, LegacyQuestsImporter
├── event/  ReputationGameplayEvents, AssaultTracker
├── data/  ReputationReloadListener, TierLoader, TitleLoader, JsonHelpers
├── command/  ReputationCommand, CommunityArgument
├── network/  ReputationNetwork + 5 packet records
└── client/  ReputationClient, ClientReputationData, ReputationScreen,
             ReputationTierToast, StandingButtonHook, ReputationKeybinds
```

`compat/McaBridge.java` from §11's sketch is folded into `compat/McaCompat.java`: with no
version branching required (§2.1) a second indirection layer would be dead weight. `client/` is
reached only from a `Dist.CLIENT` `DistExecutor` call and a `@Mod.EventBusSubscriber(value =
Dist.CLIENT)`, so no client class is referenced from dedicated-server initialisation (§36.5).
