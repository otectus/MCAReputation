# MCA: Reputation — datapack reference

Seven kinds of definition, all reloaded by `/reload`:

```
data/<namespace>/mcareputation/incidents/**/*.json           what a deed is and what it is worth
data/<namespace>/mcareputation/reputation_tiers/**/*.json     the named bands standing falls into
data/<namespace>/mcareputation/titles/**/*.json               earned badges
data/<namespace>/mcareputation/facets/**/*.json               what a player can be known for
data/<namespace>/mcareputation/recognition_tiers/**/*.json    the named bands recognition falls into
data/<namespace>/mcareputation/incident_profiles/**/*.json    what a kind of deed is socially worth
data/<namespace>/mcareputation/credit_policies/**/*.json      how repeated services are discounted
```

The last four are the profile schema added in 0.6.0; see *Public profiles* below.

For backwards compatibility, tier and title definitions are **also** read from
`data/<namespace>/mcaquests/reputation_tiers/` and `data/<namespace>/mcaquests/titles/`, so a pack
written for MCA: Quests keeps working. Where both paths define the same id the `mcareputation` one wins
and one warning names both files.

A definition's id is its namespace plus its path below the directory:
`data/mypack/mcareputation/incidents/crime/arson.json` → `mypack:crime/arson`.

**Nothing here can crash a reload.** An invalid definition is reported and skipped; the previously
loaded definitions stay live. `strictJsonValidation=true` turns any problem into a refused swap rather
than a partial one. `/mcareputation validate` reports everything at once, naming the exact id and field.

---

## Incidents

```json
{
  "display": { "translate": "mypack.incident.arson" },
  "default_delta": -30,
  "visibility": "witnessed",
  "severity": "severe",
  "tags": ["crime", "fire"],
  "retention_ticks": 336000,
  "decay": { "type": "linear_to_zero", "delay_ticks": 48000, "amount_per_day": 2 },
  "resolution": { "apologized": 0.9, "atoned": 0.4, "forgiven": 0.0, "disproven": 0.0 },
  "gossip": { "tone": "condemnation", "phrase": "mypack.gossip.arson", "with": ["player", "subject"] },
  "pinned": true,
  "retain_unwitnessed": true,
  "max_override_abs": 40
}
```

| Field | Type | Required | Meaning |
|---|---|:---:|---|
| `display` | text component or string | ✔ | The line shown in the deeds list. See *Display templates* below. |
| `default_delta` | int | ✔ | What this deed is normally worth. Negative harms standing. |
| `visibility` | enum | ✔ | `private`, `witnessed`, `village`, or `global`. |
| `severity` | enum | ✔ | `trivial`, `minor`, `moderate`, `major`, `severe`. Affects presentation and pruning order — **never** the score. |
| `tags` | string list | | Up to 16, lower-cased. Used by conditions and selectors. |
| `retention_ticks` | long | | How long a zero-contribution record is kept. Omit to keep it indefinitely. |
| `decay` | object | | `{"type":"none"}` or `linear_to_zero`. See below. |
| `resolution` | object | | Contribution multipliers per resolution, `0.0`–`1.0`. |
| `gossip` | object | | Tone, phrase key, and which variables to bind. Without a `phrase` the deed is never tellable. |
| `pinned` | bool | | Never pruned. Use for things the world should not forget. |
| `retain_unwitnessed` | bool | | Keep an unwitnessed instance as hidden, zero-contribution history instead of discarding it. |
| `max_override_abs` | int | | Ceiling on a caller-supplied delta. Default `100`. |
| `allow_private_score` | bool | | **Development only.** No shipped pack may use it; validation reports it. |
| `social_profile` | resource location | | The incident profile that says what this deed is socially worth. Absent means no recognition and no facet evidence — never a severity-derived default, because severity describes impact and impact is not publicity. See *Public profiles*. |

### Visibility

| Value | Who knows |
|---|---|
| `private` | Only a subject of the deed itself. Never spreads. **Contribution must be `0`** — private incidents are memory, not standing. |
| `witnessed` | Witnesses immediately; every other resident after their own deterministic rumour delay. |
| `village` | Every current resident, immediately. |
| `global` | Reserved for a future cross-village system. Accepted and stored, treated exactly as `village` today. |

