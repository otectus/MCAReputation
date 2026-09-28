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
   (third-party) is the exception: `[…,0.9)`.
3. Sibling APIs are consumed through vendored, hash-pinned compile-only jars (`libs/api/`,
   `gradle/sibling-apis.properties`, `verifySiblingApis`); adapters live in one package per sibling,
   load by name after `ModList.isLoaded`, and a static-link test isolates them. The `apiJar` tasks are
   reproducible, so a rebuild from unchanged sources keeps every pin valid.
4. One MCA probe fleet everywhere: `7.6.20`, `7.6.26`, `7.7.0-beta.2`, `7.7.1-alpha.2`, `7.7.1-beta.1`,
   `7.7.1-beta.2` (`+1.20.1`).
5. Every consumer of MCA: Reputation drift-checks its capability strings at startup.

## Verified tuple — Forge 1.20.1 (2026-09-27, family integration pass)

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
