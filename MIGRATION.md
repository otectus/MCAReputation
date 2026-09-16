# MCA: Reputation — migration, removal, and rollback

## If you are upgrading from 0.2.0 (Forge 1.20.1) to 0.3.0 (NeoForge 1.21.1)

World data and structure are unchanged. The same `mcareputation.dat` file loads identically; the NBT
schema was format 1 at the time of the 0.3.0 port and is loader-neutral — it later moved to format 2,
identically on both builds. See "Save format 1 → 2" below. Config keys and defaults are unchanged.

What you must update:

- **MCA Reborn** must be the 1.21.1 NeoForge build (`7.7.x`). The Forge 1.20.1 jar cannot load on
  NeoForge.
- **The optional companions**, if installed, must likewise be their 1.21.1 NeoForge builds. A Forge
  1.20.1 companion jar is simply not seen by the loader.
- **Bridges and add-ons that call this mod's API** must be recompiled. The API version is now `2`.
  Versions built against `1` will fail with `NoSuchMethodError` when they try to call a 0.3.0 method.
  See [API.md](API.md).

After the upgrade, trigger one new deed, save, exit fully, and restart. Old and new state should both
be there.

---

## If you are moving a world from Minecraft 1.20.1 (Forge) to 1.21.1 (NeoForge)

**Back the world up first, and keep the backup.** Vanilla's world upgrade is one-way, independently of
this mod: once 1.21.1 has written the world, 1.20.1 will not open it again. That is not something this
mod can undo, so treat the upgraded copy as a new artifact rather than an edit.

What this mod guarantees across that move:

- The saved-data schema is **loader-neutral** — the same version, in the same `mcareputation.dat` in
  the overworld's data storage, on both builds. Moving the world across loaders is not itself a format
  conversion: there is no loader-specific step, nothing to run, and no window in which data is
  half-migrated. (The schema itself has since moved from format 1 to format 2 — see "Save format 1 → 2"
  below — but that change applies equally regardless of which loader made the file.)
- Every score, baseline, incident (with its status, context, witnesses and dedupe key), village and
  global title, tier high-water mark, legacy-import marker, and cached village name survives exactly.
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
