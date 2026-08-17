# MCA: Reputation — Initial-Version Build Specification

> **Target release:** `0.1.0` (feature-complete initial public release)
>
> **Mod ID:** `mcareputation`
>
> **Java package:** `dev.otectus.mcareputation`
>
> **Platform:** Minecraft Java Edition 1.20.1, Forge 47.4.10+, Java 17
>
> **Required mod:** MCA Reborn 7.6–7.7
>
> **Optional companion mods:** MCA: Quests and MCA: Conversations
>
> **License:** GPL-3.0-only
>
> **Specification date:** 2026-08-14

## 1. How to use this specification

This document is the authoritative implementation specification for the initial version of **MCA: Reputation** and the companion integration changes required in **MCA: Quests** and **MCA: Conversations**.

The implementing agent must treat the requirements labeled **MUST**, **MUST NOT**, **REQUIRED**, and **ACCEPTANCE** as release gates. Suggested class names may be adjusted when repository inspection proves that another name fits the existing architecture better, but behavioral contracts, persistence rules, compatibility behavior, and test requirements may not be silently weakened.

Before writing code, the agent must complete the Phase 0 audit in §39 and record any material differences between this specification and the checked-out source. If an audited fact invalidates a design decision, stop and document the conflict rather than improvising an incompatible substitute.

The work spans three repositories:

1. A new `MCAReputation` repository containing the core mod.
2. A compatibility release of `MCAQuests` that delegates its reputation surfaces when MCA: Reputation is present while preserving standalone behavior.
3. A compatibility release of `MCAConversations` that consumes reputation state for dialogue, checks, and gossip while preserving standalone behavior.

The three mods must remain independently installable as follows:

- MCA Reborn + MCA: Reputation works.
- MCA Reborn + MCA: Quests works exactly as before.
- MCA Reborn + MCA: Conversations works exactly as before.
- Any pair of the add-ons works.
- All three add-ons together provide the full closed social loop.

## 2. Audited baseline

This specification was written against the following source revisions:

| Project | Audited revision | Repository |
|---|---|---|
| MCA: Quests | `5b1db389ae0afcdbafdbb4a4192d685e2287bbe6` | <https://github.com/otectus/MCAQuests> |
| MCA: Conversations | `c13ae585c9317463b6f3cbc987fa80e86ad0b6c3` | <https://github.com/otectus/MCAConversations> |

Important audited facts:

- Quests already contains `ReputationService`, reputation tiers, titles, tier toasts, tier/title events, `village_reputation` rewards and conditions, project/situation reputation, FTB Quests integration, and a Journal.
- Quests currently persists reputation inside `ProjectSavedData` under string scope identities such as `v:<villageId>`.
- Those values are currently **shared by the world/scope**, not keyed by player UUID. Every player therefore reads the same village score even though UI and documentation describe a player's standing.
- Existing reputation keys omit dimension identity. MCA village IDs are resolved through a level-specific `VillageManager`; a bare integer is not a sufficient long-term cross-dimension key.
- Quests titles are per player, but village-scoped titles currently use a bare integer village ID.
- Quests routes most reputation writes through one `ReputationService.award` funnel, but conditions and Journal code still read `ProjectSavedData` directly. All direct reads must be found and routed through the bridge/service during integration.
- Conversations already owns a per-villager/per-player hidden disposition vector: trust, respect, warmth, attraction, tension, and familiarity.
- Conversations already owns the built-in village gossip log and per-teller/per-listener “already told” memory behavior.
- Conversations' gossip event type is currently a closed enum and the event record has a fixed two-subject shape. Supporting external reputation incidents requires an extensible normalized gossip candidate, not another parallel gossip UI.
- Conversations already has a proven optional-classloading pattern in `QuestsBridge` and `compat.quests`.
- Conversations' deterministic check score currently combines disposition/hearts, personality fit, mood/state adjustment, and a seeded roll. Reputation must be a small additional context term, not a second relationship vector.
- Both projects isolate MCA internal imports in compatibility classes and use safe fallback behavior because MCA Reborn has no stable public API.

If the implementing agent checks out later revisions, it must re-run this audit and amend the implementation plan before coding.

## 3. Product definition

MCA: Reputation is the suite's **public memory and civic consequence layer**.

It answers four questions:

1. What has this player done in or for this village?
2. Who knows about it?
3. How has it changed the player's public standing?
4. How do other systems turn that history into dialogue and opportunities?

The player-facing promise is:

> Your deeds become stories, and those stories shape how the village treats you.

Reputation is not a duplicate hearts system and not a duplicate Conversations disposition vector. Hearts remain MCA's visible individual-affection economy. Conversations remains the owner of private interpersonal nuance. Reputation owns public, player-to-community state.

## 4. Responsibility boundaries

| Concern | Owning system | Required rule |
|---|---|---|
| Individual affection | MCA Reborn hearts | Reputation never grants hearts implicitly. |
| Private trust, respect, warmth, attraction, tension, familiarity | MCA: Conversations | Reputation never persists a duplicate per-villager vector. |
| Quest objectives, projects, situations, restitution work | MCA: Quests | Reputation supplies conditions, incidents, and outcomes; it does not implement a second quest engine. |
| Public standing with a village | MCA: Reputation | Per player, per dimension-aware village. |
| Public deed/incident history | MCA: Reputation | Canonical, structured, server-authoritative. |
| Who can currently tell a public story | MCA: Reputation | Conversations presents it but does not own the fact. |
| Dialogue wording and personality voice | MCA: Conversations | Reputation never implements a competing conversation UI. |
| Reputation/tier/title display | MCA: Reputation | Quests Journal may embed/delegate to the same data. |

## 5. Goals

The initial release must:

1. Provide per-player, per-village public reputation that is safe in multiplayer and across dimensions.
2. Retain a simple visible score and named tier while recording the structured incidents that explain it.
3. Detect a small, reliable set of core MCA-relevant player actions without per-tick world scans.
4. Track witnesses and deterministic, lazy village awareness so villagers do not know unwitnessed acts magically.
5. Provide an extensible datapack format for tiers, titles, and incident types.
6. Expose a stable, server-authoritative Java API and Forge events for other add-ons.
7. Integrate every existing Quests reputation read/write surface without double-awarding or breaking Quests-only installations.
8. Integrate Conversations through reputation-aware dialogue, a bounded check modifier, a standing topic, and the existing gossip presentation flow.
9. Preserve existing Quests scores, tiers, titles, datapacks, commands, FTB integrations, and worlds through explicit migration and compatibility aliases.
10. Provide a usable standalone UI when Quests or Conversations is absent.
11. Be bounded, exploit-resistant, idempotent, data-driven, and safe on dedicated servers.
12. Support MCA Reborn `7.6.20` and `7.7.0-beta.2` from one binary unless Phase 0 demonstrates a hard blocker.

## 6. Non-goals for the initial release

The initial version must not expand into the following systems:

1. No replacement for MCA hearts, marriage, family, mood, rank, or personality.
2. No visible per-villager reputation meter.
3. No profession-, family-, faction-, race-, or inter-village reputation scores.
4. No autonomous legal code, prisons, bounties, exile AI, guard arrest behavior, or village combat hostility.
5. No automatic trade-price rewrite. This can be added later after compatibility testing with modded merchants.
6. No global fame score. A `GLOBAL` visibility value may be reserved in the API, but initial gameplay remains village-scoped.
7. No fully simulated villager-to-villager conversation network. Awareness spreads lazily and deterministically; there is no ambient AI chatter tick.
8. No AI/LLM text generation, remote service, telemetry, or network call.
9. No Fabric, NeoForge, Minecraft 1.21+, or backport work.
10. No automatic reputation from routine trading, generic gifts, or repeated conversation clicks. These are too farmable and overlap existing relationship systems.
11. No silent takeover of Conversations dispositions. Reputation may affect check context only through the bounded term in §30.
12. No destructive rewrite of existing Quests save data. Legacy tags remain readable and retained.

## 7. Terminology

- **Community:** A dimension-aware MCA village identified by `CommunityKey`.
- **Standing:** The player's integer reputation score with a community.
- **Tier:** The named band containing a standing score.
- **Incident:** A structured record explaining a reputation-affecting or narratively relevant deed.
- **Contribution:** The portion of an incident's delta currently included in the standing score after decay/resolution.
- **Visibility:** Who can initially know an incident: private, witnessed, village, or reserved global.
- **Witness:** A living MCA villager who directly observed an incident.
- **Aware villager:** A resident who either witnessed the incident or is deemed to have learned it through deterministic lazy propagation.
- **Subject:** A villager, player, or other named entity involved in an incident.
- **Source:** The system that created the incident, such as a core combat hook, a quest, project, situation, conversation choice, command, or another mod.
- **Dedupe key:** A stable identifier proving that one logical outcome is applied at most once.
- **Baseline:** Non-incident standing imported from legacy data or set by an administrator.
- **Resolution:** A state change such as atoned, forgiven, disproven, or expired that can reduce an incident's current contribution without deleting history.

## 8. Required gameplay loop

The complete loop is:

1. A player performs an action, completes/fails structured work, or makes an explicitly public choice.
2. The server resolves a community, incident definition, subjects, witnesses, visibility, source, and dedupe key.
3. `ReputationService` commits one atomic transaction: incident record, contribution, standing, tier transition, title grant, dirty state, and post-commit events.
4. The player receives restrained feedback.
5. Conversations may present the incident through the existing Village/Events dialogue and may use current standing as bounded context.
6. Quests may unlock, suppress, branch, or resolve work based on standing, tier, title, or incident state.
7. A restitution or follow-up outcome can resolve the original incident, adjust its contribution, and create a new public incident documenting the resolution.

No participating mod may bypass the core transaction funnel to mutate canonical standing directly.

## 9. Platform and build requirements

### 9.1 Core build

- Java toolchain: 17.
- Minecraft: `1.20.1`.
- Forge: compile/test against `47.4.10`; metadata range `[47,)`.
- Official mappings: `1.20.1`.
- Compile against MCA Reborn `7.7.0-beta.2+1.20.1`.
- Runtime MCA metadata range: `[7.6,8)`.
- Test production-style runtime with MCA `7.6.20+1.20.1` and `7.7.0-beta.2+1.20.1`.
- Do not declare Architectury as a dependency of MCA: Reputation. MCA 7.6 supplies its own requirement; MCA 7.7 removed it. The new mod must contain no Architectury references.
- Use ForgeGradle 6 and the repository patterns already established by Conversations.
- Use JUnit Jupiter `5.10.2` for pure/unit tests.
- License `GPL-3.0-only` because the mod links MCA Reborn internals.

