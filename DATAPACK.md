# MCA: Reputation — datapack reference

> **Paths are unchanged by the Minecraft 1.21.1 / NeoForge port**, including the legacy `mcaquests`
> ones. The doubled namespace segment below is deliberate: these are this mod's own reload-listener
> directories, not vanilla resource folders, so the 1.21 singular/plural directory renames do not
> apply to them.
>
> One thing did change under the surface. Text fields (`display`, tier `name`, tier and title
> `description`) are now parsed by `ComponentSerialization.CODEC` rather than 1.20.1's
> `ExtraCodecs.COMPONENT`. Every text form these docs show — a plain string, a `{"translate": …}`
> object, a list of components — parses exactly as before; the change is which vanilla codec does it.
> This mod targets **Minecraft 1.21.1** and needs no `pack_format` declaration of its own, because
> NeoForge generates pack metadata for mods.

Three kinds of definition, all reloaded by `/reload`:

```
data/<namespace>/mcareputation/incidents/**/*.json          what a deed is and what it is worth
data/<namespace>/mcareputation/reputation_tiers/**/*.json    the named bands standing falls into
data/<namespace>/mcareputation/titles/**/*.json              earned badges
```

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

### Decay

```json
{ "type": "linear_to_zero", "delay_ticks": 48000, "amount_per_day": 2 }
```

Nothing happens for `delay_ticks`; after that the contribution steps toward zero by `amount_per_day`
for each **complete** Minecraft day, and stops at zero. `amount_per_day` must be positive — a policy
that never terminates is an authoring error, and `{"type":"none"}` is how you say "does not fade".

Decay is computed from a monotonic age counter, not from the world clock, so `/time set` into the past
adds nothing and can never hand back contribution a player already lost.

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
| `villager_killed` | `-40` | witnessed | none | Absorbs a preceding assault so the pair totals `-40`, not `-48`. Pinned; retained even unwitnessed. |
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
