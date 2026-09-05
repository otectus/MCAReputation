# MCA: Reputation — Java API

**API version 1.** `McaReputationApi.getApiVersion()` returns the generation this build implements.
Check it before using anything below; a bridge that refuses an unexpected version degrades gracefully,
whereas one that assumes gets a `NoSuchMethodError` in the middle of somebody's quest turn-in.

Everything stable lives in `dev.otectus.mcareputation.api`. Nothing there exposes an MCA internal type
or a mutable internal record.

---

## Contracts

These hold for every method on `McaReputationApi`, and are not restated per method:

- **Writes are server-thread only.** Called from elsewhere they refuse and log rather than corrupting
  the store.
- **Queries never hand back mutable collections.**
- **Nothing throws at an integration.** A dialogue evaluation, a quest condition, or a reward claim
  must never crash because of this mod. Failures come back as a documented fallback or a typed failure
  result.
- **Unknown player or community reads as absent or zero.** There is no meaningful difference between
  "no record" and "a stranger".
- **A refusal is a normal answer, not an error.** A duplicate dedupe key, a disabled subsystem, an
  unwitnessed deed — each returns `applied = false` with a `Reason`, and the caller should treat that
  as ordinary.

## Depending on it

MCA: Reputation must be a **compile-only** dependency, never shaded:

```gradle
def reputationClasses = file("${projectDir}/../MCAReputation/build/classes/java/main")
if (reputationClasses.exists()) {
    compileOnly files(reputationClasses)
}
```

```toml
[[dependencies.yourmod]]
    modId="mcareputation"
    mandatory=false
    versionRange="[0.1,)"
    ordering="AFTER"
    side="BOTH"
```

And gate every entry point behind a mod-present check, reaching your Reputation-importing code **by
name** so the JVM never has to resolve it on an installation without the mod:

```java
if (ModList.get().isLoaded("mcareputation")) {
    try {
        Class.forName("yourmod.compat.reputation.Backend").getMethod("register").invoke(null);
    } catch (Throwable t) {
        LOGGER.error("Reputation integration failed to start; continuing without it", t);
    }
}
```

Both companion mods in this suite do exactly that; `MCAQuests/compat/ReputationBridge.java` and
`MCAConversations/compat/ReputationBridge.java` are working references.

---

## Core types

### `CommunityKey`

```java
public record CommunityKey(ResourceLocation dimension, int villageId)
```

A **dimension-aware** village. MCA allocates village ids per level, so a bare integer names two
different places in a world with a Nether village. Never construct or compare a community any other
way. `CommunityKey.of(...)` and `CommunityKey.tryParse("minecraft:overworld/3")` are guarded and return
`Optional`; the canonical constructor rejects a negative id.

### `ReputationRequest`

Built with `ReputationRequest.builder(server, playerId, community, incidentType, source)`.

| Field | Notes |
|---|---|
| `dedupeKey` | **Supply one for every integration-created request.** See below. |
| `deltaOverride` | Clamped to the incident definition's `max_override_abs` rather than rejected. |
| `visibilityOverride` | Cannot open a scoring path a private definition is forbidden to declare. |
| `subjects`, `witnesses`, `context` | Bounded on the way in; over-cap entries are dropped, not stored. |
| `gameTime` | Defaults to the overworld's game time. |

### `ReputationResult` / `ResolutionResult` / `ImportResult`

Each carries `applied` plus a `Reason`. `DUPLICATE`, `DISABLED`, `NO_COMMUNITY`, `UNWITNESSED`, and
`ALREADY_MIGRATED` are all correct, expected answers.

`appliedDelta` is what the score **actually** moved, not what was asked for. At the clamp they differ,
and player-facing feedback must use the real number.

### `ReputationSnapshot`

Score, baseline, tier, next tier, high-water mark, titles, and the incident list for one player and one
community — produced in one place so the Journal, the Standing screen, and a villager's opinion cannot
disagree. `progressToNextTier()`, `pointsToNextTier()`, `contributingIncidents()`, and
`unresolvedNegativeIncidents()` are derived helpers.

### `VillagerOpinion`

```java
public record VillagerOpinion(UUID villagerId, String villagerName, CommunityKey community,
                              int opinion, String tierId, OpinionBasis basis, int knownIncidents)

public enum OpinionBasis {
    INVOLVED,   // the villager was a subject of at least one deed
    WITNESSED,  // the villager saw at least one deed themselves
    HEARSAY,    // everything the villager knows, they were told
    NONE        // the villager knows nothing about this player at all
}
```

