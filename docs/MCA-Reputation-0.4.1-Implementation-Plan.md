# MCA: Reputation 0.4.1 — implementation plan

**Review date:** 8 September 2026  
**Recommended release theme:** reliable consequences, earned recognition  
**Status:** source-based review and proposed implementation plan; no repository changes or runtime certification

## 1. Recommendation

Make 0.4.1 a focused reliability release with a small amount of visible polish. The most valuable improvement is that a deed has the same public meaning whether Reputation runs alone or alongside Quests, Conversations, and Crime. Recording, replaying, resolving, forgetting, and explaining that deed should agree across the suite.

The current architecture is worth preserving. Reputation is a village-scoped public memory service; Quests provides purposeful activity; Conversations makes the consequences personal; Crime owns legal consequences and their evidence. A respected resident can still dislike the player personally, and a legally settled case can still leave a bad memory. Those distinctions create better stories than a single universal approval meter.

The immediate problem is that several boundaries do not yet uphold that design. In the reviewed Forge sources, Crime claims detection authority over the positive deeds introduced in Reputation 0.4.0 without producing replacements. Duplicate delivery loses the original incident identity. Some decay paths bypass village immunity. Quest reward translation changes an omitted reward amount into zero. Resolution and title handoffs have additional consistency gaps.

**Ship the correctness work first.** Add incident-bound amends, clearer explanations of standing, and a few acknowledgment lines once those contracts are reliable. Avoid widening 0.4.1 into a faction system, a new legal simulation, or a general rewrite of the addon framework.

### Release boundary

| Scope | Recommendation |
|---|---|
| Reputation | Core fixes, additive integration contracts, bounded persistence repair, diagnostics, focused Standing screen improvements. |
| Crime companion patch | Required for detection ownership, typed delivery outcomes, pending resolutions, player/NPC separation, and assault-to-killing handoff. Reputation alone cannot correct these producer-side behaviors safely. |
| Quests companion patch | Required for optional delta preservation, stable reward identities, bound restitution targets, and canonical title transport. |
| Conversations companion patch | Required for incident-bound amends and capability-aware opinion fallback; status-aware acknowledgment is the preferred small enhancement. |
| Forge | Primary audited implementation target: Minecraft 1.20.1, Java 17. |
| NeoForge | Existing supported branch should receive equivalent fixes after a branch-specific audit and build. This is parity work, not a new port. One relevant replay fix is already present there. |
| Compatibility claims | Publish the exact tested companion versions and artifact hashes. The source versions below are review baselines, not evidence that those versions are released or runtime-compatible. |

If a companion patch cannot be shipped together, split the release claims explicitly: a Reputation core fix does not by itself mean the four-addon interaction has been fixed. Do not silently force duplicate native detection as a workaround for an overclaiming Crime adapter.

## 2. Evidence and review baseline

The repositories were inspected through GitHub at pinned commits. The review covered Reputation's service, event detectors, API, state/serialization, datapack definitions, networking, display, migration, documentation, and relevant tests. The companion review concentrated on the code that produces, queries, mirrors, or resolves Reputation state and the event paths feeding those integrations.

