# MCA: Reputation — Phase 1: Public Profiles on the 0.5.0 Foundation

**Status:** Implementation specification, not a claim of completed implementation or runtime certification.  
**Primary repository:** `otectus/MCAReputation`, Forge `main`.  
**Verified baseline:** MCA: Reputation **0.5.0**, commit `d1f5decdaf9104ddee7898f115864569196b4b29`.  
**Proposed feature release:** **0.6.0**.  
**Review date:** September 16, 2026.  
**Supersession:** This document replaces the previous Phase 1 plan in full. Do not merge its old version assumptions, pruning strategy, or API-negotiation instructions into this specification.

---

## 1. Instructions to the implementation agent

Implement a public reputation-profile layer that answers three different questions:

> What does this community think of the player? How well known is the player here? What is the player known for, and which of those facts does this particular villager actually know?

Retain the current standing economy. Add Recognition, incident-derived Facets, bounded repeat-credit policies, richer knowledge-filtered villager interpretation, and the interfaces and small content slice needed by Quests and Conversations. Preserve Crime's independent legal authority.

This is an extension of **0.5.0's delivered reliability architecture**, not an implementation of that architecture from scratch. First inspect the checkout's instructions, current source, tests, build configuration, and the companion paths listed here. Record the actual commit IDs. If HEAD has advanced, reconcile differences explicitly before editing; do not reset user changes to match this review.

The implementation must produce working code, shipped data, tests, migration, updated documentation, and a verification report. Describing an API without implementing it, or adding a condition with no consumer registration, is not completion.

Work in reviewable stages. Preserve the public ABI and existing standalone behavior. Do not introduce Ote's Lib as a prerequisite, a new loader abstraction, or a major rewrite of the suite. Do not publish a release or push to a remote merely because this document suggests a release version.

### Scope and review limits

The design is based on a **targeted, commit-pinned source review** of Reputation's delivery, reconciliation, storage, incident, API, reload, and networking paths, plus the companion reputation adapters. This review did not execute Gradle, launch Minecraft, or certify the built jars. Repository test claims are evidence supplied by the repository, not tests performed for this document. The existing production checklist still distinguishes automated coverage from unexecuted runtime verification. [R14]

---

## 2. Verified baseline: what already exists

### 2.1 Repository revisions

| Repository | Current line at review | Reviewed commit |
|---|---|---|
| `otectus/MCAReputation` | 0.5.0 | `d1f5decdaf9104ddee7898f115864569196b4b29` |
| `otectus/MCAQuests` | 1.6.5 | `5348af1865d94dd279a19e539aba94b9684c3964` |
| `otectus/MCAConversations` | 1.7.0 | `ef9259356a7bc3e4f4abe1c63cad15fd88015154` |
| `otectus/MCACrime` | 0.6.4 | `fdb602428c101c2364a0e7bb0cc5cba57aeedce1` |

Companion line labels above come from the reviewed shipping commit metadata. [R22] [R23] [R24]

Reputation's `gradle.properties` declares Minecraft 1.20.1, Forge 47.4.10, mod version 0.5.0, and an MCA runtime pin of `7.7.1-alpha.2+1.20.1`, with the accepted MCA range `[7.6,8)`. Retain Java 17 and the existing reflection-based MCA compatibility discipline. [R01]

The changelog labels 0.5.0 September 8, 2026; the reviewed shipping commit was uploaded September 16, 2026. These are different dates, not different baselines. Do not revive the unreleased 0.4.1 line: the current changelog expressly says its API-jar work shipped in 0.5.0. [R02] [R21]

### 2.2 Existing foundation to extend, not duplicate

| Area | Verified 0.5.0 implementation | Consequence for Phase 1 |
|---|---|---|
| Capabilities | `McaReputationApi.capabilities(server)` and `ReputationCapabilities` | Add profile feature identifiers; do not replace negotiation with scattered reflection probes. |
| Delivery | `deliver(IncidentDelivery)`, typed outcomes, operation receipts | Attach profile accounting to the same accepted operation. |
| Plain recording | `record()` routes through delivery | Keep one canonical acceptance path for legacy and new callers. |
| Replay lookup | Read-only `findReceipt`, `findIncident`, `receiptFloor` | Never discover an old deed by recording a synthetic probe. |
| Supersession | `recordSuperseding(..., SupersedeSpec)` | Replace an encounter once across standing and profile channels. |
| Reconciliation | `ReconciliationService` with `MUTATE`, `QUERY`, `INSPECT` | Profile aging must obey the same policy gate. |
| Standing publication | `StandingChange`, causes, quiet changes | Profile-only changes need their own coherent publication without duplicating standing events. |
| Speaker context | `SpeakerContext`, speaker-aware selectors | Use real observer context; missing context must not silently become community knowledge. |
| Opinion availability | Detailed results distinguish valid zero from unavailable | Extend that distinction to profiles. |
| Bound amends | `resolveBound(... incidentId ... operationKey)` | Repair existing consumer contracts; do not invent another resolution ledger. |
| Storage | Save format **2**, receipts, future-format raw preservation | The proposed profile format is **3**, not 2. |
| Networking | Protocol **4**, paged communities, richer deed lines | The proposed Forge profile protocol is **5**, not 4. |
| Build | Compile-only API artifact and verification | Maintain ABI and verify the complete public signature dependency closure. |

Sources: current API, capabilities, service, reconciliation, storage, networking, and build files. [R03] [R04] [R05] [R06] [R07] [R08] [R09] [R10] [R11] [R12] [R13] [R19]

### 2.3 Important change in retention policy

The previous plan recommended folding live recognition and facet values into permanent community baselines. **Do not do that.**

In 0.5.0, `CommunityReputationRecord.evictable()` rejects pinned incidents, contributing incidents, and certain recent unresolved negative incidents. `canAdmit()` and the service return a capacity refusal when nothing can be safely evicted. Some nearby comments still describe the older folding policy, but the executable predicate is stricter. [R07]

A live-value fold preserves today's displayed sum while changing tomorrow's decay, speaker knowledge, resolution options, and the explanation of the value. Phase 1 must protect live **profile** evidence too. It must not reintroduce the problem that 0.5.0 deliberately addressed.

---

## 3. Audit findings and prerequisite work

Distinguish source observations from suspected failure modes. The following are reasons to write characterization tests, not claims that this review reproduced an in-game crash.

### 3.1 Companion adoption is incomplete

The 0.5.0 changelog explicitly limits its guarantees to Reputation's side of the interface. The reviewed adapters corroborate the need for follow-up work. [R02]

**Quests:** `CanonicalReputationBackend` unconditionally invokes `.delta(award.delta())`; its selector resolution accepts a dedupe parameter but does not pass it to a bound operation; it uses a speaker-less selector path even though selectors can request knowledge of the giver. Its high-water lookup also ignores the requested ladder in favor of the snapshot field. Preserve omitted delta versus explicit zero, carry a real giver context, and bind an exact incident before settlement. [R15]

**Crime:** `CrimeReputationCompat.recordIncident()` reduces the delivery result to `Optional<UUID>`. Its `findIncident()` still sends a synthetic assault request through `record()`. The new receipt/read-only facilities already provide the intended replacement. Do not lose the distinction between accepted-private, retryable refusal, invalid request, and successful public recording. [R16]

**Conversations:** `recordSignal()` currently keys by villager, player, and decision ID, rather than an incident-bound amends identity, and it does not add witnesses to the request. Its compatibility path still uses the older gossip candidate format. A repeated menu decision is not a sufficient identity for multiple unrelated incidents, and changing residents must not multiply rewards for the same apology. [R17]

**Crime mappings:** Assault and killing intentionally reuse Reputation's canonical IDs; other mapped incidents live in `mcacrime`. Preserve this single vocabulary. Fine payment, sentencing, escape, and forgiveness are not interchangeable transitions. [R18]

### 3.2 Transaction edges that Phase 1 must characterize

| Source observation | Required test and response |
|---|---|
| Keyed `deliverInternal()` calls `recordInternal()` before appending the operation receipt; `recordInternal()` publishes. | A synchronous listener must not observe new profile evidence without the operation's accounting being complete. Stage receipt/profile/credit state before publication. Test re-entrant replay. [R05] |
| Supersession snapshots and restores scalar contribution fields around a precursor fold. | Extend staged state/rollback to every profile field, receipt decision, revision, and credit reservation. A refused successor must not alter history. [R05] [R08] |
| Admission is checked before the normal outstanding-decay reconciliation step. | A ledger whose entries become eligible at `now` must not remain permanently full because the admission path never reaches reconciliation. Use a policy-aware, non-growing preflight at one evaluation time. [R05] |
| The arriving incident is aged directly before publication. | Verify delayed delivery, disabled decay, and immunity explicitly. Reuse a canonical initial-age policy rather than inventing different profile aging. [R05] [R06] |
| Future-format storage preserves raw NBT but its documented mutator behavior allows transient, non-persisted in-memory changes. | New profile and delivery paths must refuse writes in read-only mode, not report rewards that disappear on restart. Extend that guard to touched canonical entry points. [R10] |
| Receipt retention is bounded although some comments say replayable forever. | Document and test the actual replay horizon. Never promise unbounded exactly-once recovery from a bounded receipt store. [R02] [R20] |

Treat these fixes as the **narrow reliability prerequisite** for this feature. Do not use them as a reason to refactor every subsystem in the repository.

---

## 4. Phase 1 outcomes and boundaries

### 4.1 Required player-visible outcomes

A player with near-zero standing can still be widely recognized. A helpful player can be known for reliability without being presumed brave. A violent player can have positive standing without their violence disappearing from the description. A villager who did not witness or hear of an event cannot discuss it. Two villagers can interpret the same known history differently without either receiving a second persistent reputation score.

Ship at least one working Quests eligibility example and a small Conversations profile-aware dialogue slice. The expanded Standing screen must explain recognition, prominent traits, and reduced social credit. No collection of unused getters qualifies as the complete Phase 1 experience.

### 4.2 Ownership

| System | Remains authoritative for |
|---|---|
| MCA Reborn | Identity, family, marriage, hearts, base personality and mood. |
| Conversations | Private familiarity/dispositions, dialogue presentation, authored voice, conversational outcomes. |
| Quests | Objectives, partial item deposits, completion, project participation, rewards and quest state. |
| Crime | Criminal cases, evidence, attribution, Heat/Karma, custody, enforcement and legal settlement. |
| Reputation | Canonical civic incidents, public knowledge, standing, recognition, facets and derived public interpretation. |

In particular, public recognition is **not** personal familiarity; a favorable civic opinion is **not** affection; known violence is **not** legal permission to arrest; an atonement flag is **not** personal forgiveness.

### 4.3 Deferred work

