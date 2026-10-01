# Port parity ledger — MCA: Reputation, NeoForge 1.21.1

Every difference between this port and the Forge 1.20.1 tree at `../../MCAReputation`, and its status.
The family rule is that this tree mirrors every change made there; anything that does not is listed here
as **pending** (owed, not yet ported), **not ported** (a recorded decision), or a **loader adaptation**
(the same behaviour, built the way 1.21.1 and NeoForge require). Compiled from a file-level comparison
of both working trees on 2026-09-28; updated 2026-09-30 for the audit remediation below.

## Numbers

| | Forge 1.20.1 | This port |
|---|---|---|
| `mod_version` | 0.6.1 | 0.6.0 |
| Save format (`ReputationSavedData.FORMAT_VERSION`) | 4 (adds the standing journal) | 3 |
| Network protocol (`ReputationNetwork.PROTOCOL_VERSION`) | `"5"` | `"6"` |
| API version (`getApiVersion()`) | 1 | 2 (every NeoForge sibling's Reputation bridge requires 2) |

This port stays on its 0.6.0 line by decision (recorded in `MCAReputation/docs/FAMILY_COMPATIBILITY.md`):
the Forge 0.6.1 release is built around the standing journal, which is not ported.

## Forge 0.6.1 against this port

| Forge 0.6.1 change | Status here |
|---|---|
| Standing journal: `registerStandingConsumer`, `unregisterStandingConsumer`, `pollStandingChanges`, `ackStandingChanges`, `flushStandingChanges`, the records `CaptureResult`, `StandingAckResult`, `StandingConsumer`, `StandingDelivery`, `StandingDeliveryBatch`, `StandingEnvelope`, `StandingRegistration`, and `state/StandingOutbox`; its retention bound and `/mcareputation standing consumers` (2026-09-28) | **Not ported.** Its only consumer, Ultima Kingdoms' faction layer, is Forge-only. |
| `standingBaselines(server)` and `StandingBaseline` | **Not ported**, with the journal (a migration aid for the same consumer). |
| `deliverStandingEffect(...)` | **Not ported**; Ultima Kingdoms is its only caller. |
| `CoreIncidentExemptions` and the `incident_exemptions_v1` capability | Ported. Nothing on NeoForge registers against it yet: the MCA: Crime port has no thief combat policy (see its `docs/PORT_PARITY.md`). |
| `enableCrimeIntegration` and `enableUltimaKingdomsIntegration` switches | Ported. |
| API jar read-model exports (`TitleScope`, `ReputationTiers`, `ReputationTierSet`, `ReputationTier`) | Ported. |
| The Standing button survives MCA's `InteractScreen` widget rebuilds | Ported (`client/ReputationClient`). |
| World file written to a temporary file, forced to disk and moved into place atomically (`state/DurableDataWriter`) | Not needed. NeoForge 1.21.1's own `SavedData.save` already writes through `IOUtilities.writeNbtCompressed`, which does exactly this. Forge's version also writes synchronously so the journal cursor advances only after the file is on disk; with no journal here, nothing depends on that. |
| Milestone titles for tiers crossed by one change are granted after the change is recorded, rather than inside the tier transition | Ported 2026-09-30. `TierOutcome` carries the milestone range and `publishStandingChange` grants it first, before mirrors and events, in Forge's order minus the journal append this branch does not have. |
| `McaReflectProbeTest` over the six-build MCA probe fleet | **Not ported**, by decision: this port keeps its own audited-member binding (`AUDITED_MEMBERS`, `McaBinaryAbiTest`). |

## 2026-09-30 audit remediation (Forge `AUDIT.md`)

| Forge change | Status here |
|---|---|
| Snapshot requests inside the 10-tick window are deferred, not dropped (`network/RequestPacing`, flushed from `ReputationFeedback`'s end-of-tick handler, each deferred answer contained) | Ported. `RequestPacing` and `RequestPacingTest` are byte-identical; the flush hangs off `ServerTickEvent.Post`. |
| A server-pushed screen open sends no request of its own; the screen's one-shot request needs an empty cache *and* no selection | Ported. |
| Live API reads reconcile through the publishing path first (`ReputationService.reconcileCommunityWith` with a server-thread guard, `currentScore`, `effectiveStanding`; `McaReputationApi.reconcileForRead`); `/mcareputation top` reconciles through the service | Ported. The step is published to mirrors and the event bus; Forge also appends it to the standing journal, which is not ported. `ReadPublicationTest` asserts the same steps minus the journal entry. |
| `/mcareputation debug standing` prints what the client was last sent (`ReputationNetwork.SentSnapshot`) | Ported. |
| Run configurations remap MCA's refmap (`mixin.env.remapRefMap`) | **Not needed.** NeoForge 1.21.1 runs on official names, so MCA's mixins apply in a dev run as-is (verified: `runServer` loads with MCA 7.7.36-beta.3). |

## Loader adaptations (same behaviour, different mechanism)

| Area | Forge | This port |
|---|---|---|
| Client keybind registration | `client/ReputationClientRegistration` (a MOD-bus subscriber) | registered in `client/ReputationClient` |
| Screen art | `assets/.../reputation.png`, one atlas | nine GUI sprites in 1.21.1's sprite layout, most with an `.mcmeta` |
| Resource pack metadata | ships `pack.mcmeta` | ships none; NeoForge supplies it |
| Save fixtures | adds `fixtures/mcareputation-format-4-1.20.1.nbt` for the journal | formats 1 to 3 written by the unmodified 1.20.1 serializer, read by `GoldenSavedDataCompatibilityTest` |

## Housekeeping

Two untracked patch leftovers dated 2026-09-27 sit in `src/main/java`: `api/McaReputationApi.java.rej` (its
`FEATURE_INCIDENT_EXEMPTIONS` hunk is already in `McaReputationApi.java`) and
`client/ReputationClient.java.rej` (Forge imports for the Standing button fix, which this port implements
its own way). Neither is compiled, and both can be deleted.
