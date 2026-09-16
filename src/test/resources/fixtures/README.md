# Golden saved-data fixtures

## `mcareputation-format-1-1.20.1.nbt`

Evidence of backward compatibility. **Never regenerate this file with the 1.21.1 serializer** — its
whole value is that it was written by the Forge 1.20.1 code, before the NeoForge port touched
`ReputationSavedData`.

| | |
|---|---|
| Produced by | `C:\Projects\MCAReputation` working tree — commit `8fac797` plus the uncommitted MCA: Crime core-incident-authority feature |
| Produced on | 2026-08-25 |
| Producing code | `ReputationSavedData.save(new CompoundTag())`, unmodified Forge 1.20.1 |
| Encoding | **gzip-compressed** NBT (`NbtIo.writeCompressed`) — read it back with the compressed reader |
| Size | 1029 bytes |
| SHA-256 | `8f16f473f09ee142cb6404bcd4709cc0027f5cbbf22fb6628d7b105d7c432108` |
| `version` | `1` |

### What it contains

Two players, and every schema feature the port must not lose:

- **`00000000-…-00000000000a` ("Ada")**
  - `minecraft:overworld` village **3** — cached name `Riverbend`, centre `(10, 64, -20)`, baseline
    `+25`, final score **`-70`**, tier high-water on both the `mcareputation` and legacy `mcaquests`
    ladders, two `mcaquests:` village titles, and six incidents covering every status and shape:

    | Incident UUID prefix | Status / shape | Notable fields |
    |---|---|---|
    | `11111111…` | **active**, pinned | 2 witnesses, 2 context entries, villager subject, dedupe key |
    | `22222222…` | **resolved** (`ATONED`) | witness, subject, dedupe key |
    | `33333333…` | **expired** | dedupe key |
    | `44444444…` | **hidden** (`PRIVATE` visibility) | context entry, no dedupe key |
    | `55555555…` | **folded** into `66666666…` | witness, `superseded_by` context |
    | `66666666…` | the successor kill | 2 witnesses, `SEVERE`, subject |

  - `minecraft:the_nether` village **3** — the same numeric village id in a second dimension, cached
    as `Ashfall`, score **`+60`**. This is what proves `CommunityKey` identity stays dimension-aware.
  - One global title, and a legacy-import marker for `mcaquests:legacy_reputation_v1` version `1`.

- **`00000000-…-00000000000b` ("Bo")** — a record in the *same* village as Ada with score **`-80`**,
  its own tier high-water and global title. Proves the two players' ledgers stay independent.

### How it is used

`SavedDataTest` loads it through the provider-neutral `loadPayload` helper, asserts every semantic
field above, saves it again, reloads, and asserts no semantic loss — and that the re-written
`version` is still `1`.

## `mcareputation-format-2-1.20.1.nbt`

The format-2 golden file, written by the 0.5.0 serializer and **never regenerated since**. Stored
**uncompressed** (`NbtIo.write`), so any comparison is over NBT alone.

Since the save format moved to 3 it can no longer be compared whole — the current serializer writes
`version = 3` — so `GoldenSavedDataTest` compares its **player subtrees** instead
(`theFormatTwoPlayerSubtreesStillSerializeIdentically`). That is the statement worth keeping: format 3
adds fields and moves no scalar byte, because every new field is written only when it carries
information. The same test also loads the file and asserts the v2 to v3 migration changes no total.

## `mcareputation-format-3-1.20.1.nbt`

The current golden file, asserted byte for byte by `GoldenSavedDataTest` and intended to be copied
unchanged by the NeoForge port — cross-loader byte identity is why it is stored **uncompressed**.

| | |
|---|---|
| Produced by | `/home/otectus/Projects/MCAReputation` on `feature/0.6.0-profiles`, work package P3 |
| Produced on | 2026-09-16 |
| Producing code | `GoldenSavedDataTest.profiledLedger().save(new CompoundTag())` |
| Encoding | **uncompressed** NBT (`NbtIo.write`) |
| Size | 5269 bytes |
| SHA-256 | `1a906772662000bc0331c6ba358d21cb1880b1036356a900acd3473a233ec758` |
| `version` | `3` |

### What it adds over format 2

The same two players and three communities, plus one of each thing format 3 introduced — one of each
*kind*, deliberately, because a fixture carrying only the easy case would not notice a serializer that
dropped the hard one:

- **A live payload** on Ada's rescue (`33333333…`): origin `live`, recognition 6 and bravery 8
  authored, credited at 50% as the third rescue in the window, aged two of the bravery channel's 28
  days. Half an authored point survives as `5000` subunits, which puts the fixed-point promise of
  §8.3 on disk rather than in a comment.
- **An enriched legacy payload** on the folded assault (`11111111…`): origin `legacy_enriched`,
  recognition only, full historical credit. A superseded record keeps its stored units and
  contributes none, which is what makes a refused supersession restorable.
- **An unenriched stub** on Bo's deed (`44444444…`): origin `legacy_unenriched`, no quantities at all.
- **A live credit window** on Ada's Riverbend: one group counter at two occurrences and one subject
  counter, with their frozen window duration and monotonic watermarks.
- **An unfinished migration cursor**: `profileMigration` with the manifest version, one stubbed
  record and a cursor resuming after Ada, so coverage reads `migrating` rather than complete.

Regenerate deliberately, never to make a failing assertion pass — and note that the gated test only
ever rewrites the **newest** file:
`./gradlew test --tests '*GoldenSavedDataTest' -Dmcareputation.regenerateFixtures=true`