Do not implement a generalized obligation system, dynamic redemption generator, social circles, regional fame, incident revelation, contested-evidence simulation, cross-village rumor transport, full dialogue rewrite, economic price rewriting, or global fame. Repairing existing bound amends is in scope; inventing a new amends quest framework is not.

Primary implementation is the reviewed Forge `main` branch. Other loader branches require their own pinned inventory and parity work; do not mechanically assign Forge's protocol/API numbers to NeoForge or claim an unreviewed Fabric port is complete.

---

## 5. Non-negotiable invariants

Assign these IDs to tests and code-review checklists.

| ID | Invariant |
|---|---|
| I01 | One logical producer outcome produces at most one canonical deed and one significance decision within the supported replay contract. |
| I02 | Replayed operations consume no new repeat-credit allowance and create no new profile evidence. Quiet time reconciliation is distinguishable from reapplying the deed. |
| I03 | Private or retained-unwitnessed records contribute no public recognition or community facets. No configuration bypass may make hidden facts public through the new profile channel. |
| I04 | Queries without a record do not create a player/community/credit tracker. Strict inspection neither reconciles nor changes dirty flags. |
| I05 | Standing, profile evidence, supersession, repeat accounting and receipts commit coherently before external notifications. |
| I06 | Capacity cleanup never discards live, future-relevant evidence merely because a displayed score is clamped or rounded to zero. |
| I07 | Negative standing and adverse evidence never become cheaper through repeated wrongdoing by default. |
| I08 | No profile field, absence of evidence, or partial legacy history may silently become proof of a personal relationship or a clean record. |
| I09 | All time-dependent evaluation uses one server-supplied evaluation time and the canonical pause policy; no independent UI clocks. |
| I10 | Existing public API signatures and constructors continue to link; optional companion absence cannot crash common code. |
| I11 | Learning an existing story does not create a new deed. Superseding an incident does not count two copies of the same encounter. |
| I12 | Server restart, reload, producer retries, death/dimension change, and supported migration do not reset accepted significance decisions. |
| I13 | New growing structures have explicit hard bounds and a conservative capacity result; live anti-farm state is never silently evicted to restore full credit. |
| I14 | Future-format/read-only state is not rewritten and does not accept apparently successful volatile mutations. |

---

## 6. Target architecture

Continue using `ReputationSavedData` and `ReputationService`. Add an incident-bound profile payload and pure calculators, not a second social database.

```text
Producer outcome / native detector
    -> existing identity + capability/authority validation
    -> staged canonical delivery
         -> dedupe / receipt lookup
         -> reconciliation + capacity preflight
         -> effective visibility + frozen profile rule
         -> repeat-credit decision
         -> incident, profile payload, receipt, revisions
    -> publish standing/profile changes
    -> Quests eligibility / Conversations voice / Standing screen
```

Introduce a `SocialProfileService` as orchestration around pure `ProfileMath`, `RecognitionResolver`, and `VillagerProfileResolver`. It must not expose an alternate `addFacet()` or `setRecognition()` gameplay write path. Admin diagnostics may show projected changes; production modifications still require an incident or an explicitly audited migration.

A community profile is a projection of retained canonical incidents. A villager profile is that same projection filtered through the existing awareness model. Neither is independently saved as a per-villager score.

Avoid an eager cache initially. A bounded ledger scan is simpler to validate. If later necessary, cache by server identity, player, community, ledger/profile revision, definition generation, observer identity and personality/role, plus an appropriate time/awareness boundary. A cache keyed only by standing revision is wrong: facets, recognition and rumor awareness can change without standing moving.

---

## 7. Recognition: how well known, not how well liked

### 7.1 Semantics

Retain scalar standing and its current tiers. Introduce a non-negative Recognition score, default range `0..1000`, with no conversion from standing. Positive and negative public deeds can both increase recognition. Two opposing deeds must not cancel recognition merely because their standing deltas cancel.

Use explicit authored recognition values. An old/custom incident with no profile specification contributes **zero new recognition by default**, not an automatic severity-derived bonus. Severity describes impact, not necessarily publicity or noteworthiness. Do not treat a request's `appliedDelta == 0` as evidence that no deed happened; score clamping can hide a real contribution.

`GLOBAL_RESERVED` remains the current village-equivalent scope. Give it no extra recognition multiplier and no cross-community dissemination. A future global-fame feature must be explicit.

### 7.2 Suggested default tiers

| ID | Inclusive threshold | Suggested label |
|---|---:|---|
| `unknown` | 0 | Unknown |
| `noticed` | 5 | Noticed |
| `recognized` | 15 | Recognized |
| `well_known` | 40 | Well known |
| `renowned` | 90 | Renowned |
| `famous` | 180 | Famous |

Names, thresholds and descriptions are datapack data. Recognition tier zero is a real answer for a new player, but **not** a claim that an older imported baseline had no history. Report incomplete historical coverage separately.

### 7.3 Recognition and lifecycle

Recognition normally survives apology, atonement and forgiveness and fades with its own finite authored lifetime. `DISPROVEN` removes the discredited incident's recognition by default in Phase 1. Remembering the notoriety of a false accusation would require separate authored evidence, not continued credit from a disproven deed. No such accusation-notoriety feature is required here.

Superseded precursors contribute zero recognition; the successor supplies the encounter's complete authored profile. Merely retelling the incident does not add recognition.

---

## 8. Facets: what the player is known for

### 8.1 Vocabulary and evidence

Ship seven definitions, but only award a facet when the event actually supports it:

| Facet | Range | Interpretation |
|---|---|---|
| `mcareputation:reliability` | -100..100 | Kept versus broken commitments. |
| `mcareputation:bravery` | 0..100 | Demonstrated action under danger; absence is not cowardice. |
| `mcareputation:compassion` | -100..100 | Helping versus harming people. |
| `mcareputation:lawfulness` | -100..100 | Publicly known lawful/unlawful conduct, not Crime's Heat/Karma. |
| `mcareputation:generosity` | 0..100 | Meaningful authored sacrifice; ordinary gifts do not qualify. |
| `mcareputation:mercy` | 0..100 | An explicitly known decision to spare; not inferred from merely not killing. |
| `mcareputation:violence` | 0..100 | Known violent conduct, not automatic guilt or personal fear. |

Keep supporting and opposing evidence counts/magnitudes in the derived value. A net reliability value of zero from substantial opposing evidence is different from no reliability evidence. For a unipolar facet, zero never implies the opposite trait.

All facets are public interpretation, not additional spendable currencies. No ordinary trading, gifting, walking nearby, GUI opening, or repeated conversation clicks should award facets.

### 8.2 Dominant descriptions

A dominant label requires both magnitude and evidence. Default thresholds should require at least two distinct credited incidents, except an explicitly marked major deed may qualify alone. Do not count a coalesced assault, superseded precursor, duplicate, or multiple partial deposits as several demonstrations.

Sort eligible facets by normalized absolute magnitude, then authored display order, then resource ID. Display at most three. Use each facet's positive/negative label, not generic moral coloring. Ties must be stable across JVMs and packet round trips.

For example:

```text
Standing: Honored
Recognition: Well known
Known for: Dependable · Brave · Violent
```

The detailed view may show mixed evidence even when no dominant label is justified.

### 8.3 Arithmetic

Use fixed-point internal contribution units, such as 10,000 subunits per authored point. Apply percentages without truncating each small contribution to a public integer. Sum in checked/saturating `long` arithmetic; normalize and clamp only for the read model. Quantize for display last.

For each active, public, non-superseded incident and facet:

```text
credited = authoredFacetUnits * frozenCreditBasisPoints / 10000
current  = applyFrozenLifecycle(credited, effectiveAge, resolutionState)
rawFacet = sum(current)
publicFacet = clamp(towardZero(rawFacet / 10000), facetRange)
```

Keep `rawFacet` and nonzero subunit evidence meaningful for retention even when `publicFacet` is zero. Clamp saturation must never discard the underlying evidence needed when later opposing events occur.


---

## 9. Authoring model and frozen incident evidence

### 9.1 Data locations

Extend the existing single prepared reload, rather than adding independent listeners whose ordering changes cross-reference validity. The current listener already prepares incidents, standing ladders and titles together. [R12]

```text
data/<namespace>/mcareputation/facets/**/*.json
data/<namespace>/mcareputation/recognition_tiers/**/*.json
data/<namespace>/mcareputation/incident_profiles/**/*.json
data/<namespace>/mcareputation/credit_policies/**/*.json
```

Keep existing incident, title, and standing-tier locations and legacy aliases unchanged. New IDs are namespaced resource locations, not Java enum ordinals.

Add one optional `social_profile` reference to incident definitions. Missing means no new profile contribution. Preserve old construction paths where practical with a delegating constructor; never change the constructor of an existing public API request or snapshot to carry these fields. `IncidentDefinition` currently has no profile/credit members; these are genuinely new functionality. [R09]

### 9.2 Facet definition example

```json
{
  "name": { "translate": "mcareputation.facet.reliability" },
  "description": { "translate": "mcareputation.facet.reliability.description" },
  "range": { "min": -100, "max": 100 },
  "positive_label": { "translate": "mcareputation.facet.reliability.dependable" },
  "negative_label": { "translate": "mcareputation.facet.reliability.unreliable" },
  "display_order": 10,
  "label_min_magnitude": 10,
  "label_min_evidence": 2,
  "opinion_weight_bp": 5000,
  "personality_overrides": {
    "upbeat": 6000,
    "odd": 3500
  }
}
```

Personality IDs must be validated against the actual MCA compatibility normalization used by the checkout. These example IDs are not a license to invent personality names or perform new static linkage to MCA. Unknown personality IDs are harmless authoring warnings, and unresolved personalities use the default weight.

### 9.3 Incident profile example

```json
{
  "allowed_incidents": ["mcareputation:villager_rescued"],
  "recognition": {
    "points": 6,
    "lifetime_ticks": 1344000,
    "resolution_mode": "recognition"
  },
  "facets": {
    "mcareputation:bravery": {
      "points": 8,
      "lifetime_ticks": 672000,
      "resolution_mode": "historical"
    },
    "mcareputation:compassion": {
      "points": 5,
      "lifetime_ticks": 672000,
      "resolution_mode": "evaluative"
    }
  },
  "credit_class": "commendable",
  "credit_policy": "mcareputation:rescue_service",
  "major_evidence": false
}
```

The incident definition selects it with:

```json
{
  "social_profile": "mcareputation:rescued_villager"
}
```

The last block is an **additive field fragment**, not a complete incident definition.

