# MCA: Reputation — production verification matrix

> **Status: NOT YET RUN.** This file is the checklist that must pass before 0.2.0 is tagged as
> released, not a record of it having passed.

## Why this file exists

MCA Reborn's Forge mixins ship without a refmap and with hard-coded SRG names, so they only resolve in
a production (SRG) runtime. ForgeGradle's `runClient` and `runServer` therefore do **not** exercise
real MCA behaviour, and a green dev run proves very little about a mod that links MCA internals.

Both companion mods in this suite already document that constraint, and it applies here for the same
reason. Spec Appendix D puts it plainly: "production verified" means built, reobfuscated jars were
tested in a production-style instance — not that compilation and unit tests passed.

## What has passed so far

| Gate | Status | Evidence |
|---|---|---|
| Phase 0 audit and reconciliation | ✅ | [IMPLEMENTATION_NOTES.md](IMPLEMENTATION_NOTES.md) |
| MCA 7.6 / 7.7 signature parity | ✅ | `javap` over both deobf jars; every consumed signature byte-identical |
| MCA: Reputation compiles and unit tests | ✅ | 240+ tests, 0 failures |
| MCA: Quests compiles and regression suite | ✅ | all green, including the dimension-keyed title store and Journal-link packets |
| MCA: Conversations compiles and regression suite | ✅ | all green, including the gossip merge, the standing topic, and chat-intent parity |
| Reobfuscated jar contains no shaded companion classes | ✅ | `checkJarContents` gradle task, run as part of `build` |
| No mixins to lint | ✅ | the mod ships none; asserted by `OptionalClassloadTest` |
| Production runtime matrix | ⬜ | **this document** |

### What the automated tests now pre-cover

The pre-release review added suites that pre-cover parts of this matrix, which narrows — but does not
replace — the manual pass:

- **§2 functional scenarios (partially):** the whole `ReputationService` transaction — ordering,
  dedupe, unwitnessed drop-vs-retain, listener/mirror containment, set/add exactness at the clamp,
  tier high-water seeding, and legacy-import dry-run purity and events (`ReputationServiceTest`);
  pruning at the clamp and cap-sweep behaviour (`PruningTest`, `SavedDataTest`).
- **§3 exploit resistance (partially):** dedupe replay, resolution replay/ratchet
  (`ReputationServiceTest`, `ResolutionTest`, `DedupeTest`); snapshot request pacing and timeout
  (`RequestThrottleTest`); packet bounds (`SnapshotPacketTest`).
- **§1 static checks (partially):** command-tree parsing incl. the `/mcarep` redirect and the
  community argument type (`CommandTreeTest`); two-way lang parity (`LangParityTest`); shipped
  content and validator severities (`ContentValidationTest`).

Installation-combination testing, performance, log review, and everything that needs a real SRG
runtime remain manual-only and still gate the release.

## Building the artifacts

```bash
cd MCAReputation      && ./gradlew build      # build this first; the companions compile against it
cd ../MCAQuests       && ./gradlew build
cd ../MCAConversations && ./gradlew build
```

Take the reobfuscated jars from each `build/libs/`. Record the exact filenames and hashes below.

| Artifact | File | SHA-256 |
|---|---|---|
| MCA: Reputation | | |
| MCA: Quests | | |
| MCA: Conversations | | |
| MCA Reborn | | |

---

## 1. Installation combinations

Every row must reach the main menu, load a world, and produce no ERROR attributable to these mods.

| # | Combination | Client | Dedicated server | Notes |
|---:|---|:---:|:---:|---|
| 1 | MCA 7.6.20 + Reputation | ⬜ | ⬜ | |
| 2 | MCA 7.7.0-beta.2 + Reputation | ⬜ | ⬜ | |
| 3 | MCA + Quests only | ⬜ | ⬜ | must behave exactly as 1.0.0 did |
| 4 | MCA + Conversations only | ⬜ | ⬜ | must behave exactly as 1.0.0 did |
| 5 | MCA + Reputation + Quests | ⬜ | ⬜ | |
| 6 | MCA + Reputation + Conversations | ⬜ | ⬜ | |
| 7 | MCA + Quests + Conversations, no Reputation | ⬜ | ⬜ | |
| 8 | MCA + all three | ⬜ | ⬜ | |
| 9 | MCA + all three + FTB Quests stack | ⬜ | ⬜ | |
| 10 | Dedicated server + matching clients for 5–9 | — | ⬜ | |

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
| Unwitnessed killing | Hidden, zero-contribution history; no public change | ⬜ |
| Self-defence | Reduced penalty, `self_defence` recorded in context | ⬜ |
| Sustained beating | One incident with accumulated damage, not one per damage tick | ⬜ |
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
| Install into a pre-Reputation Quests world | Eligible players inherit their balance once, as a baseline | ⬜ |
| Second login after migration | Nothing is added again | ⬜ |
| Remove Reputation | Quests reads its mirrored fallback; standing is what it was | ⬜ |
| Reinstall Reputation | Canonical data resumes; no duplication | ⬜ |

