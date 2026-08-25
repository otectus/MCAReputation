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
```

`getCheckBias` is non-zero only for `trust` and `respect`, and hard-clamped to ±8. Warmth, attraction,
tension, and familiarity are private interpersonal state; public standing has no business there.

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

`owns` is asked once per candidate gameplay event, on the server thread, and must be cheap and
honest — answer `false` the moment your detector or your bridge is off, and Reputation resumes native
detection on the very next event. This is deliberately stronger than a `ModList.isLoaded` check: an
installed-but-broken companion cannot create an incident black hole, because it simply stops claiming.

Reputation stands down only when **exactly one healthy authority** claims the kind. Zero claims means
it produces the deed itself. Two or more means the setup is ambiguous, and rather than pick a winner
it logs an error and keeps producing — a visible duplicate is recoverable, a deed that silently never
existed is not.

A throwing `owns` reads as unclaimed (rate-limited in the log). A null, id-less, or duplicate-id
authority is rejected with one error and an inert handle; the handle is never null. Close the handle
to withdraw the claim; closing twice is harmless.

Reserved surface, carried but not yet consumed in this version: `TitleDefinition.revocable`,
`TitleDefinition.icon`, and the `BuiltinIncidents.SOURCE_*` constants. Set them freely; they gain
behaviour in a later version without a format change.

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