### 9.2 Optional companion compilation

MCA: Reputation must have no compile-time dependency on Quests or Conversations.

Quests and Conversations may compile against MCA: Reputation as `compileOnly` and must never shade or bundle it. Until a Maven artifact exists, mirror Conversations' sibling-class-output development pattern or use an included/composite build. The build must emit an actionable warning if the optional classes are unavailable rather than silently compiling an integration-less release.

### 9.3 Runtime combinations

`mods.toml` rules:

- `mcareputation` requires `mca`, loads after it, side `BOTH`.
- Quests adds optional dependency `mcareputation`, loads after it when present.
- Conversations adds optional dependency `mcareputation`, loads after it when present.
- Conversations continues to load after Quests when Quests is present.
- No optional dependency may become mandatory through a class signature, static field initializer, annotation, mixin target, or event-subscriber autoload.

## 10. High-level architecture

```text
MCA Reborn / Forge world events
              |
              v
    MCA: Reputation core
    - CommunityResolver
    - IncidentRegistry
    - Witness/Awareness logic
    - ReputationService
    - ReputationSavedData
    - API + Forge events
    - UI/network/commands
         |             |
         |             |
         v             v
MCA: Conversations   MCA: Quests
- dialogue context   - conditions/rewards
- check bias         - projects/situations
- gossip voice       - restitution content
- standing topic     - Journal delegation
```

Core ownership rule:

- When MCA: Reputation is installed, its store and service are canonical.
- Quests must route all reputation-facing behavior through its optional bridge and must not separately apply the same change.
- Quests retains an internal fallback backend so a Quests-only installation preserves existing behavior.
- The Quests integration maintains a dimension-aware, per-player mirror of canonical scalar/tier/title state for graceful fallback if MCA: Reputation is later removed. Incident history remains owned by MCA: Reputation and is not mirrored.
- Conversations never owns or mirrors score state.

## 11. New repository structure

The exact file split may evolve, but responsibilities must remain isolated. Recommended layout:

```text
dev.otectus.mcareputation
├── McaReputation.java
├── McaReputationConfig.java
├── api/
│   ├── McaReputationApi.java
│   ├── ReputationRequest.java
│   ├── ReputationResult.java
│   ├── ReputationSnapshot.java
│   ├── ReputationIncidentView.java
│   └── event/
├── compat/
│   ├── McaCompat.java
│   └── McaBridge.java
├── community/
│   ├── CommunityKey.java
│   ├── CommunityResolver.java
│   └── CommunityMetadata.java
├── incident/
│   ├── IncidentDefinition.java
│   ├── IncidentRegistry.java
│   ├── IncidentLoader.java
│   ├── IncidentValidator.java
│   ├── IncidentRecord.java
│   ├── IncidentStatus.java
│   ├── IncidentVisibility.java
│   ├── IncidentSeverity.java
│   ├── IncidentSubject.java
│   ├── DecayPolicy.java
│   ├── WitnessResolver.java
│   └── AwarenessResolver.java
├── reputation/
│   ├── ReputationService.java
│   ├── ReputationTier.java
│   ├── ReputationTierSet.java
│   ├── ReputationTiers.java
│   ├── TitleDefinition.java
│   ├── TitleService.java
│   └── ReputationMath.java
├── state/
│   ├── ReputationSavedData.java
│   ├── PlayerReputationRecord.java
│   ├── CommunityReputationRecord.java
│   └── MigrationState.java
├── event/
│   └── ReputationGameplayEvents.java
├── data/
│   ├── ReputationReloadListener.java
│   └── validation helpers
├── command/
│   └── ReputationCommand.java
├── network/
│   ├── ReputationNetwork.java
│   └── packet records
└── client/
    ├── ReputationScreen.java
    ├── ClientReputationData.java
    ├── ReputationTierToast.java
    └── MCA menu injection mixin/accessor
```

Rules:

- Only `compat.mca` may import `forge.net.mca.*`.
- The public API must expose Minecraft/Java types, not MCA internal types.
- Pure score, tier, decay, awareness, NBT, and validation logic must remain testable without a running game.
- Client classes must not load on a dedicated server.
- Mixins are a last resort and must be minimal, accessor/injection-only, and covered by JSON lint.

## 12. Community identity

### 12.1 Canonical key

Use a dimension-aware immutable key:

```java
public record CommunityKey(ResourceLocation dimension, int villageId) {}
```

`villageId` must be non-negative. `dimension` must parse as a valid resource location.

Never persist or compare a community using only `int villageId`.

### 12.2 Resolution order

Given an MCA villager:

1. Ask MCA for the villager's home village.
2. Use the villager's current level dimension with that village ID.
3. If no home village exists, search the nearest MCA village within the configured fallback radius, default `128` blocks.
4. If no village resolves, the operation returns `Optional.empty()` and creates no public reputation change.

Given a player-only action:

1. Use an explicitly supplied community when a trusted server subsystem has one.
2. Otherwise use the nearest village within the fallback radius.
3. Never invent an anchor community for the initial release.

### 12.3 Metadata

Cache the following alongside a known community:

- Last known village name.
- Last known center position.
- Last resolved game time.

Use live MCA values when available. Use cached values when a village is unloaded, renamed, deleted, or inaccessible. Final fallback is a localized “Village #<id>” label including the dimension when ambiguous.

### 12.4 MCA compatibility

`McaCompat` must provide safe-fail wrappers for:

- is MCA villager;
- home village ID/name/center;
- nearest village;
- village exists/name/center/border;
- resident UUIDs and loaded residents;
- villager name, age group, personality, and current level;
- line-of-sight when used for witnessing.

Every method catches `Throwable`, logs at DEBUG when configured, and returns an empty/false fallback. Resolve MCA 7.6/7.7 personality drift without reflection if possible, following Conversations' current compatibility approach.

## 13. Canonical persistence model

### 13.1 Storage location

Use overworld `DimensionDataStorage` and save to:

```text
<world>/data/mcareputation.dat
```

One world-global `SavedData` object stores every player and every dimension-aware community. All mutations call `setDirty()`.

### 13.2 Top-level format

Persist a format version and bounded player records:

```text
version: int
players: compound keyed by player UUID
migrations: compound
```

Recommended initial format version: `1`.

### 13.3 Player record

Each player record contains:

- `communities`: list/map of community records.
- `globalTitles`: ordered unique title IDs.
- `migrationMarkers`: source ID → version/status.
- Optional last-known MCA character name for offline diagnostics only.

### 13.4 Community record

Each community record contains:

- `CommunityKey`.
- Cached community metadata.
- `baseline`: admin/imported, non-decaying integer.
- `score`: cached total after contribution reconciliation.
- `incidents`: bounded insertion-ordered incident list.
- `tierHighWater`: map of ladder ID → highest tier ID ever reached.
- `titles`: ordered unique village-scoped title IDs.
- `lastReconciledGameTime`.

Invariant:

```text
score = clamp(baseline + sum(currentContribution of retained active/history incidents))
```

The service may cache `score`, but tests must prove it can recompute and repair the value from baseline + incident contributions. A corrupted cached score must not become authoritative.

### 13.5 Bounds

Defaults:

- Score clamp: `-1000..1000`.
- Maximum incidents per player per community: `64`.
- Maximum incidents across one player: `512`.
- Maximum witnesses stored per incident: `32`.
- Maximum subjects stored per incident: `4`.
- Maximum context keys: `16`.
- Maximum context key length: `64`.
- Maximum context value/component JSON length: `4096`.
- Maximum dedupe key length: `256`.

When a cap is reached, prune in this order:

1. Expired zero-contribution incidents past retention.
2. Resolved zero-contribution incidents, oldest first.
3. Non-notable zero-contribution incidents, oldest first.
4. Oldest non-pinned incident.

Never prune an incident whose contribution is non-zero without first folding that contribution into baseline, preserving the score exactly. Never prune a pinned/notable incident unless an administrator explicitly clears it.

### 13.6 Robust loading

- Parse each player, community, title, and incident entry independently.
- A malformed entry is skipped with DEBUG/WARN context; it must not discard siblings or abort world load.
- Use `ResourceLocation.tryParse` or guarded constructors.
- Clamp integers using `long` intermediates to prevent overflow.
- Unknown incident definitions do not invalidate records. Display the ID fallback and retain their stored contribution/status.
- Unknown enum/string values fall back safely and generate a validation warning.

## 14. Incident record model

An incident record must contain at least:

```text
id                    UUID
type                  ResourceLocation
player                UUID
community             CommunityKey
createdGameTime       long
updatedGameTime       long
source                ResourceLocation
dedupeKey             optional bounded string
baseDelta             int
currentContribution   int
visibility            PRIVATE | WITNESSED | VILLAGE | GLOBAL_RESERVED
severity              TRIVIAL | MINOR | MODERATE | MAJOR | SEVERE
status                ACTIVE | APOLOGIZED | ATONED | FORGIVEN | DISPROVEN | EXPIRED
subjects              bounded list of IncidentSubject
witnesses             bounded UUID set
context               bounded typed/string map
pinned                boolean
```

`IncidentSubject` stores:

- subject kind: villager, player, entity, community, or other;
- UUID when applicable;
- cached display name;
- optional role such as victim, giver, sponsor, child, spouse, witness, or beneficiary.

Records are server-created and immutable except for status, current contribution, updated time, awareness-related cached data if later required, and pinned state.

### 14.1 Private incidents

Private incidents are narrative obligations or memories, not public standing. Their default contribution must be `0`, and validation must reject a non-zero private default unless an explicit `allow_private_score` development-only override is present. The shipped pack must never use that override.

### 14.2 Dedupe

Every integration-created incident must supply a stable dedupe key. Applying the same key for the same player and community returns the prior result and makes no mutation, event, toast, title grant, or mirror write.

Recommended keys:

- Quest: `quest:<questId>:<giverUuid>:<startGameTime>:<outcome>`.
- Situation: `situation:<instanceUuid>:<playerUuid>:<resolution>`.
- Project phase: `project:<projectId>:<instanceKey>:phase:<index>:<playerUuid>`.
- Project completion/failure: `project:<projectId>:<instanceKey>:<outcome>:<playerUuid>`.
- Conversation decision: `conversation:<villagerUuid>:<playerUuid>:<decisionId>:<policyBucket>`.
- Core assault: `assault:<playerUuid>:<victimUuid>:<coarseTimeBucket>`.
- Core killing upgrade: reuse the related assault group and store a distinct terminal key.

The store may index recent dedupe keys for lookup, but the index must be rebuilt from records on load and remain bounded.

