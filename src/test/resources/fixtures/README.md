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
