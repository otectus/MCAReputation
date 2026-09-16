# MCA: Reputation — migration, removal, and rollback

## If you are starting a new world

Nothing to do. Everyone begins as a stranger everywhere, which is the intended starting point.

---

## Save format 2 → 3

Format 3 adds the profile layer: frozen profile evidence per deed, a second per-deed clock for aging
it, and the bounded repeat-credit window accounting. A world last opened by 0.5.x is upgraded in
place, once, the next time it loads.

A ledger with no profile content still serializes to the byte-identical player subtrees format 2
produced, which is what lets an existing world load unchanged.

### The structural half, at load

Deterministic, and it moves no score, title, receipt, or revision. Every **retained, non-private**
incident whose definition names a `social_profile` gets an *unenriched stub* — a marked placeholder
carrying no quantities — and starts its profile clock from the scalar age that was actually observed
for that record, rather than from zero (which would hand a decade-old killing a fresh lifetime) or
from now-minus-created (which would bank every interval nobody ever charged).

A missing field is read as "we do not know what this was socially worth", never as "this was
unremarkable" and never as a configured profile: the first would mislabel every pre-upgrade rescue,
the second would award quantities nobody earned.

### The enrichment half, resumable and budgeted

Filling those stubs in cannot honestly happen at load time, because the content it needs comes from
the datapack reload, which may not have published yet. So it runs afterwards, in bounded passes of
eight players, from the same periodic sweep that reconciles decay, and defers entirely while the
registries are still empty (`state/ReputationSavedData.advanceProfileMigration`).

- Progress is a cursor in `state/ProfileMigrationState`. **Correctness does not depend on it**:
  enrichment only ever upgrades an unenriched stub, so a pass whose progress never reached disk is
  simply performed again, and an interruption half way through is harmless.
- What it is allowed to speak for is narrow, twice over. A frozen manifest
  (`profile/LegacyEnrichmentManifest`, version 1) names the built-in incident types it may enrich —
  assault, killing, rescue, cure, raid repelled, promise kept, promise broken — rather than reading
  the live `social_profile` field, because otherwise every `/reload` would rewrite history: a pack
  repointing `villager_rescued` at a more generous profile would retroactively award the difference.
  Custom types, and the generic completion incidents (`quest_completed`, `project_completed`,
  `situation_resolved`) whose meaning the old save never recorded, stay explicitly unenriched.
- Only the `recognition` and `historical` channels are reconstructed. An old killing therefore
  contributes the recognition and the violence it factually demonstrated, and nothing about how
  culpable or sorry anybody was — that was never stored and cannot be inferred.
- Credit is 100%, the window trackers start empty, and no standing, title, receipt, or revision moves.

### Coverage, and why it stays partial

| Coverage | Meaning |
|---|---|
| `COMPLETE_SINCE_RECORD_START` | The save never needed migrating; every payload was created live. |
| `MIGRATING` | A budgeted pass is still owed. Profile answers are provisional. |
| `PARTIAL_LEGACY` | There is legacy history, or a quarantined payload. Positive evidence is displayable; **absence proves nothing**. |

A finished conservative pass is partial history, not complete history, and it stays `PARTIAL_LEGACY`
for good. That distinction is the whole point: an authored gate asking "no evidence of violence" must
keep respecting a save that cannot prove it, and the Standing screen says "recognition history is
incomplete" rather than "nobody knows you".

### Malformed profile payloads are quarantined, and the deed is kept

Every bound is checked on the read path, not only the write one. A profile payload that fails
validation — an out-of-range quantity, credited above authored, current above credited, disagreeing
signs, a non-monotonic resolution progression, an unknown origin or credit reason, a stub that
nonetheless carries quantities — is held in the existing quarantine and its **scalar incident is
kept**. An unrecognised credit reason is refused rather than read as "full credit": that would invent
an explanation for a number a player can see. Profile payloads are counted separately from player and
incident entries, and the counter keeps counting past the bound on held copies, because "we stopped
keeping copies" must not read as "there was nothing wrong". `/mcareputation debug quarantine` reports
them.