Use a small, fully specified linear lifetime for new profile contributions. Quantize effective age to whole-day steps by default: `a = floor(effectiveAge / decayStepTicks) * decayStepTicks`, with `decayStepTicks = 24000`. At quantized age `a`, the unreconciled factor is `max(0, lifetime - a) / lifetime`. Expose an optional bounded `decay_step_ticks` in the profile definition; shipped lifetimes are whole multiples of that step. The lifetime is finite and positive in the shipped profiles. All calculations use fixed-point units and checked arithmetic. No new per-tick decay engine is needed.

This lifetime is independent of the scalar standing decay policy. A minor facet should not disappear immediately because a standing policy subtracts two whole points per day, and a narrative recognition value should not become immortal just because the standing deed has no decay.

### 9.4 Frozen payload, not live rule reinterpretation

At acceptance, store an internal `IncidentProfileEvidence` payload on the incident containing:

| Field group | Purpose |
|---|---|
| Schema/origin | Payload version and `LIVE`, `LEGACY_ENRICHED`, or `LEGACY_UNENRICHED`. |
| Rule identity | Profile ID and stable rule fingerprint for explanations. |
| Authored and credited units | Recognition and each bounded facet before/after repeat credit. |
| Lifecycle snapshot | Lifetimes and resolution-mode/multiplier data used for this deed. |
| Credit decision | Policy/group ID, window identity, decision percentage, ordinal and suppression reason. |
| Current contribution | Reconciled subunit values, not a second editable profile total. |
| Revision | Profile revision independent of ordinary scalar score changes. |

The payload is evidence attached to one deed, not another incident or an independent per-villager record. Its storage and mutation belong to the canonical incident transaction.

Freeze numeric profile rules at acceptance. Datapack edits change **future deeds**, not previously accepted quantities. Definition labels and observer interpretation weights may change on reload. Removing a profile definition or facet label does not erase existing stored units; an unknown definition displays its ID, uses neutral interpretation weight, and cannot satisfy a newly authored unknown-facet condition accidentally.

This deliberately replaces the previous plan's live reinterpretation plus frozen-baseline mixture. A server owner should not get one calculation for retained old deeds and an incompatible calculation for arbitrarily pruned ones.

### 9.5 Outcome-specific selection

Quests may need different profile rules for a humanitarian rescue, a crafting commission, and a dangerous contract that all use a generic completion incident. Provide a **new wrapper**, not arbitrary numeric overrides in the old request:

```java
public record ProfiledDelivery(
    IncidentDelivery delivery,
    Optional<ResourceLocation> profileSelection,
    Optional<SupersedeSpec> supersession
) {}
```

A new `deliverProfiled(...)` API can normalize this wrapper into the same internal commit as `deliver()` and `recordSuperseding()`. Selection must be compatible with the actual incident type and any authored source allowlist. Ordinary callers select the incident's default profile. Nothing in a C2S packet supplies trusted facet values, profile selection, credit percentages, or witness identities.

### 9.6 Reload validation

Parse all related candidate data, validate references, then publish one immutable generation. In strict mode any error rejects the generation. In lenient mode retain unrelated valid scalar incident definitions and disable the invalid **new profile attachment** with a precise warning; do not discard a working crime definition because its optional facet label is malformed. Reference-dependent new profiles must never be half-published.

Use explicit numeric hard bounds in addition to collection bounds:

| New field | Hard validation range |
|---|---|
| Authored recognition points | 0..100 per incident. |
| Authored facet points | -100..100, additionally respecting a unipolar facet's sign. |
| Facet display range | A subrange of -100..100 containing zero. |
| Recognition tier thresholds | 0..1000, strictly increasing, with a zero floor tier. |
| Opinion weights | -20000..20000 basis points. |
| Profile lifetime | 24000..100000000 ticks; finite, positive, and compatible with its decay step. |
| Profile decay step | 20..24000 ticks, not greater than lifetime. |
| Credit window | 20..100000000 ticks. |
| Credit schedule/tail percentage | 0..10000 basis points, non-increasing. |

Runtime and NBT load paths must enforce the same structural limits. Arithmetic may use wider intermediates, but a malformed save cannot smuggle larger authored values into the active model.

Reject duplicate JSON keys in the new schema rather than quietly taking the last value. Reject malformed resource IDs, non-finite/overflowing values, excessive collections, invalid lifetimes, inverted ranges, non-monotonic tier thresholds, and contradictory credit definitions sharing a group. Missing optional blocks are allowed; present-but-malformed blocks are not silently defaulted.

---

## 10. Repeat-credit policies: diminish rewards, not accountability

### 10.1 Distinguish three mechanisms

**Deduplication** recognizes a retry of the same logical outcome. **Coalescing/supersession** recognizes several signals from one encounter. **Repeat credit** reduces the significance of genuinely different but repetitive positive outcomes. They are not interchangeable.

Retain the existing native rescue/raid/assault protections. Profile code must not reinterpret every event callback, partial delivery, or damage tick as a new credited deed.

### 10.2 Use a finite authored schedule

Prefer an explicit non-increasing basis-point schedule over exponentiation and hidden rounding rules:

```json
{
  "group": "mcareputation:rescue_service",
  "window_ticks": 336000,
  "credit_schedule_bp": [10000, 10000, 5000, 2500, 0],
  "tail_bp": 0,
  "scope": "player_community",
  "subject_limit": {
    "role": "beneficiary",
    "credit_schedule_bp": [10000, 5000, 0],
    "tail_bp": 0
  }
}
```

Occurrence 1 receives schedule index 0. After the last entry use `tail_bp`. Require each entry and the tail to be within `0..10000` and non-increasing. A nonzero tail permits continued slow farming by definition; ship zero tails for repeatable routine positive deeds and describe nonzero tails honestly as pack-author choices.

The optional subject rule is a **second ceiling**, never a separate allowance. Effective percentage is the minimum of group and subject percentages. Cycling through fresh villagers cannot evade the group-wide limit. Missing required subject data takes a conservative shared/missing-subject bucket; it never creates a fresh allowance on each call.

Do not include the provider mod's name, random quest-instance UUID, display name, session ID, or UI choice ID in a shared repeat group. They belong in the operation identity where appropriate, not in the definition of which similar acts compete for social significance.

### 10.3 Time and ordering contract

Use server **acceptance order** for credit ordinals and the server's acceptance clock for fixed windows. Keep `occurredAt` for historical age and `acceptedAt` for credit accounting. An old event delivered late must not rewind a tracker or open a fresh allowance by supplying an old timestamp.

A window begins at the first accepted qualifying event and resets after its fixed duration. Failed/replayed operations do not extend it. Keep a monotonic stored watermark, so a synthetic rollback of the clock cannot reset an allowance. Use overworld game time, not player online time, wall-clock time, or day/night presentation time.

This policy is deterministic for the server's serialized commit order, **not invariant to arbitrary reordering of previously unaccepted deliveries**. State that explicitly. Recalculating prior rewards whenever late mail arrives is out of scope and would violate stable accepted outcomes.

### 10.4 What gets scaled

For a profile explicitly classified as repeatable and commendable, scale its new recognition/facet units and positive scalar standing award once. The stored scalar base delta becomes the credited amount; keep the requested/pre-credit value in the evidence decision for explanation.

Never apply a positive-credit rule to negative scalar standing. Shipped adverse and mixed crime profiles receive full accountability and have no reward-discount policy. If a custom profile contains mixed commendatory/adverse effects, Phase 1 defaults to no discount unless a validated per-channel policy exists; implementing generalized per-channel custom exceptions is not required.

A deed suppressed to zero credit still occurred and may remain a zero-contribution ledger entry if admitted. It must not gain a dominant-trait evidence count, recognition bonus, or duplicate gossip reward merely because the event was repeated. Normal objective completion and non-social item/experience rewards remain owned by Quests.

### 10.5 Bounded tracker storage without an eviction exploit

Store compact group/window counters in `CommunityReputationRecord`. They are policy-accounting state, not reputation meters. Do not rebuild them solely from retained incidents or receipts: both can expire independently.

Proposed hard limits, subject to measured fixture sizes:

| Structure | Proposed bound |
|---|---:|
| Loaded facet definitions | 64 |
| Facet entries in one incident | 8 |
| Profile definitions | 256 |
| Credit groups in one content generation | 64 |
| Group trackers per player/community | 64 |
| Subject trackers across one player/community | 128 |
| Normalized schedule entries | 32 |

Counters saturate once the schedule has reached its tail. Remove an expired tracker only after it no longer restricts the next operation. **Never evict a live tracker by LRU and then grant full credit.** At capacity, use a persisted conservative overflow decision, normally zero new positive credit until the applicable window ends; retain negative effects. Record the reason and expose it in diagnostics. It is acceptable to be less generous under pathological capacity pressure; it is not acceptable to turn that pressure into a farming strategy.

Changing a policy on reload does not reset an active window. Freeze the active window's schedule and duration until it expires. Removing and re-adding the same group preserves its live state. New definitions cannot manufacture unbounded per-instance groups; validate group registry limits and use the same conservative capacity behavior after repeated content changes.

### 10.6 Receipt horizon and stale producer retries

Reuse 0.5.0 receipts and identities. A repeated operation returns its original incident and significance outcome while the necessary receipt/incident evidence is retained. Extend an internal receipt payload or related bounded operation metadata where needed; do not create a second unbounded receipt store. Preserve old public receipt constructors/accessors.

Beyond the retained receipt floor, an absent receipt is **not proof that the operation never happened**. A companion with an ambiguous old outbox item must quarantine it for reconciliation, not retry under a fresh key. Producers must retain durable completion/acknowledgment state. Clearly distinguish exactly-once accounting within a supported recovery window from crash-proof distributed transactions across independently saved mods.

---

## 11. Canonical transaction and failure contract

### 11.1 Stage before mutation

Refactor only the relevant service seam into an internal staged operation that carries: normalized identity, immutable policy snapshot, evaluation time, pending incident, pending profile payload, credit reservation, permitted evictions, precursor changes, receipt, and old/new read models.

The ordering is:

1. Validate server thread, writable store, master enablement, request shape and normalized operation identity.
2. Resolve an existing receipt/incident first. A replay cannot reserve another allowance.
3. Capture one content/policy generation and one evaluation time. Resolve actual community, visibility, known subjects and profile selection.
4. Run policy-aware reconciliation/admission preflight without creating missing records. A dropped unwitnessed operation must still reach its accepted-no-public receipt path rather than needing space for an incident it will never create.
5. Calculate an uncommitted repeat-credit decision and aged profile contribution. Validate all collection and arithmetic bounds.
6. Stage safe evictions and any valid supersession. Re-evaluate admission for the complete operation, not separate scalar/profile fragments.
7. Commit the incident, profile payload, tracker state, receipt and revisions to the same canonical in-memory mutation. Mark all changed persisted clocks/state dirty.
8. Derive standing milestones/titles and canonical snapshots consistently.
9. Publish standing/profile/story notifications only after all accepted state is visible. Listener failure cannot undo a committed outcome or consume the operation again.