A `witnessed` deed that nobody saw has no public consequence. Depending on `retain_unwitnessed` it is
either dropped or kept as hidden history with zero contribution — which is how an unwitnessed killing
stays in the world's memory without changing anyone's opinion.

The Standing screen now shows each deed's visibility as a plain word — private, witnessed, or village —
next to it, so a record that counts for nothing does not read like something the whole village saw. When
a deed's current contribution differs from what it was originally worth, both figures are shown together
with a one-word reason: resolved, faded, or superseded.

### Decay

```json
{ "type": "linear_to_zero", "delay_ticks": 48000, "amount_per_day": 2 }
```

Nothing happens for `delay_ticks`; after that the contribution steps toward zero by `amount_per_day`
for each **complete** Minecraft day, and stops at zero. `amount_per_day` must be positive — a policy
that never terminates is an authoring error, and `{"type":"none"}` is how you say "does not fade".

Decay is computed from a monotonic age counter, not from the world clock, so `/time set` into the past
adds nothing and can never hand back contribution a player already lost.

### Occurrence, application, and decay time

A deed can be delivered late — a producer files a backdated `ReputationRequest` for something that
happened before the request arrived. Three different times are kept apart so that is handled honestly:

- **Occurrence** — when the deed actually happened, from the producer's own clock. A future value is
  clamped to now; an honest past value is kept as-is.
- **Application** — when the record entered the ledger, i.e. now, at delivery time.
- **Effective decay age** — how much decay clock the record has actually accumulated. For a backdated
  delivery this is seeded to the gap between occurrence and application *before* the record ever
  reaches a score, a mirror, a toast, or a tier event, so a late-arriving deed is announced at what it
  is worth today, not at the fresh full value it would have had on the day it happened.

History lists newest-first **by occurrence**, not by the order records were created, so a backdated
delivery slots into the chronology where it belongs rather than jumping to the top. `/mcareputation
debug standing` prints all three times per incident for diagnosis.

### Resolution

```json
{ "apologized": 0.75, "atoned": 0.25, "forgiven": 0.0, "disproven": 0.0 }
```

The multiplier is applied to the deed's **original** delta, so atoning is worth the same whether the
player does it immediately or a month later. Rounding is toward zero.

Progression is monotonic: only a strictly stronger resolution takes effect, which is what makes a
repeatable restitution quest safe. `disproven` is terminal. A status you do not list leaves the score
alone — the story changes, the number does not.

An unlisted status is not an error; it simply means "this kind of deed cannot be settled that way".

### Display templates

A `translate` display is filled with four arguments, always supplied in this order:

| Slot | Value |
|---|---|
| `%1$s` | the primary subject's name |
| `%2$s` | the second subject's name |
| `%3$s` | the source title — the quest, project, or situation behind it |
| `%4$s` | the deed's current contribution |

Every slot is always supplied, so a lang string may use as few as it likes, and adding a slot later
cannot break an existing translation. A missing fact becomes an empty string, never `null`. A display
that already carries its own `with` arguments is left exactly as authored.

### Gossip

```json
{ "tone": "condemnation", "phrase": "mypack.gossip.arson", "with": ["player", "subject"] }
```

`with` names up to four variables — `player`, `subject`, `subject_2`, `source_title`, `giver`,
`amount`, or any context key — bound in order as the phrase's arguments. `tone` is a free-form label
MCA: Conversations maps onto its own line pools; an unknown tone falls back to neutral rather than
failing. Reputation never writes the sentence itself: it supplies the key and the facts, Conversations
supplies the voice.

### The shipped incidents