### A file from a newer format

Unchanged, and it now covers the new work too: a format-4 file latches the store **read-only**, loads
nothing, refuses to migrate *and* refuses to enrich, and is handed back verbatim — including keys this
build has never heard of. Every write path refuses retryably in that state rather than applying a
mutation in memory that will never be saved.

### Running it by hand

```
/mcareputation debug profilemigration              coverage, cursor, counters, quarantined payloads
/mcareputation debug profilemigration run <budget>  enrich up to <budget> players now
```

`run` is the one mutating diagnostic here, and it is bounded, resumable, and idempotent for the same
reason the automatic pass is. It awards no standing, titles, or rewards.

### Fixtures

The suite carries a real `mcareputation-format-3-1.20.1.nbt` fixture generated by the existing gated
mechanism, holding one of each kind — a live payload credited at 50%, an enriched legacy payload, a
stub, a live credit group and subject window, and an unfinished cursor — because a fixture with only
the easy case would not notice a serializer that dropped the hard one. The format-1 and format-2
fixtures are untouched; format 2 can no longer be compared whole after an upgrade, so its player
subtrees are compared instead, which is the statement that actually matters.

---

## Save format 1 → 2

Format 2 was the 0.5.0 schema; a world coming from further back passes through this step first, in
order, on its way to format 3. A world last opened by an older build has a format-1
`<world>/data/mcareputation.dat`; this build migrates it in place, once, the next time it loads — before
anything else touches the store.

### What the migration does

- Every retained incident that carries a dedupe key gets a synthesised `APPLIED` receipt pointing at its
  own incident id, with the namespace taken from the incident's own source. This is what lets a
  companion that replays a pre-upgrade operation key learn what it already produced instead of recording
  it a second time.
- Every record carrying only the legacy `superseded_by` context entry becomes terminal — the same state
  a supersede sets going forward — with the typed link parsed when it is a valid id. A successor that
  was already pruned before the upgrade is tolerated, not an error.
- `appliedGameTime` is added to every incident and defaults to its occurrence time; the two only differ
  going forward, for a genuinely backdated delivery.
- Nothing is invented for history that was already pruned before the upgrade.

The migration is idempotent — running it again changes nothing — and it writes no event, toast, mirror
call, or reward, and never moves a score. The file is saved back at this build's format on the next
write, so a format-1 world that loads here lands at format 3 having run both steps in order.

### Quarantine instead of silent loss

A player or incident entry that cannot be parsed is no longer just logged and discarded: it is held in
memory (bounded, so a systematically corrupt file cannot become a memory problem) with its reason and
raw data, and written once per server start to `<world>/mcareputation-quarantine.nbt` (gzip-compressed).
`/mcareputation debug quarantine` reports the held entries — count, dropped overflow, and each
entry's path and reason — plus the on-disk format version and whether the store is currently
latched read-only.

### A file from a newer format

If `mcareputation.dat` was written by a format this build does not understand — you downgraded, or a
future version wrote it — the store latches **read-only**: nothing from it is loaded into live state,
nothing you do in that session is saved, and the next save writes the original bytes back unchanged. One
error names both the file's version and this build's. `/mcareputation debug quarantine` also reports the
read-only state and the on-disk version. Run the newer build, or restore a backup taken before the
downgrade — the file itself is never touched destructively.

### Backing up and rolling back

Back up `<world>/data/mcareputation.dat`, and the rest of `<world>/data/` alongside it, before
upgrading. Rolling back across a format bump is **restoring that backup**, not opening the newer file
with the older build: a 0.5.0 build meets a format-3 file as a future format and latches its store
read-only, exactly as this build does for a format-4 file, so that session neither loads nor saves
anything. Pre-0.5.0 builds, which had no such latch, keep only what they recognise and warn.

### What changes on the ground