Preserve ordinary results and error containment. A preparation failure changes no credit counter, precursor revision, receipt, or public value. Do not catch a failure after partial mutation and log “nothing was written” unless rollback actually restored everything.

`SavedData#setDirty` schedules persistence through Minecraft's save lifecycle; it is not an immediate durable disk commit. Keep the world-global Overworld store and document this distinction rather than advertising lossless cross-mod crash atomicity. [F01]

### 11.2 Typed result behavior

| Outcome | Required producer behavior | Credit effect |
|---|---|---|
| Applied public deed, including a score-clamped/profile-only deed | Persist returned linkage and acknowledgment. | Consume once. |
| Duplicate | Recover original identity/decision; acknowledge original terminal result. | No new consumption. |
| Accepted no public incident | Finish the delivery; do not retry forever. Retain a private ID only if supplied. | No public allowance consumed. |
| Disabled/read-only | Preserve pending work with a clear retry/degraded state. | None. |
| Capacity | Retry with bounded backoff after state changes; make persistent blockage visible. | None unless the deed was actually accepted with an explicitly conservative zero-credit decision. |
| Invalid payload/type | Terminal rejection; expose authoring error. | None. |
| Internal error | Treat separately from a genuinely invalid payload; bounded retry with diagnostics. | None on a rolled-back operation. |

The current service can wrap an internal error in `REFUSED_INVALID` with `ReputationResult.Reason.ERROR`. Adapters must not blindly classify only the outer enum. Add a new detailed result/capability if necessary, preserving the 0.5.0 ABI. [R05] [R20]

For keyed new profile operations, require the wrapper key and request dedupe key to agree after normalization. Use a stable digest when long identifiers need compacting; do not silently truncate two different identities into the same key. A reused identity with a different payload is a conflict, not a new award. Legacy receipts without a payload fingerprint retain their documented legacy behavior.

### 11.3 Supersession

The existing scalar seam is the starting point, but profile-aware supersession must be one staged operation. An assault worth violence `+8` upgraded to a killing profile worth violence `+20` totals **20**, not 28. The precursor remains chronological and terminal; every profile contribution is zero thereafter.

Use valid public encounter replacement and explicit supersession outcome, not merely `incident.currentContribution() != 0`, to decide profile replacement. A delayed successor may have zero standing yet still have live recognition. Conversely, a private/unwitnessed successor must not erase a publicly witnessed precursor.

On refusal or failure restore the precursor exactly, including story/profile revisions, witnesses, context, lifecycle state and links. An attempted but refused replacement must not become gossip. Test full-cap admission, a precursor protected by amends, duplicate successor delivery, delayed arrival, and restart.

---

## 12. Lifecycle, aging, and retention

### 12.1 Resolution modes

Do not run every facet through the scalar penalty multiplier indiscriminately.

| Mode | Apologized / atoned / forgiven | Disproven | Typical use |
|---|---|---|---|
| `recognition` | No moral reduction; ordinary time fading continues. | Zero by default. | How widely the deed identifies the player. |
| `historical` | Retains authored evidence; ordinary fading continues. | Zero. | Bravery or violence actually demonstrated. |
| `evaluative` | Applies frozen, explicitly authored monotonic multipliers. | Zero. | How strongly an unresolved wrong reflects on current conduct. |

For evaluative mode, defaults may follow the existing `0.75 / 0.25 / 0` policy, but snapshot them into new evidence and reject any resolution progression that would increase magnitude. Compute from the credited original and effective age, then enforce no resurrection after a stronger settlement. A positive deed should not lose its meaning because a nonsensical apology status was applied; validate applicable resolution modes at authoring time.

Repairing one's own harm is not a free new generosity or compassion award. Default fine/sentence/apology profiles should repair the relevant original evaluative contribution without awarding independent virtue. Explicit charitable work for another beneficiary remains separately authorable.

### 12.2 Canonical time

Extend `ReconciliationService`, `ReputationPolicy`, and incident reconciliation so aging can detect **profile-only** changes. Profile computations use the canonical effective elapsed age, not `now - created` recomputed in the UI. Pause controls must advance observation clocks without accumulating catch-up aging, as the existing reconciliation architecture intends. [R06]

If profile enablement can freeze profile aging while scalar standing continues aging, the incident needs a separate bounded profile elapsed/last-observed clock. Advance that clock only through the same reconciliation gate and policy snapshot; reuse the scalar elapsed clock only when their policies are identical. This is a second time channel on the incident, not an independent ticking subsystem.

Distinguish initial aging of a backdated newly accepted deed from aging an already stored paused record. Define one policy for the former and use it identically for scalar and profile admission; record any unavoidable limitation concerning freezes that predate receipt of the event. Never guess an unobserved historical freeze interval.

Master-disabled, decay-disabled, immune, re-enabled, and restart transitions require boundary tests. A lazy skip alone is insufficient if no query observed the interval: capture policy transitions for existing loaded records, or retain bounded policy epochs sufficient to calculate the elapsed active interval. Do not claim “no catch-up” unless the test includes a whole disabled interval with no intervening reads.

### 12.3 Protect the evidence, not just the visible integer

Extend both per-community and whole-player admission/eviction to reject pruning when an incident has any live profile subunits or future-relevant resolution state. Disproven/superseded records may become eligible once other existing retention requirements permit it. Pinned and live-amends protection stays intact.

Do not add `recognitionBaseline` or `facetBaselines` in Phase 1. Do not manufacture public hearsay by flattening witness-bound evidence into a community total. A future compressed archive would need to preserve temporal behavior, provenance and resolution semantics; that is a separate design problem.

New shipped profile evidence has finite lifetime so it does not add immortal retention pressure. Existing no-decay scalar deeds can still fill a bounded ledger; Phase 1 must report this honestly and support typed producer backlog, not silently inflate caps or erase records. Cap changes require measured save-size/query-cost evidence and explicit operator documentation.

Retention necessarily limits detailed historical claims. A pruned zero-evidence story is no longer usable for a detailed speaker explanation. Do not claim the system retains every person's lifelong memory; MCA/Conversations retain their separate relationship/familiarity state.


---

## 13. Knowledge-filtered villager profiles

### 13.1 Filter evidence before interpreting it

For a speaker-scoped profile, first apply the existing `AwarenessResolver`/`SpeakerContext` rules to each incident, then aggregate only permitted evidence. Do not calculate the community's facet vector and merely multiply it by a hearsay coefficient: that would reveal events the speaker has not learned.

Preserve the distinction between involvement, witnessing, hearsay, and none. Apply the existing configurable opinion weights to evaluative facet interpretation once. Do not multiply recognition by a sympathy/involvement coefficient: being a victim is stronger personal involvement, not proof that an event is more publicly famous. Speaker recognition is the recognition evidence the speaker actually knows, with its basis supplied separately.

For Phase 1, private-only events remain outside the new **public-profile** facet view, even for their subjects. The existing incident knowledge and Crime/Conversations personal-memory paths can still describe those private experiences. This avoids creating a new competing store of private relationships under the name “Reputation.”

Community recognition is not the percentage of current residents who have heard a rumor. It is the aggregate significance of public evidence. Speaker recognition answers a different, knowledge-filtered question. Label them accordingly.

### 13.2 Individual interpretation

Use a neutral default interpretation plus small data-driven personality/role adjustments. Store no permanent per-villager reputation vector.

```text
baseOpinion = existing knowledge-filtered standing opinion
knownFacetUnits = aggregate only evidence known to this observer
facetAdjustment = clamp(weighted known facets, -25, +25)
finalOpinion = clamp(baseOpinion + facetAdjustment, existing standing bounds)
```

The `25` adjustment cap is a proposed server default, configurable downward or up to a hard bound of 100. The final external Trust/Respect check contribution still obeys the existing combined **±8** limit. Never add a community bias, a legacy opinion bias, and a facet opinion bias as three independent bonuses. [R04]

Use one observer-trait resolver shared by entity and UUID API entry points. It may inspect an already loaded entity and current MCA metadata through `McaCompat`/`McaReflect`; it must not load chunks, scan the world, guess traits for an unloaded villager, or cache live entities beyond the interaction. When traits are unavailable, use neutral/default weights and expose that reduced interpretation basis in diagnostics. Entity and UUID queries for the same loaded villager must agree.

Profession-sensitive weights are allowed only through verified role/profession IDs and the existing compatibility seam. Neutral fallback is mandatory. Implement personality sensitivity first; role sensitivity must not delay the core or trigger a new family/faction subsystem.

### 13.3 No automatic warmth or friendship

Recognition can authorize a line like “I've heard your name.” It cannot authorize “My dear friend” or familiarity-based greetings. Conversations retains its own hearts/familiarity thresholds and relationship eligibility. The public profile supplies evidence, not an automatic write to `WARMTH`, `ATTRACTION`, `FAMILIARITY`, hearts, fear, or legal state.

A valid zero speaker profile stays zero. Falling back from it to a positive community profile would revive the stranger-behavior defect. Fallback is permitted only for genuinely unsupported, disabled, or unresolved functionality, and then only into neutral/authored legacy behavior, not a fabricated personal relationship.

### 13.4 Explainable disagreements

Return a small bounded explanation: which known facets contributed, the knowledge basis, the neutral/default versus resolved trait basis, and the applied capped adjustment. The screen can say “Values your reliability” without exposing hidden incident identities. Do not assert a named informant unless the source data actually records that informant; current hearsay knowledge alone does not prove who told the story.

---

## 14. API and capability contract

### 14.1 Preserve the 0.5.0 surface

Forge `getApiVersion()` stays **1** if this feature is additive. Preserve constructors and accessors of existing public `ReputationRequest`, `IncidentDelivery`, `ReputationSnapshot`, `ReputationIncidentView`, `VillagerOpinion`, `ReputationCapabilities`, receipt/outcome, and event types. New wrappers and new methods are preferable to changing compiled record shapes. [R03] [R04] [R19]

Add new profile DTOs under `api.profile` or another explicitly exported API package. Keep internal tracker/policy/mutable-incident types out of their method signatures. Use immutable defensive copies; mutable components require copies too. Map iteration order used for packets/UI must be explicitly sorted, not inferred from `Map.copyOf()`.

### 14.2 Suggested public models

These are target responsibilities; finalize exact Java signatures before implementation and document them in an API contract test.

