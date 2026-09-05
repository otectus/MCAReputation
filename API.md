# MCA: Reputation — Java API

**API version 2.** `McaReputationApi.getApiVersion()` returns the generation this build implements.
Check it before using anything below; a bridge that refuses an unexpected version degrades gracefully,
whereas one that assumes gets a `NoSuchMethodError` in the middle of somebody's quest turn-in.

Version 2 identifies the NeoForge generation. The public event types (`ReputationChangedEvent`,
`ReputationTierChangedEvent`, `ReputationIncidentCreatedEvent`, `ReputationIncidentResolvedEvent`,
`ReputationTitleGrantedEvent`) now extend `net.neoforged.bus.api.Event` instead of the Forge
equivalents. Bridges built against version 1 must be re-targeted and recompiled: their event base
class no longer links and their loader imports moved with the platform.

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

// Core-incident authority
CoreIncidentAuthorityRegistration registerCoreIncidentAuthority(CoreIncidentAuthority);
boolean                           hasExternalAuthority(CoreIncidentKind);

// Decay immunity
boolean      isDecayImmune(MinecraftServer, CommunityKey);
boolean      setDecayImmune(MinecraftServer, CommunityKey, boolean immune);
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
and makes `matches` answer false so authored fallbacks fire.  MCA: Crime's `mcacrime:*` sources honour
`enableCrimeIntegration` the same way.

### Core-incident authority

Reputation detects MCA villager assault and death itself. So does MCA: Crime. Without an agreement,
one swing costs the player two deeds. `registerCoreIncidentAuthority` is that agreement:

```java
McaReputationApi.registerCoreIncidentAuthority(new CoreIncidentAuthority() {
    public ResourceLocation authorityId() { return new ResourceLocation("yourmod", "detector"); }
    public boolean owns(CoreIncidentKind kind) { return detectorEnabled && bridgeHealthy; }
});
```

```java
public enum CoreIncidentKind {
    MCA_VILLAGER_ASSAULT,       // -> mcareputation:villager_assaulted
    MCA_VILLAGER_KILL,          // -> mcareputation:villager_killed
    MCA_VILLAGER_RESCUE,        // -> mcareputation:villager_rescued
    MCA_VILLAGER_CURE,          // -> mcareputation:villager_cured
    MCA_RAID_REPELLED,          // -> mcareputation:raid_repelled
    PLAYER_KILL_IN_VILLAGE      // -> mcareputation:player_killed_in_village
}
```

`owns` is asked once per candidate gameplay event, on the server thread, and must be cheap and
honest — answer `false` the moment your detector or your bridge is off, and Reputation resumes native
detection on the very next event. This is deliberately stronger than a `ModList.isLoaded` check: an
installed-but-broken companion cannot create an incident black hole, because it simply stops claiming.

Reputation stands down if **any registered authority** claims the kind. The first claimant wins; if zero
authorities claim it, this mod produces the deed. A claimant that throws is treated as not claiming,
so detection stays with this mod — the risk of that direction is a duplicate, which is visible in the
ledger the moment it happens; the other risk is a deed recorded by nobody, which is not recoverable.

A null authority is rejected immediately with `IllegalArgumentException`. Withdraw a claim by calling
`close()` on the returned handle; the claim ceases immediately and detection returns to this mod on the
very next event. `close()` is idempotent. Claims survive a server stop and remain active for the next
world loaded in the same JVM.

Reserved surface, carried but not yet consumed in this version: `TitleDefinition.revocable`,
`TitleDefinition.icon`, and the `BuiltinIncidents.SOURCE_*` constants. Set them freely; they gain
behaviour in a later version without a format change.

## Additive additions

The three new methods for per-villager opinion and the two for decay immunity do not bump the API
version, which stays 2. `getApiVersion()` deliberately does not move, because a bridge written against
this version remains fully compatible. A companion written for an earlier version neither calls these
methods nor is affected by them, so a mismatch is silent and fine.

For companions that must run against older servers, the recommended pattern is to probe once with
`McaReputationApi.class.getMethod("getVillagerOpinion", MinecraftServer.class, UUID.class, UUID.class, CommunityKey.class)`,
catch `NoSuchMethodError`, and cache the result. If found, populate the opinion line in the standing
screen; if not, fall back to village-level standing.

## Network compatibility

The network protocol version is `"4"` for this release. Clients and servers must match exactly at
handshake, or the connection is rejected before any data travels. The version bumps on any change to
the registered packet format.

---

## Dedupe keys

The single most important thing an integration gets right. Applying the same key for the same player
and community returns the earlier result and mutates **nothing** — no incident, no score, no event, no
toast, no title, no mirror write. That is what makes a duplicated turn-in packet, a doubled
event, a relog mid-transaction, and a datapack reload all harmless.

Recommended shapes:

| Outcome | Key |
|---|---|
| Quest | `quest:<questId>:<giverUuid>:<startGameTime>:<outcome>` |
| Situation | `situation:<instanceUuid>:<playerUuid>:<resolution>` |
| Project phase | `project:<projectId>:<instanceKey>:phase:<index>:<playerUuid>` |
| Project terminal | `project:<projectId>:<instanceKey>:<outcome>:<playerUuid>` |
| Conversation decision | `conversation:<villagerUuid>:<playerUuid>:<decisionId>` |
| Crime case | `crime:<crimeRecordUuid>` |
| Crime case resolution | `crime-resolution:<crimeRecordUuid>:<revision>` |

The rule behind all of them: include everything that makes this a *distinct* outcome, and nothing that
varies between two attempts at the *same* one. `MCAQuests`' `ReputationDedupe` centralises the shapes
so no two call sites can spell one outcome differently.

A `DUPLICATE` result **names the incident the first attempt created**. That is what makes the write
recoverable: a companion that crashed between our commit and its own link write replays the same key
and learns the id, instead of losing the link or recording a second incident to obtain one. Nothing
is mutated — `applied()` is false and `appliedDelta()` is zero — only the identity comes back.

---

## NeoForge events

All five extend `net.neoforged.bus.api.Event` and are posted on `NeoForge.EVENT_BUS`:

```java
import dev.otectus.mcareputation.api.event.ReputationTierChangedEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;

public final class MyReputationListener {

    public static void register() {
        NeoForge.EVENT_BUS.register(MyReputationListener.class);
    }

    @SubscribeEvent
    public static void onTierChanged(ReputationTierChangedEvent event) {
        if (event.upward() && event.firstTime()) {
            // a genuine new best with this village
        }
    }
}
```

> **Add-ons built against the Forge 1.20.1 artifact must be recompiled.** The event base class and
> the bus moved from `net.minecraftforge.eventbus.api.Event` / `MinecraftForge.EVENT_BUS` to the
> NeoForge equivalents above. `getApiVersion()` returns `2` to identify this generation; a bridge
> should refuse any other value. Java 21 is required.


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
