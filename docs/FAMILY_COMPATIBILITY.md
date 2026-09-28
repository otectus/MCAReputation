# MCA add-on family — compatibility matrix

The single record of which versions of the family were built and tested together, and of the rules
every repository follows. Each repository's `CLAUDE.md` restates the rules; this file holds the tuple.
Versions live in each repository's `gradle.properties`; the numbers here are the ones those files held
when the tuple was verified.

## The rules

1. MCA Reborn is the only mandatory dependency, `[7.6,8)`, bound by name across its four package roots
   (`forge.net.conczin.mca`, `forge.net.mca`, `net.conczin.mca`, `net.mca`). Architectury is never
   declared mandatory.
2. Companion ranges carry a lower bound only. Forge enforces an optional range whenever the companion is
   present, and every binding probes and degrades, so an upper bound can only refuse a launch. Townstead
   (third-party) is the exception: `[…,0.9)`. A binding audited for one exact release gates it at
   runtime, not in `mods.toml`: Ultima Kingdoms declares Recruits `[1.15.2,)` and its mixin plugin and
   adapters stay off for any other version (`ModsTomlRangesTest` rejects an exact pin).
3. Sibling APIs are consumed through vendored, hash-pinned compile-only jars (`libs/api/`,
   `gradle/sibling-apis.properties`, `verifySiblingApis`); adapters live in one package per sibling,
   load by name after `ModList.isLoaded`, and a static-link test isolates them. The `apiJar` tasks are
   reproducible, so a rebuild from unchanged sources keeps every pin valid.
4. One MCA probe fleet everywhere: `7.6.20`, `7.6.26`, `7.7.0-beta.2`, `7.7.1-alpha.2`, `7.7.1-beta.1`,
   `7.7.1-beta.2` (`+1.20.1`), replayed by every add-on's binding probe test, MCA: Mob Compatibility's
   included since 2026-09-28. The NeoForge ports of MCA: Crime and MCA: Mob Compatibility probe the
   1.21.1 line from its floor: `7.7.0` (Modrinth id `EGdYukBI`), `7.7.13`, `7.7.22`, `7.7.33` and
   `7.7.36-beta.3`. The Quests port probes `7.7.22` and `7.7.36-beta.3` although it admits `[7.7,8)`, and
   the Conversations port probes `7.7.33` and `7.7.36-beta.3` for `[7.7.13,8)`; widening both to the
   five-build list is still open. The Reputation port keeps its audited-member binding instead.
5. Every consumer of MCA: Reputation drift-checks its capability strings at startup.
6. Load order runs from provider to consumer: MCA, then MCA: Reputation, then MCA: Quests, MCA:
   Conversations, MCA: Crime and MCA: Mob Compatibility, then Ultima Kingdoms. A mod declares a
   companion it reads at setup `AFTER` and a companion that consumes it `BEFORE` (or `NONE`). Two mods
   that each declare the other `AFTER` form a cycle Forge's mod sorter refuses ("Mod Sorting failed.
   Detected Cycles"), and a server with both installed does not start; MCA: Crime and Ultima Kingdoms
   did exactly that until 2026-09-28. Crime's `ModsTomlOrderingTest` and Ultima's `ModsTomlRangesTest`
   pin that pair.
7. MCA: Mob Compatibility mirrors MCA: Crime's effects from a bridged guard's hidden proxy onto the
   guard by registry id, and the ids are a contract: exactly `mcacrime:restrained` and
   `mcacrime:sand_blinded`, the effects MCA: Crime 0.7.5 registers (`effect/CrimeEffects`).
   `ProxyEffectsContractTest` pins the list; add an id only after the Crime build it targets registers it.

## Verified tuple — Forge 1.20.1 (2026-09-28, audit remediation)

The versions are those of the 2026-09-27 tuple below; this pass fixed the findings of the 2026-09-28 family
audit (`MCAPlans/`) and re-ran every check from a clean test output.

| Add-on | Version | Checks that passed |
|---|---|---|
| MCA: Reputation | 0.6.1 (unreleased) | `cleanTest check`: 842 tests, 0 failed, 1 skipped, incl. `McaReflectProbeTest` over the six-build fleet and `verifyApiJar` |
| MCA: Quests | 1.7.1 (unreleased) | `cleanTest check`: 1,498 tests, 0 failed, 16 skipped; `townsteadProbeTest` against Townstead 0.7.6 (4 tests) and `capitalsProbeTest` against MCA Capitals 1.3.7 (3 tests) |
| MCA: Crime | 0.7.5 (unreleased) | `cleanTest check`: 2,645 tests, 0 failed, 16 skipped; `townsteadProbeTest` against Townstead 0.7.6 (12 tests) |
| MCA: Conversations | 1.8.0 (branch) | `cleanTest check`: 1,729 tests, 0 failed, 6 skipped; `verifyGeneratedConversationContent`, `verifyVoiceOverlays`; `townsteadProbeTest` (6 tests) and `capitalsProbeTest` (2 tests) against the same jars |
| MCA: Mob Compatibility | 0.2.2 (unreleased) | `cleanTest check`: 74 tests, 0 failed; `runGameTestServer`: all 29 required tests, and all 31 with `-PwithCrime` against the MCA: Crime 0.7.5 jar; `scripts/parity.py` in step (123 files) |
| Ultima Kingdoms | 0.1.1 (unreleased) | `cleanTest test`: 135 tests, 0 failed, 1 skipped |