## 15. Incident definitions

Incident behavior is data-driven under:

```text
data/<namespace>/mcareputation/incidents/**/*.json
```

Definition identity is the resource location derived from namespace + relative file path.

Required/optional fields:

| Field | Type | Required | Meaning |
|---|---|---:|---|
| `display` | text component | yes | UI description/template. |
| `default_delta` | int | yes | Default score contribution. |
| `visibility` | enum | yes | Initial knowledge rule. |
| `severity` | enum | yes | UI/notification/pruning weight. |
| `tags` | string list | no | Bounded semantic tags. |
| `retention_ticks` | long | no | How long zero-contribution history remains. |
| `decay` | object | no | `none` or `linear_to_zero`. |
| `resolution` | object | no | Contribution multipliers per resolution. |
| `gossip` | object | no | Gossip tone/key/variables. |
| `pinned` | boolean | no | Preserve as notable history. |
| `max_override_abs` | int | no | Maximum allowed caller delta override. |

Text components must support literal or translatable forms. Supported template/context variables:

- `{player}`
- `{village}`
- `{subject}` / `{subject_2}`
- `{source_title}`
- `{giver}`
- `{amount}`

Definitions must be parse-safe. Invalid definitions are skipped and reported by validation; they must not crash `/reload` or world creation.

### 15.1 Decay policies

Initial release supports only:

1. `none`: contribution never changes automatically.
2. `linear_to_zero`: after optional `delay_ticks`, move the contribution toward zero by `amount_per_day` for each complete Minecraft day elapsed.

Decay is reconciled lazily on query, mutation, player login, UI open, and a bounded periodic online-player reconciliation. It must not require scanning all saved players or all incidents every tick.

Each incident stores its current applied contribution. Reconciliation calculates the deterministic expected contribution at the current game time, applies only the difference to cached score, updates the record, and posts one merged change notification at most.

Clock rollback or `/time set` must never reverse decay and restore lost contribution. Use `updatedGameTime`/last-reconciled state monotonically.

### 15.2 Resolution multipliers

Example:

```json
{
  "resolution": {
    "apologized": 0.75,
    "atoned": 0.25,
    "forgiven": 0.0,
    "disproven": 0.0
  }
}
```

Resolving an incident atomically adjusts its current contribution to the configured rounded-toward-zero value, changes status, updates score/tier, and records the resolution source. Resolution never deletes the original record.

Repeated resolution to the same or a weaker status is idempotent. A later stronger resolution may further reduce the contribution. `DISPROVEN` cannot be downgraded.

## 16. Required built-in incident types

Ship and localize at least:

| ID | Default delta | Visibility | Decay | Notes |
|---|---:|---|---|---|
| `mcareputation:villager_assaulted` | `-8` | witnessed | linear, 2/day after 2 days | Nonlethal public harm. |
| `mcareputation:villager_killed` | `-40` total target | witnessed | none | Upgrades recent assault; does not stack to `-48`. |
| `mcareputation:quest_completed` | caller override | village | none | Generic fallback for Quests. |
| `mcareputation:quest_failed` | caller override | village | configurable/none | Only authored failures create it. |
| `mcareputation:quest_abandoned` | caller override | village | configurable/none | Only authored abandon outcomes create it. |
| `mcareputation:project_phase_completed` | caller override | village | none | Per eligible contributor. |
| `mcareputation:project_completed` | caller override | village | none | Per participant. |
| `mcareputation:project_failed` | caller override | village | configurable | Explicit negative only. |
| `mcareputation:situation_resolved` | caller override | village | none | Success or failure context. |
| `mcareputation:promise_made` | `0` | private | none | Conversation/quest obligation. |
| `mcareputation:promise_kept` | `+8` | witnessed or village | none | Usually created by a quest resolution. |
| `mcareputation:promise_broken` | authored | witnessed | configurable | Not automatic for every abandoned quest. |
| `mcareputation:public_apology` | `+1` | witnessed | linear | Cannot erase the underlying deed. |
| `mcareputation:restitution_completed` | `+4` | village | none | Also resolves a target incident. |
| `mcareputation:legacy_balance` | `0` | private/system | none | Hidden migration marker; the imported numeric balance is stored in baseline. |

Caller-override types must define safe maximums. Default `max_override_abs` is `100`.

## 17. Score, tier, and title semantics

### 17.1 Score

- Starting score is `0`.
- Score is per player + community.
- Default clamp is `-1000..1000`.
- All math uses `long` intermediates and clamps before storing `int`.
- Administrative baseline changes are distinct from incidents and require an audit log message.
- A zero-delta incident may still be stored when narratively meaningful, but it does not send score feedback.

### 17.2 Default tier ladder

The default ladder extends Quests' existing positive thresholds without changing their positive meaning:

| Tier | Threshold | Trust bias | Respect bias |
|---|---:|---:|---:|
| Infamous | `-300` | `-4` | `-8` |
| Hated | `-150` | `-3` | `-6` |
| Distrusted | `-75` | `-2` | `-4` |
| Wary | `-25` | `-1` | `-2` |
| Stranger | `0` | `0` | `0` |
| Acquaintance | `25` | `1` | `2` |
| Friend | `75` | `2` | `4` |
| Honored | `150` | `3` | `6` |
| Revered | `300` | `4` | `8` |

The lowest tier is the floor for scores below its threshold. Thresholds must strictly ascend. Tier IDs must be unique.

### 17.3 Tier transitions

- Post a transition event whenever current tier changes upward or downward.
- Send the existing celebratory toast only on upward transitions above the stored high-water mark.
- Downward transitions use restrained action-bar/chat feedback, not a celebration toast.
- High-water is tracked per player + community + ladder.
- Re-entering a previously reached tier does not regrant its title or replay its first-time toast.

### 17.4 Titles

- Titles are earned badges, not the same as current standing.
- Tier-granted titles remain earned if score later falls unless a title definition explicitly declares `revocable: true`. No shipped initial title is revocable.
- Support `GLOBAL` and `VILLAGE` scopes, with village titles keyed by `CommunityKey`.
- Title grants are idempotent and post an event only when newly added.
- Initial UI displays titles but does not add overhead nameplates or an active-title selector.

## 18. Reputation transaction service

Every mutation must use one service entry point. A recommended request/result contract is:

```java
public record ReputationRequest(
    MinecraftServer server,
    UUID playerId,
    CommunityKey community,
    ResourceLocation incidentType,
    ResourceLocation source,
    Optional<String> dedupeKey,
    OptionalInt deltaOverride,
    Optional<IncidentVisibility> visibilityOverride,
    List<IncidentSubject> subjects,
    Set<UUID> witnesses,
    Map<String, String> context,
    long gameTime
) {}

public record ReputationResult(
    boolean applied,
    UUID incidentId,
    int oldScore,
    int newScore,
    int appliedDelta,
    ResourceLocation oldTier,
    ResourceLocation newTier
) {}
```

The actual API may use a builder to keep call sites readable.

Transaction order:

1. Assert server thread or schedule onto it.
2. Validate and bound request fields.
3. Resolve incident definition.
4. Check dedupe.
5. Reconcile existing lazy decay for that player/community.
6. Compute visibility and effective delta.
7. Create/store incident.
8. Update score and cached metadata.
9. Resolve old/new tier and update high-water.
10. Grant any new title.
11. Mark save dirty.
12. Mirror through registered optional sink(s) after canonical commit.
13. Post non-cancellable Forge events.
14. Send bounded player feedback if online.

If optional mirror/integration code throws, canonical commit remains valid; catch `Throwable`, log the bridge failure, and continue. If canonical validation fails, apply nothing.

The service must also expose:

- query score/tier/snapshot;
- query known communities;
- query recent/notable incidents;
- query whether a villager knows an incident;
- resolve an incident by UUID or validated selector;
- grant/revoke/query titles;
- set/add baseline for commands/migration;
- reconcile decay;
- import a legacy snapshot idempotently.

## 19. Witnessing and awareness

### 19.1 Witness resolution

Witness scans occur only when a relevant event happens. Do not scan villages every tick.

For a core physical incident:

1. Resolve the affected community from the victim/home village.
2. Query loaded MCA villagers in an AABB around the event, default radius `24` blocks.
3. Exclude dead entities, spectators/non-villagers, and the acting player.
4. Exclude the victim from the stored broadcaster set if the incident kills the victim.
5. Require line-of-sight by default. This is configurable.
6. Sort deterministically by distance then UUID.
7. Cap stored witnesses at `32`.
8. A living nonlethal victim always knows their own assault even if no third party saw it.

A `WITNESSED` incident with no surviving witness must be retained only as a private/secret zero-contribution record if the incident type is marked `retain_unwitnessed`; otherwise skip it. The shipped killing definition retains it as hidden history with zero public contribution. It may become public only through a future explicit confession/discovery integration; automatic discovery is out of scope.

### 19.2 Visibility rules

- `PRIVATE`: only living subjects explicitly marked as knowing may use it; no natural spread.
- `WITNESSED`: witnesses know immediately. Other residents learn it after deterministic lazy spread delay.
- `VILLAGE`: every current resident of the community knows immediately.
- `GLOBAL_RESERVED`: accepted by API/serialization but treated as `VILLAGE` in the initial UI and Conversations integration. Log a DEBUG note when used.

### 19.3 Lazy deterministic spread

Do not store a growing “knows incident” set for every villager.

For a non-witness resident querying a `WITNESSED` incident, derive a stable delay from:

```text
hash(incident UUID, villager UUID, community key)
```

Map the hash into configurable `[minRumorDelayTicks, maxRumorDelayTicks]`, default `6000..48000` ticks. The villager knows the incident once:

```text
currentGameTime - incident.createdGameTime >= deterministicDelay
```

The result must be monotonic: once true, it cannot become false. A villager must currently belong to the same community unless they were an original witness.

This produces plausible propagation without tick work, mutable pairwise knowledge state, or save bloat.

### 19.4 “Already told” ownership

When Conversations is installed, it continues to own the per-teller/per-listener “already told” flag using the villager's MCA `LongTermMemory`. Reputation answers only whether the teller knows the fact and provides the normalized tellable incident.

When Conversations is absent, the Reputation UI may show a recent-deeds list; it does not simulate telling.

## 20. Core automatic gameplay events

The initial release deliberately detects a narrow, reliable set.

### 20.1 Villager assault

Use Forge living damage/attack events on the server. Attribute the action to a `ServerPlayer` when:

- the direct source is the player;
- a projectile owner is the player;
- a thrown potion owner is the player;
- optionally, a tame owned by the player causes damage when `attributeTamedDamage=true`.