What one villager personally makes of a player, derived from the community ledger through what that
villager saw, was part of, or has had time to hear. Derived per-query, never stored: nothing is added
to the save. `enableVillagerOpinion` switches the feature off; `opinionHearsayPercent` and
`opinionInvolvedPercent` weight the deeds by how the villager came to know them.

---

## `McaReputationApi`

```java
int  getApiVersion();
boolean isEnabled();

// Communities
Optional<CommunityKey> resolveCommunity(Entity villager);
Optional<CommunityKey> resolveCommunity(ServerLevel level, BlockPos pos, int radius);
List<CommunityKey>     knownCommunities(MinecraftServer server, UUID player);

// Reads
OptionalInt                  getScore(MinecraftServer, UUID, CommunityKey);
int                          getScoreOrZero(MinecraftServer, UUID, CommunityKey);
Optional<ReputationSnapshot> getSnapshot(MinecraftServer, UUID, CommunityKey);
List<ReputationSnapshot>     getAllSnapshots(MinecraftServer, UUID);
String                       getTierId(MinecraftServer, UUID, CommunityKey);
int                          getCheckBias(MinecraftServer, UUID, CommunityKey, String axis);
boolean                      matches(MinecraftServer, UUID, CommunityKey, ReputationQuery);
List<ReputationIncidentView> recentIncidents(MinecraftServer, UUID, CommunityKey, int limit);
List<ReputationIncidentView> selectIncidents(MinecraftServer, UUID, CommunityKey, IncidentQuery);
boolean                      villagerKnows(MinecraftServer, Entity villager, UUID player, UUID incident);
Optional<ExternalGossipCandidate> gossipCandidate(MinecraftServer, UUID, CommunityKey, UUID incident,
                                                  String playerName);

// Per-villager opinion
Optional<VillagerOpinion>    getVillagerOpinion(MinecraftServer, UUID player, UUID villager,
                                               CommunityKey);
Optional<VillagerOpinion>    getVillagerOpinion(MinecraftServer, UUID player, Entity villager);
int                          getOpinionBias(MinecraftServer, UUID player, UUID villager,
                                           CommunityKey, String axis);

// Writes
ReputationResult record(ReputationRequest);
ResolutionResult resolve(MinecraftServer, UUID, CommunityKey, UUID incident, IncidentStatus,
                         ResourceLocation source);
ResolutionResult resolveBySelector(MinecraftServer, UUID, CommunityKey, IncidentQuery selector,
                                   IncidentStatus, ResourceLocation source);

// Titles
boolean hasTitle(MinecraftServer, UUID, ResourceLocation, Optional<CommunityKey>);
boolean grantTitle(MinecraftServer, UUID, ResourceLocation, CommunityKey);
boolean revokeTitle(MinecraftServer, UUID, ResourceLocation, CommunityKey);

// Integration plumbing
void         registerMirror(ReputationMirror);
void         unregisterMirror(ReputationMirror);
void         registerImportProvider(LegacyImportProvider);
void         unregisterImportProvider(LegacyImportProvider);
List<String> importProviderNames();
ImportResult importLegacy(LegacyImportRequest);
boolean      openReputationScreen(ServerPlayer, CommunityKey);   // §29.7: push the standing screen

// Decay immunity
boolean      isDecayImmune(MinecraftServer, CommunityKey);
boolean      setDecayImmune(MinecraftServer, CommunityKey, boolean immune);

// Core incident detection
CoreIncidentAuthorityRegistration registerCoreIncidentAuthority(CoreIncidentAuthority);
boolean                           hasExternalAuthority(CoreIncidentKind);
```

`getCheckBias` is non-zero only for `trust` and `respect`, and hard-clamped to ±8. Warmth, attraction,
tension, and familiarity are private interpersonal state; public standing has no business there.

### Per-villager opinion

`getVillagerOpinion` queries what one villager personally makes of a player. Returns empty when the
feature is off, when the mod is disabled, or when the player has no record with the community —
there is nothing to have an opinion about. The variant taking a `UUID` returns the opinion and an empty
`villagerName`; the one taking an `Entity` resolves the community from MCA and fills in the name.

