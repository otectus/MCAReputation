# MCA: Reputation — migration, removal, and rollback

## If you are upgrading from 0.2.0 (Forge 1.20.1) to 0.3.0 (NeoForge 1.21.1)

World data and structure are unchanged. The same `mcareputation.dat` file loads identically; the NBT
schema is format 1 and will not change. Config keys and defaults are unchanged.

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

- The saved-data format is **unchanged** — still version `1`, still `mcareputation.dat` in the
  overworld's data storage. There is no conversion step, nothing to run, and no window in which data
  is half-migrated.
- Every score, baseline, incident (with its status, context, witnesses and dedupe key), village and
  global title, tier high-water mark, legacy-import marker, and cached village name survives exactly.
- Community identity stays dimension-aware, so village 3 in the Overworld and village 3 in the Nether
  remain two different places with two different reputations, as they always were.
- Every config key, default, and filename is unchanged: your existing `mcareputation-common.toml` and
  `mcareputation-client.toml` keep working untouched.

A golden copy of a 1.20.1 save is checked into this repository and asserted against on every build, so
this is a tested guarantee rather than an intention.

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