| New type | Contents |
|---|---|
| `ProfileSnapshot` | Community, authoritative standing summary, recognition value/tier, facet values, dominant IDs, ledger/profile revisions, definition generation, evaluation time, coverage. |
| `FacetValue` | Value, supporting/opposing evidence strength/counts, observed/unobserved state, label eligibility. |
| `RecognitionValue` | Numeric score, tier ID and evidence count, without conflating it with friendship. |
| `VillagerProfileSnapshot` | Observer identity, known profile, base/facet/final opinion, basis counts, observer-trait resolution quality. |
| `ProfileQueryResult<T>` | Availability, optional value and a bounded machine-readable reason. |
| `ProfileQuery` | Typed recognition/facet/evidence predicates and coverage policy. |
| `ProfileCapabilities` | Current enabled/readiness flags and supported profile schema/features, without altering the old capabilities constructor. |
| `ProfiledDelivery` / detailed outcome | Optional approved profile selection/supersession wrapper and accepted credit explanation. |

Use availability values analogous to `AVAILABLE`, `DISABLED`, `UNSUPPORTED`, `UNRESOLVED`, plus explicit `READ_ONLY`, `MIGRATING`, and contained error handling where needed. Coverage is separate: `COMPLETE_SINCE_RECORD_START`, `PARTIAL_LEGACY`, or `MIGRATING`. “Available, zero, complete” must remain distinguishable from “unavailable” and “available, zero, partial legacy.”

### 14.3 Additive operations

```text
profileCapabilities(server)
getProfileDetailed(server, playerId, community)
getVillagerProfileDetailed(server, playerId, entity)
getVillagerProfileDetailed(server, playerId, villagerId, community)
inspectStoredProfile(server, playerId, community)
matchesProfile(server, playerId, community, query)
matchesSpeakerProfile(server, playerId, speakerContext, query)
deliverProfiled(profiledDelivery)
```

All world-backed calls follow a documented server-thread contract. A convenience getter can return zero for presentation, but authored access conditions must use the detailed result, not an ambiguity-prone zero fallback.

`get...` may reconcile existing state through the canonical gate; `inspect...` is strictly read-only. Neither creates missing player/community entries. A valid new community/player combination can synthesize a neutral read model without saving it. Unresolved or read-only storage is not synthesized as an authoritative stranger.

### 14.4 Feature negotiation

Extend `ReputationCapabilities.features()` additively with identifiers such as:

```text
profile_snapshot_v1
speaker_profile_v1
repeat_credit_v1
profiled_delivery_v1
profile_change_v1
```

Advertise a feature only when its implementation and required payload handling exist. Use `ProfileCapabilities` to report dynamic disabled/migrating/read-only state; do not remove stable support information merely because a feature is temporarily disabled.

Companions should probe the presence of the **existing capabilities entry point once** only when supporting pre-0.5.0 runtimes. After that, use capability strings and runtime readiness rather than guessing from the mod version or repeatedly reflecting every method. Clear cached server-specific readiness at shutdown and world change.

The generic profile API is independent of `enableConversationsIntegration`. That toggle belongs to the Conversations adapter. Turning it off must not disable Quests eligibility, admin inspection, or native Reputation queries. [R02]

### 14.5 Predicate semantics

All ranges are ANDed. Reject empty/invalid resource IDs and inverted bounds. Unknown facet/tier IDs fail closed. A valid but unobserved facet is not negative evidence and does not satisfy a “trustworthy/nonviolent” gate by accident.

Default facet predicates require at least one eligible evidence item. An explicit `allow_unobserved` escape hatch can express “no contrary evidence known,” but must be named and documented as such. Recognition zero can legitimately identify a complete-history stranger. `allow_partial_history` defaults false for gates that rely on upper bounds or absence of adverse evidence; positive observed-evidence gates may opt in explicitly.

A speaker-scoped query never falls back to a community vector when speaker context is missing. It returns an unavailable result so the authored fallback can run.

---

## 15. Publication, revisions, and projections

Add an immutable post-commit `ReputationProfileChangedEvent` carrying player/community, old/new recognition, a bounded changed-facet set, profile revision, cause, optional incident/operation identity, and `quiet` status. A profile-only accepted deed must invalidate profile-dependent consumers even when scalar standing does not change.

Do not repurpose `ReputationChangedEvent` to emit fake nonzero standing changes. Preserve the existing `StandingChange` path. A single operation may legitimately emit its existing incident event plus one standing envelope and one profile event, but not several competing notifications of the same standing change.

Use the current cause/quiet philosophy: ordinary decay, reload presentation changes, and historical enrichment are quiet. No repeated “you became dependable” reward/toast as a value oscillates across a threshold. New dominant descriptors are derived presentation, not titles unless a future authored feature explicitly grants one.

Keep three concepts separate:

| Revision | Changes for |
|---|---|
| Ledger/profile value revision | Accepted evidence, profile value/evidence changes, resolution, supersession. |
| Definition generation | Published datapack interpretation/presentation generation. |
| Story semantic revision | Actual narrative correction such as resolution/supersession, not ordinary time fading. |

Rumor becoming known to a different observer does not mutate the global incident or reaward anything. Consumers should evaluate a fresh speaker view at an interaction boundary. Do not emit one world event for every villager whose deterministic rumor delay elapsed.

A consumer condition must either re-query during evaluation or invalidate against profile revisions and relevant time boundaries. Scalar-standing events alone are insufficient. When values fade without changing rounded display numbers, avoid meaningless event spam; dirty persisted clock/evidence state as necessary but publish only materially changed read-model/evidence semantics.

---

## 16. Companion work packages

These are explicit implementation responsibilities, not an assumption that the current bridges already support new profiles. Core-only functionality must still work without the companions.

### 16.1 MCA: Quests

Modify the always-loadable bridge interfaces and isolated `compat.reputation` backend together. Do not place new Reputation classes in shared fields, method signatures, static initializers, or unconditional event subscribers outside the optional boundary.

**Minimum reliability adoption:** preserve optional delta versus explicit zero through the entire award model; adopt typed delivery and durable linkage for repeatable outcomes; pass the giver's `SpeakerContext`; bind the selected incident ID when a restitution quest is accepted and use `resolveBound` at completion; use the supplied operation identity, not a fresh newest-incident lookup. Carry the ladder to `highWaterTierId(...)`. Keep existing title fallback behavior until a separately verified canonical-title migration is implemented. [R15]

**Profile consumers:** register a new profile condition and use Reputation's authoritative returned values. A suggested authored shape is:

```json
{
  "type": "mcareputation:profile",
  "scope": "giver",
  "recognition": { "min": 15 },
  "facets": {
    "mcareputation:reliability": { "min": 20, "min_evidence": 2 }
  },
  "allow_partial_history": false
}
```

`scope` is `community` or `giver`. The new condition must be registered through Quests' own condition registry and have an authored fallback when Reputation/profile support is absent. Reputation must not inject an unconditional Quests class into its own runtime.

**Outcome semantics:** quest completion is one social outcome even if items arrived through several deposits, the Deliver button, native MCA Gift, final turn-in, or the legacy interaction path. Reputation must not intercept inventory movement or create gifts-as-reputation farming. Use the quest-instance terminal outcome identity already available to the delivery/completion owner. Projects require per-participant credit and an explicit choice of phase versus terminal significance; do not award the full project profile at every phase and again at completion.

**Shipped vertical slice:** provide a basic commission available to a stranger and an important follow-up commission requiring observed reliability. They can be small deterministic test/example datapacks, but at least one production-authored opportunity must exercise the condition. Do not gate every ordinary job behind reputation, which would prevent starting progression.

No-profile/older-Reputation installations preserve existing quest functionality. An unavailable social reward must not consume extra inventory, duplicate XP, or reset the accepted objective.

### 16.2 MCA: Conversations

Adopt capability negotiation and detailed opinion/profile availability in the isolated adapter. Keep the older branch as a neutral fallback where required.

**Profile conditions and context:** register `conversations_reputation_profile` with `community` and `speaker` scope and the same predicate semantics as Quests. Add bounded template values for recognition tier and dominant known traits. Template interpolation may select only server-authorized facts, not arbitrary map contents or secret incident context.

```json
{
  "conversations_reputation_profile": {
    "scope": "speaker",
    "recognition": { "min": 15 },
    "facets": {
      "mcareputation:bravery": { "min": 10, "min_evidence": 1 }
    }
  }
}
```

**Bounded opinion contribution:** replace the previous public/opinion term with the canonical resolved term where available. Never add both. Preserve the check system's existing overall public bias limit and the independent private relationship axes.

**Existing amends repair:** bind the apology to an exact eligible known incident. The reward identity must prevent the same player's same apology stage for the same incident from paying again across residents, reopened screens, or reconnects. A second unrelated incident must remain independently addressable. Route witnessed actions through actual validated witness/context acquisition; do not silently label a witness-free private interaction “public.” These repair the current signal path without adding a generalized obligation system. [R17]

**Gossip:** adopt `gossipStory`/semantic revisions for corrections and acknowledgment. Do not tell a resolved or superseded story in its old accusatory form. Retelling or learning does not produce new civic evidence.

**Shipped content slice:** include short neutral-stranger, recognized-stranger, reliable, brave, notorious, and mixed-reputation variants, plus a restrained unavailable fallback. Recognition may change acknowledgment; friendliness still requires existing familiarity/hearts/disposition rules. Update all maintained locales and generated-content fixtures; do not replace the 1.7.0 dialogue/session architecture.

### 16.3 MCA: Crime

Retain one producer per core incident kind. Use `declaredKinds()` and actual `canDeliver(kind)` behavior, with proper startup/shutdown release. Do not declare ownership of rescues/cures/raids merely because the bridge is generally available.

Replace the write-capable incident probe with read-only receipts/lookup. Preserve typed outcomes through the pump/outbox, including accepted-no-public results. Queue resolution work that arrives before the public incident link instead of dropping it or resolving an unrelated case. Do not build a second retry queue beside an existing one without a migration reason. [R16]

Use the unified profile-aware supersession path for assault-to-killing parity. Reject NPC offenders from the player civic-profile producer unless an explicit player attribution exists. Preserve witness confidence, offender attribution, and private crime handling from Crime; Reputation does not rediscover suspects.

Update the actual mapped `mcacrime` incident data or attach validated companion profile mappings for `guard_assaulted`, `jailbreak`, `kidnapping`, `theft`, `mugging_murder`, and `captive_rescued`. Reuse `mcareputation:villager_assaulted` and `mcareputation:villager_killed`; do not mint duplicates. [R18]

Do not translate `REFUSED_CAPACITY` into “no crime occurred.” Crime's legal case remains authoritative. Temporary social delivery failure must not undo Heat, custody, inventory recovery, or legal settlement. A profile-only change must not pay a bounty again.