| Id | Delta | Visibility | Decay | Notes |
|---|---:|---|---|---|
| `villager_assaulted` | `-8` | witnessed | 2/day after 2 days | Coalesced: a beating is one deed. |
| `villager_killed` | `-40` | witnessed | none | Absorbs a preceding assault so the pair totals `-40`, not `-48`. Pinned; retained even unwitnessed. The absorbed assault record is left in the ledger as chronology but marked **superseded**: terminal, it never contributes again, cannot itself be resolved (an apology can no longer discharge it), and is never offered as an amends or gossip candidate — its weight belongs to the killing that absorbed it. |
| `villager_rescued` | `+6` | witnessed | 2/day after 2 days | Credited once per bucket; a second kill in the same bucket does not double-credit. Raised by `ReputationDeedEvents.onThreatKilled` when a hostile mob that is targeting or has recently hurt an MCA villager is killed. |
| `villager_cured` | `+15` | witnessed | none | Retained even unwitnessed. Raised by `ReputationDeedEvents.onVillagerCured` when a player cures a zombie villager online; an offline curer earns nothing. |
| `raid_repelled` | `+20` | village | 1/day after 14 days | Raised by `ReputationDeedEvents.onHeroOfTheVillage` when the player receives Hero of the Village after a raid victory. Dedupe key uses the raid id to prevent double-credit on effect refreshes. |
| `player_killed_in_village` | `-12` | witnessed | 2/day after 2 days | Off by default; an operator enables it via config. Raised by `ReputationDeedEvents.onPlayerKilled` when a player kills another player inside a village. |
| `quest_completed` | caller | village | none | Generic fallback for MCA: Quests. |
| `quest_failed` | caller | village | none | Only created when a quest authors it. |
| `quest_abandoned` | caller | witnessed | none | Only created when a quest authors it. |
| `project_phase_completed` | caller | village | none | Per eligible contributor. |
| `project_completed` | caller | village | none | Per participant. |
| `project_failed` | caller | village | none | Explicit negatives only. |
| `situation_resolved` | caller | village | none | To the resolving player. |
| `promise_made` | `0` | private | none | An obligation, not standing. |
| `promise_kept` | `+8` | witnessed | none | Retained even unwitnessed. |
| `promise_broken` | caller | witnessed | none | Never automatic. |
| `public_apology` | `+1` | witnessed | 1/day after 1 day | Cannot erase the underlying deed. |
| `restitution_completed` | `+4` | village | none | Usually paired with a `resolve_incident` reward. |
| `legacy_balance` | `0` | private | none | Migration marker; the imported number lives in the baseline. |

The four newest incidents — `villager_rescued`, `villager_cured`, `raid_repelled`, and
`player_killed_in_village` — are core incident kinds. Another mod can claim any of them through the
authority mechanism in `api/CoreIncidentKind.java` and provide its own deeds instead. Pack authors
can override their JSON definitions exactly as they can for `villager_killed`.

---

## Tier ladders

```json
{
  "tiers": [
    { "id": "wary", "threshold": -25, "name": { "translate": "mcareputation.tier.wary" },
      "trust_bias": -1, "respect_bias": -2 },
    { "id": "honored", "threshold": 150, "name": { "translate": "mcareputation.tier.honored" },
      "trust_bias": 3, "respect_bias": 6, "grants_title": "mcaquests:honored_of_village" }
  ]
}
```

| Field | Required | Meaning |
|---|:---:|---|
| `id` | ✔ | A bare string, not a resource location — MCA: Quests' existing ladders and save tags use bare ids. |
| `threshold` | ✔ | Inclusive minimum score. Thresholds must ascend strictly; the lowest is the floor for everything beneath it. |
| `name` | ✔ | Text component **or a plain string**, so legacy `mcaquests` ladders load unchanged. |
| `description` | | Shown in `/mcareputation tiers`. |
| `trust_bias` / `respect_bias` | | The only channel by which standing reaches MCA: Conversations' checks. |
| `grants_title` | | Granted once, the first time this tier is reached. |

The default ladder is `mcareputation:default`, aliased to `mcaquests:default` so existing FTB tasks and
packs naming the old id keep resolving. Its positive thresholds are exactly the ones MCA: Quests
already shipped — 0 / 25 / 75 / 150 / 300 — so no existing world changes meaning; the negative half is
purely additive below zero.