`getOpinionBias` answers the bounded check bias from that villager's opinion tier rather than the
village's — same ±8 ceiling and the same two axes (`trust`, `respect`) as `getCheckBias`.

### Decay immunity

`isDecayImmune` returns whether decay is currently off for a community, for every player at once. A
protected village's ledger ages only when a deed moves it. False for an unknown server or community,
and false when anything goes wrong — the safe answer is the ordinary one.

`setDecayImmune` is a write, server-thread only. Returns true when the flag actually changed. Use it
when a companion needs to freeze a village's standing temporarily (e.g., during a quest).

`registerImportProvider` is the supported registration path for §32.2 migration sources — companions
must not reach into internal packages for it. `openReputationScreen` sends the player a fresh
snapshot for the named community followed by the open-screen push; the **caller** is responsible for
having validated the interaction server-side (the Quests Journal validates that the player actually
knows the village before calling it).

Writes attributed to a companion by their `source` namespace honour that companion's `[integration]`
config toggle: with `enableQuestsIntegration=false`, an `mcaquests:*`-sourced `record`/`resolve`
returns `DISABLED` and nothing mutates; likewise `mcaconversations:*` under
`enableConversationsIntegration=false`, which also zeroes `getCheckBias`, empties `gossipCandidate`,
and makes `matches` answer false so authored fallbacks fire.

Reserved surface, carried but not yet consumed in this version: `TitleDefinition.revocable`,
`TitleDefinition.icon`, and the `BuiltinIncidents.SOURCE_*` constants. Set them freely; they gain
behaviour in a later version without a format change.

## Additive additions

The four new methods for per-villager opinion and the two for decay immunity are **additive to API
version 1**. `getApiVersion()` deliberately does not move, because a bridge written against the original
version remains fully compatible. A companion written for the original API neither calls these methods
nor is affected by them, so a mismatch is silent and fine.

For companions that must run against older servers, the recommended pattern is to probe once with
`McaReputationApi.class.getMethod("getVillagerOpinion", MinecraftServer.class, UUID.class, UUID.class, CommunityKey.class)`,
catch `NoSuchMethodError`, and cache the result. If found, populate the opinion line in the standing
screen; if not, fall back to village-level standing.

## Network compatibility

The network protocol version is `"3"` for this release. Clients and servers must match exactly at
handshake, or the connection is rejected before any data travels. The version bumps on any change to
the registered packet format.

---

## Dedupe keys

The single most important thing an integration gets right. Applying the same key for the same player
and community returns the earlier result and mutates **nothing** — no incident, no score, no event, no
toast, no title, no mirror write. That is what makes a duplicated turn-in packet, a doubled Forge
event, a relog mid-transaction, and a datapack reload all harmless.

Recommended shapes:

| Outcome | Key |
|---|---|
| Quest | `quest:<questId>:<giverUuid>:<startGameTime>:<outcome>` |
| Situation | `situation:<instanceUuid>:<playerUuid>:<resolution>` |
| Project phase | `project:<projectId>:<instanceKey>:phase:<index>:<playerUuid>` |
| Project terminal | `project:<projectId>:<instanceKey>:<outcome>:<playerUuid>` |
| Conversation decision | `conversation:<villagerUuid>:<playerUuid>:<decisionId>` |

The rule behind all of them: include everything that makes this a *distinct* outcome, and nothing that
varies between two attempts at the *same* one. `MCAQuests`' `ReputationDedupe` centralises the shapes
so no two call sites can spell one outcome differently.

---

## Forge events

All five are **server-side, posted after the canonical commit, immutable, and non-cancellable**. The
store is already consistent when a listener runs, so a listener may safely query it.

There is deliberately no pre-change cancellable event in this version. Cancellation would break idempotency —
a dedupe key consumed by a transaction that then did not happen — and cross-mod atomicity. Influence
standing through authored data, or by recording and resolving incidents of your own.

| Event | Fired when |
|---|---|
| `ReputationChangedEvent` | Standing moved, from any cause. `delta()` is the applied change. `incidentId()` is empty for decay, baseline, and import changes. |
| `ReputationTierChangedEvent` | A tier boundary was crossed, either direction. `firstTime()` distinguishes a genuine new best from re-entering a tier already held. |
| `ReputationIncidentCreatedEvent` | A deed was recorded. Note that a zero-delta narrative record fires this and **no** change event. |
| `ReputationIncidentResolvedEvent` | A status genuinely moved. A repeated or weaker resolution stays silent. |
| `ReputationTitleGrantedEvent` | A title was **newly** earned. Re-granting one already held posts nothing. |