- A duplicate delivery returns the same incident id it did the first time, instead of a bare refusal
  with no way to look the original up.
- An immune or globally-paused community's standing no longer moves through any screen, query, or
  resolution; before, opening certain screens could still age a community meant to be protected.
- A killing that absorbed a preceding assault can no longer regain weight if the killing is later
  resolved out from under it — the absorbed record is terminal.
- A companion that claims core incidents without declaring which ones no longer suppresses the newer
  positive kinds (rescue, cure, raid repelled, PvP) by default; see `coreAuthorityUndeclaredKinds` in
  CONFIG.md.
- The `mcareputation:standing` loot condition and this mod's own standing predicate no longer depend on
  `enableConversationsIntegration`.
- Asking a villager's opinion of a player who has a valid community record but no incidents there now
  answers a real zero, not "no data".
- A ledger that is completely full and has nothing left evictable now refuses the next deed outright
  instead of silently dropping older history to make room; `/mcareputation debug receipts` explains why.

---

## If you already play with MCA: Quests

MCA: Quests has had village reputation since 0.7.0. Your existing standing carries over, but it is
worth understanding exactly what carries and why the rest cannot.

### The honest limitation

Quests stored reputation **per village, shared by the whole world**. There is no record of who earned
what — that information was never written down. On a singleplayer world the distinction does not
matter, because there was only ever one person it could have described. On a server it matters a great
deal, and no amount of cleverness can reconstruct it after the fact.

So the migration does **not** invent history. Each legacy village score becomes a non-decaying
**baseline**: standing with no deed attached. Your number is preserved; your ledger honestly starts
empty and fills with things you actually do from here on.

### What is copied

- The village score, as a baseline
- The tier high-water mark, so you do not re-earn a milestone you already reached
- Village and global titles you already hold
- The village's cached name

### Who is eligible

The point of the eligibility rule is to stop a brand-new player joining an established server and
inheriting somebody else's reputation. A player qualifies if any of these is true:

- they have completed, failed, or abandoned a quest in this world
- they have a quest active now
- they have MCA: Quests progression stats
- they hold any Quests title
- the world is singleplayer

Everyone else starts at zero, which for a genuinely new player is correct.

### When it runs

At login, once. The migration marker is written **after** the store has successfully changed, so a
crash mid-import leaves the player eligible to retry rather than marked done with nothing copied. Once
the marker exists the import can never run again, no matter how many times login or a command triggers
it.

Legacy keys carry no dimension, so they are read as `minecraft:overworld` — the only thing they could
have meant, since that is where MCA generates villages.

### Doing it by hand

```
/mcareputation migrate status [player]         what has been imported, and from which providers
/mcareputation migrate run <player> --dry-run  report what would happen; write nothing
/mcareputation migrate run <player>            import now, bypassing the eligibility heuristics
```

`run` passes `force`, which means **you** are taking responsibility for the eligibility decision. It is
still idempotent: a player who has already migrated is reported as such and nothing changes.

### If you want the old shared semantics

Some server owners would rather everyone kept the shared number. Run
`/mcareputation migrate run @a` while they are online; each gets the legacy balance as their own
baseline, and standing diverges naturally from there.

---

## What MCA: Quests does on its side

MCA: Quests 1.1.0 changes its own store at the same time, independently of whether this mod is
installed:

- **v1** — `ProjectSavedData.reputation`, keyed `"v:<villageId>"`, world-shared, dimension-blind.
- **v2** — `ProjectSavedData.standingV2`, keyed by player UUID and then by `<dimension>/<villageId>`.

**The v1 tags are not deleted.** They are still written on every save, purely so a pre-1.1.0 world stays
hand-recoverable and so the import can read them. They are no longer a live gameplay path; a build-time
assertion fails the Quests build if a gameplay call site starts reading them again.

With MCA: Reputation installed, its store is canonical and Quests mirrors score, tier high-water, and
titles into v2 after each commit — which is what makes removal safe.