**On the biases.** Conversations separates check tiers by a 15-point margin, so a bias bounded at ±8
can colour a borderline outcome but can never carry a check on its own. Validation rejects anything at
or beyond ±15 and reports anything beyond ±8, and the value is hard-clamped again at read time.

## Titles

```json
{
  "name": { "translate": "mypack.title.village_guardian" },
  "description": { "translate": "mypack.title.village_guardian.description" },
  "scope": "village",
  "revocable": false,
  "icon": "minecraft:shield"
}
```

Titles work **even when undefined** — ownership is recorded against the id, and an unknown id displays
as itself. That asymmetry is deliberate: removing a datapack must never revoke something a player
earned. A missing or unregistered `icon` falls back to a name tag and never affects ownership.

`revocable` exists so a future pack can declare a badge that is lost when standing falls. No shipped
title uses it.

---

## Public profiles

Four directories, added in 0.6.0, describing what a village knows a player **for** rather than how
much it likes them. They are independent of the standing ladder: recognition is non-negative and has
no conversion from a score, so two opposing deeds that cancel each other's deltas still both make a
player more widely known.

Three rules hold across all four:

- **Duplicate JSON keys are rejected.** Gson resolves `{"points": 8, "points": 80}` to `80` without a
  word; for authored evidence that is frozen onto accepted deeds that is unrecoverable after the fact,
  so these four directories are read by a strict parser that errors on a repeated key at any depth.
  The pre-existing `incidents`, `reputation_tiers`, and `titles` directories keep Gson's behaviour, so
  packs that load today keep loading.
- **Publication is all-or-nothing per reload, after one lenient repair pass.** A rejected facet,
  ladder, or credit policy is dropped and every profile that referenced it is dropped with it; an
  incident whose `social_profile` is unusable keeps its whole scalar definition and loses only that
  attachment, so a working crime definition is never discarded over an optional reference. If anything
  still fails to validate after that, **no** profile content is published this reload and the scalar
  definitions load normally — half a bundle would freeze different quantities onto deeds accepted
  either side of one `/reload`. `strictJsonValidation=true` refuses the whole swap instead.
- **Quantities are frozen at acceptance.** Editing a profile or a credit policy changes *future*
  deeds. Labels, descriptions, and observer weights live in the facet definition and do change on
  reload; the points, lifetimes, and multipliers a deed was accepted under do not.

### Facets

`data/<ns>/mcareputation/facets/**/*.json`

```json
{
  "name": { "translate": "mypack.facet.piety" },
  "description": { "translate": "mypack.facet.piety.description" },
  "range": { "min": 0, "max": 100 },
  "positive_label": { "translate": "mypack.facet.piety.pious" },
  "display_order": 80,
  "label_min_magnitude": 10,
  "label_min_evidence": 2,
  "opinion_weight_bp": 2000,
  "personality_overrides": { "odd": 4000 }
}
```

| Field | Required | Default | Meaning |
|---|:---:|---|---|
| `name` | ✔ | — | The facet's own name. |
| `description` | | — | Shown with the facet where there is room for it. |
| `range` | ✔ | — | Inclusive `min`/`max`, a subrange of `-100 … 100` that must contain zero and must not be `0 … 0`. `min < 0` makes the facet **bipolar** (zero is a real balance of evidence); `min >= 0` makes it **unipolar** (zero never implies the opposite trait — an unproven bravery is not cowardice). |
| `positive_label` | ✔ | — | The word for a positive value. |
| `negative_label` | | — | The word for a negative value. **Required** for a bipolar facet and **rejected** for a unipolar one, which can never reach a value it would describe. |
| `display_order` | | `0` | Tiebreak for display and dominance. Magnitude at most `10000`. |
| `label_min_magnitude` | | `10` | Value needed before the facet is labelled at all. `1` … the facet's own range magnitude. |
| `label_min_evidence` | | `2` | Distinct credited deeds needed before it is labelled, unless one of them is `major_evidence`. `1 … 64`. |
| `opinion_weight_bp` | | `0` | How much this facet colours a villager's opinion, in basis points, `-20000 … 20000`. Negative weights are the point for an adverse facet. |
| `personality_overrides` | | `{}` | Per-MCA-personality weights, at most 32 entries, keys 1–48 characters, each value in the same `±20000` bound. Keys are normalised to lowercase, so `Odd` and `odd` are the same key. Applied only when the villager's personality actually resolved. |

