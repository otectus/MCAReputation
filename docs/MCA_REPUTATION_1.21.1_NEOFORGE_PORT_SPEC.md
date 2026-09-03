# MCA: Reputation — Forge 1.20.1 to NeoForge 1.21.1 port specification

**Audience:** a coding agent implementing and validating the complete port  
**Source baseline:** [`96e82978a346ffc6b0d2e72d6c0350c4971612ca`](https://github.com/otectus/MCAReputation/tree/96e82978a346ffc6b0d2e72d6c0350c4971612ca) on `main`  
**Target:** Minecraft 1.21.1, NeoForge, Java 21  
**Audit date:** 2026-09-03  
**Scope:** implementation plan and acceptance contract; this document does not itself modify the mod

> Treat the pinned commit as the semantic reference. Port its behavior; do not opportunistically redesign the reputation model, persistence schema, commands, public contracts, or companion integration while changing loaders.

## 1. Outcome and definition of done

The finished artifact is a NeoForge-only MCA: Reputation release for Minecraft 1.21.1 that:

- builds and runs on Java 21 with a current 1.21.1 NeoForge toolchain;
- loads with the **NeoForge** MCA Reborn release labeled 7.7.36 (CurseForge file 8658484), not its Fabric sibling;
- starts on both a physical client and a dedicated server;
- preserves the 1.20.1 world data in `<world>/data/mcareputation.dat` without resetting, duplicating, or merging players or dimension-qualified villages;
- preserves every command, config option, datapack definition, score rule, incident rule, title, migration marker, public query/write contract, notification, screen route, and optional-integration behavior unless this specification explicitly says otherwise;
- uses NeoForge's custom-payload networking API and applies hard decode bounds;
- contains no Forge classes, no compile-time MCA classes, no client class reachable from dedicated-server initialization, and no shaded MCA/companion/Architectury classes;
- passes the migrated automated suite plus the production runtime matrix in this document; and
- ships correct `META-INF/neoforge.mods.toml` metadata and 1.21.1 GUI sprite resources.

The port is **not complete** when it merely compiles. It is complete only after an old 1.20.1 test world opens, the saved ledger is verified, the client/server packet path works on a dedicated server, the reflection bridge binds to the released MCA NeoForge jar, and the final jar inspection passes.

## 2. Non-negotiable behavioral invariants

These are the guardrails for every implementation choice.

1. **Server authority remains absolute.** The client may request its own snapshot and suggest an interaction entity or already-known community. It may never submit a score, delta, title, incident, status, witness, or trusted village identity.
2. **Community identity stays dimension-aware.** `CommunityKey(ResourceLocation dimension, int villageId)` remains the sole identity. Never replace it with a bare MCA village id.
3. **The overworld store remains canonical.** All dimensions continue to live in the overworld's `DimensionDataStorage` under `mcareputation.dat`.
4. **NBT format remains version 1.** The Minecraft method signatures change; the serialized data does not. Do not bump `FORMAT_VERSION` unless the NBT representation itself changes.
5. **Transactions retain ordering and idempotency.** Dedupe, clamping, incident creation/resolution, score recomputation, high-water marks, titles, events, mirrors, and feedback must occur in the same semantic order as the pinned source.
6. **Events remain post-commit, immutable, server-side, and non-cancellable.**
7. **Damage detection preserves meaning.** Use actual health damage, not pre-mitigation attack damage. Cancelled or zero-damage attacks must not become deeds.
8. **Optional integrations remain optional.** MCA: Quests and MCA: Conversations must not appear on the compile or runtime class path unless deliberately included for an integration test. Absence must be clean.
9. **MCA stays reflection-only.** No `import net.conczin.mca...` may enter production or test code. The released MCA binary, rather than only its source branch, is the final ABI truth.
10. **No client leakage.** A dedicated server must be able to load every common/network class without resolving `net.minecraft.client.*` or `dev.otectus.mcareputation.client.*`.
11. **Datapack strictness remains unchanged.** A malformed entry is isolated in lenient mode; strict mode refuses the swap and leaves the previous registry live.
12. **No data deletion on a feature toggle or mod removal.**

## 3. Audited baseline

The pinned repository contains:

| Area | Audited count / state |
|---|---:|
| Production Java | 85 files |
| JUnit test classes | 27 files |
| Runtime resources | 22 files |
| Current mod version | `0.3.0` |
| Current public API version | `1` |
| Current platform | Minecraft 1.20.1, Forge 47.4.10, Java 17 |
| Current network | Forge `SimpleChannel`, protocol `"2"`, five messages |
| Current persistent format | `ReputationSavedData.FORMAT_VERSION = 1` |
| Mixins | none |
| Direct MCA imports | none; `compat/McaReflect` binds by reflection |

The existing test suite and documentation report 274 tests in the 0.3.0 change set. Record the actual baseline result on a suitable Java 17/Forge checkout before editing if the environment permits. The audit environment used for this document had Java 17 and could not execute the new Java 21/ModDevGradle build, so no build result is claimed here.

### 3.1 Migration hotspots found in the source

| Hotspot | Current file(s) | Why a semantic port is required |
|---|---|---|
| Mod bootstrap/config/registries | `McaReputationMod.java`, `McaReputationConfig.java` | Constructor injection, config type, event buses, and lifecycle registration changed |
| Networking | `network/ReputationNetwork.java`, `ClientPacketHandler.java`, `client/ClientReputationData.java` | `SimpleChannel` and `NetworkEvent.Context` must become payload types, stream codecs, registrars, and payload contexts |
| Damage/ticks | `event/ReputationGameplayEvents.java`, `network/ReputationFeedback.java`, `client/ReputationClient.java` | Tick event classes changed; `LivingHurtEvent` no longer expresses the required 1.21.1 hook |
| Saved data | `state/ReputationSavedData.java` | `SavedData.Factory` and registry-aware save/load signatures changed |
| Public events | `api/event/*`, `reputation/ServiceContext.java`, `ReputationService.java` | NeoForge event base and bus replace Forge types; cancellation model differs |
| Data codecs/registries | `IncidentDefinition.java`, `ReputationTier.java`, `TitleDefinition.java` | `ExtraCodecs.COMPONENT` is gone; item lookup should use vanilla built-in registries |
| Resource identifiers | `McaReputation.java`, `BuiltinIncidents.java`, `ReputationTiers.java`, tests | `ResourceLocation` constructors are private in 1.21.1 |
| GUI | `GuiTextures.java`, `ReputationScreen.java`, `ReputationTierToast.java` | GUI sprites, background/input signatures, and toast background access changed |
| MCA ABI bridge | `compat/McaReflect.java`, `McaCompat.java`, `McaScreenCompat.java` | Target package is now the unrelocated `net.conczin.mca` NeoForge namespace |
| Metadata/resources | `META-INF/mods.toml`, `pack.mcmeta`, texture sheet | NeoForge metadata schema/path and modern GUI sprite metadata are different |

## 4. Target matrix and pinned references

Use these values for the first green port. They are an audited, coherent set as of the audit date; re-resolve them before release, but do not casually float individual pieces during implementation.

| Component | Target | Rationale |
|---|---|---|
| Minecraft | `1.21.1` | Requested target |
| Java language/toolchain | `21` | Required by Minecraft 1.21.1 |
| NeoForge | `21.1.249` | Current official 1.21.1 MDK pin at the audit date |
| NeoForge metadata floor | `[21.1.249,)` initially | Build and test the declared minimum; lower it only after testing that lower build |
| ModDevGradle | `2.0.146` | Current official 1.21.1 MDK pin |
| Parchment | `2024.11.17` for `1.21.1` | Matches the official MDK and MCA branch |
| Gradle wrapper | `9.2.1-bin` | Current official MDK wrapper |
| Foojay resolver | `1.0.0` | Current official MDK settings |
| MCA Reborn | NeoForge release labeled `7.7.36`, file id `8658484` | Current stable 1.21.1 NeoForge listing at the audit date; its published filename includes `7.7.36-beta.3+1.21.1` |
| MCA metadata range | exact resolved `[[mods]].version` initially | The listing, filename, and in-jar version may differ; inspect the jar and do not claim an untested range |
| JUnit | retain `5.10.2` first | Avoid mixing a test framework upgrade into the loader port |
| Port release | recommend `0.4.0` | Communicates a platform/API compatibility generation change |
| Public API | recommend `2` | Public event bytecode now depends on NeoForge event types; companions must recompile |
| Network protocol | `"3"` | The payload generation and wire registration are intentionally incompatible with Forge protocol 2 |

Pinned source references used by this audit:

- MCA: Reputation: `96e82978a346ffc6b0d2e72d6c0350c4971612ca`.
- MCA Reborn `1.21.1` branch: `575691bd6e09d4be2f828340683247dc2a2c4fdb`.
- NeoForge official 1.21.1 ModDevGradle MDK: `70d335c962ee8a773b38fb0690c7e7f30d1bafa6`.
- NeoForge `1.21.1` source inspected for event and payload signatures: `6d9e718cd4c3c9ed0cfb2cd80480d777ea5feed6`.

### 4.1 MCA runtime artifact warning

MCA 1.21.1 publishes loader-specific files. Do not let a Maven coordinate silently select the Fabric file. Prefer an immutable, known-NeoForge artifact during development:

```groovy
repositories {
    exclusiveContent {
        forRepository {
            maven {
                name = 'CurseMaven'
                url = 'https://www.cursemaven.com'
            }
        }
        filter { includeGroup 'curse.maven' }
    }
}

configurations {
    localRuntime
    runtimeClasspath.extendsFrom localRuntime
}

dependencies {
    // CurseForge file 8658484: [NeoForge 1.21.1] MCAR 7.7.36.
    localRuntime "curse.maven:minecraft-comes-alive-reborn-535291:${mca_curse_file}"
}
```

If retaining Modrinth Maven, resolve its immutable **NeoForge version/file id**, inspect the resolved jar name and `META-INF/neoforge.mods.toml`, and pin that id. Do not rely on a human version selector until this has been proved. Record the chosen jar's SHA-256 in `IMPLEMENTATION_NOTES.md` and `PRODUCTION_TESTS.md`.

## 5. Implementation sequence

Use small, buildable commits. The recommended order makes compiler errors useful instead of allowing networking, GUI, and persistence failures to obscure one another.

1. **Create the port branch and capture a baseline.**
2. **Replace the build, metadata, and Java toolchain.**
3. **Perform the mechanical namespace and vanilla API sweep.**
4. **Port bootstrap, configs, registries, and event buses.**
5. **Port persistent data without changing the NBT schema.**
6. **Replace the network channel with NeoForge payloads and hard bounds.**
7. **Port gameplay hooks and public events.**
8. **Port the client boundary, screen, sprites, input, and toast.**
9. **Revalidate the MCA reflection bridge against the released jar.**
10. **Migrate and extend tests.**
11. **Update all user/developer documentation.**
12. **Run jar, client, dedicated-server, old-world, and integration matrices.**

After each phase, run the narrowest relevant tests. Do not postpone all compilation until the end.

## 6. Phase 0 — branch, baseline, and change discipline

### 6.1 Branch and evidence

Create a branch such as `port/1.21.1-neoforge` from the pinned commit. Before source edits:

```bash
git rev-parse HEAD
git status --short
./gradlew clean test build checkJarContents
```

Use Java 17 only for that untouched Forge baseline. Save:

- the test count and result;
- the output jar's SHA-256;
- `jar tf` output;
- a representative `mcareputation.dat` fixture from a 1.20.1 world; and
- screenshots or structured notes for the Standing screen at normal, small, and large GUI scales.

If the old build cannot be reproduced, document the blocker and use the pinned code/tests as the baseline. Never fabricate a passing baseline.

### 6.2 Keep the port behaviorally narrow

Allowed changes are platform compatibility, defensive bounds required by the new network API, tests needed to prove compatibility, and documentation. Defer unrelated cleanup. In particular:

- do not rename mod id `mcareputation`;
- do not rename `DATA_NAME`;
- do not change command literals or permission levels;
- do not change config filenames or keys;
- do not reorder default tiers or alter thresholds;
- do not change incident ids, source ids, title ids, or the legacy `mcaquests` aliases;
- do not introduce attachments/capabilities merely because NeoForge offers them;
- do not replace reflection with an MCA compile dependency;
- do not add mixins; and
- do not delete compatibility data or migration markers.

## 7. Phase 1 — build system and metadata

### 7.1 `settings.gradle`

Replace the Forge plugin repository block with the current MDK shape:

```groovy
pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

plugins {
    id 'org.gradle.toolchains.foojay-resolver-convention' version '1.0.0'
}

rootProject.name = 'mcareputation'
```

### 7.2 `gradle.properties`

Retain the existing mod identity/description and config comments, but replace the platform section:

```properties
minecraft_version=1.21.1
minecraft_version_range=[1.21.1]
neo_version=21.1.249
loader_version_range=[1,)
parchment_minecraft_version=1.21.1
parchment_mappings_version=2024.11.17

mca_curse_file=8658484
# Replace this placeholder with an exact range, such as [x], after reading
# the resolved MCA jar's META-INF/neoforge.mods.toml [[mods]].version.
mca_version_range=[<exact-resolved-mca-version>]

mod_version=0.4.0
```

Delete `forge_version`, `forge_version_range`, `mapping_channel`, and `mapping_version`. Update comments that still describe ForgeGradle, SRG reobfuscation, Forgix relocation, or Java 17. Keep `org.gradle.java.home` unset; use a portable Java 21 toolchain.

### 7.3 `build.gradle`

Start from the pinned official MDK rather than mechanically renaming the ForgeGradle DSL. Required structure:

```groovy
plugins {
    id 'java-library'
    id 'maven-publish'       // keep only if publishing uses it
    id 'net.neoforged.moddev' version '2.0.146'
    id 'idea'
}

version = mod_version
group = mod_group_id

base {
    archivesName = mod_id
}

java.toolchain.languageVersion = JavaLanguageVersion.of(21)

neoForge {
    version = project.neo_version

    parchment {
        minecraftVersion = project.parchment_minecraft_version
        mappingsVersion = project.parchment_mappings_version
    }

    runs {
        client {
            client()
            systemProperty 'neoforge.enabledGameTestNamespaces', project.mod_id
        }
        server {
            server()
            programArgument '--nogui'
            systemProperty 'neoforge.enabledGameTestNamespaces', project.mod_id
        }
        gameTestServer {
            type = 'gameTestServer'
            systemProperty 'neoforge.enabledGameTestNamespaces', project.mod_id
        }
        data {
            data()
            programArguments.addAll '--mod', project.mod_id, '--all',
                    '--output', file('src/generated/resources').absolutePath,
                    '--existing', file('src/main/resources').absolutePath
        }
        configureEach {
            logLevel = org.slf4j.event.Level.DEBUG
        }
    }

    mods {
        "${mod_id}" {
            sourceSet(sourceSets.main)
        }
    }
}
```

Keep the existing JUnit dependencies and `useJUnitPlatform()`. Add the immutable MCA NeoForge artifact only to `localRuntime`. Do **not** add it to `implementation` or `api`.

Delete all of the following ForgeGradle constructs:

- plugin `net.minecraftforge.gradle`;
- the top-level `minecraft { mappings ... }` block;
- dependency `minecraft "net.minecraftforge:forge:..."`;
- `fg.repository` and `fg.deobf(...)`;
- `copyIdeResources`;
- Forge run properties; and
- `jar.finalizedBy 'reobfJar'`.

ModDevGradle produces the distributable artifact; there is no `reobfJar` finalizer.

### 7.4 Preserve and strengthen `checkJarContents`

Retain the custom jar scan and keep `build` depending on it. Its forbidden roots must include:

```groovy
def forbidden = [
    'dev/otectus/mcaquests/',
    'dev/otectus/mcaconversations/',
    'dev/architectury/',
    'me/shedaniel/',
    'forge/net/mca/',
    'forge/net/conczin/mca/',
    'net/mca/',
    'net/conczin/mca/'
]
```

Add metadata assertions to the task or a separate `verifyDistributionJar` task:

- `META-INF/neoforge.mods.toml` exists exactly once;
- `META-INF/mods.toml` does not exist;
- `pack.mcmeta` is absent unless a documented nonstandard feature requires it;
- all expected language, datapack, and GUI sprite resources exist;
- no `net/minecraftforge/` class or source resource is present; and
- no dependency classes are shaded.

### 7.5 Generated NeoForge metadata

Follow the MDK's generated-metadata pattern:

```groovy
var generateModMetadata = tasks.register('generateModMetadata', ProcessResources) {
    var replaceProperties = [
        minecraft_version       : minecraft_version,
        minecraft_version_range : minecraft_version_range,
        neo_version             : neo_version,
        loader_version_range    : loader_version_range,
        mca_version_range       : mca_version_range,
        mod_id                  : mod_id,
        mod_name                : mod_name,
        mod_license             : mod_license,
        mod_version             : mod_version,
        mod_authors             : mod_authors,
        mod_description         : mod_description
    ]
    inputs.properties replaceProperties
    expand replaceProperties
    from 'src/main/templates'
    into 'build/generated/sources/modMetadata'
}

sourceSets.main.resources.srcDir generateModMetadata
neoForge.ideSyncTask generateModMetadata
```

Move the template to `src/main/templates/META-INF/neoforge.mods.toml`. A suitable dependency section is:

```toml
modLoader="javafml"
loaderVersion="${loader_version_range}"
license="${mod_license}"

[[mods]]
modId="${mod_id}"
version="${mod_version}"
displayName="${mod_name}"
authors="${mod_authors}"
description='''${mod_description}'''

[[dependencies.${mod_id}]]
modId="neoforge"
type="required"
versionRange="[${neo_version},)"
ordering="NONE"
side="BOTH"

[[dependencies.${mod_id}]]
modId="minecraft"
type="required"
versionRange="${minecraft_version_range}"
ordering="NONE"
side="BOTH"

[[dependencies.${mod_id}]]
modId="mca"
type="required"
versionRange="${mca_version_range}"
ordering="AFTER"
side="BOTH"

[[dependencies.${mod_id}]]
modId="mcaquests"
type="optional"
versionRange="[1.0,)"
ordering="BEFORE"
side="BOTH"

[[dependencies.${mod_id}]]
modId="mcaconversations"
type="optional"
versionRange="[1.0,)"
ordering="BEFORE"
side="BOTH"
```

NeoForge uses `type="required"` / `type="optional"`, not Forge's `mandatory=true/false`. Keep MCA required and both companions optional. Do not declare Architectury on behalf of MCA.

Delete `src/main/resources/META-INF/mods.toml` after the new template works.

### 7.6 `pack.mcmeta`

Delete the current Forge-era main-mod `src/main/resources/pack.mcmeta`. NeoForge synthesizes pack metadata for a mod's main resource and data packs. Keeping a single legacy `pack_format` would also be misleading because Minecraft 1.21.1 has distinct client-resource and server-data format generations. Retain a `pack.mcmeta` only for a separately registered bundled pack, which this repository does not have.

### 7.7 Wrapper

Regenerate or update the wrapper to the pinned MDK version and verify:

```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-9.2.1-bin.zip
validateDistributionUrl=true
```

Commit the wrapper jar/scripts/properties as one mechanical toolchain commit.

## 8. Phase 2 — mechanical API migration

Do this as a controlled compiler-driven pass. Apply import changes, then handle the semantic exceptions below. Do not run an indiscriminate `forge` → `neoforge` text replacement across comments, ids, Gradle properties, or historical documentation.

### 8.1 Namespace map

| Forge 1.20.1 | NeoForge 1.21.1 |
|---|---|
| `net.minecraftforge.common.MinecraftForge` | `net.neoforged.neoforge.common.NeoForge` |
| `MinecraftForge.EVENT_BUS` | `NeoForge.EVENT_BUS` |
| `net.minecraftforge.eventbus.api.Event` | `net.neoforged.bus.api.Event` |
| `net.minecraftforge.eventbus.api.SubscribeEvent` | `net.neoforged.bus.api.SubscribeEvent` |
| `net.minecraftforge.eventbus.api.EventPriority` | `net.neoforged.bus.api.EventPriority` |
| `net.minecraftforge.fml.common.Mod` | `net.neoforged.fml.common.Mod` |
| `net.minecraftforge.api.distmarker.Dist` | `net.neoforged.api.distmarker.Dist` |
| `net.minecraftforge.fml.ModList` | `net.neoforged.fml.ModList` |
| `net.minecraftforge.fml.config.ModConfig` | `net.neoforged.fml.config.ModConfig` |
| `net.minecraftforge.fml.event.lifecycle.*` | `net.neoforged.fml.event.lifecycle.*` |
| `net.minecraftforge.common.ForgeConfigSpec` | `net.neoforged.neoforge.common.ModConfigSpec` |
| `net.minecraftforge.registries.DeferredRegister` | `net.neoforged.neoforge.registries.DeferredRegister` |
| `net.minecraftforge.event.AddReloadListenerEvent` | `net.neoforged.neoforge.event.AddReloadListenerEvent` |
| `net.minecraftforge.event.RegisterCommandsEvent` | `net.neoforged.neoforge.event.RegisterCommandsEvent` |
| `net.minecraftforge.event.server.ServerStoppedEvent` | `net.neoforged.neoforge.event.server.ServerStoppedEvent` |
| `net.minecraftforge.event.entity.player.PlayerEvent` | `net.neoforged.neoforge.event.entity.player.PlayerEvent` |
| `net.minecraftforge.event.entity.player.PlayerInteractEvent` | `net.neoforged.neoforge.event.entity.player.PlayerInteractEvent` |
| `net.minecraftforge.event.entity.living.LivingDeathEvent` | `net.neoforged.neoforge.event.entity.living.LivingDeathEvent` |
| `net.minecraftforge.client.event.*` | `net.neoforged.neoforge.client.event.*` |
| `net.minecraftforge.client.settings.KeyConflictContext` | `net.neoforged.neoforge.client.settings.KeyConflictContext` |
| `net.minecraftforge.network.PacketDistributor` | `net.neoforged.neoforge.network.PacketDistributor` |

There is deliberately no mapping for `SimpleChannel`, `NetworkRegistry.newSimpleChannel`, `NetworkEvent.Context`, or `DistExecutor`. Those APIs are replaced by designs in later sections.

### 8.2 Resource locations

In 1.21.1, direct `ResourceLocation` constructors are private. Apply these rules everywhere, including tests:

```java
// Namespace and path are already separate.
ResourceLocation.fromNamespaceAndPath("mcareputation", "default");

// A validated combined string is being parsed.
ResourceLocation.parse("minecraft:overworld");

// Untrusted/optional text stays non-throwing.
ResourceLocation.tryParse(raw);
```

Production occurrences that must change:

- `McaReputation.id` and `McaReputation.questsId`;
- `ReputationTiers.DEFAULT_ID` and `LEGACY_DEFAULT_ID`;
- the built-in honored and revered title ids in `ReputationTiers`; and
- `BuiltinIncidents.SOURCE_CONVERSATIONS`.

Update every constructor occurrence in `TestFixtures`, `CommunityKeyTest`, `AwarenessTest`, `IncidentCodecTest`, `ContentValidationTest`, `ReputationServiceTest`, `SavedDataTest`, and `SnapshotPacketTest`. End with:

```bash
rg -n 'new ResourceLocation\(' src
```

Expected result: no matches.

### 8.3 Component codecs

Minecraft 1.21.1 removed `ExtraCodecs.COMPONENT`. Replace all five uses with `ComponentSerialization.CODEC`:

- `IncidentDefinition.display`;
- `ReputationTier.name`;
- `ReputationTier.description`;
- `TitleDefinition.name`; and
- `TitleDefinition.description`.

```java
import net.minecraft.network.chat.ComponentSerialization;

ComponentSerialization.CODEC.fieldOf("name")
StrictCodecs.strictOptional(ComponentSerialization.CODEC, "description")
```

This preserves both bare-string and structured component JSON. Re-run every content codec, shipped-content, and language parity test; do not “fix” the resource JSON unless the new codec genuinely rejects an existing document.

### 8.4 Item registry lookup

Replace `ForgeRegistries.ITEMS.getValue(id)` in `TitleDefinition` with the vanilla built-in registry:

```java
BuiltInRegistries.ITEM.getOptional(id)
        .filter(item -> item != Items.AIR)
```

Preserve the existing name-tag fallback and invalid-id behavior. Add/retain tests for a valid item id, an unknown id, and `minecraft:air`.

### 8.5 Imports are not proof

After compilation, run:

```bash
rg -n 'net\.minecraftforge|MinecraftForge|ForgeConfigSpec|ForgeRegistries|SimpleChannel|NetworkEvent|DistExecutor' \
    src/main/java src/test/java build.gradle settings.gradle gradle.properties
```

The expected result is empty except for intentional historical prose in documents that is clearly labeled as the old platform. The distributable source and build logic must contain none of these APIs.

## 9. Phase 3 — bootstrap, configs, registration, and buses

### 9.1 Entry-point constructor

NeoForge supplies the mod bus and container to the `@Mod` constructor. Rewrite `McaReputationMod` around constructor injection:

```java
@Mod(McaReputation.MOD_ID)
public final class McaReputationMod {
    public McaReputationMod(IEventBus modBus, ModContainer container) {
        container.registerConfig(
                ModConfig.Type.COMMON,
                McaReputationConfig.COMMON_SPEC,
                "mcareputation-common.toml");
        container.registerConfig(
                ModConfig.Type.CLIENT,
                McaReputationConfig.CLIENT_SPEC,
                "mcareputation-client.toml");

        modBus.addListener(this::onCommonSetup);
        modBus.addListener(ReputationNetwork::registerPayloads);
        COMMAND_ARGUMENT_TYPES.register(modBus);
        NeoForge.EVENT_BUS.register(this);

        ReputationTiers.replaceAll(Map.of(
                ReputationTiers.DEFAULT_ID,
                ReputationTiers.BUILTIN_DEFAULT));
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(McaReflect::selfTest);
        McaReputation.LOGGER.info(
                "[MCA: Reputation] {} ready (API v{})",
                McaReputation.MOD_ID,
                McaReputationApi.getApiVersion());
    }
}
```

Required imports include `net.neoforged.bus.api.IEventBus`, `net.neoforged.fml.ModContainer`, `net.neoforged.neoforge.common.NeoForge`, and the corresponding NeoForge lifecycle/config types.

Network registration is no longer deferred through common setup. `RegisterPayloadHandlersEvent` is itself a mod-bus event and must register payloads when fired.

### 9.2 Command argument registration

Keep the existing `DeferredRegister<ArgumentTypeInfo<?, ?>>` and `SingletonArgumentInfo.contextFree(...)` behavior. Only imports/bus wiring should change. The command argument remains synced to clients and its id remains `mcareputation:community`.

Run `CommandTreeTest` immediately after this phase. Verify both `/mcareputation` and the `/mcarep` redirect, all permission gates, suggestions, `here`, and dimension-qualified community parsing.

### 9.3 Event subscription model

Use `net.neoforged.fml.common.EventBusSubscriber` for static subscriber classes:

```java
@EventBusSubscriber(modid = McaReputation.MOD_ID)
public final class ReputationGameplayEvents { ... }

@EventBusSubscriber(modid = McaReputation.MOD_ID, value = Dist.CLIENT)
public final class ReputationClient { ... }
```

The 1.21.1 annotation does not use Forge's old `bus = Mod.EventBusSubscriber.Bus.MOD` selector. NeoForge routes `IModBusEvent` implementations to the mod bus and gameplay events to `NeoForge.EVENT_BUS`. Thus `RegisterKeyMappingsEvent` and `FMLClientSetupEvent` may live as static handlers in the client-only subscriber while screen, interaction, logout, and client tick handlers continue to receive gameplay-bus events.

Alternatively, register listeners explicitly against the injected bus. Pick one consistent style and add a smoke test/log assertion that each handler registers exactly once. Never combine annotation discovery and manual registration for the same static handler.

### 9.4 Configs

Change `ForgeConfigSpec` to `ModConfigSpec` in `McaReputationConfig`. Preserve:

- every TOML key;
- common/client separation;
- default value;
- numeric range;
- getters and clamping behavior; and
- the explicit filenames `mcareputation-common.toml` and `mcareputation-client.toml`.

Do not migrate server-authoritative values into a client config, and do not rename files to NeoForge defaults. Existing server configs must continue to load.

### 9.5 Reload, commands, and stop cleanup

Keep the three `McaReputationMod` gameplay-bus hooks, using NeoForge event imports:

- `AddReloadListenerEvent` adds one `ReputationReloadListener`;
- `RegisterCommandsEvent` calls `ReputationCommand.register`; and
- `ServerStoppedEvent` clears `AssaultTracker`, `ReputationNetwork` request stamps, `ReputationFeedback` buffers, and the gameplay tick counter.

The second-world-in-one-JVM cleanup invariant is important; do not rely on static state disappearing.

## 10. Phase 4 — saved data and old-world compatibility

### 10.1 New `SavedData` signatures

Use the 1.21.1 factory and registry-aware save method while keeping serialization pure:

```java
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.saveddata.SavedData;

private static final SavedData.Factory<ReputationSavedData> FACTORY =
        new SavedData.Factory<>(
                ReputationSavedData::new,
                (tag, provider) -> loadPayload(tag));

public static ReputationSavedData get(MinecraftServer server) {
    return server.overworld()
            .getDataStorage()
            .computeIfAbsent(FACTORY, DATA_NAME);
}

@Override
public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
    return savePayload(tag);
}

CompoundTag savePayload(CompoundTag tag) {
    // Existing version/players serialization, byte-for-byte schema unchanged.
    return tag;
}

static ReputationSavedData loadPayload(CompoundTag tag) {
    // Existing forgiving loader and migrateFormat behavior.
}
```

NeoForge 21.1.x provides the two-argument `SavedData.Factory` convenience constructor; the explicit equivalent is:

```java
new SavedData.Factory<>(
        ReputationSavedData::new,
        (tag, provider) -> loadPayload(tag),
        null)
```

Either is acceptable. The `HolderLookup.Provider` is unused because the current data contains primitives, strings, UUIDs, and nested compounds rather than registry holders. Keep it in the platform override only.

### 10.2 Test seam

Keep `savePayload` and `loadPayload` package-private and update `roundTripForTest()` plus the direct `save`/`load` calls in `SavedDataTest` to use them. This keeps a pure test seam without adding it to the public mod API, so most state tests do not need a synthetic registry provider.

If tests must exercise the actual override, obtain a real `HolderLookup.Provider` from a bootstrapped registry access; do not pass null merely to silence the signature unless the test is explicitly proving that the parameter is unused.

### 10.3 NBT compatibility contract

Keep all of the following unchanged:

- file id `mcareputation`;
- root payload keys `version` and `players`;
- UUID string keys;
- `CommunityKey` dimension and village representation;
- score/baseline/high-water/title/incident fields;
- incident ids, times, contribution/status, subjects, witnesses, context, and dedupe data;
- import provider markers; and
- lenient per-entry error containment.

Do not change `FORMAT_VERSION = 1` simply because `save` gained a registry parameter. A method signature is not a data migration.

### 10.4 Golden 1.20.1 fixture

Add a checked-in test fixture produced by the pinned 1.20.1 build. It must contain at least:

- two players;
- two villages with the same numeric id in different dimensions;
- positive and negative scores;
- a baseline;
- active, resolved, pinned, and folded incidents;
- subjects, witnesses, and context;
- global and village titles;
- tier high-water data;
- cached village metadata; and
- a completed legacy-import marker.

Test both forms:

1. load the exact compressed `mcareputation.dat` root as vanilla wrote it and extract its `data` payload;
2. pass that payload through the 1.21.1 loader;
3. assert every semantic field;
4. save with the 1.21.1 code;
5. reload it again; and
6. compare normalized semantic snapshots, allowing only vanilla's outer `DataVersion` bookkeeping to differ.

Also retain malformed-entry tests. A bad player UUID, community key, subject, witness, title, or incident must be skipped at the narrowest possible level and must not prevent the rest of the world from loading.

### 10.5 Manual world upgrade

Copy, never move, a real 1.20.1 world into a 1.21.1 test instance. Back up the entire world, not only `data/`, because Minecraft and MCA also perform their own upgrades. Validate:

- the world opens with no reputation data-loss warning;
- player/community counts match the pre-upgrade dump;
- scores, titles, high-water marks, ledger order, incident status, metadata, and import markers match;
- a new incident saves and survives a full restart; and
- opening the copied world again under 1.20.1 is **not** promised or tested as a supported downgrade.

## 11. Phase 5 — custom payload networking

### 11.1 Replace the channel, do not wrap it

Delete `NetworkRegistry.newSimpleChannel`, `SimpleChannel`, numeric discriminators, `registerMessage`, `NetworkEvent.Context`, `setPacketHandled`, and Forge `PacketDistributor.PLAYER.with(...)`. Each message becomes a `CustomPacketPayload` with a stable resource id and a `StreamCodec`.

Use protocol `"3"`:

```java
private static final String PROTOCOL_VERSION = "3";

public static void registerPayloads(RegisterPayloadHandlersEvent event) {
    PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
    registrar.playToServer(
            RequestSnapshotC2S.TYPE,
            RequestSnapshotC2S.STREAM_CODEC,
            ReputationNetwork::handleRequestSnapshot);
    registrar.playToClient(
            SnapshotS2C.TYPE,
            SnapshotS2C.STREAM_CODEC,
            ReputationNetwork::handleSnapshot);
    registrar.playToClient(
            OpenScreenS2C.TYPE,
            OpenScreenS2C.STREAM_CODEC,
            ReputationNetwork::handleOpenScreen);
    registrar.playToClient(
            ChangeS2C.TYPE,
            ChangeS2C.STREAM_CODEC,
            ReputationNetwork::handleChange);
    registrar.playToClient(
            TierToastS2C.TYPE,
            TierToastS2C.STREAM_CODEC,
            ReputationNetwork::handleTierToast);
}
```

Do not call `optional()`: the mod and its payloads are required on both client and server, as they were under the old channel.

### 11.2 Payload ids and schemas

Keep the Java record names if desired, but assign these explicit ids:

| Direction | Record | Type id | Fields / bounds |
|---|---|---|---|
| C2S | `RequestSnapshotC2S` | `mcareputation:request_snapshot` | var-int entity id; optional `CommunityKey` |
| S2C | `SnapshotS2C` | `mcareputation:snapshot` | max 64 communities; optional detail; max 64 global titles |
| S2C | `OpenScreenS2C` | `mcareputation:open_screen` | unit payload |
| S2C | `ChangeS2C` | `mcareputation:change` | two components, int delta, three booleans |
| S2C | `TierToastS2C` | `mcareputation:tier_toast` | two components |

Nested snapshot bounds:

| Structure | Hard maximum / encoding rule |
|---|---|
| community name | `CommunityMetadata.MAX_NAME_LENGTH` = 64 UTF-8 characters |
| tier id / optional next tier id | 48 UTF-8 characters |
| incident status | 32 UTF-8 characters |
| incident severity | 32 UTF-8 characters |
| selected titles | `ReputationBounds.MAX_TITLES` = 64 |
| selected incidents | `ReputationBounds.MAX_SYNCED_INCIDENTS` = 50 |
| top-level communities | `ReputationBounds.MAX_SYNCED_COMMUNITIES` = 64 |
| top-level global titles | `ReputationBounds.MAX_TITLES` = 64 |
| ages / totals | encode non-negative; reject or clamp invalid decoded values consistently |

Encode-side truncation is not a security boundary. Apply the same maxima in the decoder.

Preserve this wire order inside the new codecs:

| Structure | Ordered fields |
|---|---|
| `CommunitySummary` | `CommunityKey key`, bounded `String name`, fixed `int score`, bounded `String tierId` |
| `IncidentSummary` | `UUID id`, `ResourceLocation type`, `Component display`, non-negative var-long `ageTicks`, fixed `int contribution`, bounded `String status`, bounded `String severity`, `boolean pinned` |
| `SelectedDetail` | `key`, `name`, fixed `score`, fixed `baseline`, `tierId`, `tierName`, optional `tierDescription`, fixed `tierThreshold`, optional `nextTierId`, optional `nextTierName`, fixed `nextThreshold`, bounded title list, bounded incident list, non-negative var-int `totalIncidents` |
| `SnapshotS2C` | bounded community list, optional selected detail, bounded global-title list |
| `ChangeS2C` | `communityName`, fixed `int delta`, `tierName`, `tierChanged`, `downward`, `firstTime` |
| `TierToastS2C` | `communityName`, `tierName` |

The new protocol has no compatibility obligation to Forge protocol 2, but keeping field order and integer widths makes the rewrite reviewable against the old encoder and prevents accidental information loss.

### 11.3 Payload record pattern

Every top-level record implements `CustomPacketPayload`:

```java
public record RequestSnapshotC2S(
        int contextEntityId,
        Optional<CommunityKey> requestedCommunity)
        implements CustomPacketPayload {

    public static final Type<RequestSnapshotC2S> TYPE =
            new Type<>(McaReputation.id("request_snapshot"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestSnapshotC2S> STREAM_CODEC =
            StreamCodec.ofMember(
                    RequestSnapshotC2S::write,
                    RequestSnapshotC2S::read);

    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(contextEntityId);
        ByteBufCodecs.optional(CommunityKey.STREAM_CODEC)
                .encode(buf, requestedCommunity);
    }

    private static RequestSnapshotC2S read(RegistryFriendlyByteBuf buf) {
        return new RequestSnapshotC2S(
                buf.readVarInt(),
                ByteBufCodecs.optional(CommunityKey.STREAM_CODEC).decode(buf));
    }

    @Override
    public Type<RequestSnapshotC2S> type() {
        return TYPE;
    }
}
```

For the zero-field packet:

```java
public static final StreamCodec<RegistryFriendlyByteBuf, OpenScreenS2C> STREAM_CODEC =
        StreamCodec.unit(new OpenScreenS2C());
```

Use `StreamCodec.composite` for small records where it stays readable. Use `StreamCodec.ofMember` with explicit write/read methods for `SelectedDetail` and `SnapshotS2C`; their field counts and defensive validation make a giant composite brittle.

### 11.4 Reusable codecs

Add `CommunityKey.STREAM_CODEC`, retaining the hostile-input clamp:

```java
public static final StreamCodec<RegistryFriendlyByteBuf, CommunityKey> STREAM_CODEC =
        StreamCodec.of(
                (buf, key) -> {
                    ResourceLocation.STREAM_CODEC.encode(buf, key.dimension());
                    buf.writeVarInt(key.villageId());
                },
                buf -> new CommunityKey(
                        ResourceLocation.STREAM_CODEC.decode(buf),
                        Math.max(0, buf.readVarInt())));
```

Use the 1.21.1 codec primitives where applicable:

- `ResourceLocation.STREAM_CODEC`;
- `UUIDUtil.STREAM_CODEC`;
- `ComponentSerialization.STREAM_CODEC`;
- `ComponentSerialization.OPTIONAL_STREAM_CODEC`;
- `ByteBufCodecs.INT`, `VAR_INT`, `VAR_LONG`, and `BOOL`;
- `ByteBufCodecs.stringUtf8(max)`;
- `ByteBufCodecs.optional(codec)`; and
- `elementCodec.apply(ByteBufCodecs.list(max))` or `ByteBufCodecs.collection(ArrayList::new, elementCodec, max)`.

Components require `RegistryFriendlyByteBuf`. Register all five play payloads with `StreamCodec<? super RegistryFriendlyByteBuf, ...>`; do not downcast a plain buffer or serialize components as ad-hoc JSON.

Example bounded list:

```java
private static final StreamCodec<RegistryFriendlyByteBuf, List<Component>> TITLE_LIST_CODEC =
        ComponentSerialization.STREAM_CODEC.apply(
                ByteBufCodecs.list(ReputationBounds.MAX_TITLES));
```

The existing `CommunityMetadata` and `IncidentSubject` `FriendlyByteBuf` helpers are not used by the five packets. They may remain if they compile and have tests, or be replaced with named stream codecs in a separate mechanical commit. Do not leave two subtly different encodings for a type that is actively sent.

### 11.5 Handler threading and authority

`PayloadRegistrar` handlers run on the main thread by default in this NeoForge line. Do not cargo-cult an extra asynchronous hop. Handler shape:

```java
private static void handleRequestSnapshot(
        RequestSnapshotC2S payload,
        IPayloadContext context) {
    if (!(context.player() instanceof ServerPlayer player)) {
        return;
    }
    // Existing rate limit, selection validation, and snapshot construction.
}
```

Preserve the C2S checks exactly:

1. derive the requesting player from `context.player()`;
2. apply one accepted request per player per 10 server ticks;
3. honor a requested `CommunityKey` only when that player already has a record for it;
4. resolve a positive entity id only in the player's current `ServerLevel`;
5. require the entity to exist, be an MCA living villager, and be within 12 blocks;
6. resolve the village on the server;
7. otherwise choose the positional/known fallback through `SnapshotSelection`; and
8. build data only for the requesting player.

Continue to silently ignore rate-limited or stale hints. They are normal client races, not disconnect-worthy protocol violations.

### 11.6 Sending

```java
public static void sendTo(ServerPlayer player, CustomPacketPayload payload) {
    PacketDistributor.sendToPlayer(player, payload);
}

// Client request:
PacketDistributor.sendToServer(
        new RequestSnapshotC2S(contextEntityId, requestedCommunity));
```

Change `ClientReputationData.request` accordingly. Keep `openScreenWithSnapshot` ordering: fresh snapshot first, open-screen payload second.

### 11.7 Network tests

Rewrite `SnapshotPacketTest` to use a real registry-friendly buffer:

```java
RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
        Unpooled.buffer(),
        registryAccess,
        ConnectionType.NEOFORGE);

SnapshotS2C.STREAM_CODEC.encode(buf, original);
SnapshotS2C decoded = SnapshotS2C.STREAM_CODEC.decode(buf);
```

Obtain `registryAccess` from a bootstrapped test server/registry fixture. Add tests for:

- round trips of all five payloads;
- every optional field present and absent;
- structured/translatable/literal components;
- Unicode names at the 64-character boundary;
- max-size community/title/incident lists;
- `max + 1` encode rejection or pre-encode truncation, as intended;
- malicious decoded counts above each maximum;
- negative village id clamping;
- non-negative age and total normalization;
- stable and distinct payload ids;
- request cooldown and cleanup on logout/server stop;
- far, missing, wrong-dimension, and non-MCA context entities;
- unknown requested communities; and
- snapshot-before-open ordering.

Do not weaken the tests by reverting to direct `FriendlyByteBuf` helper calls; exercise the registered `STREAM_CODEC` objects.

## 12. Phase 6 — gameplay hooks and public events

### 12.1 Event mapping

| Existing hook | NeoForge 1.21.1 hook | Required adaptation |
|---|---|---|
| `TickEvent.ServerTickEvent` + `Phase.END` | `net.neoforged.neoforge.event.tick.ServerTickEvent.Post` | Remove phase check; retain interval and empty-player fast paths |
| `TickEvent.ClientTickEvent` + `Phase.END` | `net.neoforged.neoforge.client.event.ClientTickEvent.Post` | Remove phase check; retain one increment/flush per client tick |
| `LivingHurtEvent` | `LivingDamageEvent.Post` | Use `getNewDamage()`, not original damage |
| `LivingDeathEvent` | NeoForge `LivingDeathEvent` | Namespace change; retain LOWEST priority and attribution |
| `PlayerEvent.PlayerLoggedInEvent` | NeoForge equivalent | Namespace change |
| `PlayerEvent.PlayerLoggedOutEvent` | NeoForge equivalent | Namespace change |
| `PlayerInteractEvent.EntityInteract` | NeoForge equivalent | Namespace change |
| `ScreenEvent.Init.Post` | NeoForge equivalent | Namespace change; retain `addListener` |
| `ClientPlayerNetworkEvent.LoggingOut` | NeoForge equivalent | Namespace change; clear client cache |
| `RegisterKeyMappingsEvent` | NeoForge equivalent | Client-only mod-bus handler |

### 12.2 Damage semantics

Port `ReputationGameplayEvents.onLivingHurt` to:

```java
@SubscribeEvent(priority = EventPriority.LOWEST)
public static void onLivingDamage(LivingDamageEvent.Post event) {
    LivingEntity target = event.getEntity();
    float actualHealthDamage = event.getNewDamage();
    DamageSource source = event.getSource();
    // Existing server-side, self-defense, authority, MCA, threshold,
    // attribution, community, witness, and coalescing logic.
}
```

`LivingDamageEvent.Post` is the correct semantic replacement because it contains the final health loss after reduction/absorption processing and fires after the health change. It is not cancellable and is not emitted for a cancelled attack that never reaches final damage application. Therefore:

- remove `receiveCanceled = false`;
- compare `event.getNewDamage()` with `minimumIncidentDamage`;
- pass `getNewDamage()` into assault coalescing/context;
- keep the “MCA villager hit a player” self-defense tracking branch before the feature/authority gates; and
- update the Javadoc, which currently incorrectly describes the old hook as already being post-absorption.

Do **not** use `LivingIncomingDamageEvent` or `LivingDamageEvent.Pre` for reputation. They expose earlier/mutable stages and can record harm that is later reduced or absorbed differently.

Port `onLivingDeath` with the NeoForge import and retain the assault-to-kill fold/rollback sequence exactly. A killing after an assault must total the killing's contribution, not assault plus killing; an unwitnessed/refused killing must restore the earlier assault contribution.

### 12.3 Tick behavior

Two server post-tick consumers remain:

- `ReputationGameplayEvents` performs its configured, bounded online-player reconciliation and `AssaultTracker` sweep;
- `ReputationFeedback` flushes same-tick changes per player and per community.

Both accept `ServerTickEvent.Post` directly and use `event.getServer()`. Remove phase branches, but do not merge the two responsibilities or change the accumulation ordering without adjusting tests.

The client uses `ClientTickEvent.Post` to increment its hint clock, flush merged client presentation, and consume the unbound key mapping. It must still do exactly one pass per client tick.

### 12.4 Public NeoForge events

Change the base class import in `api/event/ReputationEvent.java` to:

```java
import net.neoforged.bus.api.Event;
```

Remove the `isCancelable()` override. NeoForge cancellation is expressed by implementing `ICancellableEvent`; these classes intentionally do not implement it, so they remain non-cancellable.

Change `ServiceContext.post`, `TestServiceContext`, and `ReputationService.postSafely` to the NeoForge `Event` type. Production posting becomes:

```java
NeoForge.EVENT_BUS.post(event);
```

Retain listener-failure containment: a throwing subscriber is logged and may not roll back an already committed transaction.

### 12.5 API generation

Set `McaReputationApi.API_VERSION` to `2` and update `API.md`. The Java method/record contracts should otherwise remain source-compatible. Explain that the generation bump exists because:

- the platform artifact is NeoForge-only;
- the five exposed event classes now extend NeoForge's event base;
- companion integrations must be rebuilt against Minecraft/NeoForge 1.21.1; and
- reporting API 1 could let a bridge mistake binary incompatibility for compatibility.

Ported companion bridges should accept API 2 explicitly. Do not make Reputation load or reflect into the companions; Quests and Conversations continue to own their optional integration side.

### 12.6 Event regression tests

Retain or add assertions that:

- zero-delta narrative incidents fire created but not changed events;
- duplicate requests fire nothing;
- a repeated/weaker resolution fires nothing;
- a newly granted title fires once;
- tier transitions retain upward/downward/first-time semantics;
- decay/import/baseline changes have no incident id;
- all events observe the committed store;
- listeners that throw do not undo state; and
- all event classes are immutable and are not `ICancellableEvent`.

## 13. Phase 7 — physical-client boundary and UI

### 13.1 Replace `DistExecutor` with an installed client sink

NeoForge removed the old `DistExecutor` used by `network/ClientPacketHandler.java`. Do not put direct client method references in payload handlers. Turn that class into a common, client-type-free dispatch bridge:

```java
public final class ClientPacketHandler {
    private static Consumer<ReputationNetwork.SnapshotS2C> snapshot = payload -> {};
    private static Runnable openScreen = () -> {};
    private static Consumer<ReputationNetwork.ChangeS2C> change = payload -> {};
    private static Consumer<ReputationNetwork.TierToastS2C> toast = payload -> {};

    private ClientPacketHandler() {}

    public static void install(
            Consumer<ReputationNetwork.SnapshotS2C> snapshotSink,
            Runnable openScreenSink,
            Consumer<ReputationNetwork.ChangeS2C> changeSink,
            Consumer<ReputationNetwork.TierToastS2C> toastSink) {
        snapshot = Objects.requireNonNull(snapshotSink);
        openScreen = Objects.requireNonNull(openScreenSink);
        change = Objects.requireNonNull(changeSink);
        toast = Objects.requireNonNull(toastSink);
    }

    static void acceptSnapshot(ReputationNetwork.SnapshotS2C payload) {
        snapshot.accept(payload);
    }

    static void openScreen() {
        openScreen.run();
    }

    static void acceptChange(ReputationNetwork.ChangeS2C payload) {
        change.accept(payload);
    }

    static void acceptToast(ReputationNetwork.TierToastS2C payload) {
        toast.accept(payload);
    }
}
```

Install the real method references only from the client package during client setup:

```java
@SubscribeEvent
public static void onClientSetup(FMLClientSetupEvent event) {
    event.enqueueWork(() -> ClientPacketHandler.install(
            ClientReputationData::acceptSnapshot,
            ClientReputationData::openScreen,
            ClientReputationData::acceptChange,
            ClientReputationData::acceptToast));
}
```

The bridge's bytecode must contain no `dev/otectus/mcareputation/client/` or `net/minecraft/client/` constant-pool references. No-op defaults are a safety net during initialization, not a way to mask a registration failure; a client smoke test must prove installation occurred before play payloads are handled.

### 13.2 Client event changes

In `ReputationClient`:

- use NeoForge `Dist`, `EventBusSubscriber`, `SubscribeEvent`, `RegisterKeyMappingsEvent`, `ScreenEvent`, `PlayerInteractEvent`, and `ClientPlayerNetworkEvent` imports;
- use `net.neoforged.neoforge.client.settings.KeyConflictContext`;
- add the `FMLClientSetupEvent` sink installation above;
- change `onClientTick` to accept `ClientTickEvent.Post` and remove the phase condition;
- keep the key unbound through `InputConstants.UNKNOWN.getValue()`;
- preserve the 200-tick interaction hint lifetime;
- preserve screen-type reflection and responsive button bounds; and
- clear the snapshot cache and entity hint on logout.

### 13.3 Screen signatures

In `ReputationScreen`:

```java
@Override
public void render(
        GuiGraphics graphics,
        int mouseX,
        int mouseY,
        float partialTick) {
    renderBackground(graphics, mouseX, mouseY, partialTick);
    // Existing layout/rendering.
}

@Override
public boolean mouseScrolled(
        double mouseX,
        double mouseY,
        double scrollX,
        double scrollY) {
    if (mouseY >= listTop && mouseY <= listBottom) {
        scroll = ScrollMath.clampScroll(
                scroll - scrollY * LINE * 2,
                contentHeight,
                listBottom - listTop);
        return true;
    }
    return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
}
```

The old one-argument `renderBackground(graphics)` call and three-argument `mouseScrolled` override do not match 1.21.1. Preserve parent-screen close behavior, selector index clamping, drag behavior, tooltip order, and request throttling.

Compile-check `SpriteButton` and the screen's widget render overrides; no behavior change is expected unless the 1.21.1 compiler identifies a signature difference.

### 13.4 Convert the sheet to GUI sprites

The old `GuiGraphics.blitNineSliced` sheet overload is no longer the public rendering path. Convert the authored pieces of `assets/mcareputation/textures/gui/reputation.png` into individual GUI-atlas sprites:

| Sprite id | Output PNG under `assets/mcareputation/textures/gui/sprites/` | Crop from current 256×256 sheet | Scaling |
|---|---|---|---|
| `mcareputation:reputation/panel` | `reputation/panel.png` | x 0, y 0, 24×24 | nine-slice 24×24, border 7 all sides |
| `mcareputation:reputation/well` | `reputation/well.png` | x 32, y 0, 16×16 | nine-slice 16×16, border 3 all sides |
| `mcareputation:reputation/progress_track` | `reputation/progress_track.png` | x 0, y 32, 8×5 | nine-slice 8×5; left/right 2, top/bottom 0 |
| `mcareputation:reputation/progress_fill` | `reputation/progress_fill.png` | x 16, y 32, 8×3 | nine-slice 8×3; left/right 2, top/bottom 0 |
| `mcareputation:reputation/scroll_groove` | `reputation/scroll_groove.png` | x 0, y 48, 14×16 | nine-slice 14×16; top/bottom 3, left/right 0 |
| `mcareputation:reputation/scroll_thumb` | `reputation/scroll_thumb.png` | x 16, y 48, 12×16 | nine-slice 12×16; top/bottom 3, left/right 0 |
| `mcareputation:reputation/separator` | `reputation/separator.png` | x 0, y 72, 8×2 | nine-slice 8×2; left/right 2, top/bottom 0 |
| `mcareputation:reputation/arrow_left` | `reputation/arrow_left.png` | x 0, y 80, 8×8 | default stretch |
| `mcareputation:reputation/arrow_right` | `reputation/arrow_right.png` | x 16, y 80, 8×8 | default stretch |

Add a sibling `.png.mcmeta` to each of the seven nine-slice sprites. Example:

```json
{
  "gui": {
    "scaling": {
      "type": "nine_slice",
      "width": 8,
      "height": 5,
      "border": {
        "left": 2,
        "right": 2,
        "top": 0,
        "bottom": 0
      }
    }
  }
}
```

Use the dimensions/borders from the table for each file. Do not add `stretch_inner`; it is not part of the inspected 1.21.1 nine-slice record.

Update `tools/GenerateGuiTexture.java` to deterministically emit the nine cropped sprites and metadata, or add a deterministic extractor beside it. Generated bytes should be repeatable. The old sheet may remain only as a documented tooling source; remove it from runtime resources once no code references it.

### 13.5 `GuiTextures`

Replace sheet UV constants with sprite ids:

```java
private static final ResourceLocation PANEL =
        McaReputation.id("reputation/panel");
// ...one id per sprite...

static void panel(
        GuiGraphics graphics,
        int x,
        int y,
        int width,
        int height) {
    graphics.blitSprite(PANEL, x, y, width, height);
}
```

Use `blitSprite` for every entry in the table. The GUI atlas reads the nine-slice behavior from metadata. Remove manual `RenderSystem.enableBlend()` / `defaultBlendFunc()` calls and the `RenderSystem` import unless visual testing demonstrates a specific remaining need.

### 13.6 Toast

`Toast.TEXTURE` is not available in 1.21.1. Use the vanilla system-toast sprite:

```java
private static final ResourceLocation BACKGROUND =
        ResourceLocation.withDefaultNamespace("toast/system");

graphics.blitSprite(BACKGROUND, 0, 0, width(), height());
```

The `render(GuiGraphics, ToastComponent, long)` and `getToken()` contracts remain usable in 1.21.1. Preserve the 5-second display, text positions/colors, and per-community token. Visually verify that literal and long translated tier/community names do not overflow unexpectedly.

### 13.7 UI acceptance matrix

Capture screenshots at GUI scale auto and fixed scales 1–4, with:

- 0, 1, 2, 64, and more-than-visible communities;
- 0, 1, and 50 visible incidents;
- long Unicode community/tier/title strings;
- positive and negative scores;
- no next tier;
- parent MCA screen present and absent;
- window resize while the screen is open; and
- scroll wheel, drag, selector arrows, keybind, Standing button, toast, action bar, and logout/rejoin.

Pixel-perfect parity is preferred for the authored frame. Functional parity and no clipping/overlap at supported sizes are release gates.

## 14. Phase 8 — MCA Reborn compatibility

### 14.1 Package root

The inspected 1.21.1 NeoForge source uses the unrelocated package root `net.conczin.mca`. For this new artifact, set:

```java
static final List<String> SUPPORTED_ROOTS =
        List.of("net.conczin.mca");
```

Do not carry the old 1.20.1 universal/Forgix roots merely because they existed:

- `forge.net.conczin.mca`;
- `forge.net.mca`; and
- `net.mca`.

A Minecraft 1.20.1 MCA jar cannot satisfy the 1.21.1 mod anyway. Add another 1.21.1 root only after an allowed release artifact is tested and its complete member set passes.

Update `McaReflect` comments to remove obsolete claims about Forge reobfuscation, SRG names, universal jars, and the `[7.6,8)` range. Preserve the important rules: class names stay string literals, static initialization cannot throw, vanilla methods are invoked through vanilla types, and `selfTest()` reports one actionable startup result.

### 14.2 Audited reflection surface

At MCA source commit `575691bd6e09d4be2f828340683247dc2a2c4fdb`, the following consumed members exist:

| Owner under `net.conczin.mca` | Member |
|---|---|
| `entity.VillagerLike` | `getAgeState()` |
| `entity.VillagerEntityMCA` | `getVillagerBrain()` |
| `entity.ai.brain.VillagerBrain` | `getPersonality()` |
| `entity.VillagerEntityMCA` | `getResidency()` |
| `entity.ai.Residency` | `getHomeVillage()` |
| `server.world.data.Village` | `getId()` |
| `server.world.data.Village` | `getName()` |
| `server.world.data.Village` | `getCenter()` |
| `server.world.data.Village` | `isWithinBorder(BlockPos, int)` |
| `server.world.data.Village` | `getResidents(ServerLevel)` |
| `server.world.data.Village` | `getResidentsUUIDs()` |
| `server.world.data.Village` | `getResidentNames()` |
| `server.world.data.VillageManager` | static `get(ServerLevel)` |
| `server.world.data.VillageManager` | `getOrEmpty(int)` |
| `server.world.data.VillageManager` | `findNearestVillage(BlockPos, int)` |
| `server.world.data.FamilyTree` | static `get(ServerLevel)` |
| `server.world.data.FamilyTree` | `getOrEmpty(UUID)` |
| `server.world.data.FamilyTreeNode` | `getName()` |

This source check is not the release gate. Download the exact MCA NeoForge jar resolved by Gradle and run `javap` or an isolated reflection test against that binary. Validate parameter erasures, static/instance status, return wrappers, and public accessibility for all entries.

### 14.3 MCA screen detection

Keep `McaScreenCompat` reflection-only. Confirm that its target interaction screen exists in the released `net.conczin.mca` client classes, and test that:

- the Standing button appears only on the intended MCA screen;
- failure to resolve the screen class removes only this convenience button;
- keybind/API-pushed screen routes still work; and
- a dedicated server never loads the screen bridge.

Prefer moving this file to `client/compat/McaScreenCompat.java` so its physical-side ownership is obvious. If preserving its current package to minimize churn, allowlist exactly this one class in the client-reference test and prove that only `ReputationClient` reaches it.

### 14.4 Failure policy

Retain the current safe failure mode:

- failed MCA binding logs one startup error;
- existing saved reputation remains readable through commands;
- no new automatic MCA deeds are detected;
- server play continues; and
- no partial member set is treated as “available.”

Because metadata makes MCA required, “MCA absent” is mostly a diagnostic/test state, but the reflection layer must remain non-throwing.

## 15. Phase 9 — datapacks and static resources

### 15.1 Keep custom reload paths

Minecraft's 1.21 singular-folder migration applies to vanilla registries/tags, not arbitrary directories scanned by this mod's `ReputationReloadListener`. Preserve these custom paths:

```text
data/<namespace>/mcareputation/incidents/**/*.json
data/<namespace>/mcareputation/reputation_tiers/**/*.json
data/<namespace>/mcareputation/titles/**/*.json
```

Also preserve the legacy compatibility paths:

```text
data/<namespace>/mcaquests/reputation_tiers/**/*.json
data/<namespace>/mcaquests/titles/**/*.json
```

Therefore the shipped resources under `data/mcareputation/mcareputation/...` and `data/mcaquests/mcaquests/titles/...` do **not** get singularized.

### 15.2 Shipped content

All existing ids and JSON files remain:

- 15 `mcareputation` incident definitions, including legacy/project/promise/quest/restitution/situation/assault/kill entries;
- `mcareputation:mcareputation/reputation_tiers/default.json`;
- `mcaquests:mcaquests/titles/honored_of_village.json`;
- `mcaquests:mcaquests/titles/revered_of_village.json`; and
- `assets/mcareputation/lang/en_us.json`.

The repository inventory contains 15 incident JSON files, despite older summaries that may say 14; use the actual tree as authority.

Run the real reload path, not only direct codec unit tests. Confirm the built-in fallback ladder is live before first reload and remains the fallback if strict reload rejects new content.

### 15.3 Reload API

`SimplePreparableReloadListener` and the existing prepare/apply architecture remain valid in 1.21.1. Compile-check the method signatures and executor/profiler parameters, but do not replace the registry system with vanilla datapack registries as part of this port.

### 15.4 Language parity

Run `LangParityTest` after all code and documentation changes. New error messages or GUI labels need matching `en_us.json` keys; deleted Forge-only diagnostics must not leave orphan keys if the test enforces two-way parity.

## 16. Phase 10 — tests

### 16.1 Preserve the existing suite

Migrate all 27 current test classes. A compiler error in a test is not a reason to delete or disable it. Pure domain tests should retain their assertions; platform-facing tests need new fixtures/imports.

| Test area | Existing classes | Port action |
|---|---|---|
| Classloading | `OptionalClassloadTest` | Rewrite Forge/DistExecutor assumptions for NeoForge, client isolation, metadata, and the single MCA root |
| API | `CoreIncidentAuthorityTest`, `McaReputationApiTest` | NeoForge event types; expect API version 2; retain authority/failure contracts |
| Client logic | `FeedbackPresentationTest`, `RequestThrottleTest`, `ScrollMathTest`, `SelectorMathTest` | Preserve as pure tests; add four-axis scroll adapter coverage if needed |
| Commands | `CommandTreeTest` | Compile under 1.21.1 command APIs; preserve the whole tree and permission assertions |
| Community | `CommunityKeyTest` | New resource-location factories and `STREAM_CODEC` round trips/bad ids |
| Data/resources | `ContentValidationTest`, `LangParityTest` | `ComponentSerialization.CODEC` and all new sprite/metadata presence checks |
| Incidents | `AwarenessTest`, `DecayTest`, `IncidentCodecTest`, `IncidentNbtRoundTripTest`, `ResolutionTest` | Mechanical identifier/API updates; preserve semantics |
| Network | `FeedbackMergeTest`, `SnapshotPacketTest` | NeoForge payload types, registry-friendly buffers, decode caps, all five packets |
| Reputation | `ReputationMathTest`, `ReputationServiceTest`, `StandingPipelineTest`, `TierTest`, `TestServiceContext` | NeoForge Event import; preserve transaction/event ordering |
| State | `DedupeTest`, `PruningTest`, `SavedDataTest` | New save seam plus golden 1.20.1 file fixture |
| Shared fixtures | `TestFixtures` | Replace ResourceLocation constructors; keep canonical ids/data |

### 16.2 Rewrite `OptionalClassloadTest`

The new static checks must prove:

- no `net.minecraftforge` import, descriptor, constant-pool entry, or resource;
- no `DistExecutor` or `SimpleChannel`;
- only `net.conczin.mca` is an accepted 1.21.1 reflection root;
- no direct MCA `CONSTANT_Class` entry anywhere;
- no companion or Architectury class reference;
- `ClientPacketHandler` has no client package/class reference in its constant pool;
- no class outside `dev.otectus.mcareputation.client` references `net.minecraft.client`, except `compat/McaScreenCompat` if it remains explicitly allowlisted as client-only;
- common class initialization succeeds under a dedicated-server classpath;
- there is no mixin config or metadata declaration;
- `META-INF/neoforge.mods.toml` exists and `META-INF/mods.toml` does not;
- MCA is required; Quests and Conversations are optional;
- dependency type fields use `required`/`optional`; and
- the jar contains every expected GUI sprite and `.png.mcmeta`.

Do not merely scan Java source strings. Keep the existing bytecode/constant-pool inspection approach and run it against compiled classes or the output jar.

### 16.3 New focused tests

Add these tests if the corresponding behavior is not already directly covered:

1. **`LegacySavedDataCompatibilityTest`** — exact 1.20.1 compressed fixture load and semantic round trip.
2. **`PayloadCodecTest`** — all packet codecs, optionals, components, maxima, and malicious counts.
3. **`DedicatedServerClassloadTest`** — load/initialize common entry, network registration, and payload DTOs with client classes unavailable.
4. **`NeoForgeMetadataTest`** — parse the generated TOML and inspect jar paths/dependencies.
5. **`GuiSpriteMetadataTest`** — decode all seven nine-slice metadata files and verify dimensions/borders match the PNGs.
6. **`McaBinaryAbiTest`** — opt-in integration test given the resolved MCA jar; check every class/method in the reflection table without linking at compile time.
7. **`DamageHookGameTest`** or harness test — prove the post-damage amount reaches threshold/coalescing and zero/cancelled damage does not.
8. **`ClientSinkInstallationTest`** — default no-op is server safe and client setup replaces every sink exactly once.

If the JUnit JVM lacks Minecraft bootstrap state needed for registry-aware components, move only those cases to GameTest or a bootstrapped test fixture. Do not replace a real codec test with a mocked codec.

### 16.4 Game/runtime tests

Automated unit tests cannot prove event wiring. Add GameTests where practical for:

- player login reconciliation;
- end-of-server-tick feedback flushing;
- damage threshold and player/tamed-owner attribution;
- assault coalescing;
- assault-to-kill folding;
- logout cleanup; and
- reload listener registration.

If MCA cannot run reliably inside GameTest, create a tiny test-only authority/resolver seam around only the MCA classification/resolution call. Do not make production server authority injectable from the client.

## 17. Phase 11 — documentation and release identity

Update every active document, not only the README.

| File | Required changes |
|---|---|
| `README.md` | Minecraft 1.21.1, NeoForge, Java 21, the tested MCA file/version range, install/build instructions, artifact wording, API v2 link |
| `API.md` | “NeoForge events,” NeoForge imports/metadata syntax, API version 2, companion recompile requirement, unchanged semantic contracts |
| `CHANGELOG.md` | New 0.4.0 section: loader/MC/Java port, payload rewrite, old-save compatibility, GUI sprite migration, API generation |
| `CONFIG.md` | Confirm filenames/keys unchanged; replace platform terminology only |
| `CURSEFORGE.md` | 1.21.1 NeoForge dependencies, Java 21, MCA version, no Forge artifact compatibility |
| `DATAPACK.md` | Confirm custom paths unchanged; document Component codec acceptance and 1.21.1 target |
| `DIAGNOSIS.md` | NeoForge log locations/version dump, new payload ids, MCA root/self-test, no Forge/SRG guidance |
| `IMPLEMENTATION_NOTES.md` | Source pins, exact resolved MCA jar and SHA-256, reflected ABI results, key port decisions |
| `MIGRATION.md` | Separate legacy Quests import from Minecraft 1.20.1 → 1.21.1 world upgrade; require backup; state no downgrade guarantee |
| `PRODUCTION_TESTS.md` | Replace old Forge/MCA 7.6–7.7 matrix with this NeoForge matrix; reset unrun runtime cells; record hashes |
| `MCA-Reputation-Initial-Version-Spec.md` | Add a prominent historical 1.20.1 Forge banner/link to the new port spec; do not silently rewrite history |
| `LICENSE.md` | No content change expected; verify packaging retains it |

Do not leave claims that the build is reobfuscated, that MCA ships a Forgix universal jar, that Java 17 is required, or that Forge 47 is supported by the new artifact.

## 18. File-by-file implementation matrix

This matrix covers the audited production tree. “Compile/behavior check” means no planned redesign, but the file must still compile and its tests must run on 1.21.1.

### 18.1 Root, API, and events

| File(s) | Action |
|---|---|
| `McaReputation.java` | Replace two ResourceLocation constructors; retain ids/logger/constants |
| `McaReputationConfig.java` | `ForgeConfigSpec` → `ModConfigSpec`; preserve all definitions |
| `McaReputationMod.java` | Constructor injection, config registration, NeoForge buses, payload listener, registry wiring, cleanup hooks |
| `api/McaReputationApi.java` | Set API version 2; NeoForge `ModList` if referenced; otherwise preserve all public methods |
| `api/CoreIncidentAuthority.java`, `CoreIncidentAuthorityRegistration.java`, `CoreIncidentKind.java` | Compile/behavior check; preserve authority ids/ownership/close semantics |
| `api/ExternalGossipCandidate.java`, `ImportResult.java`, `IncidentQuery.java`, `LegacyImportProvider.java`, `LegacyImportRequest.java` | Compile/behavior check; no platform redesign |
| `api/ReputationIncidentView.java`, `ReputationMirror.java`, `ReputationQuery.java`, `ReputationRequest.java`, `ReputationResult.java`, `ReputationSnapshot.java`, `ResolutionResult.java` | Compile/behavior check; keep immutable/public contracts |
| `api/event/ReputationEvent.java` | NeoForge Event base; remove `isCancelable` override |
| `api/event/ReputationChangedEvent.java`, `ReputationIncidentCreatedEvent.java`, `ReputationIncidentResolvedEvent.java`, `ReputationTierChangedEvent.java`, `ReputationTitleGrantedEvent.java` | Compile against new base; preserve fields and immutability |

### 18.2 Client

| File(s) | Action |
|---|---|
| `client/ClientReputationData.java` | `PacketDistributor.sendToServer`; otherwise preserve cache/merge/screen behavior |
| `client/ReputationClient.java` | NeoForge events/imports, `ClientTickEvent.Post`, client setup sink installation |
| `client/GuiTextures.java` | Replace sheet UV/nine-slice calls with GUI sprite ids/`blitSprite` |
| `client/ReputationScreen.java` | Four-argument background and scroll signatures; use `scrollY`; visual regression pass |
| `client/ReputationTierToast.java` | Use `minecraft:toast/system` sprite; retain timing/token/text |
| `client/SpriteButton.java` | Compile/render signature check |
| `client/FeedbackPresentation.java`, `GuiPalette.java`, `RequestThrottle.java`, `ScrollMath.java`, `SelectorMath.java` | Pure logic; preserve and rerun tests |

### 18.3 Commands, community, and MCA compatibility

| File(s) | Action |
|---|---|
| `command/CommunityArgument.java` | Compile under 1.21.1 Brigadier/Minecraft API; preserve parser/suggestions |
| `command/ReputationCommand.java` | Compile; preserve tree, permission, audit log, and output semantics |
| `community/CommunityKey.java` | Add `STREAM_CODEC`; retain NBT/JSON/string forms and negative-id guard |
| `community/CommunityMetadata.java` | Compile existing NBT/display helpers; optional stream-codec cleanup only if tested |
| `community/CommunityResolver.java` | Compile against 1.21.1 world/entity APIs; preserve MCA/fallback resolution order |
| `compat/McaReflect.java` | NeoForge `ModList`, single root, updated comments, binary ABI validation |
| `compat/McaCompat.java` | Compile and run every safe wrapper path; preserve fallbacks |
| `compat/McaScreenCompat.java` | Confirm 1.21.1 client screen name; retain client-only reflection/failure behavior |

### 18.4 Data, gameplay events, and incidents

| File(s) | Action |
|---|---|
| `data/ReputationContentValidator.java` | Compile with new component codec; preserve all severity/range/cross-reference checks |
| `data/ReputationReloadListener.java` | Compile listener signatures; preserve custom/legacy paths and atomic swap |
| `event/ReputationGameplayEvents.java` | NeoForge events; `LivingDamageEvent.Post`; post-tick; retain all detection semantics |
| `event/AssaultTracker.java`, `CoreIncidentAuthorities.java`, `LegacyImportProviders.java` | Compile/behavior check; preserve static cleanup and failure policy |
| `incident/IncidentDefinition.java` | `ComponentSerialization.CODEC` |
| `incident/BuiltinIncidents.java` | ResourceLocation factory |
| `incident/AwarenessResolver.java`, `DecayPolicy.java`, `GossipSpec.java`, `IncidentDisplay.java`, `IncidentRecord.java`, `IncidentRegistry.java`, `IncidentSeverity.java`, `IncidentStatus.java`, `IncidentSubject.java`, `IncidentVisibility.java`, `ResolutionPolicy.java`, `SubjectKind.java`, `WitnessResolver.java` | Compile/behavior check; preserve bounds, NBT, codecs, witness order, and fallbacks |

### 18.5 Network, reputation, state, and utilities

| File(s) | Action |
|---|---|
| `network/ReputationNetwork.java` | Full payload/codec/handler/send rewrite; preserve DTO data, selection, bounds, authority |
| `network/ClientPacketHandler.java` | Replace DistExecutor with client-type-free installed sinks |
| `network/ReputationFeedback.java` | NeoForge event types and `ServerTickEvent.Post` |
| `network/SnapshotSelection.java` | Pure logic; preserve tests |
| `reputation/ReputationTier.java` | Component codecs |
| `reputation/ReputationTiers.java` | Four ResourceLocation factories |
| `reputation/TitleDefinition.java` | Component codecs and `BuiltInRegistries.ITEM` lookup |
| `reputation/ServiceContext.java` | NeoForge Event and `NeoForge.EVENT_BUS` |
| `reputation/ReputationService.java` | NeoForge Event parameter; preserve the transaction implementation |
| `reputation/ReputationBounds.java`, `ReputationMath.java`, `ReputationTierSet.java`, `TitleScope.java`, `TitleService.java`, `Titles.java` | Compile/behavior check; preserve pure/domain behavior |
| `state/ReputationSavedData.java` | Factory/registry-aware save signature, pure payload seam, unchanged format |
| `state/CommunityReputationRecord.java`, `PlayerReputationRecord.java` | Compile NBT APIs; preserve exact schema, limits, pruning, dedupe rebuild |
| `util/EnumCodecs.java`, `StrictCodecs.java` | Compile with DataFixerUpper codecs; preserve strict optional behavior |

### 18.6 Resource operations

| Current path | Action |
|---|---|
| `src/main/resources/META-INF/mods.toml` | Delete after generating `src/main/templates/META-INF/neoforge.mods.toml` |
| `src/main/resources/pack.mcmeta` | Delete for main mod pack |
| `assets/mcareputation/textures/gui/reputation.png` | Split into nine GUI sprites; keep only as optional tooling source |
| `assets/mcareputation/lang/en_us.json` | Preserve; update keys only if code surface changes |
| `data/mcareputation/mcareputation/incidents/*.json` | Preserve all 15 files and ids; validate with new component codec |
| `data/mcareputation/mcareputation/reputation_tiers/default.json` | Preserve thresholds/ids/titles |
| `data/mcaquests/mcaquests/titles/*.json` | Preserve both compatibility definitions and paths |
| `tools/GenerateGuiTexture.java` | Emit or extract the new sprites and metadata deterministically |

## 19. Build and verification runbook

### 19.1 Static source gate

```bash
rg -n 'net\.minecraftforge|MinecraftForge|ForgeConfigSpec|ForgeRegistries|SimpleChannel|NetworkEvent|DistExecutor' \
    src/main/java src/test/java build.gradle settings.gradle gradle.properties
rg -n 'new ResourceLocation\(' src
rg -n 'ExtraCodecs\.COMPONENT|META-INF/mods\.toml' src build.gradle
```

Expected: no active-code matches.

Also scan for direct optional/MCA imports:

```bash
rg -n '^import (net\.conczin\.mca|net\.mca|forge\.net\.|dev\.otectus\.mcaquests|dev\.otectus\.mcaconversations|dev\.architectury)' \
    src/main/java src/test/java
```

Expected: empty.

### 19.2 Gradle gate

Run with Java 21:

```bash
java -version
./gradlew --version
./gradlew clean test build checkJarContents
./gradlew dependencies --configuration runtimeClasspath
./gradlew dependencyInsight --dependency minecraft-comes-alive --configuration runtimeClasspath
```

Expected:

- Gradle JVM and Java toolchain are 21;
- all migrated tests pass with no disabled tests introduced by the port;
- `build` produces one main mod jar;
- MCA appears only in development runtime resolution, not API/implementation publication;
- the MCA jar is the NeoForge 1.21.1 file;
- no Fabric Loader/API appears as an accidental MCA transitive; and
- jar verification passes.

If tests report a different count than the baseline, account for every added/removed test in the changelog.

### 19.3 Jar gate

For the final jar:

```bash
PORT_JAR="$(find build/libs -maxdepth 1 -type f -name 'mcareputation-*.jar' \
    ! -name '*sources*' ! -name '*javadoc*' | head -n 1)"
test -n "$PORT_JAR"
jar tf "$PORT_JAR" | sort
unzip -p "$PORT_JAR" META-INF/neoforge.mods.toml
sha256sum "$PORT_JAR"
```

Manually/automatically assert:

- correct mod id/version/license/authors/description;
- exact Minecraft, NeoForge, and MCA ranges;
- optional Quests/Conversations declarations;
- no Forge metadata;
- no main `pack.mcmeta`;
- 15 incidents, one ladder, two compatibility titles, language file, nine sprites, and seven sprite metadata files;
- no shaded third-party classes;
- no mixins; and
- no client reference from common class bytecode.

### 19.4 Development runtime gate

```bash
./gradlew runClient
./gradlew runServer
./gradlew runGameTestServer
```

For `runServer`, accept the EULA only in the disposable test run directory. Confirm:

- one successful MCA reflection self-test line;
- one content reload summary;
- no duplicate event or payload registration;
- no missing-refmap/mixin issue attributable to this mod;
- no `NoClassDefFoundError`;
- no tick-spam log; and
- clean stop/start state.

### 19.5 Production-style dedicated-server gate

Create a fresh server containing only:

- NeoForge 21.1.249;
- the final MCA: Reputation jar; and
- the pinned MCA Reborn NeoForge file 8658484 (release labeled 7.7.36).

Join with a separate matching client. This is the authoritative classloading/network test. Exercise:

- login;
- command query and admin mutation;
- keybind;
- MCA screen Standing button;
- snapshot selector;
- damage and killing;
- action-bar feedback and toast;
- disconnect/reconnect;
- full server restart; and
- datapack reload.

Repeat with an empty reputation save and the copied 1.20.1 world.

### 19.6 Installation combinations

| Combination | Client | Dedicated server | Required result |
|---|:---:|:---:|---|
| MCA + Reputation | ☐ | ☐ | Full standalone feature set |
| MCA + Reputation + NeoForge Quests port | ☐ | ☐ | API v2 bridge, mirror/import/screen route; no duplicate writes |
| MCA + Reputation + NeoForge Conversations port | ☐ | ☐ | API v2 bias/gossip paths; clean fallback |
| MCA + all three | ☐ | ☐ | Both bridges coexist |
| MCA + Reputation + MCA: Crime NeoForge port or authority harness | ☐ | ☐ | Core authority prevents duplicate assault/kill |
| MCA + Quests/Conversations without Reputation | ☐ | ☐ | Tested in those projects; no Reputation classload |

If a companion has no 1.21.1 NeoForge artifact, do not install a Forge jar. Use a minimal NeoForge test mod compiled against API 2 to exercise registration, mirror, import, event, authority, and open-screen contracts. Lack of an external optional port does not justify adding a hard dependency.

### 19.7 Functional regression

Carry forward every scenario in `PRODUCTION_TESTS.md`. At minimum verify:

- same numeric village id in two dimensions remains separate;
- two players in one village remain separate;
- witnessed/unwitnessed assault and killing behavior;
- self-defense reduction;
- projectile, potion, and tamed-owner attribution;
- sustained-hit coalescing;
- assault-to-kill folding and rollback;
- rumor awareness;
- decay, including backward then forward `/time` changes;
- all commands and audit logging;
- score/tier/title/ledger persistence;
- deleted/renamed village metadata behavior;
- strict and lenient reload;
- snapshot selection regression with two nearby villages;
- maximum ledger packet;
- request spam/rate limiting;
- legacy Quests import once-only behavior;
- mod removal/reinstall data behavior;
- every common subsystem toggle; and
- no idle-world scan when nobody is online.

## 20. Risk register

| Risk | Symptom | Prevention / release gate |
|---|---|---|
| Wrong MCA loader artifact | Fabric classes/dependencies or startup failure | Immutable file id, inspect jar metadata/name, record SHA-256 |
| False MCA compatibility range | Startup self-test fails on an “allowed” version | Narrow initial range; test each bound's released jar |
| Client class leaks into common bytecode | Dedicated-server `NoClassDefFoundError` | Installed sink boundary, constant-pool test, separate-server launch |
| Payload decoder allocates unbounded collections | Memory/packet abuse | `ByteBufCodecs.list(max)` on all three synced collection types |
| Packet rewrite changes authority | Client can select unknown data or another player | Retain one-intent C2S packet and all server validation tests |
| Wrong damage stage | Chip/cancelled/absorbed attacks become incidents | `LivingDamageEvent.Post.getNewDamage()` plus runtime tests |
| Old data overwritten/reset | Missing scores/ledgers after upgrade | Golden fixture, copied-world test, unchanged id/schema/version |
| Double event registration | Duplicate incidents/feedback | One registration style and single-fire tests |
| GUI sprite metadata mismatch | stretched corners, seams, missing texture | exact crop table, metadata parser test, multi-scale screenshots |
| API version ambiguity | Companion accepts incompatible events then links badly | API 2 and explicit companion handshake/rebuild |
| Historical custom paths “modernized” | shipped datapacks disappear | keep custom listener paths; real reload test |
| Forge concepts remain in docs/build | users install wrong loader/JDK/artifact | repository-wide terminology scan and doc matrix |

## 21. Suggested commit structure

Keep commits reviewable and bisectable:

1. `build: move 1.21.1 target to NeoForge ModDevGradle`
2. `refactor: migrate namespaces and 1.21 resource identifiers`
3. `fix: preserve reputation SavedData on 1.21.1`
4. `refactor: replace SimpleChannel with bounded custom payloads`
5. `fix: bind gameplay hooks to NeoForge post-damage and tick events`
6. `refactor: isolate client payload handling without DistExecutor`
7. `feat: move standing UI to 1.21 GUI sprites`
8. `fix: validate released MCA NeoForge reflection ABI`
9. `test: add old-save, payload, metadata, and server-classload gates`
10. `docs: publish NeoForge 1.21.1 compatibility and migration notes`

Do not combine generated binaries, behavior changes, and documentation into one opaque commit.

## 22. Final acceptance checklist

The implementing agent should check every box before declaring the port finished.

### Build/platform

- [ ] Java 21 and Gradle 9.2.1 are in use.
- [ ] ModDevGradle 2.0.146 and NeoForge 21.1.249 resolve.
- [ ] No ForgeGradle/reobf configuration remains.
- [ ] The correct MCA NeoForge jar is pinned and hashed.
- [ ] `clean test build checkJarContents` passes.

### Code

- [ ] No Forge imports or direct ResourceLocation constructors remain.
- [ ] Configs, command argument, reload listener, commands, and stop cleanup register exactly once.
- [ ] All five messages are required custom payloads with stable ids.
- [ ] All payload collection and string decoders are bounded.
- [ ] C2S validation remains server authoritative.
- [ ] `LivingDamageEvent.Post` supplies actual damage.
- [ ] Public events are NeoForge, non-cancellable, post-commit, and API version 2.
- [ ] `SavedData` uses a factory/provider signature without changing NBT format 1.
- [ ] Common/network bytecode contains no client references.
- [ ] MCA is reflection-only and all released-jar members resolve.

### Resources/docs

- [ ] `neoforge.mods.toml` is generated and correct.
- [ ] Old `mods.toml` and main `pack.mcmeta` are absent.
- [ ] Nine GUI sprites and seven nine-slice metadata files ship.
- [ ] All 15 incident, one ladder, two title, and language resources reload.
- [ ] Custom `mcareputation`/`mcaquests` data paths are unchanged.
- [ ] Every active Markdown document names the correct platform/version/JDK.

### Compatibility/runtime

- [ ] Exact 1.20.1 `mcareputation.dat` fixture loads and round-trips.
- [ ] A copied real world retains players, communities, scores, incidents, titles, and migration markers.
- [ ] Clean client and dedicated server start and stop.
- [ ] A separate client can join and use every screen/packet path.
- [ ] MCA interaction button, keybind, action bar, and toast work.
- [ ] Damage, death, reconciliation, decay, and feedback work.
- [ ] Quests/Conversations absence is clean; ported bridges or harness pass API 2 tests.
- [ ] No errors, client classloading failures, duplicate registration, or per-tick log spam remain.
- [ ] Final jar contents and SHA-256 are recorded.

## 23. Sources and implementation references

Repository/source pins:

- [MCA: Reputation pinned baseline](https://github.com/otectus/MCAReputation/tree/96e82978a346ffc6b0d2e72d6c0350c4971612ca)
- [MCA Reborn 1.21.1 source pin](https://github.com/Luke100000/minecraft-comes-alive/tree/575691bd6e09d4be2f828340683247dc2a2c4fdb)
- [Official NeoForge 1.21.1 ModDevGradle MDK pin](https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle/tree/70d335c962ee8a773b38fb0690c7e7f30d1bafa6)
- [NeoForge 1.21.1 source pin](https://github.com/neoforged/NeoForge/tree/6d9e718cd4c3c9ed0cfb2cd80480d777ea5feed6)
- [MCA Reborn 7.7.36 1.21.1 releases](https://www.curseforge.com/minecraft/mc-mods/minecraft-comes-alive-reborn/files/all?version=1.21.1)

NeoForge documentation:

- [NeoForge 1.21.1 documentation](https://docs.neoforged.net/docs/1.21.1/)
- [Payload networking](https://docs.neoforged.net/docs/1.21.1/networking/payload/)
- [Stream codecs](https://docs.neoforged.net/docs/1.21.1/networking/streamcodecs/)
- [Events](https://docs.neoforged.net/docs/1.21.1/concepts/events/)
- [Configuration](https://docs.neoforged.net/docs/1.21.1/misc/config/)
- [Registries](https://docs.neoforged.net/docs/1.21.1/concepts/registries/)
- [Saved data](https://docs.neoforged.net/docs/1.21.1/datastorage/saveddata/)
- [Screens and GUI sprites](https://docs.neoforged.net/docs/1.21.1/gui/screens/)
- [Resource metadata](https://docs.neoforged.net/docs/resources/metadata/)
- [Mod metadata](https://docs.neoforged.net/docs/gettingstarted/modfiles/)
- [ModDevGradle](https://docs.neoforged.net/toolchain/docs/plugins/mdg/)
- [Minecraft 1.21 primer](https://docs.neoforged.net/primer/docs/1.21/)

When documentation and the pinned 1.21.1 source disagree, compile against the pinned target and treat its source/signatures as authoritative. Before release, repeat the checks against the exact resolved binaries and record any drift in `IMPLEMENTATION_NOTES.md`.