Requirements:

- Target must be a living MCA human villager, not an MCA zombie variant unless Phase 0 intentionally broadens this.
- Ignore cancelled events and damage below configurable `minimumIncidentDamage`, default `1.0`.
- Do not create one incident per damage tick. Coalesce by player + victim + 200-tick bucket.
- The first qualifying hit creates `villager_assaulted`; later hits in the bucket may update context such as accumulated damage but do not add score again.
- Store victim as subject and resolve witnesses at the first qualifying hit.
- If the attack is clearly self-defense—victim directly damaged the player in the preceding configurable `selfDefenseWindowTicks`, default `100`—multiply the default penalty by configurable `selfDefenseMultiplier`, default `0.25`, rounding toward zero. If reliable attribution cannot be proven, apply normal rules rather than guessing.

### 20.2 Villager killing

On `LivingDeathEvent`, attribute the responsible player using the same source rules.

- Find a recent coalesced assault for the same player/victim.
- Upgrade the public contribution so total assault + killing penalty equals the killing target (`-40` by default), not `-48`.
- Preserve the assault record for narrative chronology or convert it to a linked precursor; either is acceptable if score invariants and UI remain clear.
- Recompute witnesses at death and union surviving witnesses with prior witnesses, capped deterministically.
- If no surviving witness exists, the killing remains hidden/zero-contribution as specified in §19.1.
- Killing a child or family member does not receive a special hardcoded multiplier in `0.1.0`; datapacks may define future variants.

### 20.3 Explicitly excluded automatic events

Do not infer reputation from these in the initial release:

- every trade;
- gifts;
- merely entering or leaving a village;
- healing via arbitrary modded mechanics;
- curing unless a trusted integration emits an explicit event;
- generic mob kills outside a Quests situation/project;
- sleeping, marriage, birth, divorce, or family membership;
- blocks placed/broken outside an authored Quest/Project;
- chat messages that are not authored Reputation actions.

These exclusions prevent overlap, false attribution, and farming.

## 21. Data reload, registries, and validation

### 21.1 Resource paths

Canonical paths:

```text
data/<namespace>/mcareputation/incidents/**/*.json
data/<namespace>/mcareputation/reputation_tiers/**/*.json
data/<namespace>/mcareputation/titles/**/*.json
```

Compatibility paths when Quests is present:

```text
data/<namespace>/mcaquests/reputation_tiers/**/*.json
data/<namespace>/mcaquests/titles/**/*.json
```

Canonical `mcareputation` path wins when both define the same ID. Log one warning naming both sources.

### 21.2 Atomic reload

- Parse into temporary maps.
- Validate every object and cross-reference.
- Log all validation problems together.
- Swap live immutable registries only after the complete reload succeeds.
- Invalid individual definitions are skipped in lenient mode.
- `strictJsonValidation=true` converts any error into reload failure without destroying the previous live registry.

### 21.3 Validation rules

Validate:

- unique IDs;
- strictly ascending tier thresholds;
- tier floor `<= configured minimum score` or at least `<= 0`;
- trust/respect bias absolute value `< 15` and shipped values within `8`;
- referenced title definitions exist;
- incident deltas and override bounds are within configured hard safety caps;
- private incident defaults are zero;
- decay values are non-negative and terminate;
- resolution multipliers are `0.0..1.0`;
- tags/context variable names are bounded and syntactically valid;
- translation/component forms parse;
- no invalid visibility/status/severity values;
- no title namespace/path conflict;
- default ladder exists;
- all shipped incident definitions and titles have localization.

Provide `/mcareputation validate` and include exact file/ID/field in every error.

## 22. Tier and title datapack formats

### 22.1 Tier ladder

```json
{
  "tiers": [
    {
      "id": "wary",
      "threshold": -25,
      "name": { "translate": "mcareputation.tier.wary" },
      "description": { "translate": "mcareputation.tier.wary.description" },
      "trust_bias": -1,
      "respect_bias": -2
    },
    {
      "id": "honored",
      "threshold": 150,
      "name": { "translate": "mcareputation.tier.honored" },
      "trust_bias": 3,
      "respect_bias": 6,
      "grants_title": "mcaquests:honored_of_village"
    }
  ]
}
```

For legacy Quests compatibility, accept a plain string `name` exactly as the existing codec does.

### 22.2 Title

```json
{
  "name": { "translate": "mcareputation.title.village_guardian" },
  "description": { "translate": "mcareputation.title.village_guardian.description" },
  "scope": "village",
  "revocable": false,
  "icon": "minecraft:shield"
}
```

`icon` is an item resource location used only for UI rendering. Missing/invalid icons fall back to a name-tag or emerald icon and never invalidate ownership.

## 23. Configuration

Use Forge common/server-authoritative and client config files:

```text
config/mcareputation-common.toml
config/mcareputation-client.toml
```

### 23.1 Common configuration

Required options and defaults:

```text
enableReputation=true
minimumScore=-1000
maximumScore=1000
defaultVillageSearchRadius=128
enableCoreAssaultIncidents=true
enableCoreKillingIncidents=true
minimumIncidentDamage=1.0
witnessRadius=24
maxWitnesses=32
requireWitnessLineOfSight=true
attributeTamedDamage=true
selfDefenseWindowTicks=100
selfDefenseMultiplier=0.25
assaultCoalesceTicks=200
minRumorDelayTicks=6000
maxRumorDelayTicks=48000
maxIncidentsPerCommunity=64
maxIncidentsPerPlayer=512
reconcileOnlineIntervalTicks=1200
enableScoreDecay=true
enableTierTitles=true
strictJsonValidation=false
enableQuestsIntegration=true
enableConversationsIntegration=true
mirrorQuestsFallbackState=true
migrateLegacyQuestsData=true
debugLogging=false
```

Validation/clamping:

- Minimum score must be lower than maximum.
- Radii must be bounded to safe server values, e.g. `1..128` for witnesses and `16..512` for village fallback.
- Witness/incident caps must have hard upper limits even if config is edited manually.
- Rumor maximum must be >= minimum.
- Multipliers must be finite and bounded.
- Disabling a subsystem preserves save data and changes behavior only; it does not delete records.

### 23.2 Client configuration

```text
showReputationButton=true
showChangeActionBar=true
showTierToasts=true
showNegativeTierMessages=true
mergeChangeNotifications=true
showExactScore=true
showIncidentDeltas=true
```

Exact-score and delta hiding are presentation only. Server logic remains unchanged.

## 24. Commands

Root command: `/mcareputation`, alias `/mcarep` optional.

Self-query commands require no elevated permission. Mutations require permission level 2.

Required command tree:

```text
/mcareputation get [community|here]
/mcareputation get <player> [community|here]
/mcareputation list [player]
/mcareputation history [player] [community|here] [limit]
/mcareputation add <player> <amount> [community|here] [reason]
/mcareputation set <player> <amount> [community|here] [reason]
/mcareputation incident add <player> <type> [community|here]
/mcareputation incident list [player] [community|here]
/mcareputation incident resolve <player> <incidentUuid> <status>
/mcareputation incident pin <player> <incidentUuid> <true|false>
/mcareputation title grant <player> <title> [global|community]
/mcareputation title revoke <player> <title> [global|community]
/mcareputation title list [player]
/mcareputation tiers [ladder]
/mcareputation validate
/mcareputation reload
/mcareputation migrate status [player]
/mcareputation migrate run <player|all-online> [--dry-run]
/mcareputation debug community
/mcareputation debug witnesses
```

Rules:

- `here` resolves nearest community server-side.
- Explicit community syntax must include dimension and village ID, with Brigadier suggestions from known communities.
- All output is localized where player-facing; debug/admin details may be literal but must be clear.
- Mutating commands produce an audit log with executor, target, community, old/new score, and reason/source.
- `/reload` is preferred for datapacks; the mod-specific reload command may invoke the same safe reload path.
- Existing `/mcaquests reputation` and `/mcaquests title` commands delegate to canonical state when Reputation is installed.

## 25. Public Java API

Package all stable integration types under `dev.otectus.mcareputation.api`. Do not expose internal mutable records or MCA types.

Minimum API:

```java
public final class McaReputationApi {
    public static Optional<CommunityKey> resolveCommunity(Entity villager);
    public static Optional<CommunityKey> resolveCommunity(ServerLevel level, BlockPos pos, int radius);
    public static OptionalInt getScore(MinecraftServer server, UUID player, CommunityKey community);
    public static Optional<ReputationSnapshot> getSnapshot(MinecraftServer server, UUID player,
                                                           CommunityKey community);
    public static ReputationResult record(ReputationRequest request);
    public static ResolutionResult resolve(MinecraftServer server, UUID player, CommunityKey community,
                                           UUID incident, IncidentStatus status, ResourceLocation source,
                                           Optional<String> dedupeKey);
    public static boolean hasTitle(MinecraftServer server, UUID player, ResourceLocation title,
                                   Optional<CommunityKey> community);
    public static boolean grantTitle(...);
    public static List<ReputationIncidentView> recentIncidents(...);
    public static boolean villagerKnows(MinecraftServer server, Entity villager, UUID player, UUID incident);
    public static ImportResult importLegacy(LegacyImportRequest request);
}
```

API contracts:

- Server thread only for writes.
- Queries never expose mutable collections.
- Unknown player/community returns empty/zero according to method documentation.
- Every public method catches integration-facing failures and either returns a typed failure result or documented fallback; it must not crash a dialogue/quest evaluation path.
- Binary API classes are versioned/documented in `API.md`.
- Add `getApiVersion()` returning initial integer `1` so bridges can reject unsupported future versions gracefully.

### 25.1 Extension registration

Expose setup-time registration for custom incident definition codecs or providers only if a concrete initial consumer requires it. Standard datapack incident definitions are sufficient for most add-ons. Do not build a generalized plugin framework without a use case.

Allow optional mirror sinks through a narrowly scoped interface:

```java
public interface ReputationMirror {
    void mirrorScore(UUID player, CommunityKey community, int score,
                     ResourceLocation ladder, String highWaterTier);
    void mirrorVillageTitle(UUID player, CommunityKey community, ResourceLocation title);
    void mirrorGlobalTitle(UUID player, ResourceLocation title);
}
```

Mirror failures are caught and logged after canonical commit.

## 26. Forge events

Post server-side, non-cancellable, immutable events after canonical commit:

1. `ReputationChangedEvent`
   - player UUID and nullable online `ServerPlayer`;
   - community;
   - old/new score and applied delta;
   - incident UUID/type;
   - source ID.