A listener that throws is caught and logged; the committed transaction stands.

---

## `CoreIncidentAuthority` — avoiding double detection

This mod detects several things by itself: harming, killing, curing, and rescuing MCA villagers;
village raid victories; and rare player-on-player kills within witness range. A companion that detects
the same deeds would otherwise file a second incident for the same punch, and neither mod can prevent
that from its own side. An authority is how one of them claims the deed.

```java
public enum CoreIncidentKind {
    MCA_VILLAGER_ASSAULT,       // -> mcareputation:villager_assaulted
    MCA_VILLAGER_KILL,          // -> mcareputation:villager_killed
    MCA_VILLAGER_RESCUE,        // -> mcareputation:villager_rescued
    MCA_VILLAGER_CURE,          // -> mcareputation:villager_cured
    MCA_RAID_REPELLED,          // -> mcareputation:raid_repelled
    PLAYER_KILL_IN_VILLAGE      // -> mcareputation:player_killed_in_village
}

public interface CoreIncidentAuthority {
    ResourceLocation authorityId();
    boolean owns(CoreIncidentKind kind);
    default String authorityName() { return authorityId().toString(); }
}
```

```java
CoreIncidentAuthorityRegistration claim =
        McaReputationApi.registerCoreIncidentAuthority(myAuthority);

boolean held = claim.isActive()
        && McaReputationApi.hasExternalAuthority(CoreIncidentKind.MCA_VILLAGER_ASSAULT);

claim.close();   // detection returns to this mod on the very next event
```

While `owns` answers true, this mod's automatic hook for that kind records nothing and the claimant is
expected to file the equivalent incident through `record` instead. **File the same incident type** —
`CoreIncidentKind.incidentType()` names it — so the ledger, scores, decay, gossip and witnesses behave
identically whichever mod detected the deed. A companion that claims a kind and then records something
of its own invention has removed the detection without replacing it.

`owns` is asked **per event**, not once at registration, and that is deliberate: a companion's
detection is usually conditional on its own config, and a one-time flag would let an operator switch
that config off and silently disable villager detection in *both* mods. Because it is on the damage
path, `owns` must be cheap — a couple of boolean reads — and must not call back into this API.

Failure directions are chosen so nothing is ever lost quietly. An authority that throws is treated as
**not** claiming, so detection stays here: the risk is a duplicate somebody can see in the ledger,
rather than a deed recorded by nobody. Closing the registration is the only way to withdraw a claim,
and it is idempotent.

`/mcareputation debug authorities` shows what is registered and which kinds are currently claimed.

Registering an authority is **additive to API version 1** — `getApiVersion()` deliberately does not
move, because a bridge written against the original version is still fully compatible.

---

## `ReputationMirror`

For an add-on that needs a usable fallback copy if this mod is later removed.

```java
public interface ReputationMirror {
    void mirrorScore(UUID player, CommunityKey community, int score,
                     ResourceLocation ladder, String highWaterTierId);
    void mirrorVillageTitle(UUID player, CommunityKey community, ResourceLocation title);
    void mirrorGlobalTitle(UUID player, ResourceLocation title);
}
```

Called after a successful commit, on the server thread. **Must not call back into Reputation** (it would
recurse), must not fire gameplay events or send notifications (the commit already did), and may throw —
every call is contained, so a broken mirror costs a log line and a stale copy, never the player's
standing. Only scalar state is mirrored; incident history stays owned here.

## `LegacyImportProvider`

For an add-on that holds standing predating this mod.

```java
Optional<LegacyImportRequest> buildRequest(MinecraftServer server, ServerPlayer player, boolean force);
```

Consulted at login and from `/mcareputation migrate run`. **Eligibility is the provider's judgement** —
it knows who was playing before; Reputation does not. Return empty for an ineligible player. The import
itself is guarded by a per-source migration marker, so returning the same data twice cannot double-apply
it, but a provider should still be cheap since it is asked on every login until the marker exists.

Legacy values become non-decaying **baselines**, not fabricated deeds. Where the original data cannot
say who earned what, inventing history would be worse than admitting the ledger starts here.