**Dedicated server, all together.** A Forge 47.4.10 dedicated server with MCA Reborn 7.6.26 (plus
Architectury 9.2.14 and GeckoLib 4.8.4), the five add-ons, Ultima Kingdoms 0.1.1, Townstead 0.7.6,
Recruits 1.15.2, Guard Villagers 1.6.19 and Patchouli first refused to start (the Crime/Ultima ordering
cycle in rule 6). With that fixed it starts, stops and restarts cleanly on one world, and logs MCA:
Reputation's capabilities to MCA: Crime (exemptions and the profile features live), Crime holding
detection authority, both mirrored effects, and Ultima Kingdoms' standing-journal consumer;
`/reload`, `/mcareputation standing consumers` and `/crime debug integrations` answer from the console.
Townstead 0.7.6 logs six "Attempted to load class net/minecraft/client/Minecraft for invalid dist"
errors on a dedicated server with or without the family installed; startup continues.

## Verified tuple — Forge 1.20.1 (2026-09-27, family integration pass)

(MCA: Crime's `ultima_kingdoms` ordering below is the one corrected to `BEFORE` on 2026-09-28.)

| Add-on | Version | Compiles against | Declares (optional unless noted) | Build checks that passed |
|---|---|---|---|---|
| MCA: Reputation | 0.6.1 (unreleased) | — (provider) | `mcaquests`, `mcaconversations`, `mcacrime`, `ultima_kingdoms` BEFORE `[0,)` | `check` (incl. `verifyApiJar`, `McaReflectProbeTest`), `apiJar`, `build` |
| MCA: Quests | 1.7.1 (unreleased) | Reputation 0.6.1 api jar, Crime 0.7.5 api jar | `mca` `[7.6,8)` mandatory; `architectury` optional; Townstead `[0.7.5,0.9)` | `test`, `apiJar`, `build` |
| MCA: Crime | 0.7.5 (unreleased) | Reputation 0.6.1 api jar, Quests 1.7.1 api jar | `mcareputation` AFTER; `mcaquests`, `townstead`, `ultima_kingdoms` AFTER `[0,)` | `check`, `apiJar`, `build` |
| MCA: Conversations | 1.8.0 (branch) | Quests 1.7.1, Reputation 0.6.1, Crime 0.7.5 api jars | Townstead `[0.7.5,0.9)` | `check`, `verifyGeneratedConversationContent`, `verifyVoiceOverlays`, `build` |
| MCA: Mob Compatibility | 0.2.2 (unreleased) | nothing from the family (by design) | Guard Villagers; MCA | `check`, `build`; `scripts/parity.py` in step with the NeoForge port |
| Ultima Kingdoms | 0.1.1 (unreleased) | nothing (reflection) | quests `[1.6.5,)`, crime `[0.7.5,)`, reputation `[0.6.0,)`, townstead `[0.7.6,0.9)` | `build` |

Capability handshake: Reputation API v1 with the stable feature set of 14 strings, the newest being
`incident_exemptions_v1` (`CoreIncidentExemptions`, registered by Crime's `ReputationExemptionBridge`).

## NeoForge 1.21.1 ports (`1.21.1 Ports/`)

Each port keeps a ledger of what it does not mirror (2026-09-28): `docs/PORT_PARITY.md` in the Quests,
Reputation and Crime ports; `PARITY.md`, `PORT_STATUS.md` and `tools/verify_release_parity.py` in the
Conversations port; `parity-manifest.txt` and `scripts/parity.py` for MCA: Mob Compatibility. The Crime
port's MCA range is now `[7.7,8)` instead of an exact pin, and the Quests port loads four tags it had kept
under 1.20.1 directory names. Port suites on 2026-09-28: Quests 1,536 tests, Conversations 1,772, Crime
2,664, Mob Compatibility 74 plus 31 GameTests with the NeoForge MCA: Crime jar, all with 0 failures.


Every change above is mirrored, and every port's `build` passes (2026-09-27). Differences that are deliberate: the Reputation port stays on its 0.6.0
line (the Forge 0.6.1 standing journal is not ported; the exemption API, switches, read-model exports and
the Standing button fix are); the Quests and Crime ports compile sibling adapters against the sibling
port's class output, as those builds already did, but a missing sibling is now a build failure instead of
a silent exclusion; the Conversations port vendors the ports' api jars. The Reputation port keeps its own
audited-member binding (`AUDITED_MEMBERS`, `McaBinaryAbiTest`) rather than the Forge probe fleet.

## Out-of-family consumers

- **MCA Colonies** pins Conversations 1.6.3, Quests 1.6.4, Reputation 0.5.0 and Crime 0.6.4; its
  companion patches were never merged, and every Colonies bridge fails its class probe against the
  current companions. Disregarded for now by decision.
- **Stoneborn** (resource pack) targets the Quests 1.6.5, Crime 0.7.4, Reputation 0.5.0 and
  Conversations 1.7.1 texture layouts; re-check after each release.

## Not verified by this pass

Runtime scenarios on a production server: a bridged guard assaulted with Mob Compatibility, Crime and
Reputation installed (one incident, with a community); a wanted player pursued by a bridged guard; the
thief-combat exemption with Crime's authority handed back; Townstead 0.8 beside Conversations and
Ultima; the Standing button after Interact → Back; guard refusal and the jailed pause in Quests;
Conversations' crime conditions with and without Reputation; a clean-clone build of Crime and Quests.

Still open after 2026-09-28, because each needs a connected player or a jar nobody publishes: a bounty
contract held across a restart and failed when the board empties; profiles switched on followed by a
`/reload`, seen in MCA: Crime's handshake line; the Reputation save staying bounded with Ultima Kingdoms
removed; a `talk_about` quest's text in the MCA: Quests journal; chat-mode local chat during a `/reload`;
Townstead 0.8 (`api.v1`); a Recruits release newer than 1.15.2 (none exists yet); any test task in the
NeoForge Reputation port.