2. `ReputationTierChangedEvent`
   - old/new tier IDs and indices;
   - upward/downward;
   - first-time/high-water boolean.
3. `ReputationIncidentCreatedEvent`
   - immutable incident view.
4. `ReputationIncidentResolvedEvent`
   - old/new status and contribution;
   - resolution source.
5. `ReputationTitleGrantedEvent`
   - title, scope, community optional.

Do not expose a pre-change cancellable event in `0.1.0`; cancellation would complicate idempotency and cross-mod atomicity. Add-ons should influence deltas through authored data or by creating/resolving incidents.

## 27. Networking and security

### 27.1 Channel

Use a dedicated SimpleChannel with explicit protocol version `1`. Register packets in deterministic order.

Required packets:

- `RequestReputationSnapshotC2S` — optional selected community/context villager entity ID.
- `ReputationSnapshotS2C` — bounded known-community list and selected community detail.
- `OpenReputationScreenS2C` — tells client to open after validated server interaction.
- `ReputationChangeS2C` — merged feedback.
- `ReputationTierToastS2C` — first-time upward transition.

### 27.2 Server authority

- Clients cannot send score, delta, title, incident, witness, or status values.
- Snapshot requests are rate-limited, default at most one per 10 ticks per player.
- Context-villager requests validate entity existence, same dimension, living MCA villager, and interaction distance no greater than 12 blocks.
- A player may request only their own data unless using an authorized command path.
- Packet list sizes and component/string lengths are bounded before encode and after decode.
- Packet handlers enqueue work on the correct thread and mark handled.
- Never trust a client-supplied village ID without server resolution/known-community validation.

### 27.3 Synchronization

- Sync only on screen open, explicit request, or a change affecting the online player.
- Do not sync the full incident ledger on every change.
- Send at most the newest 50 visible incident summaries per selected community.
- Merge same-tick/multi-reward changes into one feedback packet.

## 28. Standalone UI and UX

### 28.1 Entry points

1. Inject a **Standing** button into MCA's villager interaction screen when the target resolves to a village and `showReputationButton=true`.
2. Register an **Open Reputation** keybind, unbound by default to avoid conflicts.
3. Quests Journal supplies a button/link to the same screen when Quests is installed.
4. Conversations supplies an immersive “What do people think of me?” topic when installed.

The button is the guaranteed standalone path. The keybind opens the known-community index and preselects the player's current/nearest community if available.

### 28.2 Screen layout

Use existing MCA/Quests visual language rather than a visually unrelated full-screen menu.

Required elements:

- community name and dimension when needed;
- current tier;
- exact score if client config permits;
- progress toward next positive tier or distance from neutral for negative tiers;
- earned village titles;
- scrollable recent/notable incidents;
- incident description, age, status, and delta if enabled;
- obvious empty state;
- known-community selector when more than one exists;
- back/close behavior consistent with originating screen.

At small GUI scales:

- no clipping;
- component text wraps;
- incident list scrolls;
- buttons remain reachable;
- no more than one nested modal.

### 28.3 Feedback

- Routine changes use one merged action-bar line, e.g. `Village reputation +12 — Friend`.
- First-time upward tier uses a toast with village and tier.
- Downward tier uses a subdued message.
- Zero-delta narrative incidents do not display numeric feedback.
- Core assault feedback should not reveal an unwitnessed secret. Only notify of a public score change when at least one surviving witness made the incident public.
- Hidden exact-score client mode uses named tiers and qualitative progress.

### 28.4 Accessibility/localization

- Do not communicate polarity by color alone; include sign/text/icon.
- All UI strings use translation keys.
- Ship `en_us` and `pt_br` for the new core mod if feasible; `en_us` is mandatory.
- All Conversations integration additions must maintain its existing `en_us`/`pt_br` locale parity and personality-overlay lint requirements.

## 29. MCA: Quests integration

This section requires a companion Quests release. Quests must remain fully functional without MCA: Reputation.

### 29.1 Bridge/classloading seam

Add an always-loadable `compat/ReputationBridge` with zero `mcareputation` imports and a no-op/legacy-backed default. Put all actual imports under `compat/reputation/**`, loaded only after:

```java
ModList.get().isLoaded("mcareputation")
```

Wrap initialization in `catch (Throwable)` so binary drift disables integration with one clear ERROR rather than crashing.

Every existing direct reputation/title/tier read and write must route through a single Quests-side facade. Audit at least:

- `ReputationService`;
- `VillageReputationCondition`;
- `ReputationTierCondition`;
- `JournalService`;
- `QuestManager.grantVillageReputationRewards`;
- `ProjectManager` reputation paths;
- `ProjectRewardDistributor`;
- `SituationManager.applyOutcome`;
- FTB reputation tasks/rewards;
- commands;
- tier/title services/events;
- validation/editor known-ID paths.

An `rg` assertion/test must fail if forbidden direct `ProjectSavedData.reputation(...)` calls remain outside the legacy backend/migration code.

### 29.2 Quests-only fallback

When Reputation is absent:

- preserve current rewards, conditions, tiers, titles, Journal, projects, situations, FTB integration, commands, and events;
- upgrade the fallback store to per-player, dimension-aware v2 semantics where practical;
- read legacy shared `v:<id>` values through migration described in §32;
- do not require Reputation datapacks or classes.

### 29.3 Quest reputation outcome block

Add an optional top-level block to quest definitions:

```json
{
  "reputation": {
    "complete": {
      "delta": 12,
      "incident": "mcareputation:quest_completed",
      "visibility": "village",
      "tags": ["service", "reliable"]
    },
    "fail": {
      "delta": -4,
      "incident": "mcareputation:quest_failed",
      "visibility": "village"
    },
    "abandon": {
      "delta": -2,
      "incident": "mcareputation:quest_abandoned",
      "visibility": "witnessed"
    }
  }
}
```

Rules:

- `complete`, `fail`, and `abandon` are all optional.
- Failure/abandon default to no reputation change.
- Existing `mcaquests:village_reputation` quest reward remains valid. If no `reputation.complete` exists, translate the sum of existing rewards into a generic `quest_completed` request.
- If both exist, the top-level block is authoritative and the legacy reward is display/compatibility only; never apply both. Validation warns about ambiguity.
- Use quest title, ID, giver UUID/name, category, active start time, and template-frozen title context.
- Completion transaction happens once inside the existing atomic claim flow, after reward claim guard and before event notification. Any bridge failure must not block quest completion.
- Fail and abandon paths use explicit authored outcomes only.

### 29.4 Project reputation

Extend `ReputationSpec` fields to accept existing integer shorthand or an object:

```json
{
  "reputation": {
    "on_phase_complete": {
      "delta": 3,
      "incident": "mcareputation:project_phase_completed",
      "recipients": "phase_contributors"
    },
    "on_project_complete": {
      "delta": 10,
      "incident": "mcareputation:project_completed",
      "recipients": "all_participants"
    },
    "on_fail": {
      "delta": -4,
      "incident": "mcareputation:project_failed",
      "recipients": "all_participants"
    }
  }
}
```

Integer shorthand defaults:

- phase complete → current-phase contributors;
- project complete → all players who contributed at least once;
- project fail → all participants/contributors;
- zero → nobody.

The current shared world score must not receive a single anonymous award. Each recipient gets an idempotent player/community incident.

Existing shared reward target `sponsor_village` attached to `village_reputation` must be interpreted as `all_participants` under per-player Reputation, with a one-time validation warning recommending explicit recipient syntax. Other shared reward targets retain their normal recipient sets.

### 29.5 Situations

Extend situation outcome reputation from integer shorthand to optional object:

```json
{
  "success": {
    "reputation": {
      "delta": 16,
      "incident": "mcareputation:situation_resolved",
      "recipients": "resolving_player"
    }
  }
}
```

Defaults:

- success → resolving player;
- failure → accepted participants if delta is explicitly negative;
- cleared → nobody unless explicitly authored;
- never award players who neither accepted nor resolved the situation.

Use situation instance UUID in dedupe key. The existing `SituationResolvedEvent` remains valid.

### 29.6 Conditions and rewards registered when both mods are present

Quests must expose or register:

- existing `mcaquests:village_reputation` → canonical per-player score;
- existing `mcaquests:reputation_tier` → canonical tier;
- existing `mcaquests:grant_title` → canonical title service;
- `mcareputation:has_incident` condition;
- `mcareputation:incident_status` condition;
- `mcareputation:resolve_incident` reward;
- optional `mcareputation:record_incident` reward for pack authors who need a zero/nonstandard standalone incident.

Example restitution reward:

```json
{
  "type": "mcareputation:resolve_incident",
  "selector": {
    "type": "mcareputation:villager_assaulted",
    "status": "active",
    "newest": true
  },
  "resolution": "atoned"
}
```

Selectors must be bounded, server-resolved, and deterministic. UUID selection is preferred when a chain has stored it. Never let a client choose an arbitrary incident to resolve.

### 29.7 Journal

When Reputation is present:

- Quests Journal reads score/tier/title snapshots through the bridge.
- It includes dimension-aware community identity.
- It adds a `View Deeds` button opening Reputation's screen for the selected community.
- It does not maintain or display a contradictory local score.
- Completed-quest archive remains owned by Quests.

When Reputation is absent, the current Journal remains usable through the fallback backend.

### 29.8 Legacy Quests API events

For compatibility with consumers:

- Translate Reputation's first-time upward tier change into the existing `ReputationTierReachedEvent`.
- Translate newly granted titles into the existing `TitleGrantedEvent`.
- Do not post each event twice for a Quests-originated transaction.
- Document that the existing village ID accessors cannot express dimension; add new dimension/community accessors without removing old methods. Old methods return the integer ID for source compatibility.

### 29.9 FTB Quests

All current FTB reputation tasks/rewards must delegate through the same bridge:

- per-player score and tier;
- dimension-aware nearest village resolution;
- title reads/grants;
- banked reputation delivery;
- recheck on Reputation change/tier/title events.

No FTB path may write the old shared map directly. Existing FTB authoring IDs and serialized objects remain compatible.

## 30. MCA: Conversations integration

This section requires a companion Conversations release. Conversations must remain fully functional without Reputation.

### 30.1 Bridge/classloading seam

Add `compat/ReputationBridge` following the exact design discipline of `QuestsBridge`:

- always-loaded interface contains only Java/Minecraft types;
- guarded implementation under `compat.reputation` imports Reputation API;
- initialize only after mod-present check;
- catch `Throwable` and fall back to unavailable;
- every dialogue query returns a safe neutral value on failure.

Recommended query surface:

```java
interface ReputationQueries {
    boolean isAvailable();
    int score(ServerPlayer player, Entity villager);
    String tierId(ServerPlayer player, Entity villager);
    int checkBias(ServerPlayer player, Entity villager, String axis);
    boolean matches(ServerPlayer player, Entity villager, ReputationQuery query);
    Optional<ExternalGossipCandidate> nextGossip(ServerPlayer player, Entity teller,
                                                  Set<String> types, long maxAge);
    void markTold(ServerPlayer player, Entity teller, UUID incidentId);
    boolean recordConversationSignal(ServerPlayer player, Entity villager,
                                     ReputationSignal signal);
}
```

### 30.2 Dialogue conditions

Always register parse-safe keys so datapacks do not crash when Reputation is absent:

```text
conversations_reputation
conversations_reputation_incident
```

Suggested `conversations_reputation` JSON:

```json
{
  "min": 75,
  "max": 299,
  "min_tier": "friend",
  "max_tier": "honored",
  "has_title": "mcareputation:village_guardian"
}
```

All fields are optional and ANDed. Unknown tier/title/community safely returns 0. When integration is absent or disabled, return 0 so authored disabled-context fallbacks fire.

Suggested incident query:

```json
{
  "types": ["mcareputation:villager_assaulted"],
  "statuses": ["active", "apologized"],
  "tags": ["crime"],
  "known_to_speaker": true,
  "max_age": 168000
}
```

### 30.3 Check-context modifier

Amend `CheckInputs` with an explicit `publicStandingFit` integer and include it in `CheckResolver` score:

```text
score = axis/hearts term
      + hearts term
      + personality fit
      + public standing fit
      + mood/state adjustment
      + seeded roll
```

Rules:

- Only `TRUST` and `RESPECT` checks receive a non-zero term.
- Read the term from current tier definition.
- Hard clamp to `[-8, 8]` even if malformed external data bypasses validation.
- Warmth, attraction, tension, and familiarity receive `0`.
- Reputation never writes a disposition axis and never grants hearts.
- The term remains smaller than the 15-point tier margin so public standing colors but cannot single-handedly determine a check.
- With Reputation absent/disabled, value is exactly `0` and all existing deterministic seeds/outcomes remain unchanged except for the record-constructor change required by tests.

### 30.4 External gossip normalization

Do not create a second gossip menu or seed duplicate generic `QUEST` events.

Refactor Conversations gossip selection around a normalized candidate type that can represent built-in and external facts:

```text
id
typeId (ResourceLocation/string)
created
community/village
phrase key/prefix
localized component arguments
teller eligibility
already-told identity
source provider
```

Requirements:

- Existing marriage/divorce/death/birth/arrival/departure/quest gossip remains behaviorally compatible.
- Legacy bare names deserialize as `mcaconversations:<name>`.
- The built-in saved log remains its own provider.
- Reputation bridge supplies candidates from incidents the teller knows.
- Combine candidates and select newest deterministically by creation time then stable ID.
- The condition and say action must query the same normalized selection so they cannot disagree.
- “Already told” remains `mcaconversations.gossip.<eventUuid>.<playerUuid>` in MCA long-term memory.
- External translation/component arguments may exceed the old fixed A/B subject pair but are capped at four.
- If Reputation disappears, external candidates simply vanish; built-in gossip remains intact.

When Reputation is active, `ConversationsQuestsEvents` continues to set giver quest memories and proud/annoyed states, but skips seeding the generic completed-quest `QUEST` gossip because Reputation's named quest incident is the canonical story. Existing persisted QUEST gossip is retained until normal expiry.

### 30.5 Standing topic

Add a Village-category topic reachable through GUI and chat:

- “What do people think of me around here?”
- Variants for positive, neutral, negative, and unresolved-incident states.
- Villager answers reference tier and, where available, one recent known notable deed.
- Player may ask how to make amends when a resolvable negative incident exists.
- The answer can open a Quests restitution offer/menu when Quests is installed and eligible.
- Babies/toddlers do not deliver civic assessments. Children/teens receive age-appropriate reduced lines if included.
- Provide safe neutral/no-village/no-Reputation fallbacks.
- Both GUI and typed-chat frontends must reach the same topic and decisions.
- Maintain every existing branching-conversation invariant: 2–5 answers, graceful exit, explicit replay policy, no reward on opener/navigation, and locale/overlay parity.

### 30.6 Reputation conversation action

Always register a parse-safe action:

```text
conversations_reputation_signal
```

Example:

```json
{
  "incident": "mcareputation:public_apology",
  "decision": "standing.apology.public",
  "visibility": "witnessed",
  "policy": "once_per_incident"
}
```

The action references an incident definition; it does not accept arbitrary unbounded score deltas. It must route through Conversations' decision/replay guards before calling Reputation. Generic small talk, navigation, asking the opener, and repeated apology clicks cannot award standing.

An apology may reduce an original incident only when an authored action explicitly resolves it. The shipped standing topic's basic apology creates the small `public_apology` incident and sets Conversations state, but does **not** erase the original deed. Restitution/forgiveness remains a later authored outcome.

### 30.7 Template variables

Add safe optional variables to Conversations' template engine:

- `reputation_tier`
- `reputation_score` (only used where exact display is intended)
- `reputation_village`
- `reputation_recent_deed`
- `reputation_title`

Missing values use neutral localized fallbacks and never break a line.

## 31. Combined narrative flows

### 31.1 Promise → quest → public deed

1. Villager discusses a personal/community problem in Conversations.
2. Player chooses an authored promise stance.
3. Conversations records `promise_made`, private, zero score, with villager and decision context.
4. Conversations signals Quests or opens the eligible quest.
5. Quest completion resolves the promise and records `promise_kept`/`quest_completed` with one dedupe transaction.
6. Reputation adjusts standing and creates a village-visible incident.
7. Conversations villagers can tell the named story once each.
8. Quests tier/incident conditions can unlock follow-up work.

Abandoning does not automatically harm reputation unless the quest author supplied an abandon outcome. If authored, a broken promise may begin witnessed/private and become publicly tellable according to awareness rules.

### 31.2 Raid/situation

1. Quests opens a village raid situation.
2. Player accepts and completes the defense objective.
3. Situation resolution creates one incident for the resolving player.
4. The incident records situation ID, village, giver/subjects, and public service tags.
5. Reputation crosses a tier if appropriate.
6. Conversations uses praise/relief gossip in personality voice.
7. Quests unlocks defender/leadership work at the new tier.

### 31.3 Assault → confrontation → restitution

1. Player publicly attacks a resident.
2. Reputation records witnesses and a negative contribution.
3. The victim/witnesses can confront or gossip through Conversations.
4. “How can I make this right?” surfaces an eligible restitution quest.
5. Completion uses `resolve_incident` to mark the assault `ATONED`, reducing its remaining penalty according to definition.
6. A separate `restitution_completed` incident adds a small positive public deed.
7. The victim's Conversations disposition/memory may remain wary; public forgiveness does not reset private relationship state.

## 32. Migration and backward compatibility

### 32.1 Legacy Quests standing audit problem

Existing Quests reputation is keyed by scope/village and is not player-specific. The migration cannot reconstruct historical individual contributions. The initial migration must prioritize preserving the standing users previously saw while making future state correct.

### 32.2 Migration policy

On first login/query with Quests + Reputation installed:

1. Detect whether player migration marker `mcaquests:legacy_reputation_v1` exists.
2. Read legacy shared `v:<id>` scores and tier high-water values.
3. Treat legacy IDs as `minecraft:overworld` because the old key omitted dimension.
4. A player is eligible to inherit the legacy snapshot when any is true:
   - they have Quests completion/failure/abandon history;
   - they have an active quest;
   - they have Quests progression stats;
   - they hold any Quests title;
   - they are the singleplayer owner/current integrated-server player;
   - config/admin explicitly requests migration.
5. Copy each legacy score into Reputation **baseline**, not a visible deed incident.
6. Copy tier high-water and player-owned global/village titles.
7. Copy cached village names when resolvable.
8. Mark migration complete only after the canonical save mutates successfully.
9. Never repeat or add legacy values again.

New multiplayer players with no prior Quests state start at 0 by default. Provide config/admin override to grant legacy shared standing to all online players if a server owner prefers exact old shared semantics.

### 32.3 Quests mirror/fallback v2

Patch Quests fallback storage to maintain:

- player UUID;
- dimension-aware community key;
- score;
- per-ladder high-water;
- dimension-aware village titles.

When Reputation is active and `mirrorQuestsFallbackState=true`, canonical commits mirror these values after success. Mirror writes do not fire gameplay events or notifications and cannot call back into Reputation.

Retain old `reputation` and `repTierHW` NBT compounds read-only for rollback/manual recovery. Do not delete them.

### 32.4 Legacy datapacks and IDs

- Continue loading `mcaquests/reputation_tiers` and `mcaquests/titles` paths.
- Preserve `mcaquests:default` ladder ID.
- Preserve existing `mcaquests:honored_of_village` and `mcaquests:revered_of_village` title IDs.
- Preserve existing quest reward/condition JSON.
- Existing positive thresholds remain exactly 0/25/75/150/300.
- Negative tiers are additive below zero.

### 32.5 Conversations data

- Existing `mcaconversations_gossip.dat` remains readable unchanged or via a versioned adapter.
- Existing `QUEST` gossip events are not migrated into incidents; they age out normally.
- Existing dispositions, progress, LongTermMemory flags, and quest memories are untouched.

### 32.6 Removal/downgrade behavior

- Removing Reputation stops incident behavior and Conversations integration cleanly.
- Quests reads its mirrored fallback scalar/title state.
- No code may attempt to deserialize Reputation classes when the mod is absent.
- Reinstalling Reputation resumes its retained canonical data; migration markers prevent duplication.
- Downgrading to an old Quests build is not guaranteed, but legacy tags are retained to maximize recoverability.

## 33. Balance and anti-exploit requirements

1. All changes are server-authoritative.
2. Every structured integration outcome has a stable dedupe key.
3. Assault is coalesced; killing upgrades rather than stacks blindly.
4. Routine trades, gifts, and conversation openers grant no reputation.
5. Conversation reputation actions use existing replay policy/budget guards.
6. Quest failure/abandon changes are opt-in per definition.
7. Project reputation is awarded only to defined contributors/participants, never anonymous world state.
8. Situation success defaults to the resolving player, not everyone nearby.
9. Score clamp and override caps prevent malicious datapacks from overflowing state.
10. Admin baseline changes are logged and do not masquerade as organic incidents.
11. Unwitnessed harm does not change public standing.
12. Exact score changes cannot be repeated by packet spam, duplicate Forge events, relogging, re-opening menus, or reloading datapacks.
13. Decay reconciliation is monotonic and cannot be exploited by time rollback.
14. Resolving an incident is idempotent and cannot yield repeated positive restitution rewards unless the quest itself is legitimately repeatable and selects a new incident.