A facet whose definition disappears from the packs keeps its stored units: only presentation degrades,
and the missing definition's weight is neutral, so removing a pack cannot change any villager's opinion.

**Shipped facets** (all with `label_min_magnitude` 10 and `label_min_evidence` 2):

| Id | Range | Display order | `opinion_weight_bp` |
|---|---|---:|---:|
| `reliability` | `-100 … 100` | 10 | `5000` |
| `bravery` | `0 … 100` | 20 | `3000` |
| `compassion` | `-100 … 100` | 30 | `6000` |
| `lawfulness` | `-100 … 100` | 40 | `4000` |
| `generosity` | `0 … 100` | 50 | `3000` |
| `mercy` | `0 … 100` | 60 | `2000` |
| `violence` | `0 … 100` | 70 | `-6000` |

None of them authors a `personality_overrides` entry; a pack may add its own.

### Recognition ladders

`data/<ns>/mcareputation/recognition_tiers/**/*.json`, same shape as a tier ladder but with no biases
and no title grants — recognition is not friendship, and being known is not an achievement.

```json
{
  "tiers": [
    { "id": "unknown",  "threshold": 0,  "name": { "translate": "mypack.recognition.unknown" } },
    { "id": "notorious", "threshold": 250, "name": { "translate": "mypack.recognition.notorious" },
      "description": { "translate": "mypack.recognition.notorious.description" } }
  ]
}
```

| Field | Required | Meaning |
|---|:---:|---|
| `id` | ✔ | Bare string, 1–48 characters, unique within the ladder. |
| `threshold` | ✔ | Inclusive recognition value at which the tier begins, `0 … 1000`. |
| `name` | ✔ | The rung's name. |
| `description` | | Longer text for the rung. |

Thresholds must ascend strictly, the lowest rung must be exactly `0` — recognition starts at zero, and
a ladder whose floor began higher would leave an unknown player in no tier at all — and a ladder holds
at most 32 rungs. The default ladder is `mcareputation:default` and is also compiled into the mod, so a
pack that deletes the file does not leave every player tierless.

**Shipped ladder:** `unknown` 0, `noticed` 5, `recognized` 15, `well_known` 40, `renowned` 90,
`famous` 180.

### Incident profiles

`data/<ns>/mcareputation/incident_profiles/**/*.json`

```json
{
  "allowed_incidents": ["mypack:crime/arson"],
  "recognition": { "points": 12, "lifetime_ticks": 1344000, "resolution_mode": "recognition" },
  "facets": {
    "mcareputation:lawfulness": {
      "points": -10,
      "lifetime_ticks": 672000,
      "resolution_mode": "evaluative",
      "resolution_bp": { "apologized": 7500, "atoned": 2500, "forgiven": 0, "disproven": 0 }
    }
  },
  "credit_class": "adverse",
  "major_evidence": true,
  "decay_step_ticks": 24000
}
```

| Field | Required | Default | Meaning |
|---|:---:|---|---|
| `allowed_incidents` | | `[]` | Which incident ids may use this profile, at most 32. An empty list permits any. A `deliverProfiled` selection is validated against the same list. |
| `recognition` | | — | The recognition contribution. `points` is `0 … 100`; negative recognition is not a concept, since becoming infamous makes you better known, not less. |
| `facets` | | `{}` | Facet id → contribution, at most 8 entries. `points` is `-100 … 100` and must also respect that facet's own range and sign. |
| `credit_class` | | `neutral` | `commendable`, `adverse`, `mixed`, or `neutral`. Only `commendable` may carry a `credit_policy`, and a `commendable` profile with any negative facet is rejected — repetition must never make harm cheaper. |
| `credit_policy` | | — | The repeat-credit policy this profile competes in. Authoring one on a non-`commendable` class is an error rather than a silent no-op. |
| `major_evidence` | | `false` | One such deed alone satisfies a facet's `label_min_evidence`. |
| `decay_step_ticks` | | `24000` | The granularity profile aging is quantized to, `20 … 24000`. |

