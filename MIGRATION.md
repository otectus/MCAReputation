# MCA: Reputation — migration, removal, and rollback

## If you are upgrading from 0.2.0 (Forge 1.20.1) to 0.3.0 (NeoForge 1.21.1)

World data and structure are unchanged. The same `mcareputation.dat` file loads identically; at 0.3.0
the NBT schema was still format 1. (0.5.0 moves it to format 2 and 0.6.0 to format 3 — see *Save
format 1 → 2* and *Save format 2 → 3* below, both of which apply whichever loader you came from, and
both of which a format-1 file goes through in order, in one load.) Config keys and defaults are
unchanged apart from the two 0.5.0 additions and the eight 0.6.0 `[profiles]` keys listed in
CONFIG.md.

What you must update:

- **MCA Reborn** must be the 1.21.1 NeoForge build (`7.7.x`). The Forge 1.20.1 jar cannot load on
  NeoForge.
- **The optional companions**, if installed, must likewise be their 1.21.1 NeoForge builds. A Forge
  1.20.1 companion jar is simply not seen by the loader.
- **Bridges and add-ons that call this mod's API** must be recompiled. The API version is `2`, and
  neither 0.5.0 nor 0.6.0 moves it — everything both releases add is additive, including 0.6.0's
  whole `api.profile` surface. Versions built against `1` will fail with `NoSuchMethodError` when
  they try to call a 0.3.0 method. Note that `1` is the *Forge* line's number for the same feature
  set; the two loaders' API versions are separate lineages and a Forge-built bridge does not link
  here whatever the number says. See [API.md](API.md).

After the upgrade, trigger one new deed, save, exit fully, and restart. Old and new state should both
be there.

---

## If you are moving a world from Minecraft 1.20.1 (Forge) to 1.21.1 (NeoForge)

**Back the world up first, and keep the backup.** Vanilla's world upgrade is one-way, independently of
this mod: once 1.21.1 has written the world, 1.20.1 will not open it again. That is not something this
mod can undo, so treat the upgraded copy as a new artifact rather than an edit.

What this mod guarantees across that move:

- The saved-data format is **the same number on both loaders**, still `mcareputation.dat` in the
  overworld's data storage, and the bytes are identical: a format-1 file written on 1.20.1 loads here,
  a format-2 file written by the Forge 0.5.0 build loads here, and a format-3 file written by the
  Forge 0.6.0 build loads here with nothing left to migrate. Crossing loaders is never itself a
  conversion step. Arriving from a build older than 0.6.0 does run the in-place migrations described
  below, on either loader, exactly once each and in order.
- Every score, baseline, incident (with its status, context, witnesses and dedupe key), village and
  global title, tier high-water mark, legacy-import marker, and cached village name survives exactly.
  At format 3 that extends to the profile layer: every frozen evidence payload with its exact
  subunits, both aging clocks, the repeat-credit counters with their watermarks, and an unfinished
  enrichment cursor.
- Community identity stays dimension-aware, so village 3 in the Overworld and village 3 in the Nether
  remain two different places with two different reputations, as they always were.
- Every config key, default, and filename is unchanged: your existing `mcareputation-common.toml` and
  `mcareputation-client.toml` keep working untouched.

Golden copies of a 1.20.1-format save — one format 1, one format 2 — are checked into this repository
and asserted against on every build: the format-1 fixture must load and migrate to format 2 with the
documented totals, and the format-2 fixture (written by the Forge build) must load and re-save
byte-for-byte unchanged. This is a tested guarantee rather than an intention.

What you must update:

- **MCA Reborn** must be a 1.21.1 build (`7.7.x`). The Forge 1.20.1 jar cannot load on NeoForge.
- **The optional companions**, if you use them, must likewise be their 1.21.1 NeoForge builds. A Forge
  1.20.1 companion jar is simply not seen by the loader; it will not half-work.
- **Add-ons that call this mod's API** must be recompiled. The API version is now `2`; version `1`
  built against the Forge artifact will fail with `NoSuchMethodError` when 0.3.0 methods are called.
  The event base class also moved with the platform. See [API.md](API.md).

After the upgrade, trigger one new deed, save, exit fully, and restart. Old and new state should both
be there. If a score has reset, stop and restore the backup rather than playing on — reputation is
written on the same autosave path as MCA's own village data, and a reset is a symptom worth
diagnosing, not a thing to play through.

### Downgrading

Not supported. There is no path back from 1.21.1 to 1.20.1, for this mod or for the world.

---

## Save format 1 → 2

Since this version the on-disk schema is format 2. This is a schema change, not a loader change: a
format-1 `mcareputation.dat` — written by either build — migrates in place, once, the next time it
loads, before anything else touches the store.

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
call, or reward, and never moves a score. The file is saved back as format 2 on the next write.

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
upgrading. After a save has been written as format 2, an older build cannot read the new receipts or the
terminal-supersede flag; its loader keeps only what it recognises and warns rather than crashing. The
supported rollback is restoring that pre-upgrade backup — not opening a format-2 file with an older
build and hoping.

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

## Save format 2 → 3