Fine payment and serving a sentence may alter the original civic evaluative state through the existing policy. They never create automatic personal forgiveness, erase a historical violence facet immediately, or manufacture standalone generosity rewards.

### 16.4 Acceptance when only one checkout is available

The primary agent may complete Reputation core without companion write access. In that case, deliver exact companion patch requirements, capability contracts, and executable consumer fixtures, and mark the release **core complete / suite adoption pending**. Documentation alone is not suite integration. Do not describe all four mods as verified until the companion implementations and combination tests actually run.

---

## 17. Initial shipped tuning and anti-exploit content rules

The following are proposed starting values, not claims about existing data. New profile data must not silently retune existing scalar standing values except an explicitly enabled future positive repeat-credit policy.

| Event | Recognition | Suggested facets | Credit treatment |
|---|---:|---|---|
| Villager rescued | 6 | Bravery +8, compassion +5 | Bounded rescue group plus beneficiary ceiling. |
| Villager cured | 10 | Compassion +10 | Preserve cure identity; limit repeated cycling of the same beneficiary. |
| Raid repelled | 14 | Bravery +12, reliability +4 | Existing raid identity plus a conservative repeat group. |
| Villager assaulted | 4 | Violence +8, compassion -4; lawfulness only with valid unlawful context | No reduced accountability. |
| Villager killed | 18 | Violence +20, compassion -12; lawfulness from valid context | Supersedes precursor once. |
| Explicit reliable commission completed | 3 | Reliability +4 | Group by authored work class, not all quests globally. |
| Authored broken commitment | 3 | Reliability -6 | Only if an actual negative outcome was authored. |
| Meaningful donation project | 5 | Generosity +6 | Only designated authored contribution; not routine gifting. |
| Explicit sparing outcome | 4 | Mercy +6 | Requires a real authored outcome, not failure to attack. |
| Existing public apology / own fine / own sentence | 0 new virtue | Repair linked original evaluative evidence | Bound operation, no independent virtue farming. |
| Captive rescued | 8 | Bravery +8, compassion +8 | Crime-approved rescue identity and beneficiary context. |

Do not infer bravery from killing any entity, greed from theft without the intended context, reliability loss from every technical quest failure, lawfulness from generic PvP, or mercy from an absence of action. Existing optional PvP policy remains optional. Self-defense and accidental harm must preserve authoritative event context rather than receiving blindly duplicated criminal judgment.

Default profile lifetimes should be finite, modest, and documented. Begin with roughly 28 in-game days for ordinary facet evidence and 56 for recognition, using stable whole-day decay steps. Major content can author longer lifetimes within hard bounds. Treat these as testable balance defaults; do not assert they are proven balanced before playtesting.

To prevent a generic authored event from bypassing a specific policy, companion outcome profiles must explicitly map to the intended stable credit group. A repeated quest cannot change its display title or random instance ID to obtain a new group.

---

## 18. Standing screen, networking, and client state

### 18.1 Preserve the 0.5.0 interface improvements

Keep paged communities, true total counts, visibility labels, base-versus-current deed values, superseded indicators, throttled requests, and clearing stale speaker opinion on community change. These already exist and must not be dropped by a redesign. [R02] [R11]

The default presentation is concise:

```text
Riverwood
Honored  •  Well known
Known for: Dependable · Brave

Alice's public view: Respectful
Knows your recent rescue; values bravery.

Deeds
Rescued Miriam
Publicly witnessed • ordinary social credit
```

The example is illustrative wording, not a requirement to add a new opinion tier named “Respectful.” Use actual authored tier labels in production.

Exact recognition/facet numbers belong in details/tooltips or an explicit numerical-display setting. Keep neutral visual styling, text signs, keyboard navigation, narration, long-name wrapping, and a usable deed list at small GUI scales. Do not communicate positive/negative meaning by color alone.

Add one compact profile-details expansion or pane rather than a full dossier redesign. Give recognition and the top traits a few lines; move secondary explanations into scrollable content before they crowd out the existing screen.

### 18.2 Honest explanation states

Render genuine unknown, temporarily unavailable, no selected villager, partial legacy history, and read-only data distinctly. Incomplete imported history should read like “Recognition history is incomplete,” not “Nobody knows you.” A profile query error must not show old data from a previous villager/community.

For accepted repeat-limited deeds, show the requested versus credited social value and the policy reason in details. Do not imply the quest failed or the items disappeared because its social reward was reduced. Avoid an action-bar notice for every tiny facet change.

### 18.3 Protocol 5 payload

For the reviewed Forge branch, move **4 → 5**. Add a bounded profile summary to the selected detail and a bounded optional observer summary. Server-calculated tier/trait components travel over the wire; the client must not need the server's arbitrary datapack registry to reconstruct authority. [R11] [F02]

Transmit at most three dominant traits by default and at most eight detailed entries in one pane/page. Use per-field limits and a deliberate serialized byte budget, for example 16 KiB for the new profile subpayload. Validate list lengths **before allocating** on decode, bound component complexity and ID/string lengths, and check negative/oversized page values. Truncation at encoding is not sufficient defensive decoding.

Include a request/generation identity and selected community/observer identity so late replies cannot apply the wrong profile after a user switches selection. Preserve current throttling and add coalesced refresh only while the relevant screen is open. Do not poll every player or villager continuously.

Server handlers validate the sender, world, selected community authorization, observer existence, dimension and interaction distance. Do not force-load chunks to answer an entity request. Physical-client handlers remain isolated from dedicated-server classes, and world access uses the logical main thread. [F02]


---

## 19. Save format 3 and migration from 0.5.0

### 19.1 Structural migration

Increment Forge `ReputationSavedData.FORMAT_VERSION` **2 → 3**. Continue using the current world-global data file and existing player/community/incident identities. Add profile payloads, bounded credit state, and coverage/version markers; do not rename the file or create parallel stores.

The structural migration is deterministic and independent of live world entities. Preserve scalar baselines, stored contributions, incident IDs, source/operation keys, witnesses, subjects, supersession links, receipt floors, titles, high-water state, metadata and decay immunity. Existing 1→2 migration still runs first when needed. A migrated save written again must not repeat enrichment or grant new titles/rewards.

Older incidents initially receive a `LEGACY_UNENRICHED` marker rather than reading a missing field as a newly configured full profile. New incidents intentionally created while profiles were disabled get a different `DISABLED_AT_OCCURRENCE` marker; they must not be mistaken for upgrade candidates later.

### 19.2 Conservative historical enrichment

Provide a versioned, fixed migration manifest for **retained, unambiguously typed facts**, not a reconstruction from the current standing number. A baseline of +300 does not prove bravery, compassion or recognition. Titles are not a second profile award either.

Once required definitions are ready, enrich eligible retained built-in public events using a frozen migration mapping. Use their canonical effective age and current status/supersession. Mark those contributions `LEGACY_ENRICHED` with historical credit 100% because the old system did not store repeat-credit decisions. Do not retroactively shrink scalar standing or fabricate how much credit a player would have received under a policy that did not exist.

Do not infer legal culpability for old assault/killing records lacking authoritative context. Their factual recognition/violence may be enrichable, while lawfulness or an evaluative moral label may not be. Unrecognized/custom/ambiguous types remain explicitly unenriched. Provide a dry-run report; custom backfill requires an explicit authored mapping and administrative action, not every `/reload` silently rewriting history.

Repeat trackers start without fabricated pre-upgrade counters. Existing dedupe/receipts still prevent replaying old accepted outcomes for new credit. Explain that anti-grind accounting applies prospectively from the upgrade.

### 19.3 Bounded initialization and coverage

Do not force-load villagers or chunks for migration. If the in-memory save has many players, run one resumable, budgeted migration pass over saved records, not a recurring world scan. Persist a cursor/progress marker, block affected profile mutations or report `MIGRATING` until each relevant record is safe, and test interruption/resumption.

A partially enriched profile carries `PARTIAL_LEGACY` coverage. Positive known evidence may still be displayed, but absence-based gates must respect the coverage rule. Enrichment is quiet and does not award scalar standing, hearts, quest items, duplicate titles or bounty money.

### 19.4 Corruption and downgrade behavior

Bound new maps/lists before deserializing them into active state. Preserve/quarantine malformed profile payloads without discarding an otherwise valid scalar incident. A quarantined contribution makes coverage incomplete; it is not proof of zero adverse evidence. Persist quarantine metadata with hard bounds and actionable diagnostics.

Keep the future-format raw-preservation behavior. For a version newer than 3, profile APIs report read-only/unavailable and touched write paths refuse before any ephemeral mutation. Test that no canonical state, receipt, timer or pending reward claims success while persistence is unavailable. Returning an unchanged raw tag is not sufficient if gameplay simultaneously receives fabricated volatile success. [R10]

Document that downgrade requires a compatible backup for fully functional older-version play. Do not promise that 0.5.0 will understand profile format 3.

---

## 20. Configuration and exact disabled behavior

Keep tuning in datapacks, not dozens of TOML sliders. Suggested new settings:

| Setting | Default | Behavior |
|---|---|---|
| `enableProfiles` | true | Enables new evidence creation and profile queries; false retains existing payloads, freezes their profile-aging clock and reports profiles disabled. Scalar standing continues under existing rules. |
| `enableRepeatCredit` | true | Enables reward scaling. When false, future qualified awards use 100%, but accepted operations still advance bounded accounting for the current window; historical decisions never change. |
| `enableFacetOpinion` | true | Enables the bounded facet adjustment, not direct relationship writes. |
| `maxFacetOpinionAdjustment` | 25 | Range 0..100; clamps the combined facet interpretation. |
| `showRecognition` | true | Client presentation only. |
| `showKnownFor` | true | Client presentation only. |
| `showObserverProfile` | true | Client presentation only. |
| `showExactProfileValues` | false | Optional numerical details; separate from existing scalar-score visibility. |

Repeat accounting is conceptually separate from profile display. Disabling profiles must not silently delete a repeat tracker or restore unlimited positive standing. A lightweight accepted-credit decision can still be stored with the incident even when its profile payload is intentionally disabled. Disabling repeat credit is an explicit operator bypass, not a historical rewrite.

Existing master enablement stops new Reputation mutations. Existing global decay disablement and community immunity apply through the canonical gate to profile aging too. Master/profile/decay transitions require precise no-catch-up behavior; active credit windows follow their separate monotonic world-time contract and are not turned into extra allowances by cosmetic settings.

Integration toggles affect only the relevant adapter. Generic profile queries, native incident handling, Quests, and commands must not accidentally depend on Conversations being enabled.

Update safe-before-config-load accessors and policy snapshots; do not reach directly into live config fields from pure arithmetic. Configuration can tighten bounds but must not discard live state when limits are lowered. Existing above-new-limit records remain preserved, with conservative future admission and clear diagnostics.