Each contribution takes `points`, a `lifetime_ticks` (`24000 … 100000000`, finite, at least the decay
step and a whole multiple of it — a lifetime that is not a whole multiple would silently truncate), a
`resolution_mode`, and optional frozen `resolution_bp` multipliers.

`resolution_mode` decides what an apology, atonement, forgiveness, or disproof does to that channel:

- `recognition` — nothing. Being known for something is not undone by apologising for it.
- `historical` (the default) — nothing. The violence a killing demonstrated still happened.
- `evaluative` — settles at the frozen `resolution_bp` multipliers. This is the "how does this reflect
  on you *now*" channel.

`resolution_bp` entries are basis points (`0 … 10000`), default `7500 / 2500 / 0 / 0`, must be
non-increasing along `apologized → atoned → forgiven → disproven`, and `disproven` must be exactly
`0`: a stronger settlement may never restore magnitude a weaker one already reduced, and a disproven
deed contributes nothing. A profile must author recognition, at least one facet, or both.

**Shipped profiles.** Every recognition channel is `1344000` ticks (56 in-game days) in `recognition`
mode; every facet channel is `672000` ticks (28 in-game days).

| Profile | Attached to | Recognition | Facets | Credit |
|---|---|---:|---|---|
| `assaulted_villager` | `villager_assaulted` | `4` | `violence +8` (historical), `compassion -4` (evaluative) | adverse, no policy |
| `killed_villager` | `villager_killed` | `18` | `violence +20` (historical), `compassion -12` (evaluative) | adverse, no policy; `major_evidence` |
| `rescued_villager` | `villager_rescued` | `6` | `bravery +8`, `compassion +5` (historical) | commendable, `rescue_service` |
| `cured_villager` | `villager_cured` | `10` | `compassion +10` (historical) | commendable, `cure_service` |
| `repelled_raid` | `raid_repelled` | `14` | `bravery +12`, `reliability +4` (historical) | commendable, `raid_defense`; `major_evidence` |
| `kept_commitment` | `promise_kept` | `3` | `reliability +4` (historical) | commendable, `commission_work` |
| `broken_commitment` | `promise_broken` | `3` | `reliability -6` (evaluative) | adverse, no policy |
| `donation_project` | selectable for `project_completed`, `project_phase_completed` | `5` | `generosity +6` (historical) | commendable, `donation_project` |
| `spared_outcome` | selectable for `situation_resolved` | `4` | `mercy +6` (historical) | commendable, `mercy_sparing` |

The last two ship with no `social_profile` on their incidents on purpose: a completed project or a
resolved situation can mean very different things, so a producer names the profile explicitly through
`deliverProfiled`. `public_apology` and `restitution_completed` deliberately attach nothing — zero is
already the default, and repairing the deed they answer is the resolution machinery's job, not a new
virtue award.

### Credit policies

`data/<ns>/mcareputation/credit_policies/**/*.json`

```json
{
  "group": "mypack:escort_work",
  "window_ticks": 336000,
  "credit_schedule_bp": [10000, 5000, 2500, 0],
  "tail_bp": 0,
  "scope": "player_community",
  "subject_limit": {
    "role": "client",
    "credit_schedule_bp": [10000, 5000, 0],
    "tail_bp": 0
  }
}
```

| Field | Required | Default | Meaning |
|---|:---:|---|---|
| `group` | ✔ | — | The allowance's identity. Two files that disagree about one group are a validation error. |
| `window_ticks` | ✔ | — | How long one allowance window lasts, `20 … 100000000`. |
| `credit_schedule_bp` | ✔ | — | Percentage of authored value per occurrence, in basis points. Occurrence 1 takes index 0. 1–32 entries, each `0 … 10000`, non-increasing. |
| `tail_bp` | | `0` | What every occurrence past the last entry takes. May not exceed the last entry: the tail continues the schedule rather than restarting it. A non-zero tail permits slow farming by definition, which is an honest pack choice rather than a bug. |
| `scope` | | `player_community` | `player_community` (one allowance per player per village) or `player_global` (one across every village — stricter, never more generous). |
| `subject_limit` | | — | A **second ceiling** on one participant, never a parallel allowance: the effective percentage is the minimum of the group and subject percentages, so rotating beneficiaries resets the ceiling and not the allowance. `role` is a 1–48 character label, lowercased. |

