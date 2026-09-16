# MCA: Reputation — production verification matrix

> **Status: NOT YET RUN.** This file is the checklist that must pass before 0.3.0 is tagged as
> released, not a record of it having passed.

## Why this file exists

A green `./gradlew build` is not a release gate. It proves the code compiles against the pinned MCA
artifact and that the domain logic behaves — it says nothing about whether the mod starts on a real
client, whether a real dedicated server resolves a client class it should not, or whether a real
1.20.1 world survives the upgrade with its ledger intact.

The 1.20.1 reason for this file was that MCA's Forge mixins shipped SRG-named with no refmap, so
`runClient`/`runServer` did not exercise real MCA at all. **That specific obstacle is gone on 1.21.1**:
MCA's NeoForge artifact is mojmap and loads as a real mod in the dev runs, so `runClient` and
`runServer` here *do* exercise genuine MCA behaviour. The gate remains anyway, for the reason Spec
Appendix D actually gives: "production verified" means the built jars were tested in a production-style
instance, not that compilation and unit tests passed. There is no reobfuscation step to worry about any
more — `build/libs/mcareputation-0.6.0.jar` *is* the artifact that ships.

## What has passed so far

| Gate | Status | Evidence |
|---|---|---|
| Phase 0 audit and reconciliation | ✅ | [IMPLEMENTATION_NOTES.md](IMPLEMENTATION_NOTES.md) |
| MCA 7.7.36-beta.3 signature confirmation | ✅ | `javap` over the pinned `mca-neoforge` jar; every consumed signature present and compatible (IMPLEMENTATION_NOTES §2.1) |
| MCA: Reputation compiles and unit tests | ✅ | **888 tests, 0 failures, 1 skipped** at 0.6.0 (0.5.0 on this branch was 531; 0.4.1 was 401; the 1.20.1 Forge baseline before the port was 255). The skip is the golden-fixture regeneration case, which only runs under `-Dmcareputation.regenerateFixtures=true`. |
| Golden 1.20.1 saved data loads under 1.21.1 | ✅ | `GoldenSavedDataCompatibilityTest` against three fixtures written by the unmodified Forge serializer: the format-1 file (migrated through both steps on load), the Forge 0.5.0 format-2 file (migrated 2 → 3), and the Forge 0.6.0 format-3 file — loaded with nothing to migrate, its frozen profile subunits and credit counters asserted exactly, and its SHA-256 pinned so a local regeneration cannot quietly replace the evidence |
| Config keys and defaults unchanged | ✅ | `ConfigParityTest` pins both key sets and both filenames exactly; 0.5.0's two additions (`limits.receiptRetentionTicks`, `integration.coreAuthorityUndeclaredKinds`) and 0.6.0's eight `[profiles]` keys (four COMMON, four CLIENT) are pinned there and documented in CONFIG.md |
| No Forge or relocated-MCA reference survives | ✅ | `NeoForgePortLintTest` (source, 19 idioms) + `OptionalClassloadTest` (bytecode) + `checkJarContents` (packaged bytecode) |
| Dedicated-server safety of the packet seam | ✅ | `ClientPacketSinkTest` + a bytecode assertion that no class under `network/` names a client type |
| MCA: Quests compiles and regression suite | ✅ | **331 tests, 0 failures**, with `compat/reputation/**` compiled against this port for the first time |
| MCA: Conversations compiles and regression suite | ✅ | **537 tests, 0 failures**, with `compat/reputation/**` compiled and the optional dependency entry restored |
| Shipped jar contains no shaded companion classes | ✅ | `checkJarContents`, run as part of `build` |
| No mixins to lint | ✅ | the mod ships none; asserted by `OptionalClassloadTest` and `checkJarContents` |
| Save format 1 → 2 migration | ✅ | `SavedDataMigrationTest` over the checked-in format-1 fixture: receipts recovered, legacy supersessions made terminal, idempotent on a second run, no score moved — and still run *before* the 2 → 3 step when a format-1 file arrives, asserted separately |
| Save format 2 → 3 migration and enrichment | ✅ | `ProfileEvidencePersistenceTest`: profiled public incidents stubbed and nothing else, the adopted clock, enrichment idempotent across two runs and across an interruption mid-cursor, an unwritten pass repeated harmlessly, deferral while the registries are unpublished, and a ledger with no profile content still serializing byte-identically to format 2 |
| MCA profession getter audited against the pinned jar | ✅ | `javap` over `mca-neoforge-7.7.36-beta.3+1.21.1.jar`: `public net.minecraft.resources.ResourceLocation getProfessionId()` on `net.conczin.mca.entity.VillagerEntityMCA`. Re-checked on every `check` by `McaBinaryAbiTest` (optional tier) and resolved at runtime by `McaTraitFallbackTest` |
| Compile-only API jar still links for an outside consumer | ✅ | `verifyApiJar` (68 classes, every export present, no resources) + `verifyApiJarLinks` (a consumer compiled against the api jar **alone**) + `verifyApiJarLinkage` (a 0.5.0-era consumer compiled against the `b70f320` api jar and run against today's classes) |
| A save from a future format is not rewritten | ✅ | `SavedDataTest` / `GoldenSavedDataTest`: the store latches read-only, `setDirty` is inert, and the original bytes are written back verbatim |
| Protocol version moved with the wire format | ✅ | `"5"` → `"6"` for 0.6.0's profile subpayload, asserted by nothing automatic — the registrar compares by equality, so the pairing gates in §1 below are the only real check. Note the Forge line went `"4"` → `"5"` for the same release; the two lineages are separate and were never wire-compatible |
| Production runtime matrix | ⬜ | **this document** |

### What the automated tests now pre-cover

The pre-release review and the 1.21.1 port added suites that pre-cover parts of this matrix, which
narrows — but does not replace — the manual pass:

- **§2 functional scenarios (partially):** the whole `ReputationService` transaction — ordering,
  dedupe, unwitnessed drop-vs-retain, listener/mirror containment, set/add exactness at the clamp,
  tier high-water seeding, and legacy-import dry-run purity and events (`ReputationServiceTest`);
  pruning at the clamp and cap-sweep behaviour (`PruningTest`, `SavedDataTest`).
- **§3 exploit resistance (partially):** dedupe replay, resolution replay/ratchet
  (`ReputationServiceTest`, `ResolutionTest`, `DedupeTest`); snapshot request pacing and timeout
  (`RequestThrottleTest`); packet bounds on **both** encode and decode, including rejection one over
  every limit (`SnapshotPacketTest`).
- **§1 static checks (partially):** command-tree parsing incl. the `/mcarep` redirect and the
  community argument type (`CommandTreeTest`); two-way lang parity (`LangParityTest`); shipped
  content and validator severities (`ContentValidationTest`).
- **World migration (partially):** the saved-data half is covered by the golden fixture. What is
  *not* covered is the vanilla world upgrade around it — chunk conversion, MCA's own data, and the
  interaction between the two. §2 below still gates the release.

Installation-combination testing, in-game combat attribution, UI behaviour, performance, and log
review remain manual-only and still gate the release.

## Building the artifacts

Build this repository **first** — both companions compile against its class output:

```bash
cd MCAReputation_1.21.1   && ./gradlew build
cd ../MCAQuests_1.21.1    && ./gradlew build
cd ../MCAConversations_1.21.1 && ./gradlew build
```

Java 21 is required; the foojay toolchain resolver provisions it if `JAVA_HOME` is older. Take the
jars from each `build/libs/` — they are the distributable artifacts as built, with no reobfuscation
step. Record the exact filenames and hashes below.

| Artifact | File | SHA-256 |
|---|---|---|
| MCA: Reputation | `mcareputation-0.6.0.jar` | `46f9981050a58d7512a3a277fd751163501c1a5444e46016eb1d8f5d9bd9c9b8` |
| MCA: Reputation API jar | `mcareputation-0.6.0-api.jar` | `4379d6d8a3a5828d0e57018ac952cf6c1d62440f0e646e370d93943fb37e901f` |
| MCA: Quests | `mcaquests-1.1.0.jar` | (to be recorded) |
| MCA: Conversations | `mcaconversations-neoforge-2.0.0+1.21.1.jar` | (to be recorded) |
| MCA Reborn | `mca-neoforge-7.7.36-beta.3+1.21.1.jar` | `de4763d34a41cb84ffa392b87cdb23191beddda2323b56552a1a2fcd7c436fc3` |

Environment to record with the results: Minecraft `1.21.1`, NeoForge `21.1.249`, Java `21`, plus the
tester and date.

---

## 1. Installation combinations

Every row must reach the main menu, load a world, and produce no ERROR attributable to these mods.

| # | Combination | Client | Dedicated server | Notes |
|---:|---|:---:|:---:|---|
| 1 | NeoForge + MCA + Reputation | ⬜ | ⬜ | the baseline row |
| 2 | NeoForge + Reputation, **no MCA** | ⬜ | ⬜ | must be a clean "missing required dependency: mca" from the loader, never a linkage crash |
| 3 | MCA + Quests only | ⬜ | ⬜ | must behave exactly as it does without Reputation |
| 4 | MCA + Conversations only | ⬜ | ⬜ | must behave exactly as it does without Reputation |
| 5 | MCA + Reputation + Quests | ⬜ | ⬜ | |
| 6 | MCA + Reputation + Conversations | ⬜ | ⬜ | |
| 7 | MCA + Quests + Conversations, no Reputation | ⬜ | ⬜ | |
| 8 | MCA + all three | ⬜ | ⬜ | |
| 9 | MCA + all three + FTB Quests stack | ⬜ | ⬜ | |
| 10 | Dedicated server + matching clients for 5–9 | — | ⬜ | |
| 11 | 0.5.0 dedicated server + **0.4.1** client, and the reverse | — | ⬜ | must be refused at the handshake on `PROTOCOL_VERSION` (`"5"` vs `"4"`), never a join followed by a snapshot decode error |
| 12 | 0.6.0 dedicated server + **0.5.0** client, and the reverse | — | ⬜ | must be refused at the handshake on `PROTOCOL_VERSION` (`"6"` vs `"5"`). This is the 0.6.0 pairing gate and the one row the profile subpayload makes load-bearing: a 0.5.0 client that completed the handshake would read the new profile panes as the tail of a `SelectedDetail` it thinks it understands |

For rows 3, 4 and 7, confirm the log line stating MCA: Reputation is not installed, and confirm no
`NoClassDefFoundError` anywhere.

## 2. Functional scenarios

| Scenario | Expected | ⬜ |
|---|---|:---:|
| Two villages with the same numeric id in different dimensions | Standing kept entirely separate | ⬜ |
| Two players build different standing in one village | Each sees only their own; A's deed never moves B's score | ⬜ |
| Witnessed assault | Standing falls; witnesses recorded; the deed appears in the ledger | ⬜ |
| Unwitnessed assault | Standing does not move, and the player is not told one way or the other | ⬜ |
| Killing after an assault | Total is the killing's figure (−40), not the sum (−48); the assault line remains at zero | ⬜ |
| **Lethal-hit event order (port-critical)** | `LivingDamageEvent.Post` must fire **before** `LivingDeathEvent` for a lethal player hit. The fold/link/rollback algorithm depends on seeing the precursor assault before the death transaction. If this environment fires them the other way round, the death handler must be refactored to derive the lethal assault atomically — do not accept −48 where −40 is intended | ⬜ |
| Mitigated chip damage | A hit fully absorbed by armour or enchantments records **no** deed: the threshold is compared against `getNewDamage()`, the health actually lost | ⬜ |
| Unwitnessed killing | Hidden, zero-contribution history; no public change | ⬜ |
| Self-defence | Reduced penalty, `self_defence` recorded in context | ⬜ |
| Sustained beating | One incident with accumulated damage, not one per damage tick | ⬜ |
| Villager rescued | Standing rises by 6; credited once per bucket, so a second kill in the same bucket does not double-credit | ⬜ |
| Villager cured, player online | Standing rises by 15; the curing player earns the credit | ⬜ |
| Villager cured, player offline | Nothing happens; a logged-out curer earns no standing | ⬜ |
| Raid repelled | Standing rises by 20; effect refresh does not double-credit, keyed by raid id | ⬜ |
| Player killed in village, off by default | Standing and ledger do not move | ⬜ |
| Player killed in village, enabled | Standing falls by 12 (killer's perspective) when a player is killed inside a village | ⬜ |
| Per-villager opinion, eyewitness | Standing screen shows the breakdown for a specific villager the player has standing with | ⬜ |
| Per-villager opinion, distant resident | A villager distant from the deed sees it later once the rumour spreads, and the score reflects it | ⬜ |
| Relog and full restart | Score, tier, titles, and ledger all persist | ⬜ |
| Village renamed | Display updates; identity and standing unchanged | ⬜ |
| Village deleted | History retained with the last cached name | ⬜ |
| Rumour spread | A distant resident does not know immediately, and does know later | ⬜ |
| Decay over several days | Contribution steps down as authored, and stops at zero | ⬜ |
| `/time set` backwards then forwards | No contribution is returned | ⬜ |
| Quest with a `reputation.complete` block | Applies once, to the completing player | ⬜ |
| Quest with only the legacy `village_reputation` reward | Applies once, translated to a generic completion | ⬜ |
| Quest with both | The block wins; the legacy reward does not also apply | ⬜ |
| Quest failure and abandonment without an authored outcome | Nothing happens | ⬜ |
| Project phase and completion | Each contributor credited once, offline ones included | ⬜ |
| Project with `sponsor_village` reward target | Credited to every participant, with the one-time warning | ⬜ |
| Situation resolution | Credited to the resolving player only | ⬜ |
| FTB reputation task and reward | Reads and writes this player's standing; rechecks on change | ⬜ |
| Restitution quest | `resolve_incident` softens the original; `record_incident` adds the positive deed | ⬜ |
| Repeating a restitution quest | The second run does not soften it again | ⬜ |
| Gossip | Personality-voiced, told once per teller/listener, respects the rumour delay | ⬜ |
| Private bad relationship, high public standing | Dialogue reflects both, and they do not overwrite each other | ⬜ |
| Restitution then talking to the victim | Public standing improves; the victim's private disposition does not reset | ⬜ |
| Every subsystem disabled in config | Still playable; nothing is deleted | ⬜ |
| `/reload` with a broken datapack, lenient | Only the bad definition is skipped | ⬜ |
| `/reload` with a broken datapack, strict | The swap is refused and the previous definitions stay live | ⬜ |
| Scoreboard objective set to display slot | Scoreboard list appears once; selecting it shows the village leaderboard | ⬜ |
| Tab-list suffix on tier crossing | When a player reaches a new tier, their suffix in the tab list updates | ⬜ |
| `/mcareputation export` | Command succeeds; the json file can be read back and contains the expected data | ⬜ |
| `/mcareputation top here` | Lists top players in the nearest village by standing | ⬜ |
| `/mcareputation community here decay off` survives restart | The decay setting persists across server restart | ⬜ |
| Advancement criterion with `mcareputation:standing` predicate | Loot only granted if the predicate condition matches | ⬜ |
| Advancement fires on `mcareputation:tier_reached` | Advancement grants when a player's tier changes in the specified community | ⬜ |
| Install into a pre-Reputation Quests world | Eligible players inherit their balance once, as a baseline | ⬜ |
| **Standing screen: profile header** | Two new lines above the ledger — how well known the player is in the selected village, and what for, in the recognition ladder's own words. Neither appears for a village nobody has interacted with | ⬜ |
| **Standing screen: Details expansion** | A `Details` button beside the village arrows, collapsed on every open, expanding to at most eight facet rows inside the scrollable list. Keyboard-reachable and narrated; expanding sends no packet | ⬜ |
| **Standing screen: selection change clears the profile** | Turning a page or selecting another village blanks both profile lines and hides the Details button until the reply lands — never shows the previous village's traits under the new name | ⬜ |
| **Standing screen: observer pane** | Opened from a villager, the screen also says what *that villager* knows the player for. A villager inside its own rumour delay says it has heard nothing; its pane is never silently the village's wider view | ⬜ |
| **Profile labels come from the server's pack** | On a dedicated server, edit a facet's `name` in the server's datapack only, `/reload`, and reopen the screen: the client shows the **server's** word, not the one it shipped with | ⬜ |
| **Repeat credit** | Rescue the same village's villagers repeatedly: the shipped schedule pays 100% / 100% / 50% / 25% / 0% across a 14-day window, and rotating beneficiaries lowers the ceiling rather than resetting the allowance | ⬜ |
| **Wrongdoing is never discounted** | Repeat an adverse deed: every instance costs its full authored amount, whatever the repetition | ⬜ |
| **Facet-aware opinion** | Two villagers with identical knowledge but different MCA personalities report different opinions of the same player, within `maxFacetOpinionAdjustment`; what reaches a Conversations Trust/Respect check stays inside ±8 | ⬜ |
| **Profiles switched off mid-world** | `enableProfiles=false`, `/reload`: the screen's profile lines disappear, the ledger and standing are untouched, and nothing is deleted. Play for several in-game days, switch it back on, `/reload`: no burst of catch-up fading, and `/mcareputation debug profile` shows the frozen interval was not charged | ⬜ |
| **Legacy enrichment** | On a world upgraded from 0.5.0, `/mcareputation debug profilemigration` reports coverage `migrating` with a cursor, then `partial_legacy` once the periodic sweep finishes. An old rescue then shows recognition and bravery, and an old killing shows recognition and violence but nothing evaluative | ⬜ |
| **Retention protects profile evidence** | Fill one community's ledger to its cap with records still holding live profile evidence: the next deed is refused with a capacity reason rather than pruning the evidence, and the same ledger prunes and admits again once a lifetime expires | ⬜ |
| **The four new debug commands** | `debug profile`, `debug credit`, `debug profileincident`, `debug profilemigration` all answer at permission 2 and are refused below it. Running `debug profile` twice in a row must not move any figure it prints — it is an inspecting read | ⬜ |
| Second login after migration | Nothing is added again | ⬜ |
| Remove Reputation | Quests reads its mirrored fallback; standing is what it was | ⬜ |
| Reinstall Reputation | Canonical data resumes; no duplication | ⬜ |

## 2b. World migration from Minecraft 1.20.1

New for the NeoForge port, and the row most worth doing carefully: the golden fixture proves the
serializer round-trips, but only a real world proves the serializer runs on the data a real world
actually contains, in the presence of vanilla's own chunk upgrade and MCA's own saved data.

**Use a copy. Back up the copy.** The vanilla upgrade is one-way; there is no going back to 1.20.1.

| # | Step | Result |
|---:|---|:---:|
| 1 | On 1.20.1, record scores, tiers, titles and a few representative incidents for at least two players and two villages — including two villages with the same numeric id in different dimensions | ⬜ |
| 2 | Copy the world, then back up the copy separately | ⬜ |
| 3 | Open the copy in the 1.21.1 / NeoForge instance and let the vanilla world upgrade run | ⬜ |
| 4 | Reputation saved data loads exactly once, with no "future format" warning and no corruption-containment log line | ⬜ |
| 4b | The save is now format 3, with pre-upgrade profiled deeds marked for enrichment; after a few minutes of play `/mcareputation debug profilemigration` reports the pass finished and coverage `partial_legacy`. No score, tier, or title moved at any point | ⬜ |
| 5 | Every score, dimension/village id, title, incident, status, context, witness set, dedupe entry and tier high-water mark matches step 1 | ⬜ |
| 6 | Trigger one new deed, save, exit **fully**, restart, and confirm both the old and the new state persist | ⬜ |
| 7 | Rename a village; the cached display name updates while the community identity (and therefore the standing) does not | ⬜ |
| 8 | Repeat steps 3–6 on a brand-new 1.21.1 world, to catch initialisation paths that existing data hides | ⬜ |
| 9 | Confirm the existing `mcareputation-common.toml` and `mcareputation-client.toml` were read as-is, with no keys reset to defaults | ⬜ |

If step 5 shows a reset, **stop and restore the backup** rather than playing on. Reputation is written
on the same autosave path as MCA's own village data; a reset is a symptom to diagnose, not to accept.

---

## 3. Security and exploit scenarios

| Attempt | Expected | ⬜ |
|---|---|:---:|
| Spam the snapshot request packet | Rate limited to one per 10 ticks; no ledger walk per request | ⬜ |
| Duplicate quest completion / turn-in packet | Dedupe key makes the second a no-op | ⬜ |
| Repeat the same conversation apology | Recorded once; further clicks do nothing | ⬜ |
| Claim a context villager far away or in another dimension | Rejected server-side | ⬜ |
| Request another player's data | Refused | ⬜ |
| Malformed packet with a negative village id | Clamped; never trusted without server resolution | ⬜ |
| Datapack with a huge delta, context, or retention | Clamped or rejected by validation | ⬜ |
| Datapack with a private incident carrying a score | Rejected at parse time | ⬜ |
| Maximum incident ledger, then open the screen | Bounded packet; no disconnect; no multi-second delay | ⬜ |
| Kill a villager with an arrow, a splash potion, and a tamed wolf | All three attributed to the player | ⬜ |
| Damage a villager below `minimumIncidentDamage` repeatedly | No incident | ⬜ |

## 4. Performance

| Measurement | Target | Result |
|---|---|---|
| Idle server, nobody online | No measurable tick cost attributable to this mod | |
| One assault with 50 loaded villagers | Witness selection completes without a tick stall | |
| Screen open at the maximum retained ledger | No multi-second delay, no oversized packet | |
| Save growth over a long session | Bounded by the configured caps, not by playtime | |

## 5. Log review

After each combination, confirm:

- INFO on successful optional integration activation, migration summary, and datapack reload summary
- WARN only for genuinely invalid or ambiguous data, or bounded pruning
- ERROR only where a bridge genuinely failed — and the game kept running
- No player NBT, chat content, or server paths in any log line
- No line emitted per tick

## Sign-off

| | |
|---|---|
| Tested by | |
| Date | |
| MCA versions | |
| Result | |
