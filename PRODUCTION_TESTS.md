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