Credit only ever reduces a positive contribution. A non-positive one passes through untouched, so no
schedule, authoring mistake, or future call site can make wrongdoing cheaper through repetition.

**Shipped policies.** All six use a `336000`-tick window (14 in-game days), `player_community` scope,
and `tail_bp: 0`, so no shipped deed pays forever.

| Policy | Group schedule (%) | Subject limit |
|---|---|---|
| `rescue_service` | 100, 100, 50, 25, 0 | `beneficiary`: 100, 50, 0 |
| `cure_service` | 100, 100, 50, 0 | `beneficiary`: 100, 0 |
| `raid_defense` | 100, 50, 25, 0 | — |
| `donation_project` | 100, 50, 0 | — |
| `mercy_sparing` | 100, 50, 0 | `beneficiary`: 100, 0 |
| `commission_work` | 100, 100, 75, 50, 25, 0 | `client`: 100, 50, 25, 0 |

---

## Quest, project, and situation integration

With MCA: Quests installed, three more surfaces become available. Their full schemas are in that mod's
`DATAPACK.md`; in brief:

```json
"reputation": {
  "complete": { "delta": 12, "incident": "mcareputation:quest_completed", "visibility": "village" },
  "fail":     { "delta": -4 },
  "abandon":  { "delta": -2, "visibility": "witnessed" }
}
```

Failure and abandonment default to **nothing**. Every field accepts the legacy bare integer.

Conditions and rewards, registered whether or not Reputation is installed (so a suite-authored pack
still loads on a Quests-only install, where they simply never match):

```json
{ "type": "mcareputation:has_incident", "incident": "mcareputation:villager_assaulted",
  "status": ["active", "apologized"], "known_to_giver": true }

{ "type": "mcareputation:resolve_incident", "incident": "mcareputation:villager_assaulted",
  "resolution": "atoned" }

{ "type": "mcareputation:record_incident", "incident": "mcareputation:restitution_completed" }
```

A `resolve_incident` that names no incident, status, or tag is refused rather than picking one
arbitrarily.

## Conversation integration

With MCA: Conversations installed, two dialogue conditions and one action become meaningful. Both
conditions are registered unconditionally, so a pack using them loads either way and scores `0` without
Reputation — which is what lets your authored fallback branch fire.

```json
{ "conversations_reputation": { "min": 75, "min_tier": "friend" } }

{ "conversations_reputation_incident": {
    "types": ["mcareputation:villager_assaulted"], "statuses": ["active"],
    "known_to_speaker": true, "max_age": 168000 } }

{ "action": "conversations_reputation_signal",
  "incident": "mcareputation:public_apology",
  "decision": "standing.apology.public",
  "visibility": "witnessed" }
```

The action names an **incident definition**, never a raw delta. How much an apology is worth is decided
by that definition, and the dedupe key — villager, player, decision id — makes the second click a
no-op. That is what stops repeated clicking from farming standing.

Template variables for `conversations_say`: `reputation_tier`, `reputation_score`,
`reputation_village`, `reputation_recent_deed`, `reputation_title`. Each falls back to a neutral
localized phrase when nothing resolves, so a line never breaks.

---

## Loot conditions and advancement triggers

### The `mcareputation:standing` loot condition

The `mcareputation:standing` condition gates loot tables, item modifiers, and advancement criteria on
a player's standing with a village. It works anywhere a `LootItemCondition` does — including in
`predicates/` files, which is how an advancement criterion uses it.

```json
{
  "condition": "mcareputation:standing",
  "community": "here",
  "player": "this",
  "min": 20,
  "max": 80,
  "min_tier": "friend",
  "max_tier": "revered",
  "has_title": "mcareputation:village_hero"
}
```