## 2b. Standing that does not move, and deliveries that do not register

The 0.3.0 report — *"no matter what I do I have '25 more to acquaintance' and my rank is 'stranger',
additionally delivery quests don't seem to be registering"*. See [DIAGNOSIS.md](DIAGNOSIS.md); this is
the script that reproduces it and confirms the fix. **Run it on a dedicated server with a separate
client as well as in singleplayer**: the display half is a server-side selection bug whose symptom is
identical in both, and only a second client proves the sync leg carries the corrected selection.

Set up once: a world with **two** MCA villages within 128 blocks of each other (the default
`defaultVillageSearchRadius`), so "the village you are standing in" and "the village that gave you the
quest" can differ. Note both ids from `/mcareputation debug community`.

### The observability gate — run this first, and after every step below

| Step | Expected | ⬜ |
|---|---|:---:|
| `/mcareputation debug standing` | Prints raw score, baseline, incident count, active tier and its threshold, next tier and the **exact remaining amount**, the store being read, the community the screen would select, whether you have a record there, registered mirrors, and the MCA binding status | ⬜ |
| `/mcareputation debug standing <player> <dimension>/<villageId>` | Same, for another player and an explicit community; an operator can answer "is the number moving?" without a source checkout | ⬜ |
| A community with no record | Says `NO RECORD … the screen shows a synthesised floor-tier detail here (score 0), which is not a stored value` — the line that distinguishes "0 stored" from "nothing stored" | ⬜ |

### S1 — standing moves, and the screen shows the standing you have

| Step | Expected | ⬜ |
|---|---|:---:|
| Complete any bundled MCA: Quests quest | Standing with the giver's village rises; `debug standing` shows a non-zero score and a shrinking "remaining"; the action-bar line appears | ⬜ |
| Same, with `debugLogging = true` in MCA: Quests | One DEBUG line naming the quest when a quest genuinely declares no outcome — never silence | ⬜ |
| Repeat until the score reaches 25 | Tier flips to Acquaintance exactly at 25, the toast fires once, and the caption becomes "50 more to Friend" | ⬜ |
| Walk to the **other** village, one you have no record with, and open the standing screen | It shows the village you have standing in — **not** "Stranger, 25 more to Acquaintance". This is the regression | ⬜ |
| With standing in exactly one village, stand in the other and open the screen | The `<` `>` selector arrows are present, and one press reaches the village you have standing with | ⬜ |
| Click a villager of the unknown village and press **Standing** | *Now* it says Stranger — an explicit look at that village is the one case where the floor-tier detail is the honest answer | ⬜ |
| Relog, then restart the server | Score, tier and remaining are unchanged | ⬜ |
| **Dedicated server, second client:** both players complete quests for the same village | Each sees only their own figure; neither sees the other's; both update without reopening the screen twice | ⬜ |

### S2 — a delivery credits the moment it is handed over

| Step | Expected | ⬜ |
|---|---|:---:|
| Accept a `deliver_to_villager` quest with `"recipient": {"mode": "self"}` (e.g. *last banner home*), then right-click the giver holding the payload | Objective goes 1/1 within a second; the payload is consumed | ⬜ |
| Accept *a warm meal* / *a meal for mother* (`mode: family`, `require: nearby`) | The quest log names the bound parent by name and village, and the highlight glows them | ⬜ |
| **Walk the parent well away from the giver** (or wait for them to wander), then hand over the bread | Objective goes 1/1. Before the fix this credited only while the parent stood within 12 blocks of the *giver* — i.e. never, once you had gone to find them | ⬜ |
| Accept the same quest when no relative can be bound (kill or remove the parent between the offer being drawn and accepting) | One WARN naming the quest and the relation, and the quest log shows a reason line instead of a silent 0/1 | ⬜ |
| Deliver to a **different** villager of the same relation | Nothing is credited and nothing is consumed | ⬜ |
| Relog mid-quest, then finish the delivery | Progress and the bound recipient both survive; the delivery still credits | ⬜ |
| **Dedicated server, second client:** both players run the same delivery quest from the same giver | Each binds their own recipient; one player's hand-over never credits the other's objective | ⬜ |

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