0.6.0 adds public profiles, and profiles are the first thing this mod stores that is not a number: one
frozen evidence payload per accepted deed, a second aging clock per incident, bounded repeat-credit
counters per community, and a migration cursor. A world last opened by 0.5.0 (on either loader) has a
format-2 file, and this build migrates it in place, once, before anything else touches the store. A
format-1 file goes through **both** steps in order, in that one load: the 1 → 2 receipt recovery and
supersede adoption happen first, and 2 → 3 then walks over their result.

### What the structural migration does

- Every retained **public** incident whose definition names a `social_profile` gets an *unenriched
  stub*: a marker that says "this deed has social meaning that has not been reconstructed", carrying
  no quantities at all, and its profile clock starts at the age the scalar record already had.
- Nothing else. No score, tier, title, receipt, or revision moves; no facet or recognition value is
  invented. Reading a missing field as "no social meaning" would mislabel every pre-upgrade rescue as
  unremarkable, and reading it as a configured profile would award quantities nobody earned — so the
  migration says neither, and marks the gap instead.
- A record with no profile payload still serializes to the **byte-identical** player subtree format 2
  produced. Every new field is written only when it carries information, which is what lets an
  existing world load unchanged.

The structural half is deterministic and idempotent. The file is saved back as format 3 on the next
write.

### Legacy enrichment: what the stubs become

The stubs cannot be filled in at load time, because what they need — the facet and profile content —
comes from the datapack reload, which may not have published yet. So enrichment is a separate,
**resumable and budgeted** pass: eight players at a time from the periodic reconciliation sweep, which
defers entirely while the registries are empty. On a long-running world it finishes within a few
minutes of play and you will not notice it running.

What it will reconstruct is narrow, deliberately, and in two ways:

- **Only the built-in deed types**, named by a frozen manifest. A pack that repointed
  `villager_rescued` at a lavish custom profile cannot retroactively award the difference; a custom
  or generic deed stays explicitly unenriched forever.
- **Only the recognition and historical channels.** An old killing contributes the recognition and
  the violence it factually demonstrated, and nothing about culpability or remorse — those are
  evaluative judgements, and nobody was there to make them.

Credit is 100%, the repeat-credit counters stay empty, and no standing, title, receipt, or revision
moves. Correctness does not depend on the cursor reaching disk: enrichment only ever upgrades a stub,
so a pass whose progress was not saved is simply performed again.

### Why history reads as incomplete afterwards

Until the pass finishes, a profile's *coverage* reports `MIGRATING`; afterwards — and for any world
that ever had legacy history or a quarantined payload — it reports `PARTIAL_LEGACY`, permanently. Only
a world created on 0.6.0 or later reports `COMPLETE_SINCE_RECORD_START`.

That is not pessimism, it is the honest answer: a conservative pass reconstructed some of your history
and declined to invent the rest, so a datapack gate that asks *"has this player never done X"* keeps
respecting the gap rather than answering from an assumption. The Standing screen says "recognition
history is incomplete" rather than "nobody knows you", because a missing import hides evidence, and
reading that gap as absence is wrong in the one direction that flatters a stranger.

### Turning profiles off

`enableProfiles=false` is not a delete and not a rollback. Stored payloads are retained, their profile
clock **freezes**, and profile queries report themselves disabled. Turning it back on resumes where it
stopped rather than paying out the disabled interval as a burst of catch-up fading — the freeze
intervals are tracked so an unobserved disabled period and an observed one produce the same answer.
Scalar standing continues under its own rules throughout, and repeat-credit accounting still advances
on each accepted deed, so the switch is never a way to reset an allowance.

### Backing up and rolling back

Back up `<world>/data/mcareputation.dat` before upgrading, as for any format bump. After a save has
been written as format 3, an older build reads the fields it recognises and ignores the profile
subtrees — it will not crash, but a subsequent save from that older build **drops them**, and the
evidence is not recoverable except from the backup. The supported rollback is restoring the
pre-upgrade backup.

A file from a format *newer* than this build's is still handled the way *A file from a newer format*
describes above: latched read-only, loaded into nothing, migrated and enriched not at all, and handed
back verbatim including keys this build has never heard of.

### What changes on the ground

- Villagers can now say what they know you **for**, not only how much they like you, and the Standing
  screen has two new header lines plus a **Details** expansion for it.
- A villager's opinion of you can shift by up to `maxFacetOpinionAdjustment` (25 by default) based on
  the facets that villager personally knows you for. What leaves this mod for a Conversations
  Trust/Respect check is still bounded at ±8, unchanged — that is a different quantity in different
  units, and a saturated facet term can move you to a different rung but never add a second bias
  beside that rung's own.
- Repeating the same service in one village is worth progressively less: the shipped policies pay
  100% / 100% / 50% / 25% / 0% over 14 in-game days for a rescue, with a second ceiling per
  beneficiary. Wrongdoing is never discounted.
- A record still holding live profile evidence is no longer pruned to make room. A ledger with nothing
  else evictable refuses the next deed instead; every authored lifetime is finite, so the pressure is
  temporary.
- `/mcareputation debug profile`, `debug credit`, `debug profileincident`, and `debug profilemigration`
  are new, all at permission level 2. The last one is the only one that can mutate anything, and only
  with an explicit `run <budget>`.
---

## If you are starting a new world

Nothing to do. Everyone begins as a stranger everywhere, which is the intended starting point.

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