| Repository | Branch | Source version | Reviewed commit |
|---|---|---|---|
| MCAReputation | `main` | 0.4.0 | [`9abc5e2a9f51`](https://github.com/otectus/MCAReputation/tree/9abc5e2a9f513bf7c059990f5a9f12369c1378df) |
| MCAQuests | `main` | 1.6.2 | [`724b07f0565c`](https://github.com/otectus/MCAQuests/tree/724b07f0565c91d6e3adc2e42f58adff40573e19) |
| MCAConversations | `main` | 1.6.1 | [`df0b5c82d8e0`](https://github.com/otectus/MCAConversations/tree/df0b5c82d8e0758068ca22b58d759c881c150db3) |
| MCACrime | `main` | 0.6.0 | [`d0d493462fbe`](https://github.com/otectus/MCACrime/tree/d0d493462fbe1ae4311ec801f0b6270325568f0f) |

Reputation's `neoforge/1.21.1` branch was spot-checked at [`8e0ac5ef1a74`](https://github.com/otectus/MCAReputation/tree/8e0ac5ef1a7493c3e1f1df71df04275fffd60f40), also labeled 0.4.0. Its `ReputationResult.duplicate(...)` already returns the original incident UUID; Forge needs that backport. Other NeoForge paths and the three NeoForge companion implementations need their own verification. Do not generalize every Forge finding to that branch. [Neo-result]

**Evidence labels used below:**

- **Confirmed source defect:** a concrete mismatch is visible across the inspected implementation paths. The player scenario describes the expected consequence of that code, not a Minecraft reproduction performed during this review.
- **Runtime risk:** event ordering, MCA binary behavior, or a client interaction still needs an installed-game reproduction.
- **Enhancement:** an intentional proposed design improvement, rather than a claim that existing behavior violates its specification.

No Gradle build, automated test execution, dedicated-server session, or client session was run for this review. Existing test sources are useful evidence of intended behavior, but their presence is not a passing test result. The repositories' production-test guidance also makes development launches an insufficient substitute for testing built jars against the actual MCA binaries. [R-production]

## 3. How the four addons currently fit together

### Ownership and data flow

| Concern | Current owner and handoff | Boundary to preserve |
|---|---|---|
| Public village standing | Reputation stores player UUID → dimension-aware community → baseline, incidents, titles, high-water tiers. | One canonical standing value per player/community. No accidental cross-dimension merge or global fame. |
| Native civic deeds | Reputation detects villager assault/killing, rescue, cure, raid victory, and optionally PvP; an external authority can suppress a detector. | Exactly one effective producer for each overlapping event kind. Adding an enum member must not transfer its ownership unintentionally. |
| Crime and law | Crime commits cases, evidence, witnesses, resolutions, and legal state; a persisted outbox sends mapped public incidents and later resolutions to Reputation. | Guards, heat, fines, custody, criminal NPC behavior, and legal truth remain Crime's responsibility. |
| Quests and projects | Quests uses a canonical Reputation backend when available and retains legacy standing/title storage for fallback and migration. Rewards can record and resolve incidents. | Quests owns completion and reward transactions. A handoff carries the actual quest instance and target, rather than recreating them from current context. |
| Conversations | Conversations queries standing, titles, known incidents, and per-villager opinion; it uses a bounded trust/respect check term and supplies dialogue/gossip. | Hearts and private social axes remain private. Reputation supplies facts, not dialogue routing or romantic outcomes. |
| Personal opinion | Reputation derives opinion from incident awareness, using different weights for an involved villager, an eyewitness, and hearsay/baseline. | A valid opinion of zero is meaningful; it is not evidence that the API failed. |
| Compatibility state | Mirrors, optional adapters, runtime classloading guards, and legacy imports allow the addons to run independently. | Removing a companion must not destroy canonical history or require its classes to load. Reinstalling must not pay rewards twice. |

The current public score is a clamped total of baseline plus retained incident contributions. Baseline represents administrative/imported standing and, currently, contributions folded out of the ledger by pruning. Incident definitions, resolution multipliers, tiers, titles, and decay policies are data-driven. Private zero-impact records can preserve narrative history without changing public standing. [R-service] [R-community] [R-incident]

Reputation 0.4.0 ships 19 incident definitions. Some useful balance anchors are shown below; they are defaults, not a proposal to retune the entire ladder. [R-incidents]

| Deed | Default public delta | Visibility | Relevant behavior |
|---|---:|---|---|
| Villager assaulted | −8 | Witnessed | Fades after a delay; amends can soften it. |
| Villager killed | −40 | Witnessed | No normal decay; unseen history may be retained privately at zero public impact. |
| Villager rescued | +6 | Witnessed | Fades; native detection has a time-bucket dedupe key. |
| Villager cured | +15 | Witnessed | No normal decay; recognition and conversion identity need runtime checks. |
| Raid repelled | +20 | Village | Longer delay before fading. |
| Player killed in village | −12 | Witnessed | Detection is off by default. |
| Public apology | +1 | Witnessed | Small, short-lived recognition; Conversations currently overrides its visibility to village. |
| Restitution completed | +4 | Village | Important example of the omitted-delta translation defect. |

Keep the existing positive thresholds at 0, 25, 75, 150, and 300 unless gameplay testing identifies a specific problem. Keep negative tiers, positive progression, and the distinction between current standing and earned high-water milestones. Opinion's involvement/eyewitness/hearsay weighting and the Conversations trust/respect limit of ±8 are useful constraints to retain. [R-tiers] [R-opinion] [C-bridge]

### What already works well enough to build on

- Dimension is part of community identity, and public standing is per player.
- Definitions and selectors are validated rather than treated as unrestricted input; reload uses temporary definition sets and cross-validation.
- The state layer recomputes score from its underlying components on load rather than trusting a cached score.
- Resolution already has monotonic status rules, including a terminal disproven state.
- Native killing detection already attempts to supersede a recent assault and roll back if the killing has no public consequence.
- Crime already has an outbox and stable case-derived creation keys. The solution should repair that delivery protocol, not replace it with synchronous best-effort calls.
- Quests already distinguishes modern completion/failure reward blocks from legacy rewards. Preserve that precedence.
- Conversations keeps public standing out of warmth and attraction, and has absent-addon behavior worth retaining.

## 4. Priorities and release gates

**P0** is the first blocker to fix because it defeats a headline 0.4.0 feature in the normal suite. **P1** covers loss, duplication, misattribution, or materially inconsistent state. **P2** covers presentation, usability, and bounded gameplay improvement. Priority is release sequencing, not a claim of security severity.

| ID | Priority | Finding/work item | Evidence | Main owner |
|---|---|---|---|---|
| F01 | P0 | Crime claims every core incident kind, suppressing unimplemented positive deeds | Confirmed source defect | Crime + Reputation contract |
| F02 | P1 | Forge duplicate result omits original incident UUID | Confirmed; NeoForge fix exists | Reputation |
| F03 | P1 | Delivery erases result reasons; recovery lookup can write; link/queue failures are not fully accounted for | Confirmed source defects | Crime + Reputation API |
| F04 | P1 | Case resolution before creation acknowledgment is dropped | Confirmed source defect | Crime |
| F05 | P1 | Crime-owned assault then killing can double-charge public standing | Confirmed path mismatch | Crime + Reputation |
| F06 | P1 | NPC offenders can be sent into the player reputation store | Confirmed source defect | Crime |
| F07 | P1 | Direct reconciliation bypasses decay immunity; score reads and mirrors disagree | Confirmed source defects | Reputation |
| F08 | P1 | A superseded assault can regain a contribution after resolution and reconciliation | Confirmed source defect | Reputation |
| F09 | P1 | Pruning erases replay identity and resolvable history; write/load limits disagree | Confirmed source defects | Reputation + producer retention contract |
| F10 | P1 | Backdated delivery is applied before aging; insertion order can misrepresent recency | Confirmed source defects | Reputation |
| F11 | P1 | Quests turns absent delta into zero and omits stable record identities | Confirmed source defects | Quests |
| F12 | P1 | Restitution can resolve a newly selected incident; giver-awareness is not carried through | Confirmed source defects | Quests + Reputation API |
| F13 | P1 | Title writes, revocations, high-water queries, and fallback mirrors can diverge | Confirmed source gaps | Quests + Reputation |
| F14 | P1 | Generic predicates depend on Conversations toggle; absent-state and opinion availability are ambiguous | Confirmed source defects | Reputation + Conversations |
| F15 | P1/P2 | Amends identity is per villager/decision rather than per offense; gossip lacks resolution context | Confirmed identity mismatch; semantic enhancement | Conversations + Reputation |
| F16 | P2 | Standing display, pagination, visibility labels, and live config cleanup need refinement | Confirmed source limitations | Reputation |
| F17 | P1 release gate | Crime can build successfully without the optional adapter | Confirmed build behavior | Suite release pipeline |

F01–F14 and F17 form the reliability track. F15's replay/farming correction belongs with it when the revised amends action is enabled; its extra dialogue and F16 polish can follow after the underlying contracts pass. The storage work in F09 needs a reviewed migration before implementation, rather than an opportunistic cap increase.

## 5. Detailed implementation specifications

### F01 — make detection authority explicit per event kind

**Evidence.** `CrimeCoreAuthority.owns(CoreIncidentKind kind)` ignores `kind`: it returns the same configuration/availability expression for every value. Reputation's enum now includes rescue, cure, raid victory, and PvP in addition to assault and killing. `ReputationDeedEvents` returns early when an authority claims those new kinds. The inspected Crime mapping does not produce those four additions. This suppresses positive native deeds when the normal Crime adapter is active. [X-adapter] [X-mapping] [R-kinds] [R-deeds] [R-authorities]

**Implementation.**

1. Fix Crime's implementation to explicitly own only `MCA_VILLAGER_ASSAULT` and `MCA_VILLAGER_KILL`.
2. Make effective ownership depend on that producer being enabled and capable of delivering that kind. A registration handle being active is not the same as an effective claim.
3. Decouple authority setup and fresh delivery from `replayPendingOperations`. Today its false branch returns before authority registration and skips the entire pump. Define that option as historical replay policy, or rename/migrate it if the intended behavior is to pause all delivery. The setting must not silently stop unrelated fresh integration work.
4. Add an additive registration option that declares the supported set of kinds. Keep old callers loadable. Document how legacy broad implementations behave; do not silently reinterpret every third-party implementation.
5. Expose effective ownership per kind in diagnostics, including the reason a producer is unavailable. Re-evaluate on supported config changes and release handles on server stop.

**Acceptance.** With all four addons installed, rescue, cure, and raid rewards still originate in Reputation; assault and killing originate once in Crime. Toggle Crime detection/integration off and restore native ownership predictably. Add a test that iterates every enum member with the real Crime adapter loaded, so a future enum expansion cannot repeat this regression.

### F02–F04 — make cross-mod delivery recoverable and truthful

**Duplicate identity.** Forge's duplicate branch finds the existing record and returns `notApplied(DUPLICATE, ...)`, whose incident ID is empty. Crime expects that ID to repair a case link after a lost acknowledgment. Backport NeoForge's additive `ReputationResult.duplicate(...)` factory and service call. The result must be `applied=false`, delta zero, and the original ID. A successful delivery can be a no-op. [R-service] [R-result] [Neo-result]

**Outcome loss.** Crime reduces the result to `Optional<UUID>`. Unwitnessed, disabled, unknown-definition, invalid, and unexpected-failure outcomes can all become an empty optional. The pump then infers `UNKNOWN_TARGET` from holding authority and may dead-letter the operation and apply a local village penalty. That is not a valid inference from “no public incident was created.” [X-adapter] [X-pump]

**Unsafe lookup.** `findIncident` sends a new generic assault request with the dedupe key. Under a datapack that makes that assault public without witnesses, a failed lookup can itself create an incident. Replace this with a genuinely read-only receipt/incident lookup. Never synthesize a different request to test whether the original succeeded. [X-adapter]

**Lost early resolution.** `CrimeIntegrationHooks.onResolved` returns if the case has no linked Reputation incident yet. A fine, served sentence, or pardon can therefore be saved while creation is pending, with no corresponding resolution operation to deliver later. The later link does not repair that omission. [X-hooks]

**Proposed delivery contract.** The names below are design proposals, not claims about existing API symbols.

| Outcome | Producer action | Public/local consequence |
|---|---|---|
| Applied | Persist returned incident link and acknowledgment | Emit one canonical consequence. |
| Already applied | Recover the original link; acknowledge | No repeated standing, title, or toast. |
| Accepted without public incident | Persist a terminal receipt for the operation | No fallback punishment. The private/legal case remains valid. |
| Disabled by policy | Follow the documented pause/drop policy, distinctly from missing addon | Do not reinterpret intentional disablement as a failed crime penalty. |
| Temporarily unavailable/error | Retry with the original immutable payload and operation ID | No speculative second public penalty. |
| Invalid definition/payload | Record an actionable terminal error | Explicit configured recovery, never a guessed substitute incident. |
| Capacity exhausted | Backpressure/retry with bounded producer storage | No silent omission or eviction of live state. |

Implement this as an adapter-level detailed result plus additive Reputation receipt APIs. Existing `ReputationResult` callers remain supported. The receipt identity should contain producer namespace, player, community, and operation key; do not change the collision semantics of old unnamespaced keys without a migration.

Queue resolution by **case ID and resolution revision**, even before the create link exists. Delivery first obtains the creation receipt, then applies the desired monotonic resolution to that exact incident. If creation was legitimately accepted with no public incident, the dependent resolution can complete as a no-public-work outcome. A stronger later resolution supersedes weaker pending intent without changing the case's legal history.

Check the return value of `CrimeCaseService.linkReputationIncident`; do not report success if the link was not committed. Treat outbox admission as part of the producer's local transaction: `enqueueOperation` currently only logs on overflow, while local penalty suppression predicts successful canonical delivery. Add an explicit admission/result path and recovery state so queue exhaustion cannot leave an untracked gap. [X-pump] [X-hooks] [X-case]

**Crash semantics.** An outbox and dedupe keys do not make two independently saved mod files an atomic transaction. Specify and test both directions of a partial save: Reputation saved/Crime link missing, and Crime acknowledged/Reputation write missing. Retain enough producer payload and reconciliation state to repair either within the supported replay window. Acknowledge this limit in API documentation; do not advertise unconditional exactly-once delivery.

**Acceptance.** Exercise lost acknowledgment, save/reload before link, resolution-before-create, repeated resolution, missing definitions, private retained incidents, truly dropped unwitnessed incidents, explicit disablement, absent addon, link failure, and full outbox. Each test asserts both standing and case/link/outbox state. A read-only lookup test must prove ledger size and score do not change even under a public-assault datapack.

### F05–F06 — preserve the meaning and actor of a Crime event

**Assault followed by killing.** Reputation's native kill handler folds a recent assault; Crime commits harm and killing as separate cases and sends generic record requests. That producer path does not perform the native fold. At the default deltas, one fatal encounter can therefore leave −8 and −40 instead of the standalone path's −40. [R-gameplay] [X-detector] [X-adapter]

Add a service operation that atomically records a successor incident and supersedes an exact eligible precursor. Crime supplies the precursor link/case relationship and encounter context; Reputation owns the public accounting. Require the same player, subject, community, and a bounded encounter window. Do not merge unrelated assaults, arbitrary recent negative records, or another victim's case. Keep legal charges and their resolution in Crime; public de-duplication is not permission to erase legal records.

Evaluate the successor's actual visibility and present contribution before retiring the precursor. An unseen killing must not refund a witnessed assault. Emit one final score/tier transition, not transient “assault refunded” and “murder charged” promotions. Native and Crime-owned events should call the same public-accounting seam.

**NPC offenders.** `CrimeDetector.commitNpc` marks `OFFENDER_KIND="npc"` and invokes the same integration hook. The hook does not filter this marker, and the adapter passes `offenderId` as Reputation's player UUID. An NPC theft can consequently create a player reputation record for a villager UUID. [X-detector] [X-hooks] [X-adapter]

Filter non-player offenders before queueing and again when delivering historical operations. Prefer an explicit typed actor-kind field at the boundary. Do not use “currently online” as the test: offline player crimes remain legitimate. Preserve Crime's NPC cases, relationships, and memories. Offer an audit report for existing suspected NPC-keyed entries; do not guess a human owner or bulk-delete history.

**Acceptance.** Fatal encounter parity with/without Crime; separate-victim and separate-encounter controls; unseen-kill rollback; NPC crime leaves player-store cardinality unchanged; offline player delivery remains valid.

### F07 — centralize reconciliation and its side effects

**Evidence.** Village decay immunity is checked in `ReputationSavedData.reconcilePlayer`, but service methods call `CommunityReputationRecord.reconcile` directly. Record, duplicate, resolution, snapshots, recent-history queries, and administrative paths can therefore bypass that policy. A supposedly immune village can change merely because a screen or API path reconciles it. `score()` reads cached state, while snapshots reconcile; periodic reconciliation does not notify the normal mirrors and tier-change consumers. [R-saved] [R-service] [R-community]

Create one policy-aware reconciliation entry point for a player/community and route every path through it. Keep a pure “inspect stored state” operation for diagnostics rather than giving every read an independent mutation policy. The common result should describe old/new score, tier, contribution changes, revision, and cause.

Define these semantics before coding:

- **Master disabled:** gameplay influence is neutral and background evolution is frozen; diagnostic inspection can show retained state explicitly.
- **Decay disabled:** existing standing remains active, but contribution aging is frozen.
- **Community immune:** the same freeze applies to that community across every entry point.
- **Re-enable:** recommendation is no catch-up decay for the paused interval. Keep historical incident age separate from its effective decay age. If a different policy is chosen, document it and test it explicitly.
- **Resolution during a decay freeze:** a permitted explicit resolution can still soften an incident, but must not run hidden decay first.
- **No stored community:** querying a valid community yields the effective neutral view without creating a persistent record merely to answer a question.

After a real score change, publish one canonical change envelope and update mirrors. Include a cause such as deed, resolution, decay, admin, import, or reload. Decay is quiet: it should update the displayed tier and conditions without replaying deed toasts or inventing a first-time heroic deed. Preserve intentionally earned high-water titles separately from the current tier.

Use targeted reconciliation rather than reconciling all of a player's communities for every nearby villager's opinion query. Cache derived opinion only against explicit state/definition revisions and a bounded time validity; invalidate on witness/status changes, reload, freeze changes, and server lifecycle changes. Measure before introducing a larger cache layer.

**Acceptance.** Query score, snapshot, opinion, incident selection, commands, loot conditions, and Quests fallback after the same elapsed time; they agree. Repeat all paths with community immunity and master/decay switches. Mirrors observe actual decay changes once, and a screen opening does not alter protected standing.

### F08–F09 — protect incident lifecycle, replay identity, and saved data

**Superseded is a lifecycle state.** `foldInto` zeroes settled/current contribution and stores `superseded_by`, but leaves the incident's moral status active. A later resolution recalculates settled delta from the original base; its current contribution initially stays zero, but later reconciliation can restore a penalty. Existing tests cover folding followed by decay and ordinary resolution separately, not this combined sequence. [R-incident] [R-resolution-tests]

Add an explicit superseded/terminal accounting state, separate from apologized/atoned/forgiven/disproven. A superseded record never contributes, never becomes an amends candidate, and never revives through resolution or decay. Preserve the original deed and successor link for explanation. Migrate existing `superseded_by` records into this state; validate links without requiring that a pruned successor still exists.

**Pruning currently changes future behavior.** Dedupe is reconstructed from retained incidents. Once a record is pruned, its identity disappears even though its contribution may have been folded into baseline. Replaying the same key can then pay again. Pruning can also remove a target that Crime or a restitution quest later needs to resolve. Folding a still-decaying contribution into baseline preserves today's score but freezes its future decay. Folding known witness-specific history into a generic baseline can also change per-villager opinion. [R-player] [R-community] [R-dedupe-tests]

Separate three concepts:

1. **Visible history limit:** how many full narrative rows the client shows or requests.
2. **Live accounting/obligation retention:** records whose contribution, awareness, decay, or future resolution still matters.
3. **Operation receipts:** compact delivery identity and outcome needed for replay recovery, even when there is no visible incident.

For 0.4.1, take the conservative route: stop evicting records with live accounting or external obligations just to satisfy a display limit. Introduce bounded receipt storage and explicit admission/backpressure when a hard state budget is exhausted. Retain only the context necessary for economic/awareness correctness when compacting. Do not attempt an elaborate rumor archive in the same patch.

Pick actual capacity defaults from stress tests. Increasing 64 to a larger number alone does not fix identity loss. Conversely, keeping every record forever is not a bounded design. A receipt eviction rule must be coordinated with the producer's retry/finalization horizon. An operation older than the supported receipt floor must enter explicit reconciliation/manual recovery, not be accepted as a fresh deed. No finite cache can promise eternal dedupe for arbitrary old keys.

**Write/load limits disagree.** Runtime pruning can leave more than the incident cap when entries are pinned; load reads only the first capped number. A saved overflow can therefore lose contributions on restart. The community “hard cap” can also be exceeded when nothing is evictable. Unknown future save versions are warned about rather than preserved by a robust read-only policy. [R-community] [R-player] [R-saved]

Enforce one budget/admission policy before mutation and use the same invariants on load. Do not silently truncate and call the resulting score a repair. Preserve recoverable raw data in a quarantine/export path when an old save exceeds the new budget or contains an unsupported schema. Refuse destructive rewrites of future-format data. Validate bounded strings, collections, duplicate IDs, numeric accumulation, and timestamps while retaining valid neighboring records.

**Migration recommendation:** use an explicit new save format for receipts, lifecycle flags, and freeze bookkeeping. The inspected format is 1; format 2 is a proposed target. Back up before conversion, build the new state in memory, validate totals and links, then save once. Migration details and rollback limits are in section 9.

**Acceptance.** A pruned/display-hidden deed cannot repay; a pending case remains resolvable; decay and opinion remain equivalent before/after compaction; pinned overflow survives reload without score loss or fails safely before writing; a future-format file is preserved; folded → resolved → reconciled remains zero.

### F10 — distinguish event time from application time

Crime deliberately supplies the original case time. Reputation's record path uses the request time for reconciliation, creates the new contribution, and emits effects before reconciling that new contribution to the server's present time. A delayed old deed can briefly affect current standing at full strength and trigger effects it would not earn today. [X-adapter] [R-service]

Store `occurredAt` separately from `appliedAt`/current reconciliation time. Calculate the arriving incident's present contribution before committing score, titles, mirrors, or feedback. Do not move the existing community's effective clock backward to accommodate a late request. Validate unreasonable future timestamps while retaining honest historical timestamps for imported/replayed facts.

`incidentsNewestFirst()` reverses insertion order, which is not chronological order after backdated delivery. Use a common deterministic order by occurrence time and stable ID for recent-history/UI/gossip paths. The API's `IncidentQuery.select` already sorts explicitly; preserve that behavior and remove the inconsistent raw-list path rather than rewriting a working selector. [R-community] [R-query]

**Acceptance.** Deliver an old decaying deed after a newer deed: present score and title effects reflect its current contribution, history remains chronologically correct, and reconciliation time never regresses.

### F11–F12 — carry a quest transaction all the way to its intended incident

**Omitted amounts become zero.** `RecordIncidentReward` documents that omitted `delta` uses the incident definition, but calls `.delta(delta.orElse(0))`. `ReputationAward` carries an integer, and `CanonicalReputationBackend` always forwards it as an override. The default +4 restitution definition thus becomes a zero-delta narrative entry when used in the documented minimal reward form. An explicitly authored zero must remain different from an omitted amount. [Q-record] [Q-award] [Q-backend]

Add a detailed award payload with an optional delta, stable operation identity, subject, community, occurrence time, and recognized audience. Keep the existing award API as a legacy explicit-amount path. Forward a delta override only when present. Do not infer absence from the numeric value zero.

The named `record_incident` reward also supplies no stable dedupe key. Give each completion reward an identity derived from the persisted quest instance, player, completion outcome, and reward slot. Re-evaluating the same reward uses the same identity; completing a new legitimate instance uses a new one. Do not use an ephemeral menu session or a random UUID regenerated on retry.

Audit payload fidelity at the same seam. The current award's tags are not forwarded; a witnessed reward cannot carry a witness list through this path; invalid visibility can be ignored rather than rejecting the reward. For 0.4.1, prefer definition-owned tags unless per-instance tags receive explicit schema/storage support. Validate unsupported fields rather than silently promising behavior. Supply audience information only when the producing quest actually knows it; do not make every witnessless reward village-public to avoid a refusal.

**Restitution target drift.** `ResolveIncidentReward.grant` constructs a selector at turn-in using the current giver/community. The canonical backend accepts but does not use a resolution dedupe argument and selects again on each call. A new offense committed after accepting a quest can become its target. Retrying after the first target changes status can select a second qualifying offense. Monotonic status prevents repeating a transition on one record; it does not make a moving selector idempotent. [Q-resolve] [Q-backend]

Bind the exact incident at offer/acceptance and persist that binding in quest state. The binding needs player, community, incident ID, relevant subject/giver, intended resolution, and sufficient definition/policy revision information to explain later changes. At turn-in:

1. Verify that the quest still belongs to this player and the bound incident is available.
2. Verify that the giver/authority is eligible to offer this form of restitution.
3. Resolve that exact incident using the reward's stable operation ID.
4. Commit any separately authored completion deed once, with its own stable reward slot.
5. If the target is already at an equal/stronger legitimate state, complete according to the authored reward policy; do not select a replacement offense.

Keep the selected community stable when the giver moves or the player turns in elsewhere. If a world change makes a binding invalid, present an explicit unavailable/cancel/rebind flow owned by Quests. Automatic rebinding must never be a silent fallback.

**Knowledge filtering is incomplete.** Quests' `HasIncidentCondition` can request `known_to_giver`, but the bridge does not carry the giver UUID into the generic selector. `McaReputationApi.selectIncidents` applies `IncidentQuery.select` without a speaker-aware knowledge evaluation. Another resolution selector path rejects the speaker-required query instead. [Q-condition] [Q-backend] [R-api] [R-query]

Add an explicit speaker-aware query overload/context. Evaluate visibility and awareness using the actual speaker and community; fail closed if a caller requests knowledge filtering without a speaker. Keep an intentional player/admin inspection selector separate. A quest giver must not infer a private unseen crime from the existence of a zero-impact ledger record.

**Acceptance.** Omitted delta uses the current definition; explicit zero stays zero; duplicate completion pays once; new quest instance pays once; malformed visibility rejects; witnessed reward has honest audience. Accept restitution for offense A, commit offense B, then finish/retry: only A changes. An uninformed giver cannot offer a knowledge-gated task. Same village ID in another dimension never matches.

### F13 — make titles and high-water milestones consistent

Quests still writes titles into its own `PlayerTitles` store. Its canonical backend reads a union of Reputation and the online player's Quests capability to make those writes visible locally. Reputation-only consumers can miss the title, offline reads differ, and an old local copy can outlive canonical revocation. Reputation's direct village-title mutations and revocations do not flow through a complete authoritative mirror contract. `tierHighWater` in the canonical Quests backend ignores the supplied ladder and returns the default snapshot's high-water tier. Global-title enumeration via community snapshots can miss a player who has a global title and no community record. [Q-title] [Q-backend] [R-title] [R-mirror]

Implement source- and scope-aware title writes to Reputation while it is canonical. Retain Quests' local store as a fallback mirror and preserve unrelated quest/story titles. Introduce a versioned full-state mirror or explicit grant/revoke events with a reconciliation revision; a grants-only stream cannot repair a revoked title.

Use direct global/village title queries, including offline players. Reconcile on login and backend transitions without using an online-only union as permanent authority. Mirror score, high-water, and title changes caused by direct API/admin operations as well as deeds. Honor the requested ladder in high-water queries. Audit the `mcaquests:default`/`mcareputation:default` ladder translation in import and mirror code, and write an explicit mapping test rather than assuming the identifiers are interchangeable. [Q-import]

Decide the skipped-tier milestone policy. Recommendation: crossing from 0 directly to 300 earns each newly crossed positive milestone title once, while emitting one concise notification for the actual new tier. A destination-only grant can omit an intermediate title permanently. Current tier, historical highest tier, and revocable story titles should have distinct semantics. Decay can lower current tier without retroactively “unearning” a permanent milestone.

**Acceptance.** Quest-earned title appears in Journal, Standing, and Conversations; offline query agrees; revoke it and all canonical consumers agree; unrelated local titles survive reconciliation. A custom ladder's high-water query returns that ladder's data. A large positive jump grants the documented milestones once. Global-only title holders are visible without creating a fake community.

### F14 — separate generic query behavior from integration switches

`McaReputationApi.matches` checks `conversationsIntegrationEnabled`, but it is also used by Reputation's generic standing condition, including the loot-condition path. Turning off Conversations integration can therefore turn off a core standing predicate. When a valid community has no stored snapshot, the method accepts only an empty query: a neutral `min: 0` condition can behave differently before and after metadata/state is created. Other query methods do not consistently follow the API's promise that a disabled system returns neutral values. [R-api] [R-condition]

Define a short truth table and implement it centrally:

| State | Effective gameplay standing | Incident eligibility | Storage inspection |
|---|---|---|---|
| Enabled, valid new community | Score 0 and corresponding neutral tier | Empty history | No persistent record required |
| Enabled, existing community | Current reconciled standing | Filtered by query/audience | Full authorized retained state |
| Invalid/unresolved community | Explicitly unavailable | No match | No invented community |
| Reputation master disabled | Neutral influence according to the documented predicate policy | Disabled/no actionable candidates | Retained state remains inspectable for diagnostics |
| Conversations integration disabled | Generic Reputation/Quests/loot behavior remains active | Generic API remains usable | Conversation adapter contributes no influence |
| Opinion feature disabled/unsupported | Public standing is still available | No derived personal opinion | Capability result says why |

For feature disablement, specify whether a non-empty standing predicate fails closed or evaluates against neutral standing; do not let different consumers make contradictory assumptions. Recommended default: core generic numeric predicates evaluate the effective neutral value, while action/incident requirements fail closed when the feature is unavailable. Test title predicates explicitly rather than treating “neutral” as an unspecified wildcard.

Conversations currently probes opinion API support once, then can choose an opinion result of zero even when that feature is disabled. Add availability/capability information that distinguishes **a valid neutral opinion**, **unsupported opinion**, **disabled opinion**, and **unresolved context**. Only fall back to public-standing bias in the documented unavailable/disabled cases. Never fall back merely because a villager genuinely knows nothing; doing so would turn private ignorance into public omniscience. [C-adapter] [C-bridge]

### F15 — bind amends and gossip to a real story

Conversations' recorded-signal key combines villager, player, and decision ID. The shipped public-apology option uses a fixed decision ID and overrides visibility to `village`. As a result, the same villager/decision is effectively once-per-retained-key across unrelated future offenses, while different residents provide different reward keys for the same apology. Retention can later reopen the original key. This is not a reliable once-per-offense policy. The action parser also needs to implement or reject the documented policy field rather than leaving it aspirational. [C-adapter] [C-registrar] [C-amends]

Introduce a bound incident action for amends. Identity should include player, community, target incident, and action kind; the speaker supplies authorization/knowledge, not an unlimited new reward identity. A first valid public apology may receive the small authored +1 recognition once for that incident/community. A future distinct offense may support a new apology. Repeating dialogue, changing residents, reconnecting, or reopening a screen cannot pay again.

Keep the private response in Conversations: hearts, respect, tone, and willingness to forgive can depend on that resident and the existing social system. Reputation owns any public record/status transition. Crime decides whether a legal obligation has been discharged. Saying sorry must not automatically clear a warrant, refund a fine, or reset the victim's personal feelings.

Reputation's gossip candidate currently provides incident identity, phrase/tone, age, and contribution without enough semantic resolution context for a correction story. Add status, meaningful story revision, and a link to a superseding incident where appropriate. Conversations can then say that a deed was atoned for or an accusation disproven instead of using an unqualified original account. Key correction announcements by a semantic revision, not every decay tick; respect Conversations' own already-told memory and cooldowns. [R-api] [C-adapter] [C-bridge]

**Acceptance.** One offense → one public apology reward across multiple residents; a later offense permits its own action; uninformed speakers do not offer private details; legal state and private hearts change only through their owning systems. A disproven incident can produce one correction and cannot reappear as a fresh active accusation through replay.

### F16 — improve the Standing screen and visible feedback

The aim is to answer three player questions: **What do they think of me? What changed it? What can I still do about it?**

| Improvement | Implementation direction | Acceptance |
|---|---|---|
| Explain contribution | Show original deed, current contribution, resolution state, and whether ordinary fading applies. Separate retained history from current score. | A resolved or faded deed is not presented as a fresh full penalty. |
| Label audience | Carry private/witnessed/village visibility into detail DTOs and label it plainly. The current incident summary does not carry that distinction. | A private zero-impact record cannot be mistaken for what the entire village knows. |
| Show all communities | Separate paginated lightweight summaries from selected-community detail. The current summary cap is 64 while the state budget allows substantially more communities. | A negative/older village remains reachable; show count and navigation rather than silently omitting it. |
| Keep context honest | When displaying a nearby villager's opinion, verify and label the relationship to the selected community. | Selecting another community clears or explicitly explains a historical/contextual opinion. |
| Clean up display state | Remove owned scoreboard/tab adornments when disabled; invalidate cached display decisions on config changes. | Off → on works immediately and leaves no stale suffix/score. |
| Respect scoreboard ownership | Validate configured objective ownership and criteria before writing or removing it. | An existing objective owned by another mod/admin is not repurposed or deleted. |
| Preserve navigation | Keep current/nearest community selection and deliberate user selection stable; avoid replacing selection with the player's highest score. | Existing selector behavior remains intact after refresh and dimension travel. |
| Accessibility and density | Maintain the established vanilla gray/white visual style, readable wrapping, keyboard focus, and localized labels. | Test long village names, translated text, low resolution, and all sibling menu buttons together. |

These are self-inspection screens: the current issue is clarity, not evidence that another player's private ledger is exposed. Preserve server-side self/permission checks and bounded packet requests. If the wire shape changes, increment the protocol and test mismatched clients explicitly. [R-network] [R-display] [R-diagnosis]

### F17 — make the shipped adapter reproducible

Crime's build excludes `compat/reputation/**` when the sibling Reputation class directory is missing, unless `-PrequireReputation` is set. A standalone build can thus succeed while producing a jar whose runtime behavior is indistinguishable from a missing adapter. Quests fails loudly for the missing Reputation compile surface; Conversations warns and its direct adapter references still require the surface to compile. All three use sibling build output rather than a versioned published API dependency. [X-build] [Q-build] [C-build]

For the release pipeline, build pinned sibling surfaces in a clean environment, enable Crime's `-PrequireReputation=true` and `-PrequireQuests=true` gates where relevant, and assert the adapter classes are present in the resulting jar. Smoke-test adapter loading from the packaged, remapped jar. Do not infer integration availability from mod IDs alone.

A small reproducible API/compile-surface artifact or explicit composite-build setup is worthwhile, but it must preserve optional runtime dependencies and avoid shading companion classes. Do not move existing public classes merely to make a tidier API jar: the current integration surface includes types outside the nominal `api` package, and moving them can break binaries. A new shared framework dependency is unnecessary for this patch.

## 6. Additive contracts to agree before coding companions

Keep the inspected API version 1 compatible where possible. Add methods/types and capability negotiation rather than changing existing public record constructors or method descriptors. If a breaking change proves necessary, version it deliberately and ship companion updates with an explicit compatibility range. The current Forge network protocol is 3; protocol 4 is a proposed target only if the planned packet changes are adopted. [R-api] [R-network]

| Contract | Required fields/behavior | Compatibility strategy |
|---|---|---|
| Effective capabilities | Supported operation features, feature enabled state, effective detector kinds, readiness reason | Add a capability query; old callers keep existing methods. |
| Incident delivery | Immutable operation identity, player actor, community, source, occurred-at, optional delta, subject, audience, definition ID | Add a detailed request/result; retain legacy request semantics. |
| Receipt lookup | Source/player/community/operation key → original outcome and optional incident ID | Read-only; no synthetic record probe. Include no-public-incident receipts. |
| Bound resolution | Exact incident ID, stable operation ID, desired status, authorized context, producer revision | Keep selector APIs for deliberate discovery; use exact IDs for committed rewards. |
| Superseding a precursor | Exact precursor/successor, validated common context, atomic public-accounting result | Reuse from native and Crime producer paths. |
| Speaker-aware query | Explicit speaker, player, community, selector; known-state rules | Reject unsupported knowledge filtering rather than silently broadening it. |
| Standing change | Old/new effective score and tier, revision, cause, changed scopes | One mirror/event dispatch path; quiet decay/reload changes. |
| Title synchronization | Source ownership, global/community scope, set or grant/revoke revision, requested ladder | Reconcile fallback copies; do not union forever. |
| Gossip story | Incident ID plus meaningful status revision, current/historical contribution, permitted facts | Optional enriched candidate; old adapters retain their safe baseline behavior. |

Keep all service mutations on the server's owning thread. Validate context and capability at the boundary. An unexpected adapter failure should produce a visible diagnostic and a typed unavailable/error result, while preserving the other addons' ability to operate. Avoid broad catch-and-default behavior that turns all failures into a legitimate neutral answer.

## 7. Small enhancements worth including

### 7.1 A complete amends loop

This is the most valuable creative addition because it connects all four mods using responsibilities they already have.

1. An awake, informed villager recognizes a specific unresolved public deed.
2. Conversations offers a response appropriate to that resident: acknowledgment, apology, or a path to make amends.
3. Quests, when installed, creates a task bound to that deed and the appropriate giver/community.
4. Completing it requests the authored public resolution exactly once.
5. Crime independently acknowledges any legal restitution that its own rules authorize.
6. Later dialogue can recognize the repair without pretending the original event never happened.

Start with two carefully authored templates: assistance to a harmed resident, and return of stolen property through Crime's existing ownership/restitution mechanics. If a case cannot legally be settled by such work, the dialogue says the public relationship improved while legal obligations remain. A killed victim cannot personally forgive the player; use an eligible community representative and a narrower atonement result, not a fabricated victim interaction.

**Balance rule:** do not let a player create an offense and earn a net standing profit by completing its trivial apology/restitution loop. Resolution already returns some lost standing. For the new self-caused restitution templates, recommend a zero-delta completion story by default; reserve the definition's positive service reward for independently valuable work with its own verified outcome. Keep the single apology's +1 recognition short-lived and test the complete timeline through decay. Do not globally change the existing restitution definition to mask the optional-delta bug. An apology is recognition of responsibility, not an endlessly repeatable civic service.

Ship fallback behavior deliberately: without Quests, a valid conversational apology can still be recorded; without Conversations, the bound task can still be offered through Quests; without Crime, core public wrongdoing still supports public amends. Reputation alone remains functional.

### 7.2 Recognition for deeds that already happened

Add a small set of acknowledgment variations for rescue, cure, raid defense, a completed community project, and a fulfilled promise. These consume an existing incident and its knowledge state; talking about a deed does not award it again.

- A rescued villager can be personally grateful while the wider village has only heard a weaker account.
- A community representative can recognize a completed project attributed to its actual sponsoring village, even if the player turns in elsewhere.
- A promise outcome can be acknowledged when Conversations confirms fulfillment; opening a promise menu or merely agreeing is not another public reward.
- A resident can acknowledge atonement and still express personal hurt. This keeps the distinction between public standing and MCA hearts visible in play.
- A correction can travel after a case is disproven, with Conversations controlling delivery and repetition.

Keep this a small data/content pass. Reuse the existing authoring and localization pipeline. Do not hand-edit generated Conversations content when its source compiler owns the output.

### 7.3 Strengthen positive-deed attribution before adding more rewards

The new deed detectors deserve targeted runtime tests once F01 stops suppressing them. [R-deeds] [R-witnesses]

| Area | What is established | Test or refinement |
|---|---|---|
| Sleeping witnesses | Reputation's basic visual witness resolver does not explicitly exclude sleeping villagers; Crime has richer observation behavior. | Verify event wake ordering. Exclude sleeping third-party visual observers, but distinguish a directly harmed victim who wakes and knows the act. Do not copy Crime's whole hearing/confidence simulation. |
| Cure identity | The cure key uses the curer and converted entity identity; the hook resolves attribution/community at conversion time. | Verify identity persistence across the actual MCA conversion chain, chunk unload, re-infection/re-cure, and offline curer. Add bounded delayed attribution only if the runtime sequence requires it. |
| Raid victory | Recognition uses the hero-effect/raid-victory path. | Verify effect timing, raid state, player position, multiple defenders, and duplicate effect application in built jars. Do not assume an event-order failure from static inspection alone. |
| Rescue | Recognition depends on the killed hostile being associated with an endangered villager and on witness/subject context. | Test real rescue, incidental mob kill, repeated staged combat, pet/projectile attribution, and two players. Credit the recognized deed once; retain a bounded cooldown. |
| Criminal evidence | Crime forwards witness identities into the civic request. | Establish whether each exported identity means recognized actor, reportable public evidence, or merely observation. Do not turn anonymous hearing/suspicion into named public guilt. |

If cure farming is confirmed, use a persistent civic identity or producer receipt that survives the tested conversion chain. Do not introduce a speculative identity system based only on an assumption about Minecraft UUID behavior. If delayed attribution cannot find a legitimate community within its bounded window, record a diagnostic rather than crediting an arbitrary nearby village.

### 7.4 Leave broader additions for a later release

Defer global renown, factions, reputation-based trading economics, an independent rumor-propagation simulation, automatic guard hostility from public tier alone, and new punishment systems. Also defer a large retuning of tier thresholds and scores until corrected integrations produce trustworthy gameplay observations.

The shipped definitions include room for legal settlement and rescue stories, but a definition is not proof that an authoritative producer delivers that event. Before enabling new bonuses, identify the exact producer and operation identity. Paying the same fine or serving the same sentence must never become a renewable positive-standing source. Prefer settlement to soften the existing penalty; add separate praise only for a distinct, verified civic deed.

## 8. Implementation sequence and ownership

Use reviewable changes with contract fixtures between repositories. Each row can become one or more pull requests; the size column is relative effort, not a calendar estimate. The full reliability track is substantial for a patch release, so prepare release candidates and keep optional content off the critical path.

| Step | Work package | Principal files/areas | Depends on | Relative size and completion evidence |
|---|---|---|---|---|
| A | Capture contracts and reproduce defects | Existing service/state/API tests; real companion-adapter fixtures; pinned build manifest | None | Medium. Focused failing cases for F01–F14, plus written freeze, retention, title, and replay semantics. |
| B | Immediate authority and duplicate fixes | Crime `CrimeReputationCompat`, `CrimeIntegrationPump`; Reputation `ReputationResult`, duplicate branch, authority registry | A | Small. Positive deeds survive Crime; duplicate returns original UUID; NeoForge fix is reused. |
| C | Canonical lifecycle and reconciliation | `ReputationService`, `IncidentRecord`, saved/community/player state, title/event/mirror dispatch | A | Large. Immunity, query consistency, backdated application, terminal superseding, and quiet change events pass. |
| D | Retention, receipts, and migration | Saved-data codec/version, state budgets, read-only receipt API, migration/export diagnostics | C + agreed producer horizon | Large. Replay after hidden history, overflow, future format, and load/save invariants pass. |
| E | Crime delivery and encounter parity | `ReputationOps`/adapter, integration hooks/pump/operation payloads, case links, detector actor/precursor context | B–D | Large. Failure injection, pre-link resolution, NPC filtering, capacity behavior, and fatal encounter parity pass. |
| F | Quests reward and title integrity | `ReputationAward`, `RecordIncidentReward`, `ResolveIncidentReward`, quest instance binding, canonical backend, title/import/mirror paths | C–D | Large. Optional delta, exact-target replay, knowledge, offline title and fallback tests pass. |
| G | Conversations integration and minimal content | Reputation bridge/adapter, action registrar, authored standing/amends content, gossip candidates | C–F for full amends loop | Medium. Capability fallback, one apology per incident, correction-story cooldowns, generated-content checks pass. |
| H | Standing UI, diagnostics, docs | Network DTOs, screen/selector, `StandingDisplay`, commands, config/API/migration/production docs | C–G as applicable | Medium. Pagination, visibility, config cleanup, language/layout, permissions and wire compatibility pass. |
| I | NeoForge parity and packaged release | Existing NeoForge branches, loader APIs, build gates, compatibility manifest | Stable common behavior in B–H | Medium/Large. Separate Java 21/1.21.1 compilation and built-jar runtime evidence. |

The first useful engineering checkpoint is B plus the regression fixtures from A. The next is C/D: do not have three companions invent different receipt, clock, or title semantics while the canonical model is still moving. Land E/F/G against a pinned additive API surface. H's cosmetic work can be cut without weakening a correctness gate.

### Guardrails for implementation

- Keep Reputation's no-mixin, optional-MCA-reflection architecture. Do not import MCA implementation classes into its core to solve one event-order problem.
- Preserve runtime-absent behavior for each companion. Compile-time availability is not permission to make a runtime dependency mandatory.
- Keep player/community/subject IDs authoritative; names are display metadata.
- Keep stable operation identity outside ephemeral UI state and outside configurable display-history retention.
- Emit feedback after final committed accounting, and publish each semantic event once.
- Preserve existing authored IDs and defaults unless a specific migration is documented. Reject malformed overrides transactionally.
- Add tests for externally observable state and failure boundaries. Avoid tests that only assert a source string or mirror a helper's implementation.
- Update each repository's own generated content through its owning tooling. Keep unrelated Townstead, map, dialogue-pack, or legal features out of these patches.

## 9. Migration, recovery, and rollout

### Pre-upgrade inventory

Produce an operator-readable, read-only report with save format, record counts, scores, live/hidden/pinned incidents, receipt coverage, superseded records, unknown definitions, decay immunity, pending companion operations, missing case links, and title mirror revisions. Include a summary count plus identifiers for investigation; avoid dumping player narratives into routine logs.

Use the existing export/diagnostic infrastructure where possible. Back up the world and the relevant companion data together before a multi-addon upgrade. Record jar hashes, loader/Minecraft/MCA versions, configs, and datapack IDs in the test fixture manifest.

### Migration rules

| Existing state | Required handling |
|---|---|
| Valid format-1 Reputation data | Preserve player/community keys, incident IDs, baseline, current contributions, witness/subject data, titles, high-water, and migration markers. Add explicit schema defaults. |
| Retained incident with dedupe key | Create a receipt that returns the same incident ID; preserve legacy lookup compatibility. |
| Already-pruned history | Do not invent old receipt identities or claim that replay protection was recovered. Use producer case/quest records only when identity and original outcome can be established; otherwise report the gap. |
| `superseded_by` history | Mark terminal for accounting, retain original historical fact, and never restore a contribution merely because status changes. |
| Existing decay immunity | Preserve the current contribution at upgrade and initialize the new freeze clock there. Earlier decay already applied cannot be inferred and reversed automatically. |
| Pending Crime create + later case resolution | Derive missing desired resolution work from retained case revision history, bind it to the creation operation, and deliver in order. |
| Old Crime NPC operations | Mark non-player civic work ineligible without deleting the underlying NPC case. Report pre-existing suspect Reputation rows for review. |
| Quests legacy standing | Keep existing import markers and precedence; never add canonical mirrored balances back as fresh baseline. Test mod removal/reinstallation. |
| Title mirror drift | Reconcile by source/scope and revision, preserving unrelated titles. Do not blindly union revoked canonical titles back in. |
| Over-budget or malformed records | Preserve recoverable raw data, report the exception, and apply the documented admission/quarantine policy. Do not silently discard live contribution to satisfy a cap. |
| Future save format | Preserve untouched and refuse destructive conversion; provide an actionable incompatible-format diagnostic. |

Validate migration before committing: score totals should match at the chosen effective time, IDs remain stable, no event/toast/reward fires merely because data was imported, and a second migration attempt is a no-op. Explicitly allow only the changes mandated by repaired superseding or documented reconciliation semantics, with an audit entry explaining each.

Receipts cannot reconstruct a deed that was lost before 0.4.1 or recover provenance already folded out of history. Offer targeted recovery tooling that can preview proposed links/adjustments and apply only established identities. Do not automatically replay every historical quest or apply a global reputation compensation bonus.

### Rollback

A downgrade is not automatically safe after a new schema or companion payload is written. The supported rollback should restore the coordinated pre-upgrade backup, unless an explicit tested downgrade exporter is implemented. Never let old-format readers silently rewrite new receipts and lifecycle state. Test an interrupted migration and preserve the original file until conversion is validated.

### Rollout stages

1. Run unit/contract/migration checks on the exact candidate sources and build clean artifacts.
2. Test copies of representative worlds: Reputation-only, all addons, long-running high-history world, and a world with pending Crime/Quests work.
3. Run the built-jar multiplayer suite on Forge and then independently on NeoForge.
4. Publish a release candidate with a precise compatibility list and known limitations. Request focused observations on deed recognition, amends, title consistency, and migration reports.
5. Release final artifacts only after the required gates pass. If schedule pressure emerges, remove optional acknowledgment/UI polish; do not waive data-integrity or producer-authority gates.

## 10. Verification plan

### Automated and integration regression cases

Extend the existing tests around `ReputationService`, `StandingPipeline`, `Dedupe`, `Pruning`, `SavedData`, `Resolution`, `Decay`, `CoreIncidentAuthority`, `McaReputationApi`, opinion, packets, and display state. The current dedupe suite deliberately expects a pruned key to disappear; that expectation must change with the new retention contract. Add actual companion-adapter tests that exercise the translated payload, not only JSON parsing or bridge stubs. [R-dedupe-tests] [R-resolution-tests] [R-tests]

| Test | Scenario | Required invariant |
|---|---|---|
| T01 | Iterate all six core kinds with Crime active | Only implemented assault/killing kinds are claimed. |
| T02 | Disable/re-enable Crime detection, integration, and historical replay separately | Effective authority and fresh delivery follow the documented truth table. |
| T03 | Send the identical incident operation twice | Same incident ID, zero second delta, one notification/title effect. |
| T04 | Drop create acknowledgment, save/reload, replay | Recover the original link without a second incident. |
| T05 | Save an acknowledged producer while canonical write is missing | Recovery detects and repairs the missing canonical outcome within the promised horizon. |
| T06 | Resolve Crime case before creation delivery/link | Exact case resolution eventually reaches the original public incident. |
| T07 | Repeat weaker/equal/stronger resolution revisions | Monotonic result; no replacement target or repeated reward. |
| T08 | Unwitnessed dropped incident versus private-retained incident | Both complete correctly; neither produces guessed fallback public punishment. |
| T09 | Missing definition, invalid payload, bridge exception, explicit disablement | Distinct outcomes and retry/terminal behavior. |
| T10 | Receipt lookup under a datapack with public assault visibility | Lookup never changes score, ledger, receipts, or events. |
| T11 | Full producer outbox, failed link, and full Reputation budget | Explicit recovery/backpressure; no predicted-but-untracked canonical write. |
| T12 | Assault → killing with and without Crime | Equivalent final public result for the same evidence; legal cases remain intact. |
| T13 | Witnessed assault → unseen killing | Prior public assault is not refunded. |
| T14 | Different victims/encounters, guard assault, mugging | Superseding does not merge unrelated public facts. |
| T15 | NPC offender and offline player offender | NPC does not enter player store; offline player can be credited/blamed correctly. |
| T16 | Every query/mutation path under village immunity | No hidden decay bypass. |
| T17 | Master/decay freeze, elapsed time, re-enable | Documented pause-clock policy; no accidental catch-up. |
| T18 | Decay across a tier with canonical and Quests reads | Scores/tiers/mirrors agree; no repeated deed celebration. |
| T19 | Fold → resolve → reconcile → reload | Superseded record remains permanently non-contributing. |
| T20 | Hide/prune history, then replay a known operation | Existing receipt prevents another reward. |
| T21 | Compaction with decay, witnesses, unresolved case, and title history | Future contribution, opinion, and resolvability remain equivalent. |
| T22 | Pinned overflow, community cap, malformed/future-format NBT | No silent truncation or destructive rewrite; bounded processing. |
| T23 | Delayed old deed arrives after new deeds | Present contribution before effects; stable chronological ordering. |
| T24 | Quest delta absent, explicit 0, positive/negative override, out-of-range override | Definition/default/clamp semantics survive translation. |
| T25 | Same completion repeated versus new quest instance | First is a no-op; second is independently eligible. |
| T26 | Accept restitution for A, commit B, turn in and retry | Only A can resolve, with stable community and reward identity. |
| T27 | Known-to-giver query with informed, uninformed, missing, and moved speaker | Awareness is evaluated explicitly and fails closed when context is missing. |
| T28 | Quest title grant/revoke, offline read, login, global-only title holder | Canonical and mirrored scope agree without inventing communities. |
| T29 | Custom ladder high-water and multi-tier jump | Correct ladder; documented milestone grants once. |
| T30 | Disable Conversations integration while evaluating loot/Quests conditions | Generic Reputation predicates remain independent. |
| T31 | Valid zero opinion versus unsupported/disabled opinion | Only unavailable cases take the documented public-standing fallback. |
| T32 | Apologize through multiple residents and reconnect/retry | One public reward per incident; future distinct incident remains eligible. |
| T33 | Disprove/atone after original gossip was told | One meaningful correction; no decay-tick gossip spam. |
| T34 | 65+ communities, long names, negative distant village | Every permitted community remains navigable; bounded detail payloads. |
| T35 | Display off/on; reused foreign scoreboard objective | Clean owned state; no foreign objective mutation. |
| T36 | Reload malformed/valid datapacks and change policies | Invalid reload preserves active definitions; valid reload invalidates derived caches coherently. |
| T37 | Legacy import, remove/re-add companions, migrate twice | No balance/title duplication or loss of authoritative state. |
| T38 | World A stop → world B start in the same process | Authority, pending UI, caches, and callbacks do not leak between servers. |

### Addon combination matrix

Run these eight combinations with Reputation present. “Present” means the packaged adapter loads and reports the expected capabilities, not simply that a jar appears in the mods directory.

| Quests | Conversations | Crime | Main check |
|---|---|---|---|
| Absent | Absent | Absent | Native deeds, private/witnessed behavior, decay, commands, Standing screen. |
| Present | Absent | Absent | Quest awards, exact-target restitution, title mirrors, legacy import. |
| Absent | Present | Absent | Standing/opinion checks, amends fallback, narrative acknowledgment. |
| Absent | Absent | Present | Authority split, public/legal separation, delivery and resolution recovery. |
| Present | Present | Absent | Conversation-to-quest handoff with a bound public incident. |
| Present | Absent | Present | Crime restitution and quest completion without dialogue dependency. |
| Absent | Present | Present | Legal settlement, public correction, personal response without quest dependency. |
| Present | Present | Present | Full amends loop, deed parity, title consistency, multiplayer and reload. |

Also run the corresponding eight combinations **without Reputation**, including the empty-addon baseline, to verify optional classloading and existing fallback behavior. Most of those controls can be automated startup/contract smoke tests; concentrate full gameplay sessions on the combinations exercising a changed path. Test disabling an installed integration separately from uninstalling it: those are different states with different retained work.

### Built-jar runtime gates

Use a dedicated server and at least two clients for the full suite. Test the actual Forge 1.20.1 artifacts under Java 17 and the separate NeoForge 1.21.1 artifacts under Java 21. Record the chosen common MCA binary. Current repositories use different development MCA pins, so a green development launch in one repository does not certify the others.

Verify reflection bindings against every MCA line the release actually claims to support, including production names/remapping. If a supported range cannot be validated, narrow the published claim rather than claiming every version in a metadata range was tested. Keep loader-specific APIs and dependencies in their existing branch-specific locations.

Required installed-game scenarios:

- Two players with different standing in the same village; separate villages with the same numeric ID in different dimensions.
- Day/night, sleeping observers, blocked sight, direct victim knowledge, and witnessed/unwitnessed assault and killing.
- Cure and rescue attribution with player disconnect, projectile/pet involvement, chunk unload, repeated attempts, and multiple players where relevant.
- Raid victory timing and duplicate hero-effect application.
- Restitution accepted before a new offense, turn-in after travel, giver relocation, and save/reload between acceptance and reward.
- Crime resolution while Reputation is temporarily unavailable; replay after reinstall; queue/link failure fixtures.
- Full Standing/Journal/conversation/guard menus at small and large GUI scale, long names, supported languages, and rapid repeated input.
- Server-authoritative packet validation: another player's UUID, unauthorized history request, excessive page/detail requests, stale selection, and protocol mismatch.

Crime has an open [guard challenge screen issue](https://github.com/otectus/MCACrime/issues/4) reporting a client crash after attacking villagers. Its crash log was not analyzed in this review, so neither root cause nor Reputation involvement is established. Reproduce that flow as a suite regression gate and route any actual UI fix to the owning repository.

### Performance evidence

Measure reconciliation and opinion-query cost on a populated village and on players near configured history/community limits. Record server tick time, per-query allocations/time, save size, packet sizes, outbox drain duration, and bounded log volume before/after. Use the same fixture for comparison. Set release budgets from those measurements rather than inventing millisecond guarantees in advance.

The expected design is bounded work per tick, per page, and per delivery budget; no full-history packet for every menu refresh; and no all-community reconciliation for every NPC line. Do not prune correctness-critical data merely to make a benchmark look smaller.

## 11. Documentation and final acceptance

Update the README, API, configuration, datapack, migration, diagnosis, and production-test documents to reflect the actual 0.4.1 contracts. Some retained guidance still references earlier release/protocol assumptions. Generate the compatibility/version table from release metadata where practical, while keeping API version, save format, and network protocol distinct.

Document:

- The producer for each native event kind and the effect of each integration switch.
- The difference between event occurrence time, effective decay time, and application time.
- The replay guarantee, receipt retention horizon, terminal no-public outcomes, and explicit recovery beyond the horizon.
- Exact-target restitution, optional delta semantics, witness requirements, and supported per-instance fields.
- Current tier versus high-water milestone and revocable story title.
- What an observer can know, and why a private relationship can differ from public standing.
- The new storage budget/admission policy and recovery/rollback procedure.
- Packaged build prerequisites and actual tested addon/MCA/loader versions.

Diagnostics should make failures distinguishable: producer unclaimed, bridge absent, integration disabled, unwitnessed accepted, unknown incident, pending create, pending resolution, receipt expired, capacity exhausted, or title mirror stale. Include operation/case/incident identifiers where useful to an operator; keep implementation language out of ordinary player dialogue.

**Definition of done for 0.4.1:**

1. Each enabled deed has one effective public producer in every supported combination.
2. Duplicate or recovered operations cannot pay twice or lose their original identity within the documented recovery horizon.
3. Resolutions reach the exact intended record, including when they precede creation acknowledgment.
4. NPC actors, other players, communities, and dimensions cannot be conflated.
5. Score, tier, titles, opinions, mirrors, and conditions agree on one effective state and on feature availability.
6. Decay immunity, superseding, retention, and migration preserve the documented accounting invariants.
7. Existing worlds have a tested conversion, failure recovery, and supported rollback path.
8. Built adapters are present; absent-addon behavior remains valid; Forge and NeoForge claims have separate evidence.
9. New amends content is replay-safe, knowledge-aware, and does not replace Crime's legal state or MCA's private relationships.
10. The release notes list only fixes and scenarios actually verified in the candidate artifacts.

## 12. Source index

References throughout this plan point to the reviewed commit, not a moving branch. Source defects should be rechecked against any intervening commits before implementation. Paths below are the primary evidence anchors; each linked class also provides the surrounding control flow.

| Area | Primary anchors |
|---|---|
| Canonical recording and reads | [Reputation service][R-service], [public API][R-api], [result type][R-result], [NeoForge duplicate result][Neo-result] |
| Native detection | [Core kinds][R-kinds], [authority registry][R-authorities], [positive deed hooks][R-deeds], [assault/killing hooks][R-gameplay], [witness resolver][R-witnesses] |
| State and lifecycle | [Saved data][R-saved], [player state][R-player], [community state][R-community], [incident lifecycle][R-incident], [incident selector][R-query] |
| Content and derived state | [Incident definitions][R-incidents], [tier defaults][R-tiers], [opinion resolver][R-opinion], [title service][R-title], [mirror contract][R-mirror], [standing condition][R-condition] |
| Client and operations | [Network][R-network], [standing display][R-display], [diagnosis guidance][R-diagnosis], [production checks][R-production] |
| Crime producer and delivery | [Adapter/authority][X-adapter], [mapping][X-mapping], [hooks][X-hooks], [pump][X-pump], [detector][X-detector], [case service][X-case], [build][X-build] |
| Quests rewards and state | [Record reward][Q-record], [resolve reward][Q-resolve], [award payload][Q-award], [canonical backend][Q-backend], [incident condition][Q-condition], [title service][Q-title], [legacy import][Q-import], [build][Q-build] |
| Conversations | [Canonical adapter][C-adapter], [bridge/check/gossip contract][C-bridge], [action registrar][C-registrar], [shipped amends dialogue][C-amends], [build][C-build] |
| Existing regression foundations | [Reputation test tree][R-tests], [dedupe tests][R-dedupe-tests], [resolution tests][R-resolution-tests] |

[R-service]: https://github.com/otectus/MCAReputation/blob/9abc5e2a9f513bf7c059990f5a9f12369c1378df/src/main/java/dev/otectus/mcareputation/reputation/ReputationService.java
[R-api]: https://github.com/otectus/MCAReputation/blob/9abc5e2a9f513bf7c059990f5a9f12369c1378df/src/main/java/dev/otectus/mcareputation/api/McaReputationApi.java
[R-result]: https://github.com/otectus/MCAReputation/blob/9abc5e2a9f513bf7c059990f5a9f12369c1378df/src/main/java/dev/otectus/mcareputation/api/ReputationResult.java
[R-kinds]: https://github.com/otectus/MCAReputation/blob/9abc5e2a9f513bf7c059990f5a9f12369c1378df/src/main/java/dev/otectus/mcareputation/api/CoreIncidentKind.java
[R-authorities]: https://github.com/otectus/MCAReputation/blob/9abc5e2a9f513bf7c059990f5a9f12369c1378df/src/main/java/dev/otectus/mcareputation/event/CoreIncidentAuthorities.java
[R-deeds]: https://github.com/otectus/MCAReputation/blob/9abc5e2a9f513bf7c059990f5a9f12369c1378df/src/main/java/dev/otectus/mcareputation/event/ReputationDeedEvents.java
[R-gameplay]: https://github.com/otectus/MCAReputation/blob/9abc5e2a9f513bf7c059990f5a9f12369c1378df/src/main/java/dev/otectus/mcareputation/event/ReputationGameplayEvents.java
[R-witnesses]: https://github.com/otectus/MCAReputation/blob/9abc5e2a9f513bf7c059990f5a9f12369c1378df/src/main/java/dev/otectus/mcareputation/incident/WitnessResolver.java
[R-saved]: https://github.com/otectus/MCAReputation/blob/9abc5e2a9f513bf7c059990f5a9f12369c1378df/src/main/java/dev/otectus/mcareputation/state/ReputationSavedData.java
[R-player]: https://github.com/otectus/MCAReputation/blob/9abc5e2a9f513bf7c059990f5a9f12369c1378df/src/main/java/dev/otectus/mcareputation/state/PlayerReputationRecord.java
[R-community]: https://github.com/otectus/MCAReputation/blob/9abc5e2a9f513bf7c059990f5a9f12369c1378df/src/main/java/dev/otectus/mcareputation/state/CommunityReputationRecord.java
[R-incident]: https://github.com/otectus/MCAReputation/blob/9abc5e2a9f513bf7c059990f5a9f12369c1378df/src/main/java/dev/otectus/mcareputation/incident/IncidentRecord.java
[R-query]: https://github.com/otectus/MCAReputation/blob/9abc5e2a9f513bf7c059990f5a9f12369c1378df/src/main/java/dev/otectus/mcareputation/api/IncidentQuery.java
[R-incidents]: https://github.com/otectus/MCAReputation/tree/9abc5e2a9f513bf7c059990f5a9f12369c1378df/src/main/resources/data/mcareputation/mcareputation/incidents
[R-tiers]: https://github.com/otectus/MCAReputation/blob/9abc5e2a9f513bf7c059990f5a9f12369c1378df/src/main/resources/data/mcareputation/mcareputation/reputation_tiers/default.json
[R-opinion]: https://github.com/otectus/MCAReputation/blob/9abc5e2a9f513bf7c059990f5a9f12369c1378df/src/main/java/dev/otectus/mcareputation/reputation/OpinionResolver.java
[R-title]: https://github.com/otectus/MCAReputation/blob/9abc5e2a9f513bf7c059990f5a9f12369c1378df/src/main/java/dev/otectus/mcareputation/reputation/TitleService.java
[R-mirror]: https://github.com/otectus/MCAReputation/blob/9abc5e2a9f513bf7c059990f5a9f12369c1378df/src/main/java/dev/otectus/mcareputation/api/ReputationMirror.java
[R-condition]: https://github.com/otectus/MCAReputation/blob/9abc5e2a9f513bf7c059990f5a9f12369c1378df/src/main/java/dev/otectus/mcareputation/data/StandingCondition.java
[R-network]: https://github.com/otectus/MCAReputation/blob/9abc5e2a9f513bf7c059990f5a9f12369c1378df/src/main/java/dev/otectus/mcareputation/network/ReputationNetwork.java
[R-display]: https://github.com/otectus/MCAReputation/blob/9abc5e2a9f513bf7c059990f5a9f12369c1378df/src/main/java/dev/otectus/mcareputation/event/StandingDisplay.java
[R-diagnosis]: https://github.com/otectus/MCAReputation/blob/9abc5e2a9f513bf7c059990f5a9f12369c1378df/DIAGNOSIS.md
[R-production]: https://github.com/otectus/MCAReputation/blob/9abc5e2a9f513bf7c059990f5a9f12369c1378df/PRODUCTION_TESTS.md
[R-tests]: https://github.com/otectus/MCAReputation/tree/9abc5e2a9f513bf7c059990f5a9f12369c1378df/src/test/java/dev/otectus/mcareputation
[R-dedupe-tests]: https://github.com/otectus/MCAReputation/blob/9abc5e2a9f513bf7c059990f5a9f12369c1378df/src/test/java/dev/otectus/mcareputation/state/DedupeTest.java
[R-resolution-tests]: https://github.com/otectus/MCAReputation/blob/9abc5e2a9f513bf7c059990f5a9f12369c1378df/src/test/java/dev/otectus/mcareputation/incident/ResolutionTest.java
[X-adapter]: https://github.com/otectus/MCACrime/blob/d0d493462fbe1ae4311ec801f0b6270325568f0f/src/main/java/dev/otectus/mcacrime/compat/reputation/CrimeReputationCompat.java
[X-mapping]: https://github.com/otectus/MCACrime/blob/d0d493462fbe1ae4311ec801f0b6270325568f0f/src/main/java/dev/otectus/mcacrime/compat/CrimeIncidentMapping.java
[X-hooks]: https://github.com/otectus/MCACrime/blob/d0d493462fbe1ae4311ec801f0b6270325568f0f/src/main/java/dev/otectus/mcacrime/integration/CrimeIntegrationHooks.java
[X-pump]: https://github.com/otectus/MCACrime/blob/d0d493462fbe1ae4311ec801f0b6270325568f0f/src/main/java/dev/otectus/mcacrime/integration/CrimeIntegrationPump.java
[X-detector]: https://github.com/otectus/MCACrime/blob/d0d493462fbe1ae4311ec801f0b6270325568f0f/src/main/java/dev/otectus/mcacrime/detect/CrimeDetector.java
[X-case]: https://github.com/otectus/MCACrime/blob/d0d493462fbe1ae4311ec801f0b6270325568f0f/src/main/java/dev/otectus/mcacrime/ledger/CrimeCaseService.java
[X-build]: https://github.com/otectus/MCACrime/blob/d0d493462fbe1ae4311ec801f0b6270325568f0f/build.gradle
[Q-record]: https://github.com/otectus/MCAQuests/blob/724b07f0565c91d6e3adc2e42f58adff40573e19/src/main/java/dev/otectus/mcaquests/quest/reward/RecordIncidentReward.java
[Q-resolve]: https://github.com/otectus/MCAQuests/blob/724b07f0565c91d6e3adc2e42f58adff40573e19/src/main/java/dev/otectus/mcaquests/quest/reward/ResolveIncidentReward.java
[Q-award]: https://github.com/otectus/MCAQuests/blob/724b07f0565c91d6e3adc2e42f58adff40573e19/src/main/java/dev/otectus/mcaquests/compat/ReputationAward.java
[Q-backend]: https://github.com/otectus/MCAQuests/blob/724b07f0565c91d6e3adc2e42f58adff40573e19/src/main/java/dev/otectus/mcaquests/compat/reputation/CanonicalReputationBackend.java
[Q-condition]: https://github.com/otectus/MCAQuests/blob/724b07f0565c91d6e3adc2e42f58adff40573e19/src/main/java/dev/otectus/mcaquests/quest/condition/leaf/HasIncidentCondition.java
[Q-title]: https://github.com/otectus/MCAQuests/blob/724b07f0565c91d6e3adc2e42f58adff40573e19/src/main/java/dev/otectus/mcaquests/quest/title/TitleService.java
[Q-import]: https://github.com/otectus/MCAQuests/blob/724b07f0565c91d6e3adc2e42f58adff40573e19/src/main/java/dev/otectus/mcaquests/compat/reputation/QuestsStandingImport.java
[Q-build]: https://github.com/otectus/MCAQuests/blob/724b07f0565c91d6e3adc2e42f58adff40573e19/build.gradle
[C-adapter]: https://github.com/otectus/MCAConversations/blob/df0b5c82d8e0758068ca22b58d759c881c150db3/src/main/java/dev/otectus/mcaconversations/compat/reputation/ConversationsReputationCompat.java
[C-bridge]: https://github.com/otectus/MCAConversations/blob/df0b5c82d8e0758068ca22b58d759c881c150db3/src/main/java/dev/otectus/mcaconversations/compat/ReputationBridge.java
[C-registrar]: https://github.com/otectus/MCAConversations/blob/df0b5c82d8e0758068ca22b58d759c881c150db3/src/main/java/dev/otectus/mcaconversations/compat/mca/ConversationsMcaRegistrar.java
[C-amends]: https://github.com/otectus/MCAConversations/blob/df0b5c82d8e0758068ca22b58d759c881c150db3/src/main/resources/data/mcaconversations/dialogues/conversations.topic.standing.incident.respond.json
[C-build]: https://github.com/otectus/MCAConversations/blob/df0b5c82d8e0758068ca22b58d759c881c150db3/build.gradle
[Neo-result]: https://github.com/otectus/MCAReputation/blob/8e0ac5ef1a7493c3e1f1df71df04275fffd60f40/src/main/java/dev/otectus/mcareputation/api/ReputationResult.java