Balance defaults should make ordinary quest help meaningful without allowing a few trivial deliveries to reach Revered. Audit built-in Quests reputation rewards against the 25/75/150/300 thresholds and adjust only through an explicit content-balance pass. Do not globally award reputation for all 150+ quests unless their definitions actually contain the reputation reward/outcome.

## 34. Performance requirements

Hard rules:

- No global per-tick scan of players, villagers, villages, incidents, or Quests state.
- Witness queries occur only on relevant physical events and only over loaded entities in a bounded AABB.
- Lazy awareness uses hash/time calculation, not pairwise saved state.
- Decay reconciliation processes only the queried/online player's selected communities and is rate-limited.
- Datapack registries are immutable snapshots between reloads.
- Journal/screen packets are capped.
- Dedupe indexes and incident collections are bounded.
- Community resolution may be cached within one transaction but must not retain live MCA objects.
- Avoid reflection in hot paths.
- Use DEBUG logs sparingly and never one line per tick.

Performance acceptance targets in a production-style dedicated server:

- No measurable idle tick cost attributable to Reputation with no events/UI queries.
- One assault event with 50 loaded villagers completes witness selection in bounded time without tick stall.
- A player with maximum retained incidents opens the screen without multi-second delay or oversized packet disconnect.
- Save size grows according to configured caps, not playtime without bound.

## 35. Failure and compatibility behavior

### 35.1 Safe degradation

- Missing Quests → core Reputation + UI works.
- Missing Conversations → incidents/score/UI/Quests work; no gossip dialogue.
- Missing Reputation → Quests and Conversations keep their current standalone behavior.
- Bridge init failure → one ERROR, integration disabled, no crash.
- Missing incident definition after reload → retain record and score; display ID fallback.
- Missing village → retain historical record with cached metadata; no new nearest-village reassignment.
- Missing/dead subject → cached name remains.
- Corrupt one NBT entry → skip that entry only.
- Client without valid snapshot → empty/loading state, no guessed score.

### 35.2 MCA version drift

- Keep all MCA imports in one compatibility package per repository.
- Build against 7.7 beta2 and production-test 7.6.20.
- Match Conversations' personality alias policy for 7.6/7.7.
- If an MCA signature differs, adapt without leaking version checks across core classes.
- Do not claim support based only on `runClient`; current MCA mixin packaging requires production-style verification.

### 35.3 Logging

- INFO: successful optional integration activation, data migration summary, datapack reload summary.
- WARN: invalid/skipped datapack entries, ambiguous legacy+canonical definitions, bounded data pruning, recoverable migration ambiguity.
- ERROR: optional bridge detected but failed to initialize, strict reload rejected, unrecoverable save-level issue.
- DEBUG: MCA access safe-fail, individual malformed NBT entry, witness/awareness details, dedupe refusal, score math when debug enabled.

Do not log entire player NBT, chat, or sensitive server paths.

## 36. Automated test requirements

### 36.1 Core pure/unit tests

Required test groups:

1. `CommunityKeyTest`
   - dimension + ID equality;
   - NBT/packet round trip;
   - two dimensions with same village ID stay distinct;
   - malformed dimension/negative ID fails safely.
2. `ReputationMathTest`
   - clamp, overflow, baseline + contributions;
   - positive/negative/zero.
3. `IncidentCodecTest`
   - every enum/field/default;
   - malformed entry skip;
   - unknown definition fallback.
4. `IncidentNbtRoundTripTest`
   - subjects, witnesses, context, status, dedupe, components;
   - per-entry corruption containment.
5. `DedupeTest`
   - identical transaction applies once across save/load;
   - different player/community/outcome does not collide.
6. `DecayTest`
   - delay, daily steps, rounding toward zero, completion;
   - time rollback never restores contribution;
   - reconciliation idempotency.
7. `ResolutionTest`
   - apology/atoned/forgiven/disproven multipliers;
   - stronger-only/idempotent state progression;
   - tier changes caused by resolution.
8. `TierTest`
   - all default boundaries including negatives;
   - upward/downward transitions;
   - high-water title/toast once;
   - malformed ladder validation;
   - bias clamp below margin.
9. `AwarenessTest`
   - witness immediate;
   - deterministic delay stable;
   - monotonic learning;
   - private never spreads;
   - village immediate;
   - nonresident rules.
10. `PruningTest`
    - cap order;
    - fold non-zero contribution into baseline before prune;
    - pinned protection;
    - total score invariant.
11. `MigrationTest`
    - Quests legacy keys → overworld community baseline;
    - eligibility rules;
    - title/high-water copy;
    - marker prevents repeat;
    - malformed/partial legacy state.
12. `SnapshotPacketTest`
    - bounds, component sizes, empty state, ordering.
13. `OptionalClassloadTest`
    - core jar loads with no Quests/Conversations classes.
14. `ContentValidationTest`
    - shipped incidents, ladder, titles, translations.

### 36.2 Core event/GameTests or harness tests

- direct player assault attribution;
- projectile attribution;
- tamed damage toggle;
- cancelled/low-damage ignored;
- assault coalescing;
- killing upgrade total;
- witness radius/LOS/cap/deterministic order;
- no witness → no public score;
- server-only execution;
- snapshot request validation/rate limit.

### 36.3 Quests companion tests

- bridge absent → current fallback behavior;
- bridge present → one canonical write and one mirror, no duplicate;
- all direct legacy reads removed outside backend/migration;
- legacy reward shorthand maps once;
- top-level complete block overrides legacy reward without stacking;
- fail/abandon no-op unless authored;
- transaction dedupe under duplicate completion/event;
- project phase/contributor recipient semantics;
- project `sponsor_village` compatibility mapping;
- situation success/failure recipient semantics;
- Journal canonical snapshot;
- old commands delegate;
- FTB tasks/rewards delegate and recheck;
- legacy Quests events translated once;
- no Reputation class load when absent.

### 36.4 Conversations companion tests

- bridge absent → all reputation conditions return neutral and existing outcomes are unchanged;
- publicStandingFit included exactly once and clamped;
- non-trust/respect axes receive zero;
- seeded check determinism remains;
- normalized gossip selects newest across built-in + external providers;
- condition/action select identical candidate;
- already-told memory dedupes external incident;
- persisted legacy gossip still loads;
- Quests completion does not seed duplicate generic gossip when Reputation active;
- Quests memories/states still apply;
- standing topic graph lint, chat intent parity, graceful exits, replay policies;
- reputation action cannot farm;
- `en_us`/`pt_br` and all personality overlay parity.

### 36.5 Static/jar checks

- no shaded Quests/Conversations/Architectury classes in Reputation jar;
- no sibling imports in always-loaded bridge classes;
- no client class reference from dedicated-server initialization;
- mixin JSON references real mixins and refmap;
- no accidental absolute JDK path requirement in distributed build docs;
- `LICENSE`, `README`, `CHANGELOG`, `CONFIG`, `DATAPACK`, and `API` present.

## 37. Production verification matrix

Because MCA's packaged mixins are not reliably verifiable through the ordinary ForgeGradle dev client, perform production-style tests with built jars.

### 37.1 Installation combinations

Test all:

1. MCA 7.6.20 + Reputation.
2. MCA 7.7.0-beta.2 + Reputation.
3. MCA + Quests only.
4. MCA + Conversations only.
5. MCA + Reputation + Quests.
6. MCA + Reputation + Conversations.
7. MCA + Quests + Conversations without Reputation.
8. MCA + all three.
9. MCA + all three + supported FTB Quests stack.
10. Dedicated server + matching clients for combinations 5–9.

### 37.2 Functional scenarios

- create two villages with same numeric ID in different dimensions if test tooling permits, or unit/instrument the key separation;
- two players build different standing in the same village;
- player A's deed never changes player B's score;
- witnessed assault and unwitnessed assault differ;
- killing upgrades penalty without double count;
- score/tier/title persist through relog and full restart;
- village rename updates display without changing identity;
- missing/deleted village preserves cached history;
- quest, project, situation, FTB, and conversation integrations each apply once;
- gossip is personality-voiced, told once, and respects awareness delay;
- private bad relationship overrides/contrasts with high public standing in dialogue;
- restitution reduces public incident without resetting Conversations disposition;
- config disabled-state fallbacks remain playable;
- `/reload` with bad datapack retains prior live registry in strict mode and skips only bad item in lenient mode;
- install into a pre-Reputation Quests world and verify migration;
- remove Reputation and verify Quests mirrored fallback;
- reinstall and confirm no migration/dedupe duplication.

### 37.3 Security/exploit scenarios

- spam UI request packet;
- duplicate quest completion/turn-in packet;
- repeated conversation apology;
- projectile/tamed damage attribution;
- `/time set` backward/forward around decay;
- malformed packet/community ID;
- malicious datapack with huge delta/context/retention;
- maximum incident ledger and packet.

Record exact mod versions, logs, and pass/fail results in `PRODUCTION_TESTS.md` before release.

## 38. Documentation deliverables

New MCAReputation repository must include:

- `README.md` — player-facing overview, installation, standalone and suite behavior.
- `CHANGELOG.md` — `0.1.0` entry.
- `LICENSE.md` — GPL-3.0-only.
- `CONFIG.md` — every config option/default/range/disabled behavior.
- `DATAPACK.md` — complete incident/tier/title schemas and examples.
- `API.md` — public Java API, events, thread/failure contracts.
- `MIGRATION.md` — Quests legacy policy, limitations, commands, rollback/removal behavior.
- `PRODUCTION_TESTS.md` — completed verification matrix.
- Source comments around compatibility, persistence format, and transaction invariants.

Update Quests:

- README integration/requirements section;
- DATAPACK with outcome blocks, recipient semantics, conditions/rewards;
- CONFIG and FTB documentation;
- API/event documentation;
- changelog and migration note.

Update Conversations:

- README optional Reputation integration;
- DATAPACK with new conditions/actions/variables and external gossip behavior;
- CONFIG;
- chat-mode/topic authoring docs;
- changelog.

Player-facing copy should describe behavior plainly. Developer docs may use exact class/API names.

## 39. Implementation phases and stop gates

### Phase 0 — inspection and written reconciliation

Before code:

1. Re-read current Quests/Conversations source and their local instructions.
2. Confirm audited revisions or record newer commits.
3. Enumerate every Quests direct reputation/title/tier read/write using `rg`.
4. Confirm current MCA 7.6/7.7 village APIs and dimension behavior from actual jars.
5. Confirm Forge events available for damage/death attribution.
6. Confirm MCA interaction screen injection point on both supported MCA versions.
7. Confirm sibling optional compile/build workflow.
8. Write `IMPLEMENTATION_NOTES.md` listing discrepancies and final class/file plan.

**STOP:** If MCA village IDs are proven globally unique across dimensions, retain dimension in `CommunityKey` anyway for save clarity and future safety; do not regress to bare IDs. If production binaries cannot support both MCA versions, stop and report exact incompatible signatures before narrowing support.

### Phase 1 — pure core domain

Implement community key, incident/tier/title definitions, validators, score math, decay, awareness, records, and pure tests. No UI or sibling integration yet.

**Gate:** All pure tests green; default content validates.

### Phase 2 — persistence and transaction service

Implement versioned SavedData, pruning, dedupe, canonical transaction order, resolution, titles/high-water, API, and events.

**Gate:** NBT/migration/idempotency/tier tests green; recomputation invariant proven.

### Phase 3 — MCA/Forge gameplay integration

Implement MCA compat adapter, community resolver, assault/killing attribution, witnesses, config, commands.

**Gate:** server-side harness/GameTests green; no idle global scan.

### Phase 4 — network and standalone UI

Implement packets, snapshot builder, menu button, keybind, screen, feedback, localization.

**Gate:** dedicated server starts; packet bounds/security tests green; small-scale UI inspected.

### Phase 5 — Quests companion patch

Add bridge/fallback/mirror, refactor all read/write paths, outcome codecs, project/situation recipients, Journal, FTB, migration, tests, docs.

**Gate:** Quests-only behavior regression suite green; Reputation combination awards exactly once.

### Phase 6 — Conversations companion patch

Add bridge, check term, normalized gossip provider, conditions/actions/variables, standing topic, duplicate-gossip suppression, tests/locales/docs.

**Gate:** Conversations-only regression suite green; all existing graph/content/locale tests plus new integration tests green.

### Phase 7 — production verification and release hardening

Build reobfuscated jars; run §37 matrix; inspect logs; update docs/changelogs; verify jar contents and licensing.

**STOP:** Do not call the release complete based only on compilation/unit tests or `runClient`.

## 40. Suggested commit sequence

Keep commits reviewable and avoid mixing unrelated cleanup:

1. `MCAReputation: scaffold Forge 1.20.1 project and metadata`
2. `MCAReputation: add community and incident domain model`
3. `MCAReputation: add tier/title loaders and validation`
4. `MCAReputation: add versioned persistence and transaction service`
5. `MCAReputation: add API events, commands, and migration hooks`
6. `MCAReputation: add MCA action attribution and witness logic`
7. `MCAReputation: add network, UI, and localization`
8. `MCAQuests: add Reputation bridge and per-player fallback backend`
9. `MCAQuests: route quests/projects/situations/FTB through bridge`
10. `MCAQuests: add migration, Journal delegation, tests, and docs`
11. `MCAConversations: add Reputation bridge and check context`
12. `MCAConversations: normalize external gossip and suppress duplicates`
13. `MCAConversations: add standing topic/actions/locales/tests`
14. `All: production verification fixes and release documentation`

Do not combine repositories into one commit unless they already live in a monorepo. Record compatible version triplets in each changelog.

## 41. Release acceptance criteria

The initial release is accepted only when every statement is true:

### Core correctness

- [ ] Standing is per player and dimension-aware village.
- [ ] One canonical transaction funnel owns every mutation.
- [ ] Incidents explain score and remain bounded.
- [ ] Dedupe survives save/load and prevents double application.
- [ ] Score recomputes from baseline + contributions.
- [ ] Negative and positive tiers resolve correctly.
- [ ] Upward high-water/title behavior fires once; downward transitions work.
- [ ] Decay/resolution preserve score invariants.
- [ ] Unwitnessed harm does not change public standing.
- [ ] No global idle tick scan exists.

### Standalone product

- [ ] MCA + Reputation starts client and dedicated server.
- [ ] Standing button opens a server-authoritative screen.
- [ ] Known villages, score/tier/titles, and incident list render safely.
- [ ] Commands/config/datapacks/localization are complete.
- [ ] Save persists across restart and tolerates corrupt individual entries.

### Quests integration

- [ ] Quests-only installations retain behavior.
- [ ] Every old reputation/title/tier/FTB path delegates when Reputation is present.
- [ ] No direct shared-score reads remain outside fallback/migration.
- [ ] Quest/project/situation changes apply to correct player recipients once.
- [ ] Failure/abandon penalties are opt-in.
- [ ] Journal displays canonical data and opens deeds.
- [ ] Legacy world migration is idempotent and documented.
- [ ] Removing Reputation leaves mirrored Quests fallback standing.

### Conversations integration

- [ ] Conversations-only installations retain behavior and check outcomes.
- [ ] Reputation modifies only trust/respect checks within hard cap.
- [ ] External incidents use the existing gossip flow and told-memory behavior.
- [ ] No duplicate generic quest gossip occurs with Reputation active.
- [ ] Standing topic works in GUI and typed chat with graceful fallbacks.
- [ ] Generic conversations cannot farm public standing.
- [ ] Locale, personality overlay, and graph lint tests pass.

### Verification/distribution

- [ ] Full automated suites pass in all three repositories.
- [ ] Reobfuscated jars pass the production matrix on MCA 7.6.20 and 7.7.0-beta.2.
- [ ] Dedicated server classloading is clean.
- [ ] Optional dependencies are not bundled.
- [ ] Documentation and changelogs match actual behavior.
- [ ] No known data-loss, duplication, crash, or unbounded-growth defect remains.

## 42. Deferred roadmap

Explicitly defer these ideas until the initial foundation is stable:

- profession/family/faction standings;
- global fame and inter-village news;
- guards, fines, exile, arrest, bounties, and legal ownership;
- trade-price effects;
- village elections/rank/political offices;
- monuments, banners, and physical memorials;
- false rumors, lies, investigations, and rumor source credibility;
- body discovery for unwitnessed killings;
- wandering-villager/traveler rumor transfer;
- KubeJS/command scripting API;
- FTB Teams team-shared reputation mode;
- active/displayed title selection;
- reputation decay policies more complex than none/linear;
- profession/personality-specific valuation of the same incident;
- cross-loader or newer Minecraft ports.

## Appendix A — complete default ladder example

```json
{
  "tiers": [
    { "id": "infamous", "threshold": -300, "name": { "translate": "mcareputation.tier.infamous" }, "trust_bias": -4, "respect_bias": -8 },
    { "id": "hated", "threshold": -150, "name": { "translate": "mcareputation.tier.hated" }, "trust_bias": -3, "respect_bias": -6 },
    { "id": "distrusted", "threshold": -75, "name": { "translate": "mcareputation.tier.distrusted" }, "trust_bias": -2, "respect_bias": -4 },
    { "id": "wary", "threshold": -25, "name": { "translate": "mcareputation.tier.wary" }, "trust_bias": -1, "respect_bias": -2 },
    { "id": "stranger", "threshold": 0, "name": { "translate": "mcareputation.tier.stranger" }, "trust_bias": 0, "respect_bias": 0 },
    { "id": "acquaintance", "threshold": 25, "name": { "translate": "mcareputation.tier.acquaintance" }, "trust_bias": 1, "respect_bias": 2 },
    { "id": "friend", "threshold": 75, "name": { "translate": "mcareputation.tier.friend" }, "trust_bias": 2, "respect_bias": 4 },
    { "id": "honored", "threshold": 150, "name": { "translate": "mcareputation.tier.honored" }, "trust_bias": 3, "respect_bias": 6, "grants_title": "mcaquests:honored_of_village" },
    { "id": "revered", "threshold": 300, "name": { "translate": "mcareputation.tier.revered" }, "trust_bias": 4, "respect_bias": 8, "grants_title": "mcaquests:revered_of_village" }
  ]
}
```

## Appendix B — incident definition example

```json
{
  "display": {
    "translate": "mcareputation.incident.villager_assaulted",
    "with": ["{subject}"]
  },
  "default_delta": -8,
  "visibility": "witnessed",
  "severity": "major",
  "tags": ["crime", "violence"],
  "retention_ticks": 336000,
  "decay": {
    "type": "linear_to_zero",
    "delay_ticks": 48000,
    "amount_per_day": 2
  },
  "resolution": {
    "apologized": 0.75,
    "atoned": 0.25,
    "forgiven": 0.0,
    "disproven": 0.0
  },
  "gossip": {
    "tone": "condemnation",
    "phrase": "mcareputation.gossip.villager_assaulted"
  },
  "max_override_abs": 20
}
```

## Appendix C — integrated quest example

```json
{
  "format_version": 1,
  "id": "example:make_amends",
  "weight": 10,
  "category": "restitution",
  "title": { "translate": "example.quest.make_amends.title" },
  "giver": { "adult_only": true },
  "conditions": {
    "all_of": [
      {
        "type": "mcareputation:has_incident",
        "incident": "mcareputation:villager_assaulted",
        "status": ["active", "apologized"],
        "known_to_giver": true
      }
    ]
  },
  "objectives": [
    { "type": "mcaquests:item_delivery", "item": "minecraft:golden_apple", "count": 1, "consume": true }
  ],
  "rewards": [
    { "type": "mcareputation:resolve_incident", "incident": "mcareputation:villager_assaulted", "resolution": "atoned", "newest": true },
    { "type": "mcareputation:record_incident", "incident": "mcareputation:restitution_completed" }
  ],
  "repeat": { "type": "once" },
  "turn_in": { "mode": "original_giver" }
}
```

## Appendix D — implementation honesty rules

The coding agent must not claim a requirement is complete merely because a class or JSON field exists. Completion requires observable behavior and the corresponding test/production gate. In particular:

- “Per-player” means two simultaneous players demonstrably diverge.
- “Dimension-aware” means identical numeric village IDs do not collide.
- “Idempotent” means duplicate calls before and after save/load do not change score twice.
- “Optional integration” means the JVM starts with the optional jar absent.
- “Gossip integration” means the same incident is selected, spoken, and marked told once through Conversations' real dialogue flow.
- “Migration” means a copied legacy balance/title/high-water survives restart and is not recopied.
- “Production verified” means built reobfuscated jars were tested in a production-style instance, not just unit tests or ForgeGradle `runClient`.