| Field | Type | Default | Meaning |
|---|---|---|---|
| `community` | string | `"here"` | `"here"` resolves the nearest village to the loot origin (or the entity's position if no origin); or an explicit `"<dimension>/<villageId>"` string like `"minecraft:overworld/3"`. Malformed strings cause a load error. |
| `player` | string | `"this"` | `"this"` is the context entity; `"killer"` is whatever killed it. |
| `min`, `max` | int | none | Score bounds, inclusive. Both optional. |
| `min_tier`, `max_tier` | string | none | Tier ids from the tier ladder, compared by position (not by name). Both optional. |
| `has_title` | string | none | A title resource location. Optional. |

All standing fields are optional and ANDed together. An empty block `{}` is a deliberate no-op, not an
error. At runtime the condition answers false — rather than throwing — when the entity is not a
server player or when no village resolves for the given community.

#### Example: a loot table predicate

A `predicates/` file in `data/mypack/` that checks for standing:

```json
{
  "condition": "mcareputation:standing",
  "community": "minecraft:overworld/3",
  "player": "this",
  "min": 75,
  "min_tier": "friend"
}
```

#### Example: an advancement with a tier-crossing criterion

An advancement that fires when a player reaches the `friend` tier in any village:

```json
{
  "display": {
    "title": { "translate": "mypack.adv.made_friend" },
    "description": { "translate": "mypack.adv.made_friend.desc" },
    "frame": "goal",
    "show_toast": true
  },
  "criteria": {
    "reached_friend": {
      "trigger": "mcareputation:tier_reached",
      "conditions": {
        "tier": "friend",
        "upward_only": true,
        "player": [
          { "condition": "mcareputation:standing", "min_tier": "friend" }
        ]
      }
    }
  },
  "requirements": [["reached_friend"]]
}
```

Note that `player` must be a JSON array; a bare object is parsed as a vanilla entity predicate and the standing condition would be ignored.

### The `mcareputation:tier_reached` advancement trigger

The `mcareputation:tier_reached` trigger fires when a player's standing with a community crosses a
tier boundary — either up or down.

```json
{
  "trigger": "mcareputation:tier_reached",
  "conditions": {
    "tier": "friend",
    "community": "minecraft:overworld/3",
    "upward_only": true
  }
}
```

| Field | Type | Default | Meaning |
|---|---|---|---|
| `tier` | string | any | The tier id to match. Omit to fire on any tier crossing. |
| `community` | string | any | The explicit community as `"<dimension>/<villageId>"`. Omit to fire in any village. |
| `upward_only` | bool | `true` | When true, fire only on upward crossings. When false, fire on both directions. Set to false if you want to notice when standing falls into a tier. |

**Note:** the condition's field parsing and the trigger's matching rules are covered by unit tests
(`StandingConditionTest.java` and `TierReachedTriggerTest.java`); the full predicate and advancement
documents above are not parsed by any test and are not shipped in the jar, so pack authors should
validate them in a development world.

---

## Validation checklist

`/mcareputation validate` reports, with the exact id and field:

- unique ids; strictly ascending thresholds; a floor tier that a score of 0 can actually fall into
- referenced titles that exist in the same namespace
- deltas and override caps within the configured score range
- private incidents with a non-zero delta, and any use of `allow_private_score`
- decay values that are negative or never terminate
- resolution multipliers outside `0.0`–`1.0`
- biases beyond the shipped ±8 limit
- incidents that can have no observable effect at all
- pinned incidents that also set a retention window

For the profile schema it additionally reports:

- an authored facet value outside its facet's own range or sign, and a facet or credit policy that no
  loaded pack defines
- two credit policy files that disagree about one credit group
- a recognition ladder whose top rung sits above the maximum recognition value, so nobody could reach it
- an incident whose `social_profile` names a profile no pack defines, or one that profile's
  `allowed_incidents` does not admit
- advisory warnings for a private incident that names a profile, a facet nothing can weight, a credit
  policy with a non-zero tail, and a missing default recognition ladder

An error in the profile schema disables only what cannot be published (see *Public profiles*); an
advisory warning never refuses a reload, even in strict mode.