---

## 21. Diagnostics, API artifact, and documentation

### 21.1 Diagnostics

Add permission-appropriate inspection commands, following the existing command conventions rather than inventing parallel permissions:

```text
/mcareputation debug profile <player> <community>
/mcareputation debug credit <player> <community>
/mcareputation debug profileincident <player> <community> <incident>
/mcareputation debug profilemigration
```

The profile inspection should show availability, coverage, revisions, raw/clamped recognition/facets, evidence counts, selected rule versions and any zero-credit/capacity reason. Credit inspection should show stable group/window identities, saturated ordinal, next nominal allowance and overflow state. Incident inspection explains authored, credited, settled and current profile units plus supersession.

Retain the existing receipt, authority, quarantine, and supersede diagnostics. Export may include full bounded technical detail for an administrator, but ordinary UI packets should not leak raw UUID sets, secret case context or other players' private history. Use `INSPECT` for diagnostics that promise no mutation.

### 21.2 API artifact completeness

Update `apiJar` and `verifyApiJar` deliberately. The current check verifies configured exports and resource absence; it is not proof that every public signature dependency is available to an isolated consumer. [R13]

Add an external-style compile fixture that depends only on the produced API jar plus the ordinary Forge/Minecraft compile environment. Exercise old API entry points and the new profile models. Export the minimal transitive signature closure or redesign a new signature to avoid internal types; never solve a missing class by bundling the whole runtime implementation into the API artifact.

Run a binary-linkage fixture compiled against the 0.5.0 API and loaded against the new implementation. Ensure new consumers gracefully avoid new classes/methods on an older provider. Check the runtime jar contains no shaded Quests, Conversations, Crime, MCA or Architectury classes.

### 21.3 Documentation files

Update `README.md`, `CHANGELOG.md`, `API.md`, `DATAPACK.md`, `CONFIG.md`, `MIGRATION.md`, `PRODUCTION_TESTS.md`, and relevant implementation/store descriptions. Add a feature-specific design/verification document with exact commands, artifact hashes, tests run, skipped cases and unresolved companion adoption.

Remove or correct touched comments describing live-fold pruning, forever-retained receipts, already-shipped 0.4.1 APIs, or a protocol/save version that is no longer current. Do not mechanically rewrite historical changelog entries.

---

## 22. Implementation sequence and file-level responsibilities

Treat this as a dependency-ordered delivery plan. The named new classes are suggested locations, not existing code.

| Work package | Main files/areas | Exit gate |
|---|---|---|
| P0 — Pin and characterize | Repository instructions, `gradle.properties`, current tests, reviewed companion adapters | Baseline versions/commits logged; existing failures distinguished from new work. |
| P1 — Reliable acceptance seam | `ReputationService`, receipt handling, `ReputationPolicy`, read-only guard, supersession | Re-entrant replay, admission-after-aging, full rollback, read-only and typed-outcome tests. No feature payload yet. |
| P2 — Pure schema/math | New `profile` and `credit` definitions/resolvers; `IncidentDefinition`; `StrictCodecs` usage | Strict codec tests, fixed-point math, stable ordering, valid shipped definitions. |
| P3 — Snapshot persistence | `IncidentRecord`, `CommunityReputationRecord`, `PlayerReputationRecord`, `ReputationSavedData` | v2→v3 round trips, frozen payloads, tracker capacity, resumable enrichment. |
| P4 — Reconciliation/retention | `ReconciliationService`, incident lifecycle, both cap paths, policy transitions | Profile-only fading, protected evidence, no catch-up, no LRU credit reset. |
| P5 — API/capabilities | `McaReputationApi`, new API profile DTOs, feature strings, publication | Detailed availability, predicate semantics, old binary fixture, API-jar closure. |
| P6 — Observer interpretation | Existing `OpinionResolver` plus new pure observer resolver, `McaCompat` seams | Knowledge-first aggregation, matching entity/UUID views, bounded combined bias, no relationship writes. |
| P7 — Client/network | `ReputationNetwork`, client cache/handler/screen and pagination tests | Protocol 5, bounded decode, stale-reply rejection, compact accessible profile display. |
| P8 — Consumer adoption | Quests backend/conditions; Conversations bridge/content; Crime adapter/pump/mappings | A playable cross-mod vertical slice and explicit version/capability fallbacks. |
| P9 — Certification | Build scripts, docs, production fixture worlds and matrix | Reproducible test evidence and truthful core-versus-suite release status. |

Suggested new internal areas:

```text
profile/
  IncidentProfileDefinition
  IncidentProfileEvidence
  FacetDefinition
  RecognitionTierSet
  ProfileRegistryBundle
  ProfileMath
  SocialProfileService
  VillagerProfileResolver

credit/
  CreditPolicy
  CreditWindowState
  CreditDecision
  CreditReservation
  CreditResolver

api/profile/
  ProfileSnapshot
  FacetValue
  RecognitionValue
  VillagerProfileSnapshot
  ProfileQueryResult
  ProfileAvailability
  ProfileCoverage
  ProfileQuery
  ProfileCapabilities
  ProfiledDelivery
```

Use existing source conventions and test seams. Do not create a giant `SocialManager` containing persistence, networking, behavior, arithmetic and UI.

### Practical build sequence

With a Java 17 Gradle environment and the repository's actual available tasks:

```bash
./gradlew test
./gradlew build
./gradlew apiJar verifyApiJar checkJarContents
```

Record all baseline failures before changing code. Build companion consumers against the produced artifact, not an accidentally available neighboring class directory. The exact companion artifact-path flag must come from that checkout's build file; do not invent one in a script.

Runtime validation uses built reobfuscated jars in the supported production-style MCA setup. Follow the repository's existing warning that development runs alone do not establish actual MCA compatibility. [R14]

---

## 23. Required automated tests and concrete fixtures

Implement these tests with the real service context/NBT paths wherever possible. Pure calculators alone cannot establish transaction correctness.

### 23.1 Numerical and semantic fixtures

| Fixture | Setup | Required result |
|---|---|---|
| Recognized neutral | Public standing +20 and -20; each has recognition 10, same evaluation age zero | Standing 0, recognition 20, not an unknown person. |
| Mixed evidence | Reliability +12 and -12 from two distinct credited deeds | Value 0, observed with one supporting and one opposing item; not “no evidence.” |
| No evidence | Valid new player/community, no saved record | Available zero/complete read model; no new persistent entries. |
| Private harmful event | Private event contains an authored public profile reference | Community recognition/facets 0; no public allowance consumed. |
| Group credit | Schedule 100%,100%,50%,25%,0%; base scalar/profile points 8; no subject rule | Credited points 8,8,4,2,0; repeated fifth outcome is still a deed but supplies no positive evidence. |
| Subject ceiling | Same subject, group 100%,100%,50%; subject 100%,50%,0% | Effective 100%,50%,0%; group is not bypassed. |
| Fresh-subject rotation | Exhaust group allowance, then help a new subject | New subject cannot restore group allowance. |
| Native duplicate | Replay identical operation before and after save/load | Same incident/receipt, no second evidence count, no extra credit consumption. |
| Adverse repeat | Two distinct negative events, scalar -8 each | -8 and -8; no reward policy reduces the second. |
| Fraction preservation | Two credited contributions of 0.5 point each | Aggregate 1 point; do not truncate each one to zero before summing. |
| Linear profile age | Authored 8, lifetime 28 days, effective age 14 days, no resolution | Current 4, independently of the scalar standing decay policy. |
| Supersession | Assault violence +8 then valid public killing profile +20 | Total violence 20; predecessor contribution stays zero across later resolution/reload. |
| Private successor | Public assault followed by a private successor | Existing public evidence is not refunded or erased. |
| Disproof | One recognized violent deed becomes disproven | Its new profile contributions become zero; no automatic virtue award. |
| Facet-only event | Existing standing is clamped; a new eligible deed adds a facet | Profile changes and consumers refresh even with no scalar delta. |
| Empty facet predicate | Unknown/unobserved violence with a max-zero gate | Default gate fails; only an explicitly named allow-unobserved policy changes that. |

Use exact subunit assertions for arithmetic and separate assertions for UI rounding. Numeric examples assume definitions that actually authorize those contributions.

### 23.2 Transaction and failure injection

Inject a failure before each commit boundary: profile selection, counter reservation, capacity selection, precursor staging, receipt staging and external publication. Assert no partial change before commit and stable committed state after listener failure.

Inside a post-commit listener, replay the same operation and query receipt/profile state. It must observe one coherent outcome. Attempt to re-use an operation key with a conflicting profile payload. Verify a typed conflict and no extra award.

Test dropped unwitnessed delivery when the community is full: it should not need an incident slot. Test a genuinely public profile-only event at capacity: it must obey normal admission. Test same source event delivered through plain `record`, `deliver` and profile wrappers: all normalize to one identity where the caller is referring to the same operation.

### 23.3 Clock, pause and delayed delivery

Test no-reads-between-disable-and-reenable intervals, reload while paused, shutdown while paused, community immunity, master disable, profile disable, and a delayed event arriving during those modes. Distinguish occurrence age from acceptance-window counting.

Test the actual `/time set` command in runtime and a synthetic backward **game-clock** step in unit fixtures; do not assume they exercise the same clock. A restart preserves tracker watermarks, frozen coefficients and accepted decisions. Deliver out-of-order unaccepted events in both orders and assert the documented acceptance-order semantics, not an unattainable order-independent award history.

### 23.4 Capacity, retention and registry abuse

Fill the ledger with pinned/live scalar/live profile evidence. Admission must refuse without losing state. A display-clamped facet and a subunit contribution still protect their evidence. Advance effective time until eligible profile-only evidence expires and prove admission can recover without an unrelated API call.

Fill all group and subject tracker slots, then rotate identities and reload definitions. No live-state eviction may restore full credit. Expire a window and verify safe cleanup. Lower configured caps below current usage and prove the loader preserves existing valid live data while restricting new growth.

Remove a profile/facet definition and re-add it. Stored coefficients persist; current labels/interpretation update only as documented. Reject a malformed optional profile without disabling an otherwise working scalar crime definition in lenient mode. Strict mode keeps the previous complete generation.

### 23.5 Migration and damaged saves

Use real v2 fixtures containing: positive/negative baselines, retained receipts, old pruned history, superseded incidents, private deeds, non-default dimensions, titles, disabled decay, immune communities and malformed profile candidates.

Assert all scalar values and identities are unchanged by structural migration at the same evaluation point. Run enrichment twice and interrupt/restart it mid-pass. Never recreate rewards. Verify partial-coverage gates and unknown custom records. Load a future-format fixture and assert raw preservation, no new effective mutations and explicit unavailability.