---

## Removing MCA: Reputation

Supported, and reversible.

1. **MCA: Quests** falls back to its own v2 store, which the mirror has been keeping current. Players
   keep the standing they had. Quest, project, and situation reputation, tiers, titles, the Journal,
   and the FTB tasks all keep working; what disappears is the deed ledger, since Quests never had one.
2. **MCA: Conversations** stops factoring standing into trust and respect checks — the term becomes
   exactly `0`, so every seeded outcome returns to what it would have been. The reputation dialogue
   conditions score `0`, so your authored fallback branches fire. Built-in gossip is untouched.
3. **The save data stays.** `<world>/data/mcareputation.dat` is left alone. Nothing tries to
   deserialize a Reputation class when the mod is absent.

## Reinstalling it

Your canonical data is still there and is picked up as it was. Migration markers prevent a second
import, and incident dedupe keys prevent anything being re-applied. Standing that changed in Quests
while Reputation was gone stays in Quests' v2 store; the canonical store resumes from where it left
off, so the two can differ by whatever happened in between. If you want them reconciled, set the
canonical value explicitly:

```
/mcareputation set <player> <amount> <dimension>/<villageId> "reconciling after reinstall"
```

Every such change is written to the server log with the executor, the target, the community, the old
and new score, and your reason.

## Rolling back to a pre-1.1.0 MCA: Quests

Not guaranteed, but not hopeless: the v1 `reputation` and `repTierHW` tags are retained, so an older
Quests build will read the world and find the shared numbers exactly as it left them. Anything earned
after the upgrade lives in v2 and an older build cannot see it.

Back up `<world>/data/` before trying it.

---

## Datapack and content compatibility

Nothing here needs editing.

- `mcaquests:default` still resolves — it is an alias for the canonical ladder.
- `mcaquests:honored_of_village` and `mcaquests:revered_of_village` are still the ids the default
  ladder grants, so titles players already hold stay meaningful.
- The positive thresholds are unchanged: 0 / 25 / 75 / 150 / 300. Negative tiers are purely additive
  below zero.
- Tier and title definitions in `data/<ns>/mcaquests/…` are still loaded.
- `mcaquests:village_reputation` rewards, and `mcaquests:reputation_tier` / `village_reputation`
  conditions, all still work — now reading and writing *your* standing rather than a shared number.
- The integer shorthand in project and situation reputation blocks parses exactly as before.

## MCA: Conversations data

Untouched. `mcaconversations_gossip.dat` loads unchanged, existing `QUEST` gossip events age out
normally rather than being migrated, and dispositions, progress, `LongTermMemory` flags, and quest
memories are not modified.

---

## Troubleshooting

**"My standing reset when I joined the server."** You were probably not eligible — you had no prior
Quests history in that world. Check `/mcareputation migrate status <player>`, and use
`/mcareputation migrate run <player>` if you want to grant it anyway.

**"Two players had the same number and now they differ."** That is the fix working. The old number was
shared by everybody; each of you now has your own.

**"A village I have standing with is gone."** History is kept with the last name the village had.
Deleting a village does not delete what happened there.

**"The Journal and the Standing screen disagree."** They cannot — both read the same snapshot through
the same bridge. If you are seeing it, the bridge failed to initialise; look for a single ERROR line
from MCA: Quests at startup.

**"A deed did not record and nothing else looks wrong."** Check `/mcareputation debug receipts
<player> <community>` — the community's ledger may be full with nothing left evictable, in which case
the deed was refused with `CAPACITY` rather than silently dropping older history. Raise
`maxIncidentsPerCommunity`, clear a pin, or wait for open negative deeds to age past
`receiptRetentionTicks`.

**"Some of my save data went missing after an upgrade."** Check `/mcareputation debug quarantine`. A
malformed entry is now held and written to `<world>/mcareputation-quarantine.nbt` instead of being
silently dropped; the report names the path and the reason.