### 23.6 Observer and integration behavior

Create a subject, witness, uninformed resident, resident after rumor delay, and former resident. Test each against the same canonical ledger. Unloaded role/personality uses a declared fallback; it cannot import public facts not known to that observer. Entity and UUID APIs for a loaded villager agree.

Keep a high-standing player at low private familiarity. Profile-aware greeting must acknowledge reputation without friendship language. Combined public check bias remains bounded at ±8. Turning off Conversations integration cannot break Quests/generic profile conditions.

Quests tests cover omitted versus zero delta, one completion across gift/manual/partial delivery routes, incident-bound restitution across relog, per-participant projects, and a profile-only eligibility change. Crime tests cover read-only recovery, accepted-private acknowledgment, pending resolution before linkage, authority handoff, NPC-offender filtering and supersession parity. Conversations tests cover one bound apology reward across speakers, independent apologies for different incidents, and status-aware gossip revision.

### 23.7 Network, ABI and performance

Use malicious lengths, components, unknown IDs, negative pages, huge pages and out-of-order response generations. Decode must reject unsafe allocations. A packet cannot obtain a remote villager or another player's profile by supplying an arbitrary ID.

Compile and link consumers against old and new API jars. Launch dedicated-server classloading fixtures without companions. Check runtime and API jars for forbidden bundled classes/resources.

Benchmark one populated community and all-communities pagination with maximum supported new payloads. Record time, allocations, NBT size and packet bytes rather than asserting an invented performance target. No per-tick scan of all saved players, all villages, or all entities is allowed. One-time bounded migration over saved records is explicitly different from an ongoing scan.

---

## 24. Production verification and release gates

### 24.1 Combination matrix

With MCA as the base dependency, test all 16 installed/absent combinations of Reputation, Quests, Conversations and Crime at least for startup/classloading and basic fallback. Run the full feature flow for Reputation alone, each relevant pair, the four-mod suite, and the current supported MCA package-root variants. Dedicated-server and integrated-server results must be distinguished.

Old companion binaries with new Reputation should retain compatible baseline behavior without claiming new profile support. New companion binaries with old 0.5.0 Reputation should fall back by capability, not crash. Absent Reputation must not disable ordinary quest completion, conversation closure, or Crime enforcement.

### 24.2 In-world acceptance story

Start with two players and two villages, including a dimension-identity fixture. One player begins genuinely unknown, completes a basic commission, earns observed reliability and receives the follow-up opportunity. Another player performs opposing public deeds, ending with low standing but nonzero recognition. An uninformed villager remains neutral until awareness permits acknowledgment.

Then exercise a witnessed assault, a valid killing upgrade, an apology and a linked settlement. The UI, the quest condition, and the speaker view agree on their respective scopes. Crime's legal state does not become a facet or vanish because the public evaluation improves. Reopen screens, reconnect, restart, and repeat delivered operation keys. No additional item reward, social credit, title or bounty is granted.

Test long names, narrow screens, large GUI scales, keyboard-only navigation, profile-disabled mode and partial legacy data. Preserve the community pagination and stale-opinion cleanup added in 0.5.0.

### 24.3 Definition of done

**Core complete** requires working Recognition and Facets, frozen incident evidence, bounded repeat accounting, canonical publication, migration, protected retention, observer queries, usable UI, protocol 5, save format 3, ABI-preserving API changes, and passing documented automated/build gates.

**Suite complete** additionally requires the actual Quests/Conversations/Crime adoption work, working authored consumers and the combination/runtime matrix. An API example or a pending companion document does not satisfy it.

**Release certified** additionally requires exact artifact filenames and SHA-256 hashes, commands/environment, tests and skipped tests, production results and unresolved risks. Do not copy old test counts into the release report or label compilation as in-world certification.

### Final design rule

> Record the deed once. Preserve who can know it. Derive the public interpretation from that evidence. Let each companion own the reaction.

The value of this phase is not seven more progress bars. It is a reliable, explainable shared answer to what the player has actually become known for, without erasing the distinction between reputation, familiarity, forgiveness and law.

---

## Appendix A. Source index and review provenance

All repository links below are pinned to the reviewed commits. They identify the implementation that informed this plan; proposed types, numeric tuning and future API names in the body are design decisions, not claims that those features already ship.

| Reference | Reviewed source and relevance |
|---|---|
| [R01] | Reputation build/version/MCA baseline in `gradle.properties`. |
| [R02] | 0.5.0 changelog, shipped reliability work and explicitly pending companion adoption. |
| [R03] | Current capability snapshot and feature identifiers. |
| [R04] | Current public API, detailed opinion availability, speaker and bound-resolution contracts. |
| [R05] | Actual delivery, receipt, admission, commit, publication and supersession implementation. |
| [R06] | Policy-aware reconciliation, freeze semantics and intents. |
| [R07] | Community scalar invariant, live-evidence eviction protection and admission. |
| [R08] | Incident reconciliation, supersession terminality, rollback and story revisions. |
| [R09] | Current incident-definition schema; no Recognition/Facet/Credit schema yet. |
| [R10] | Save format 2 and future-format/read-only storage behavior. |
| [R11] | Protocol 4, server-validated selection, request paging and networking. |
| [R12] | Prepared reload and cross-registry validation architecture. |
| [R13] | API artifact exports and verification/build tasks. |
| [R14] | Production verification checklist and runtime certification limits. |
| [R15] | Quests' canonical Reputation backend, omitted-delta and selector adoption work. |
| [R16] | Crime's current Reputation adapter and write-capable lookup probe. |
| [R17] | Conversations' current Reputation adapter, signals and gossip path. |
| [R18] | Crime-to-Reputation canonical incident and resolution mappings. |
| [R19] | Existing ABI-preserving `IncidentDelivery` wrapper. |
| [R20] | Typed receipt outcomes; compare bounded retention with overbroad comments. |
| [R21] | Reputation shipping commit metadata. |
| [R22] | Quests 1.6.5 commit metadata and native Gift/item-delivery work. |
| [R23] | Conversations 1.7.0 commit metadata and existing presentation/session work. |
| [R24] | Crime 0.6.4 commit metadata. |
| [F01] | Official Forge 1.20.1 SavedData lifecycle and Overworld attachment. |
| [F02] | Official Forge 1.20.1 SimpleImpl thread, handshake and defensive-packet guidance. |

[R01]: https://github.com/otectus/MCAReputation/blob/d1f5decdaf9104ddee7898f115864569196b4b29/gradle.properties
[R02]: https://github.com/otectus/MCAReputation/blob/d1f5decdaf9104ddee7898f115864569196b4b29/CHANGELOG.md
[R03]: https://github.com/otectus/MCAReputation/blob/d1f5decdaf9104ddee7898f115864569196b4b29/src/main/java/dev/otectus/mcareputation/api/ReputationCapabilities.java
[R04]: https://github.com/otectus/MCAReputation/blob/d1f5decdaf9104ddee7898f115864569196b4b29/API.md
[R05]: https://github.com/otectus/MCAReputation/blob/d1f5decdaf9104ddee7898f115864569196b4b29/src/main/java/dev/otectus/mcareputation/reputation/ReputationService.java
[R06]: https://github.com/otectus/MCAReputation/blob/d1f5decdaf9104ddee7898f115864569196b4b29/src/main/java/dev/otectus/mcareputation/reputation/ReconciliationService.java
[R07]: https://github.com/otectus/MCAReputation/blob/d1f5decdaf9104ddee7898f115864569196b4b29/src/main/java/dev/otectus/mcareputation/state/CommunityReputationRecord.java
[R08]: https://github.com/otectus/MCAReputation/blob/d1f5decdaf9104ddee7898f115864569196b4b29/src/main/java/dev/otectus/mcareputation/incident/IncidentRecord.java
[R09]: https://github.com/otectus/MCAReputation/blob/d1f5decdaf9104ddee7898f115864569196b4b29/src/main/java/dev/otectus/mcareputation/incident/IncidentDefinition.java
[R10]: https://github.com/otectus/MCAReputation/blob/d1f5decdaf9104ddee7898f115864569196b4b29/src/main/java/dev/otectus/mcareputation/state/ReputationSavedData.java
[R11]: https://github.com/otectus/MCAReputation/blob/d1f5decdaf9104ddee7898f115864569196b4b29/src/main/java/dev/otectus/mcareputation/network/ReputationNetwork.java
[R12]: https://github.com/otectus/MCAReputation/blob/d1f5decdaf9104ddee7898f115864569196b4b29/src/main/java/dev/otectus/mcareputation/data/ReputationReloadListener.java
[R13]: https://github.com/otectus/MCAReputation/blob/d1f5decdaf9104ddee7898f115864569196b4b29/build.gradle
[R14]: https://github.com/otectus/MCAReputation/blob/d1f5decdaf9104ddee7898f115864569196b4b29/PRODUCTION_TESTS.md
[R15]: https://github.com/otectus/MCAQuests/blob/5348af1865d94dd279a19e539aba94b9684c3964/src/main/java/dev/otectus/mcaquests/compat/reputation/CanonicalReputationBackend.java
[R16]: https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/compat/reputation/CrimeReputationCompat.java
[R17]: https://github.com/otectus/MCAConversations/blob/ef9259356a7bc3e4f4abe1c63cad15fd88015154/src/main/java/dev/otectus/mcaconversations/compat/reputation/ConversationsReputationCompat.java
[R18]: https://github.com/otectus/MCACrime/blob/fdb602428c101c2364a0e7bb0cc5cba57aeedce1/src/main/java/dev/otectus/mcacrime/compat/CrimeIncidentMapping.java
[R19]: https://github.com/otectus/MCAReputation/blob/d1f5decdaf9104ddee7898f115864569196b4b29/src/main/java/dev/otectus/mcareputation/api/IncidentDelivery.java
[R20]: https://github.com/otectus/MCAReputation/blob/d1f5decdaf9104ddee7898f115864569196b4b29/src/main/java/dev/otectus/mcareputation/api/ReceiptOutcome.java
[R21]: https://github.com/otectus/MCAReputation/commit/d1f5decdaf9104ddee7898f115864569196b4b29
[R22]: https://github.com/otectus/MCAQuests/commit/5348af1865d94dd279a19e539aba94b9684c3964
[R23]: https://github.com/otectus/MCAConversations/commit/ef9259356a7bc3e4f4abe1c63cad15fd88015154
[R24]: https://github.com/otectus/MCACrime/commit/fdb602428c101c2364a0e7bb0cc5cba57aeedce1
[F01]: https://docs.minecraftforge.net/en/1.20.1/datastorage/saveddata/
[F02]: https://docs.minecraftforge.net/en/1.20.1/networking/simpleimpl/
