# MCA: Reputation — Minecraft 1.21.1 / NeoForge port plan

**Purpose:** implementation-ready plan for porting MCA: Reputation from Minecraft 1.20.1 Forge to Minecraft 1.21.1 NeoForge.

**Repository baseline:** [`otectus/MCAReputation` at `8fac797`](https://github.com/otectus/MCAReputation/commit/8fac797bb452976ff92a97bc5266460943ee15f3), inspected 2026-08-25. The target MCA API was checked against the upstream `1.21.1` branch at `2fb08515d985810323a91f03477a36595b517682` and the `7.7.36+1.21.1` release artifact.

**Intended reader:** a coding agent that will make the changes, run the automated and manual verification, and prepare a release-quality pull request.

---

## 1. Outcome and fixed decisions

The finished branch must produce a single NeoForge mod JAR for Minecraft 1.21.1. It must retain the mod id, Java package, gameplay behavior, public API semantics, commands, configuration keys, localization keys, datapack ids, and existing saved-data format unless this plan explicitly says otherwise.

Use these target pins. Do not substitute an unbounded `latest` version during the port.

| Item | Current baseline | Port target |
|---|---:|---:|
| Minecraft | `1.20.1` | `1.21.1` |
| Loader | Forge `47.4.10` | NeoForge `21.1.248` |
| Build plugin | ForgeGradle `[6.0,6.2)` | ModDevGradle `2.0.144` |
| Gradle wrapper | existing Forge wrapper | `9.2.1` |
| Java | `17` | `21` |
| Mappings | Mojang official `1.20.1` | Parchment `2024.11.17` for `1.21.1` |
| MCA Reborn compile pin | `7.7.0-beta.2+1.20.1` | `7.7.36+1.21.1` |
| MCA runtime range | `[7.6,8)` | `[7.7.36,8)` |
| Mod version | `0.2.0` unreleased | keep `0.2.0` |
| Public API version | `1` | keep `1` |
| Network protocol | `2` | `3` |
| Saved-data format | `1` | keep `1` |

Version rationale:

- The repository says `0.1.0` was never published and `0.2.0` is the first intended release. This port changes the platform, not the feature contract, so keep `mod_version=0.2.0`. If a `0.2.0` artifact is published before this branch merges, resolve that release-management conflict deliberately and bump the port; do not silently publish two different binaries under one version.
- Keep `McaReputationApi.getApiVersion()` at `1`: the API operations and event meanings remain the same. NeoForge consumers must be recompiled because their loader/event imports change, but that is a platform boundary rather than a new application-level API contract.
- Bump the network protocol to `3` because `SimpleChannel` messages are being replaced by named NeoForge payloads. Although 1.20.1 clients cannot join 1.21.1 servers anyway, the explicit bump makes the incompatible wire revision auditable.
- Keep `ReputationSavedData.FORMAT_VERSION == 1` and `DATA_NAME == "mcareputation"`. The target signatures change, but the NBT schema does not.

The port is **not** a multi-loader conversion. Preserve the current Forge source on its existing history and implement the NeoForge target on a dedicated branch such as `port/1.21.1-neoforge`. Do not add Architectury, a loader abstraction, mixins, or reflection merely to keep one source tree compiling for both platforms.

---

## 2. Repository inventory and migration surface

The baseline is a complete server-authoritative reputation system, not a small event hook. It contains 75 main Java classes, 24 test classes, 241 tests according to the changelog, custom commands, five packets, reloadable codec data, an MCA compatibility layer, a client screen, and world-global `SavedData`.

The main subsystems are:

| Subsystem | Primary files | Port risk |
|---|---|---|
| Loader lifecycle and config | `McaReputationMod`, `McaReputationConfig`, build metadata | High |
| Custom networking | `ReputationNetwork`, `ClientPacketHandler`, `ClientReputationData` | High |
| World persistence | `ReputationSavedData` and NBT records | High |
| Gameplay event capture | `ReputationGameplayEvents`, `ReputationFeedback` | High |
| MCA linkage | `compat/McaCompat`, `compat/McaScreenCompat` | High |
| Client UI | `ReputationClient`, `ReputationScreen`, toast/cache helpers | Medium |
| Public event API | `api/event/*`, `ReputationService`, `ServiceContext` | Medium |
| Datapack reload/codecs | `ReputationReloadListener`, definitions and validators | Medium |
| Commands | `CommunityArgument`, `ReputationCommand` | Medium |
| Pure domain model | incidents, tiers, math, awareness, pruning, resolution | Low |
| Resources and docs | metadata, JSON, Markdown | Medium |

Direct legacy dependencies found in the baseline:

- Forge imports occur in 12 main files and one test support file.
- MCA imports occur only in `McaCompat`, `McaScreenCompat`, and the optional-classloading test, which is the boundary to preserve.
- `new ResourceLocation(...)` occurs in three main files and six test files.
- `javax.annotation.Nullable` occurs in 15 main/test files and should be normalized to JetBrains annotations for the Java 21/NeoForge toolchain.
- `ExtraCodecs.COMPONENT` occurs in `IncidentDefinition`, `ReputationTier`, and `TitleDefinition`.
- The three old tick handlers are in `ReputationClient`, `ReputationGameplayEvents`, and `ReputationFeedback`.
- The only old damage event is `LivingHurtEvent` in `ReputationGameplayEvents`.
- The only Forge item-registry lookup is in `TitleDefinition`.

Do not rewrite low-risk domain code preemptively. First make platform boundary changes, compile, and use compiler failures to identify genuine vanilla 1.21.1 drift.

---

## 3. Behavioral invariants

Treat these as non-negotiable acceptance criteria throughout the implementation:

1. **Server authority remains intact.** A client can request only its own snapshot and may supply only a context entity id or an already-known community hint. It cannot author scores, incidents, titles, witnesses, deltas, or arbitrary player ids.
2. **Saved worlds remain readable.** A reputation save created by the 1.20.1 build loads under 1.21.1 without resetting scores, identities, baselines, incidents, titles, dedupe entries, high-water marks, or cached village metadata.
3. **Reputation identity remains dimension-aware.** The same numeric MCA village id in two dimensions must remain two distinct `CommunityKey` values.
4. **Damage uses actual health loss.** The configured chip-damage threshold must be applied after mitigation, and canceled or fully negated hits must not become public deeds.
5. **Assault/death coalescing remains exact.** An assault followed by a killing totals the killing penalty, not assault plus killing, including the current rollback behavior when the death carries no public weight.
6. **No client class is initialized on a dedicated server.** Common packet registration must not reference `Minecraft`, screens, toast classes, or MCA client GUI classes in its constant pool.
7. **Optional companions remain optional.** Do not add compile or runtime dependencies on MCA: Quests or MCA: Conversations; do not shade their classes.
8. **No Architectury and no mixins.** MCA Reborn is the only required content-mod dependency.
9. **All variable-size network fields remain bounded.** Keep or strengthen collection and string limits on both encode and decode.
10. **Datapack reload remains atomic.** In strict mode a failed reload leaves the previous live registry in place; in lenient mode only invalid definitions are skipped.
11. **No silent feature cuts.** Commands, standing button, unbound key, screen, feedback, toasts, public events, legacy import seams, and all shipped definitions must remain.
12. **No downgrade promise.** Test upgrading a copy of a 1.20.1 world. Clearly document that opening a world in 1.21.1 is not a supported path back to 1.20.1.

---

## 4. Branch and implementation sequence

Use small, reviewable commits in this order. Do not combine the saved-data or networking rewrites with unrelated cleanup.

1. `chore: capture 1.20.1 port baseline`
2. `build: migrate ForgeGradle project to NeoForge ModDevGradle`
3. `refactor: move lifecycle config and event bus wiring to NeoForge`
4. `refactor: update Minecraft 1.21.1 identifiers codecs and saved data`
5. `refactor: move MCA compatibility layer to 1.21.1 packages`
6. `refactor: replace SimpleChannel messages with NeoForge payloads`
7. `refactor: port gameplay and client events to NeoForge`
8. `test: migrate harness and add save/payload compatibility coverage`
9. `docs: document 1.21.1 NeoForge support and migration`
10. `chore: complete production verification matrix`

Before the first edit:

- Create the port branch from `8fac797bb452976ff92a97bc5266460943ee15f3`.
- Run `./gradlew clean test build` with JDK 17 and record the baseline result and test count. If it is not 241 passing tests, stop and explain the pre-existing discrepancy rather than treating it as a port regression.
- Record the baseline artifact hash.
- Produce a checked-in golden saved-data fixture using the unmodified 1.20.1 code as described in section 13. Do this before changing serializers.
- Make a disposable copy of a representative 1.20.1 world for later manual migration testing. Never use the only copy.

At the end of every commit, run at least `./gradlew compileJava compileTestJava`. At the end of every subsystem, run the full test task.

---

## 5. Build-system migration

### 5.1 Wrapper and settings

Update the Gradle wrapper to `9.2.1`, matching the current official 1.21.1 ModDevGradle MDK. Preserve executable bits on `gradlew`.

`gradle/wrapper/gradle-wrapper.properties` must contain:

```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-9.2.1-bin.zip
networkTimeout=10000
validateDistributionUrl=true
```

Replace the Forge Maven plugin repository in `settings.gradle`. The target is:

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

Do not add an absolute `org.gradle.java.home`; continue to rely on `JAVA_HOME` or toolchain provisioning.

### 5.2 `gradle.properties`

Replace the Forge/mapping properties with these pins:

```properties
org.gradle.jvmargs=-Xmx4G
org.gradle.daemon=false

parchment_minecraft_version=1.21.1
parchment_mappings_version=2024.11.17

minecraft_version=1.21.1
minecraft_version_range=[1.21.1]
neo_version=21.1.248
neo_version_range=[21.1.248,)
loader_version_range=[1,)

mca_version=7.7.36+1.21.1
mca_version_range=[7.7.36,8)

mod_id=mcareputation
mod_name=MCA: Reputation
mod_license=GPL-3.0-only
mod_version=0.2.0
mod_group_id=dev.otectus.mcareputation
mod_authors=otectus
mod_description=Public standing with MCA villages: your deeds become stories, and those stories shape how the village treats you.
```

Remove `forge_version`, `forge_version_range`, `mapping_channel`, and `mapping_version`. Rewrite comments that claim ForgeGradle cannot run on JDK 21.

### 5.3 `build.gradle`

Replace ForgeGradle with:

```groovy
plugins {
    id 'java-library'
    id 'idea'
    id 'net.neoforged.moddev' version '2.0.144'
}
```

Keep `version`, `group`, `base.archivesName`, UTF-8 compilation, JUnit logging, and the custom JAR checks. Change the Java toolchain to 21.

Configure ModDevGradle along this shape:

```groovy
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
        configureEach {
            systemProperty 'forge.logging.markers', 'REGISTRIES'
            logLevel = org.slf4j.event.Level.DEBUG
        }
    }

    mods {
        mcareputation {
            sourceSet(sourceSets.main)
        }
    }

    unitTest {
        enable()
        testedMod = mods.mcareputation
    }
}
```

Keep the Modrinth repository exclusive to `maven.modrinth`, but remove the ForgeGradle-specific `fg.repository` and `fg.deobf` paths:

```groovy
repositories {
    exclusiveContent {
        forRepository {
            maven {
                name = 'Modrinth'
                url = 'https://api.modrinth.com/maven'
            }
        }
        filter { includeGroup 'maven.modrinth' }
    }
}

dependencies {
    implementation "maven.modrinth:minecraft-comes-alive-reborn:${mca_version}"

    testImplementation 'org.junit.jupiter:junit-jupiter-api:5.10.2'
    testImplementation 'org.junit.jupiter:junit-jupiter-params:5.10.2'
    testRuntimeOnly 'org.junit.jupiter:junit-jupiter-engine:5.10.2'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}
```

Retain `test { useJUnitPlatform() }`. The ModDevGradle `unitTest` block is important because tests reference Minecraft classes; do not remove it just because most domain tests are pure.

Delete the `minecraft { ... }` ForgeGradle block, the explicit `minecraft "net.minecraftforge:forge:..."` dependency, `copyIdeResources`, `reobfJar`, and `finalizedBy 'reobfJar'`. ModDevGradle's `jar`/`build` output is the distributable remapped artifact.

### 5.4 Generated metadata

Adopt the official MDK metadata-template pattern:

- Move `src/main/resources/META-INF/mods.toml` to `src/main/templates/META-INF/neoforge.mods.toml`.
- Register a `generateModMetadata` `ProcessResources` task.
- Expand `minecraft_version_range`, `neo_version_range`, `loader_version_range`, `mca_version_range`, and all current mod descriptive properties.
- Add the generated directory to `sourceSets.main.resources` and register it with `neoForge.ideSyncTask`.
- Stop expanding `pack.mcmeta`.

Do not retain both metadata files. The built JAR must contain `META-INF/neoforge.mods.toml` and must not contain `META-INF/mods.toml`.

### 5.5 Static artifact gates

Preserve and extend `checkJarContents`:

- Change the forbidden MCA package root from `forge/net/mca/` to `net/conczin/mca/`.
- Keep the companion and Architectury roots forbidden.
- Add `net/minecraftforge/` as a forbidden bytecode constant-pool string, not merely a forbidden JAR entry prefix.
- Also reject `forge/net/mca/` in bytecode so no legacy relocation survives.
- Assert that `META-INF/neoforge.mods.toml`, `assets/mcareputation/lang/en_us.json`, and representative shipped data files are present.
- Assert that `META-INF/mods.toml` is absent.

A simple Gradle check may read every packaged `.class` entry as ISO-8859-1 and search for slash-form package strings. This is not a semantic bytecode verifier, but it reliably catches stale constant-pool references. Make `build` depend on this gate.

---

## 6. Loader metadata and resources

### 6.1 `neoforge.mods.toml`

Use `modLoader="javafml"`, `loaderVersion="${loader_version_range}"`, and the existing license/name/version/authors/description. Add the repository issue tracker and project URL if desired, but do not fabricate release URLs.

Translate each dependency entry:

| Current | Target |
|---|---|
| `modId="forge"` | `modId="neoforge"` |
| `mandatory=true` | `type="required"` |
| `mandatory=false` | `type="optional"` |
| Forge range | `${neo_version_range}` |

Required dependency entries:

```toml
[[dependencies.${mod_id}]]
    modId="neoforge"
    type="required"
    versionRange="${neo_version_range}"
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
```

Retain `mcaquests` and `mcaconversations` as `type="optional"`, version range `[1.0,)`, ordering `BEFORE`, side `BOTH`. Their absence must never block startup. Update the comments to say “NeoForge” and make clear that their integration features require compatible 1.21.1 ports of those mods.

Keep the explicit “no mixins” and “no Architectury” intent, but remove statements tied specifically to Forge's relocated MCA package.

### 6.2 Remove `pack.mcmeta`

Delete `src/main/resources/pack.mcmeta`. NeoForge generates synthetic pack metadata for mods, so keeping the 1.20.1 `pack_format: 15` file would be stale and unnecessary.

Do **not** rename these custom paths:

- `data/mcareputation/mcareputation/incidents/**`
- `data/mcareputation/mcareputation/reputation_tiers/**`
- `data/mcaquests/mcaquests/titles/**`

The repeated directory is the mod's deliberately authored reload-listener path, not a vanilla plural-to-singular resource directory affected by 1.21 changes.

### 6.3 Small repository hygiene

Fix the `.gitignore` artifact typo from `/mcaconversations-*.jar` to `/mcareputation-*.jar` if that typo is still present. Do not mix other unrelated cleanup into the port.

---

## 7. Mechanical namespace and API map

Apply these mappings, then run the compiler. Avoid global replacement of the bare word `Forge` in historical changelog text unless that text describes current behavior.

| Forge 1.20.1 symbol | NeoForge/Minecraft 1.21.1 target |
|---|---|
| `net.minecraftforge.common.MinecraftForge.EVENT_BUS` | `net.neoforged.neoforge.common.NeoForge.EVENT_BUS` |
| `net.minecraftforge.eventbus.api.Event` | `net.neoforged.bus.api.Event` |
| `SubscribeEvent`, `EventPriority` | `net.neoforged.bus.api.*` equivalents |
| `net.minecraftforge.fml.common.Mod` | `net.neoforged.fml.common.Mod` |
| `@Mod.EventBusSubscriber` | `net.neoforged.fml.common.EventBusSubscriber` |
| `net.minecraftforge.api.distmarker.Dist` | `net.neoforged.api.distmarker.Dist` |
| `ForgeConfigSpec` | `net.neoforged.neoforge.common.ModConfigSpec` |
| `ModConfig` | `net.neoforged.fml.config.ModConfig` |
| `MinecraftForge.EVENT_BUS.register(...)` | `NeoForge.EVENT_BUS.register(...)` |
| `DeferredRegister` | `net.neoforged.neoforge.registries.DeferredRegister` |
| Forge game events | same class names under `net.neoforged.neoforge.event...`, except the explicit tick/damage changes below |
| Forge client events/settings | `net.neoforged.neoforge.client...` |
| `ForgeRegistries.ITEMS.getValue(id)` | `BuiltInRegistries.ITEM.getOptional(id)` or equivalent optional lookup |
| `new ResourceLocation(ns, path)` | `ResourceLocation.fromNamespaceAndPath(ns, path)` |
| `new ResourceLocation(text)` | `ResourceLocation.parse(text)` |
| `ExtraCodecs.COMPONENT` | `ComponentSerialization.CODEC` |
| `javax.annotation.Nullable` | `org.jetbrains.annotations.Nullable` |

MCA relocation map:

| Old MCA package | Target MCA package |
|---|---|
| `forge.net.mca.entity.VillagerEntityMCA` | `net.conczin.mca.entity.VillagerEntityMCA` |
| `forge.net.mca.entity.VillagerLike` | `net.conczin.mca.entity.VillagerLike` |
| `forge.net.mca.entity.ai.relationship.AgeState` | `net.conczin.mca.entity.ai.relationship.AgeState` |
| `forge.net.mca.server.world.data.*` | `net.conczin.mca.server.world.data.*` |
| `forge.net.mca.client.gui.InteractScreen` | `net.conczin.mca.client.gui.InteractScreen` |

The target MCA `1.21.1` branch still provides the methods this adapter uses: village id/name/center/border/resident queries, `VillageManager.get`, `getOrEmpty`, `findNearestVillage`, family-tree lookup, residency home-village lookup, age state, and villager personality string conversion. Keep those calls direct and isolated rather than replacing them with reflection.

---

## 8. Entrypoint, registration, and config

### 8.1 `McaReputationMod`

Change the constructor to injected NeoForge services:

```java
public McaReputationMod(IEventBus modBus, ModContainer modContainer) {
    modContainer.registerConfig(ModConfig.Type.COMMON, McaReputationConfig.COMMON_SPEC,
            "mcareputation-common.toml");
    modContainer.registerConfig(ModConfig.Type.CLIENT, McaReputationConfig.CLIENT_SPEC,
            "mcareputation-client.toml");

    COMMAND_ARGUMENT_TYPES.register(modBus);
    modBus.addListener(ReputationNetwork::register);
    NeoForge.EVENT_BUS.register(this);

    ReputationTiers.replaceAll(Map.of(ReputationTiers.DEFAULT_ID,
            ReputationTiers.BUILTIN_DEFAULT));
}
```

Imports:

- `net.neoforged.bus.api.IEventBus`
- `net.neoforged.fml.ModContainer`
- `net.neoforged.fml.config.ModConfig`
- `net.neoforged.neoforge.common.NeoForge`

Remove `ModLoadingContext.get()` and `FMLJavaModLoadingContext.get()`. Do not call the network registration from `FMLCommonSetupEvent`; payload registration has its own mod-bus event.

Keep the game-bus instance handlers for:

- `AddReloadListenerEvent`
- `RegisterCommandsEvent`
- `ServerStoppedEvent`

On server stop, continue clearing `AssaultTracker`, network rate limits, feedback buffers, and tick counters.

### 8.2 Command argument registration

Keep the deferred registration and `SingletonArgumentInfo.contextFree(CommunityArgument::community)` behavior. Reimport `DeferredRegister` from NeoForge. If the target generic registry type produces a compile error, create the register against `Registries.COMMAND_ARGUMENT_TYPE`; do not replace the custom argument with a greedy string. The custom argument exists to keep `minecraft:overworld/3`, player names, and `here` unambiguous and synchronized to clients.

### 8.3 Config

In `McaReputationConfig`, replace `ForgeConfigSpec` with `ModConfigSpec`; the builder/value APIs are intentionally similar. Preserve:

- every config key and default;
- common versus client spec separation;
- clamps/ranges;
- output filenames;
- public accessor behavior.

Add or update a test that loads both specs and asserts all documented keys still exist. An existing server's `mcareputation-common.toml` and a client's `mcareputation-client.toml` must remain usable without manual editing.

---

## 9. Networking rewrite

This is a functional rewrite, not an import replacement.

### 9.1 Payload inventory

Retain the five logical packets:

| Direction | Current record | Target payload id |
|---|---|---|
| C2S | `RequestSnapshotC2S` | `mcareputation:request_snapshot` |
| S2C | `SnapshotS2C` | `mcareputation:snapshot` |
| S2C | `OpenScreenS2C` | `mcareputation:open_screen` |
| S2C | `ChangeS2C` | `mcareputation:change` |
| S2C | `TierToastS2C` | `mcareputation:tier_toast` |

Each top-level packet record must implement `CustomPacketPayload` and define:

```java
public static final Type<PacketName> TYPE =
        new Type<>(McaReputation.id("payload_name"));
public static final StreamCodec<RegistryFriendlyByteBuf, PacketName> STREAM_CODEC =
        StreamCodec.ofMember(PacketName::write, PacketName::read);

@Override
public Type<PacketName> type() {
    return TYPE;
}
```

Use `StreamCodec.unit(new OpenScreenS2C())` for the empty open-screen packet. Retain `CommunitySummary`, `IncidentSummary`, and `SelectedDetail` as nested value records; they do not need their own payload `TYPE`.

Use `RegistryFriendlyByteBuf` for all five top-level codecs so component serialization has registry context. Existing primitive helpers such as `CommunityKey.write/read(FriendlyByteBuf)` may stay on `FriendlyByteBuf`, because `RegistryFriendlyByteBuf` is compatible with that lower-level API.

Use these target serializers:

- `ComponentSerialization.STREAM_CODEC` for `Component` values;
- `RegistryFriendlyByteBuf.writeResourceLocation` / `readResourceLocation` for identifiers inside the manual member codecs;
- explicit UUID, varint, varlong, boolean, bounded UTF, optional, and collection helpers for the rest.

Do not use Java serialization or JSON inside packets.

### 9.2 Registration

Replace `SimpleChannel`, `NetworkRegistry.newSimpleChannel`, numeric discriminators, and `NetworkEvent.Context` with:

```java
public static void register(RegisterPayloadHandlersEvent event) {
    PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
    registrar.playToServer(RequestSnapshotC2S.TYPE, RequestSnapshotC2S.STREAM_CODEC,
            ReputationNetwork::handleRequestSnapshot);
    registrar.playToClient(SnapshotS2C.TYPE, SnapshotS2C.STREAM_CODEC,
            ReputationNetwork::handleSnapshot);
    registrar.playToClient(OpenScreenS2C.TYPE, OpenScreenS2C.STREAM_CODEC,
            ReputationNetwork::handleOpenScreen);
    registrar.playToClient(ChangeS2C.TYPE, ChangeS2C.STREAM_CODEC,
            ReputationNetwork::handleChange);
    registrar.playToClient(TierToastS2C.TYPE, TierToastS2C.STREAM_CODEC,
            ReputationNetwork::handleTierToast);
}
```

Use:

- `net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent`
- `net.neoforged.neoforge.network.registration.PayloadRegistrar`
- `net.neoforged.neoforge.network.handling.IPayloadContext`
- `net.neoforged.neoforge.network.PacketDistributor`

The registrar's default handlers run on the main thread. Keep them there; do not call a method that opts them onto the network thread. Remove `enqueueWork` and `setPacketHandled`. For C2S, get the player from `IPayloadContext.player()` and require a `ServerPlayer` before touching server state.

### 9.3 Sending

Make the common send helper type-safe:

```java
public static void sendTo(ServerPlayer player, CustomPacketPayload payload) {
    PacketDistributor.sendToPlayer(player, payload);
}
```

In client code, replace `CHANNEL.sendToServer(...)` with `PacketDistributor.sendToServer(...)`.

Keep the ordering in `openScreenWithSnapshot`: send a fresh `SnapshotS2C` first, then `OpenScreenS2C`.

### 9.4 Server validation and rate limiting

Port the current request handler without loosening it:

- one accepted request per player per 10 server ticks;
- clear stamps on logout and all stamps on server stop;
- honor a requested community only if the player already has a record for it;
- a context entity must exist in the player's current server level, be an MCA villager, and be within 12 blocks;
- otherwise resolve the nearest village and then the best-known community;
- always build the result from canonical server state.

Do not use a client-supplied community id as proof that the community exists.

### 9.5 Decode bounds

The current code bounds lists before encoding but uses unbounded `readList` calls. Strengthen the port by rejecting an encoded count outside the corresponding limit before allocating:

| Field | Limit |
|---|---:|
| snapshot communities | `ReputationBounds.MAX_SYNCED_COMMUNITIES` |
| selected incidents | `ReputationBounds.MAX_SYNCED_INCIDENTS` |
| selected titles | `ReputationBounds.MAX_TITLES` |
| global titles | `ReputationBounds.MAX_TITLES` |
| community name | `CommunityMetadata.MAX_NAME_LENGTH` |
| tier/title/status/severity ids | preserve current explicit maxima |

Implement one private `readBoundedList` helper that reads the count, rejects negative/oversized values with a decoder exception, and then decodes exactly that many values. Do not allocate and truncate an oversized inbound list. Keep outbound `.limit(...)` calls as defense in depth.

### 9.6 Client-only dispatch without `DistExecutor`

NeoForge 1.21.1 no longer provides the old Forge `DistExecutor` used by `ClientPacketHandler`. Do not solve this by directly importing `ClientReputationData` into `ReputationNetwork`.

Implement a common, client-type-free dispatch seam:

- Make `ClientPacketHandler` a public class marked internal (for example with `@ApiStatus.Internal`), because its installer must be reached from the separate `client` package. Keep its packet-dispatch methods package-private.
- `ClientPacketHandler` contains a public nested `Sink`, a no-op sink, and a `volatile` current sink.
- The sink interface mentions only the four S2C packet record types and `void openScreen()`; it must not mention `Minecraft`, `Screen`, toast, or any class under `net.minecraft.client`.
- `install(Sink)` is the only mutation point and rejects `null`.
- The four S2C handlers call the current sink.
- A `Dist.CLIENT` client setup handler in `ReputationClient` installs a sink backed by `ClientReputationData` method references during `FMLClientSetupEvent`.
- On a dedicated server the client subscriber is never loaded and the common sink remains a no-op.

Add a focused test that installs a fake sink and verifies all four dispatch paths. Extend `OptionalClassloadTest` to scan `ClientPacketHandler` and `ReputationNetwork` for client package names.

### 9.7 Packet tests

Rewrite `SnapshotPacketTest` around each payload's `STREAM_CODEC` and a `RegistryFriendlyByteBuf`. For isolated literal/translatable-component round trips, construct it over an `Unpooled.buffer()` with `RegistryAccess.EMPTY`; use the registry provider supplied by the ModDevGradle test environment if a case contains registry-backed component data. Reset the reader index before decode and release the buffer after each test. Test:

- exact round trip of all five packets;
- empty, normal, and maximum-sized lists;
- encode-side truncation at every limit;
- decode rejection for every limit plus one;
- maximum strings and invalid overlong strings;
- optional fields present/absent;
- negative/invalid community identifiers according to the existing `CommunityKey` contract;
- protocol id and payload ids are unique.

---

## 10. Saved-data migration

### 10.1 NeoForge 1.21.1 adapter

Refactor `ReputationSavedData` to use the 1.21.1 lookup-aware signatures:

```java
private static final SavedData.Factory<ReputationSavedData> FACTORY =
        new SavedData.Factory<>(ReputationSavedData::new,
                ReputationSavedData::load);

public static ReputationSavedData get(MinecraftServer server) {
    return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
}

@Override
public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
    return savePayload(tag);
}

private static ReputationSavedData load(CompoundTag tag,
                                          HolderLookup.Provider registries) {
    return loadPayload(tag);
}
```

Keep package-private/provider-neutral `savePayload(CompoundTag)` and `loadPayload(CompoundTag)` helpers. The reputation schema stores primitive values, strings, UUIDs, and resource-location text and does not require a registry lookup; the target API's provider should be an adapter parameter, not a reason to rewrite the schema.

The 1.21.1 `SavedData.Factory` constructor takes the create supplier and lookup-aware load function. The mod continues to own its schema version; do not attach an unrelated vanilla data-fix type.

### 10.2 Schema invariants

Do not change:

- `DATA_NAME` (`mcareputation`);
- overworld/global data-storage location;
- root key names;
- `FORMAT_VERSION` (`1`);
- player UUID encoding;
- dimension plus village-id community encoding;
- baseline/score/ledger semantics;
- incident UUIDs, timestamps, status, context, witnesses, and contribution values;
- title ownership, dedupe, high-water, migration markers, or cached metadata;
- load-time corruption containment, cap enforcement, or score recomputation.

If a vanilla NBT helper changed signature, adapt only the call site. Do not “modernize” the stored representation during this loader port.

### 10.3 Golden compatibility test

Before changing the baseline serializer, create `src/test/resources/fixtures/mcareputation-format-1-1.20.1.nbt` with the 1.20.1 code. Populate a real `ReputationSavedData`, call the baseline `save(new CompoundTag())`, and write that payload with `NbtIo`; record whether it is compressed so the target test reads it the same way. The fixture must contain at least:

- two players;
- two same-number villages in different dimensions;
- positive and negative baselines/scores;
- active, resolved, expired, hidden, and folded incidents;
- witnesses and context fields;
- village and global titles;
- dedupe entries;
- tier high-water values;
- legacy-import state;
- cached village name/center.

Document the producing commit and SHA-256 beside the fixture. The 1.21.1 test must load it through `loadPayload`, assert every semantic field, save it again, reload it, and assert no semantic loss. It must also assert the written `version` is still `1`.

Never regenerate this fixture with the target serializer; it is evidence of backward compatibility.

---

## 11. Gameplay and lifecycle events

### 11.1 Damage and death

Replace `LivingHurtEvent` with `LivingDamageEvent.Post` from `net.neoforged.neoforge.event.entity.living`.

Use:

- `event.getEntity()` for the damaged living entity;
- `event.getSource()` for attribution;
- `event.getNewDamage()` for actual health lost after reductions.

Drop `receiveCanceled=false`: `LivingDamageEvent.Post` is post-application and is not the cancellable incoming-damage stage. Keep `EventPriority.LOWEST` only if accepted by the target event and useful for deterministic ordering; it is no longer what guarantees cancellation handling.

For the two branches in the existing method:

1. When an MCA villager damages a player, record the self-defense window only when `getNewDamage() > 0`.
2. When a player damages an MCA villager, compare `getNewDamage()` to `minimumIncidentDamage`, then pass that exact value into assault accumulation.

Keep `LivingDeathEvent` for killing and keep the existing attribution logic for direct players, projectile owners, thrown sources, and optionally tamed-animal owners. Validate in runtime that `LivingDamageEvent.Post` occurs before `LivingDeathEvent` for lethal player damage; the current fold/link/rollback algorithm depends on seeing the precursor assault before the death transaction.

### 11.2 Tick events

Replace phase-filtered tick handlers:

| Current | Target |
|---|---|
| `TickEvent.ServerTickEvent` plus `phase == END` | `ServerTickEvent.Post` |
| `TickEvent.ClientTickEvent` plus `phase == END` | `ClientTickEvent.Post` |

The target classes are in `net.neoforged.neoforge.event.tick` and `net.neoforged.neoforge.client.event`, respectively. Remove phase checks; the event subtype already expresses the phase.

Apply this to:

- online-player reconciliation in `ReputationGameplayEvents`;
- merged feedback flush in `ReputationFeedback`;
- client request/feedback/key processing in `ReputationClient`.

Preserve counters, intervals, online-only iteration, empty-server fast paths, and server-stop resets.

### 11.3 Remaining events

Reimport and preserve behavior for:

- `PlayerEvent.PlayerLoggedInEvent`;
- `PlayerEvent.PlayerLoggedOutEvent` or the exact logout event currently used by feedback cleanup;
- `PlayerInteractEvent.EntityInteract`;
- `ClientPlayerNetworkEvent.LoggingOut`;
- `ScreenEvent.Init.Post`;
- `RegisterKeyMappingsEvent`;
- `AddReloadListenerEvent`;
- `RegisterCommandsEvent`;
- `ServerStoppedEvent`.

Use the top-level NeoForge `@EventBusSubscriber`. Its old `bus=MOD` selector is deprecated/ignored in 1.21.1; remove it. NeoForge routes mod-bus event types such as `RegisterKeyMappingsEvent` to the mod bus. Keep every client subscriber constrained with `value = Dist.CLIENT`.

---

## 12. MCA Reborn compatibility layer

### 12.1 Compile target

Compile against the NeoForge artifact `maven.modrinth:minecraft-comes-alive-reborn:7.7.36+1.21.1`. The upstream 1.21.1 branch uses Java 21, NeoForge, and the non-relocated `net.conczin.mca` package.

### 12.2 `McaCompat`

Change imports only, then compile before altering behavior. Preserve the adapter's fail-soft `try/catch` policy and safe defaults around:

- MCA villager detection;
- living/adult checks;
- villager names and personalities;
- home-village and nearest-village resolution;
- village identity, name, center, border, residents, and resident names;
- family-tree name lookup.

The target branch retains the required classes and responsibilities. If an exact signature differs in the pinned artifact, make the smallest adjustment inside `McaCompat`; do not leak MCA imports into `CommunityResolver`, events, commands, API, or state classes.

After compiling, use `javap` or IDE symbol inspection against the resolved 7.7.36 JAR and record the exact consumed signatures in `IMPLEMENTATION_NOTES.md`, replacing the obsolete Forge 7.6/7.7 parity section.

### 12.3 `McaScreenCompat`

Change the import to `net.conczin.mca.client.gui.InteractScreen`. Keep the event-based `instanceof` check and do not add an accessor mixin for MCA's private villager field. Keep the catch-all fallback so a later MCA screen move merely hides the convenience button rather than crashing.

Update its documentation from “Forge screen-init event” to “NeoForge screen-init event” and from `forge.net.mca.*` to `net.conczin.mca.*`.

### 12.4 Classloading boundary

Update `OptionalClassloadTest` so:

- only files under `compat` may contain `net/conczin/mca/` references;
- only client code and `McaScreenCompat` may contain `net/minecraft/client/` references;
- no class contains `forge/net/mca/` or `net/minecraftforge/`;
- common network registration and common mod initialization load without resolving client classes;
- no companion, Architectury, or mixin config/class is packaged.

---

## 13. Vanilla 1.21.1 code changes

### 13.1 Resource locations

In `McaReputation`:

```java
ResourceLocation.fromNamespaceAndPath(namespace, path)
```

Use it in both `id` and the legacy `questsId` helper. Replace all direct constructors in:

- `BuiltinIncidents`;
- `ReputationTiers`;
- `TestFixtures`;
- `CommunityKeyTest`;
- `ContentValidationTest`;
- `SnapshotPacketTest`;
- `ReputationServiceTest`;
- `SavedDataTest`.

Use `ResourceLocation.parse("namespace:path")` for a single combined string. Do not use `tryParse` where invalid input currently needs to fail validation.

### 13.2 Components in codecs and packets

Replace `ExtraCodecs.COMPONENT` with `ComponentSerialization.CODEC` in:

- `IncidentDefinition`;
- `ReputationTier`;
- `TitleDefinition`.

Keep the current strict optional-field wrapper and all codec validation. Re-run `IncidentCodecTest`, `TierTest`, and `ContentValidationTest` against every shipped JSON file.

Use `ComponentSerialization.STREAM_CODEC` for network payload components, as specified earlier. Do not conflate the JSON/NBT `Codec` with the binary `StreamCodec`.

### 13.3 Item registry lookup

In `TitleDefinition.iconStack`, replace `ForgeRegistries.ITEMS.getValue(id)` with a vanilla built-in-registry optional lookup:

```java
Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(Items.AIR);
```

Retain the `Items.NAME_TAG` fallback for absent ids or `Items.AIR`. Add a test for known, missing, and explicit-air icons if the ModDev test registry permits it.

### 13.4 Annotations

Replace `javax.annotation.Nullable` imports with `org.jetbrains.annotations.Nullable` in the API, event records, client screen/throttle, witness resolver, network, service/title code, and test context. This is source-only and must not change nullability behavior or serialized forms.

### 13.5 Compile-and-confirm probes

These APIs appear structurally compatible but must be compiled and tested rather than assumed:

- `SimplePreparableReloadListener.prepare/apply` signatures;
- `FriendlyByteBuf` primitive methods used by `CommunityKey`, `CommunityMetadata`, and `IncidentSubject`;
- Brigadier `ArgumentTypeInfo` registration and `CommandSourceStack` construction in tests;
- `Toast` render signature;
- `GuiGraphics` drawing/scissor APIs;
- `CommonComponents.GUI_DONE`;
- `ResourceKey` and dimension registry construction;
- NBT list/compound convenience methods.

If one changed, make a narrow adapter and add a regression test. Do not use broad reflection or suppress compilation warnings to bypass the target API.

---

## 14. Client UI and input

### 14.1 `ReputationClient`

Reimport all client and interaction events under NeoForge. Keep:

- the key unbound by default;
- `KeyConflictContext.IN_GAME`;
- the 200-tick context hint lifetime;
- server validation of the hinted entity;
- the standing button at the existing clamped position;
- client cache clearing on logout.

Change the client tick handler to `ClientTickEvent.Post`. Register the key mapping through the NeoForge mod-bus event and remove the obsolete nested `bus=MOD` annotation argument.

Add an `FMLClientSetupEvent` handler that installs the packet sink described in section 9.6. Installation must happen before a client can join a world; it must not require `Minecraft.getInstance().player` to be non-null.

### 14.2 `ReputationScreen`

Apply the known 1.21.1 method signatures:

```java
renderBackground(graphics, mouseX, mouseY, partialTick);
```

and:

```java
@Override
public boolean mouseScrolled(double mouseX, double mouseY,
                             double deltaX, double deltaY) {
    // Use deltaY for vertical scrolling.
    ...
    return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
}
```

Retain dynamic sizing, text wrapping, scissoring, scrollbar math, request throttling, parent-screen return, truncation text, and non-pausing behavior. Re-run `ScrollMathTest`, `RequestThrottleTest`, and the manual small-GUI-scale scenarios.

### 14.3 Toast and feedback

Compile `ReputationTierToast`, `FeedbackPresentation`, `ClientReputationData`, and `ReputationFeedback` against the new signatures. Preserve:

- per-community buffering;
- merged change notifications;
- upward milestone versus downward presentation;
- title/tier components sent resolved from the server;
- cache and pending-feedback cleanup across worlds.

Do not move datapack text resolution to the client; a dedicated server's live datapack registry remains authoritative.

---

## 15. Public API, commands, reload data, and domain model

### 15.1 Public events

Change `ReputationEvent` to extend `net.neoforged.bus.api.Event`. Change event posting in `ReputationService` and `ServiceContext` from `MinecraftForge.EVENT_BUS` to `NeoForge.EVENT_BUS`.

Preserve:

- the five event classes and their data;
- event ordering relative to a committed transaction;
- containment of listener exceptions;
- the fact that throwing add-on listeners cannot turn an already committed operation into `ERROR`;
- mirror/import-provider registration APIs;
- nullable fields and defensive copies.

Update JavaDoc and `API.md` imports/examples to show NeoForge subscriptions. State explicitly that add-ons compiled for the Forge artifact must be ported/recompiled.

### 15.2 Commands

Reimport `RegisterCommandsEvent` and compile the existing Brigadier tree. Preserve every literal, redirect, permission rule, optional player/community form, audit log, and tab-completion behavior. `CommandTreeTest` must still cover:

- `/mcareputation` and `/mcarep`;
- `here`;
- unquoted `minecraft:overworld/3`;
- player-name disambiguation;
- history and incident forms;
- global versus village title grants.

Do not replace the custom argument with `StringArgumentType` to make compilation easier.

### 15.3 Reload listener and codecs

Keep the reload listener registration on `AddReloadListenerEvent`. Preserve preparation off-thread, atomic apply, strict/lenient behavior, cross-definition validation, and error messages with resource path/field.

After component-codec migration, run a real `/reload` with:

- all built-in data;
- one valid override;
- one invalid definition in lenient mode;
- the same invalid definition in strict mode;
- the legacy `mcaquests` title paths.

### 15.4 Pure domain code

The incident, decay, awareness, pruning, dedupe, resolution, reputation math, and record classes should not be redesigned. Only change imports or target API calls required by compilation. Any behavior-changing diff in these packages requires a test explaining why it is necessary for 1.21.1.

---

## 16. File-by-file work map

### Build and root files

| File | Required action |
|---|---|
| `build.gradle` | Replace ForgeGradle with ModDevGradle; add NeoForge runs/unit tests; remove `fg.deobf`/reobf; generate metadata; strengthen JAR checks. |
| `settings.gradle` | Use plugin portal and foojay `1.0.0`; keep root name. |
| `gradle.properties` | Apply exact target pins and remove Forge mapping/version properties. |
| `gradle/wrapper/*`, `gradlew*` | Upgrade wrapper to Gradle `9.2.1`. |
| `.gitignore` | Correct artifact-name typo only. |
| `src/main/resources/META-INF/mods.toml` | Delete after moving content. |
| `src/main/templates/META-INF/neoforge.mods.toml` | Add translated NeoForge metadata. |
| `src/main/resources/pack.mcmeta` | Delete. |

### Main Java files that definitely change

| File/group | Required action |
|---|---|
| `McaReputation.java` | Resource-location factories. |
| `McaReputationConfig.java` | `ModConfigSpec` imports/types. |
| `McaReputationMod.java` | Constructor injection, mod/game buses, payload listener, DeferredRegister import. |
| `api/event/ReputationEvent.java` | NeoForge `Event`; annotation import. |
| `api/*.java`, `api/event/*.java` with `Nullable` | JetBrains annotation and docs. |
| `ReputationClient.java` | NeoForge client events, post tick, key registration, client packet sink install. |
| `ReputationScreen.java` | new background/scroll signatures; annotation. |
| `McaCompat.java` | `net.conczin.mca` imports and exact target signatures. |
| `McaScreenCompat.java` | target `InteractScreen` import and docs. |
| `ReputationGameplayEvents.java` | `LivingDamageEvent.Post`, post tick, NeoForge imports. |
| `BuiltinIncidents.java` | resource-location factories. |
| `IncidentDefinition.java` | `ComponentSerialization.CODEC`. |
| `ClientPacketHandler.java` | remove `DistExecutor`; installable common sink. |
| `ReputationFeedback.java` | NeoForge API and server post tick. |
| `ReputationNetwork.java` | complete custom-payload rewrite and decode bounds. |
| `ReputationService.java` | NeoForge event bus; annotation. |
| `ServiceContext.java` | NeoForge `Event`; annotation. |
| `ReputationTier.java` | component codec. |
| `ReputationTiers.java` | resource-location factories. |
| `TitleDefinition.java` | component codec, built-in item registry, annotation if present. |
| `ReputationSavedData.java` | `SavedData.Factory`, lookup-aware signatures, provider-neutral helpers. |
| all other files with `javax.annotation.Nullable` | JetBrains import only. |

### Tests that definitely change

| File/group | Required action |
|---|---|
| `OptionalClassloadTest` | new/forbidden package roots and common/client network seam assertions. |
| `TestFixtures` | resource-location factories. |
| `CommandTreeTest` | target command source/registry constructor changes if required. |
| `CommunityKeyTest` | resource-location factories and target buffer if needed. |
| `ContentValidationTest` | factories and target component codec. |
| `SnapshotPacketTest` | payload `STREAM_CODEC`, registry-aware buffer, all bounds. |
| `ReputationServiceTest` | factories and NeoForge event context. |
| `TestServiceContext` | NeoForge event import and JetBrains annotation. |
| `SavedDataTest` | target helper signatures plus golden 1.20.1 fixture. |
| codec/tier tests | verify `ComponentSerialization.CODEC` behavior. |
| remaining tests | compile and run unchanged; do not weaken assertions. |

### Files expected to be behaviorally unchanged

The API value records, client presentation/math helpers, community model, most command logic, data validator/reload transaction, `AssaultTracker`, legacy import provider registry, awareness/decay/resolution/domain records, reputation math/bounds/title logic, state record internals, and strict/enum codecs should retain behavior. A coding agent may make narrow signature/import edits, but broad rewrites here are a review warning.

---

## 17. Automated verification

### 17.1 Required commands

Run with JDK 21:

```bash
./gradlew --version
./gradlew clean compileJava compileTestJava
./gradlew test
./gradlew build
./gradlew checkJarContents
```

The first command must report Gradle 9.2.1 and Java 21. The full suite must have no failures or skipped regressions. The passing count must be at least the 241-test baseline, plus new parameterized cases added for target payloads and persistence.

Also inspect the artifact:

```bash
jar tf build/libs/mcareputation-0.2.0.jar
```

Verify:

- one production JAR, not a dev/unmapped dependency bundle;
- NeoForge metadata present and old Forge metadata absent;
- built-in data and language JSON present;
- no MCA, companion, Architectury, Forge, or client-only duplicate classes are shaded;
- no mixin config;
- no source `pack.mcmeta`.

### 17.2 Test coverage that must remain green

Do not delete or relax the existing tests for:

- score arithmetic and exact set/add behavior;
- tier transitions/high-water;
- decay monotonicity;
- awareness and witness behavior;
- resolution ratchet/replay protection;
- dedupe;
- pruning and cap enforcement;
- saved-data corruption containment;
- incident NBT/codec round trips;
- reload content validation and language parity;
- command tree parsing;
- request throttling and feedback merge/presentation;
- optional classloading.

### 17.3 New automated tests

Add at minimum:

1. Golden 1.20.1 saved-data compatibility test.
2. Round-trip tests for all five NeoForge payloads.
3. Decode-overflow rejection for each bounded collection.
4. Client packet sink dispatch test.
5. Source/JAR scan rejecting `net.minecraftforge` and `forge.net.mca` references.
6. Metadata expansion test or JAR assertion for all dependency types/ranges.
7. Config key/default parity test.

If the event system can be exercised cheaply through NeoForge's test framework, add an ephemeral-server or GameTest case proving that a mitigated villager hit uses `LivingDamageEvent.Post#getNewDamage`. Do not block the port solely on making a full entity-combat unit test if the manual runtime matrix covers it reliably.

---

## 18. Runtime and production verification

Automated tests are necessary but not sufficient. Build the actual JAR and test it in a clean production-style NeoForge 1.21.1 client and dedicated server with MCA Reborn 7.7.36.

### 18.1 Startup matrix

| Combination | Client | Dedicated server | Expected |
|---|:---:|:---:|---|
| NeoForge + MCA + Reputation | required | required | clean startup/world join |
| NeoForge + Reputation, no MCA | required | required | loader reports missing required `mca`, not a linkage crash |
| NeoForge + MCA only | required | required | MCA behaves normally |
| NeoForge + MCA + Reputation, no companions | required | required | all core features available |
| Plus a compatible Quests port, if available | required | required | optional API bridge only |
| Plus a compatible Conversations port, if available | required | required | optional API bridge only |
| All compatible companions, if available | required | required | no duplicate integration or classloading error |

Do not hold the core port hostage to unpublished companion ports. If they are unavailable, mark only those optional rows “blocked: no compatible artifact,” and remove any documentation claiming those integrations were runtime-tested on 1.21.1.

### 18.2 World migration

On a copy of a real 1.20.1 world:

1. Record player/community scores, titles, and representative incidents before upgrade.
2. Back up the copy.
3. Open it in the Minecraft 1.21.1/NeoForge instance and allow vanilla world upgrade.
4. Verify the reputation `SavedData` loads once with no “future format” or corruption warning.
5. Check the same scores, dimensions/village ids, titles, incidents, statuses, contexts, witnesses, dedupe behavior, and high-water milestones.
6. Trigger one new deed, save, exit fully, restart, and verify old and new data persist.
7. Rename a village and verify cached display metadata updates without changing identity.
8. Never reopen this upgraded copy in 1.20.1 as a supported workflow.

Also test a brand-new 1.21.1 world to catch initialization paths hidden by existing data.

### 18.3 Networking and UI

Test on a dedicated server with a matching client:

- keybind remains unbound and can be assigned;
- right-clicking an MCA villager captures a context hint;
- the Standing button appears on `InteractScreen` without a mixin;
- a stale/far/wrong-dimension entity hint is rejected server-side;
- snapshot selection, community cycling, retry timeout, and rate limiting work;
- screen layout is usable at minimum and maximum GUI scale;
- vertical wheel scrolling uses `deltaY` and clamps correctly;
- maximum retained ledger is truncated visibly and does not disconnect;
- tier names/descriptions/titles are resolved from server datapacks;
- upward toast, downward feedback, merged changes, and logout cache clearing work;
- protocol mismatch is rejected cleanly.

### 18.4 Gameplay regression scenarios

At minimum execute:

- witnessed assault above the damage threshold;
- fully mitigated or below-threshold chip damage;
- unwitnessed assault;
- repeated hits coalescing into one incident;
- villager hits player, then player retaliates inside and outside the self-defense window;
- lethal direct hit;
- assault followed by death totals the killing value, not both values;
- arrow, thrown potion, and tamed-wolf attribution;
- unwitnessed death and assault rollback behavior;
- two players in one village;
- same numeric village id in two dimensions;
- decay across days and after time is moved backward/forward;
- `/reload` valid, lenient-invalid, and strict-invalid cases;
- every command branch and permission level;
- server restart and client world switching.

Pay special attention to lethal event order. If NeoForge fires death before the post-damage handler in this exact environment, refactor the death handler to derive/fold the lethal assault atomically without double recording, and add a regression test. Do not accept `-48` where the intended total is `-40`.

### 18.5 Performance and logs

Confirm:

- no per-tick log spam;
- idle server with no players does no reputation scan;
- the periodic task visits online players only;
- witness resolution remains bounded and deterministic;
- maximum ledger snapshot stays comfortably below NeoForge's payload limits;
- no client classes or rendering libraries are resolved on the dedicated server;
- logs do not expose player NBT, chat contents, or filesystem paths;
- invalid optional integration failures remain contained.

Record exact artifact filenames, SHA-256 hashes, Java version, NeoForge version, MCA version, test date, and tester in `PRODUCTION_TESTS.md`.

---

## 19. Documentation and release work

Update these files as part of the same PR:

| Document | Required changes |
|---|---|
| `README.md` | Minecraft 1.21.1, NeoForge 21.1.248+, Java 21, MCA 7.7.36+, new install/build steps, companion caveat. |
| `CHANGELOG.md` | Make the unreleased `0.2.0` compatibility table target NeoForge; add a “Ported” section; retain historical Forge notes only under clearly historical headings. |
| `API.md` | NeoForge imports/event bus, Java 21, rebuild requirement for add-ons, API version remains 1. |
| `CONFIG.md` | Confirm unchanged filenames/keys and remove Forge-only paths. |
| `DATAPACK.md` | Confirm paths unchanged; document 1.21.1 target and component codec behavior; remove old pack-format advice. |
| `MIGRATION.md` | Add 1.20.1 Forge → 1.21.1 NeoForge world-copy procedure, backup warning, format-1 compatibility, and no downgrade guarantee. |
| `IMPLEMENTATION_NOTES.md` | Replace Forge/MCA relocation and 7.6 parity analysis with target 7.7.36 signatures and NeoForge decisions. |
| `PRODUCTION_TESTS.md` | Replace Forge/reobfuscation language and old version matrix; execute and sign off the new matrix. |
| `CURSEFORGE.md` | NeoForge 1.21.1 dependency/version/upload metadata and release text. |

Search the full tree for and review every occurrence of:

```text
Forge 47
ForgeGradle
MinecraftForge
net.minecraftforge
forge.net.mca
1.20.1
Java 17
reobf
SRG
mods.toml
pack_format
SimpleChannel
```

Some occurrences belong in historical release notes; current instructions and code comments must not describe the old platform as current.

Release packaging:

- Publish only the `build/libs/mcareputation-0.2.0.jar` production artifact.
- Mark the loader NeoForge and game version 1.21.1.
- Declare MCA Reborn required; companions optional.
- Include the production artifact SHA-256 in the verification record.
- Do not publish until every non-blocked production row is signed off.

---

## 20. Risk register

| Risk | Detection | Mitigation |
|---|---|---|
| Client types leak into common packet code | dedicated-server startup; bytecode scan | installable common sink; `Dist.CLIENT` setup owns method references |
| Saved data silently resets | golden fixture; copied-world test | preserve name/version/schema and provider-neutral serializer |
| Damage threshold changes meaning | focused event/manual combat tests | `LivingDamageEvent.Post#getNewDamage` |
| Lethal event order double-charges | assault→death scenario | preserve fold/rollback; refactor atomically if target order differs |
| Packet allocation or disconnect on large ledger | overflow tests; max-ledger runtime | bounded decode and encode lists; resolved components only |
| MCA package/signature drift | compile and `javap` | keep all direct links in two compat classes |
| Old Forge classes survive a mechanical port | source/JAR scan | build-failing legacy-reference task |
| Metadata accidentally accepts wrong MC/loader | generated metadata test; no-MCA startup | exact MC range, pinned NeoForge minimum, required MCA range |
| Companion claims exceed available ports | startup matrix | keep optional; document only tested target artifacts |
| Datapack folders renamed as vanilla resources | content tests and `/reload` | explicitly preserve custom listener paths |
| “Green dev run” mistaken for release proof | production checklist | test built JAR in clean client and dedicated server |
| Unrelated domain refactor introduces score drift | diff review; 241+ regression tests | isolate platform commits; require a test for every behavior change |

---

## 21. Definition of done

The port is complete only when all of the following are true:

- [ ] Branch starts from the recorded baseline commit and the pre-port test result is documented.
- [ ] Gradle 9.2.1, Java 21, ModDevGradle 2.0.144, NeoForge 21.1.248, Parchment 2024.11.17, and MCA 7.7.36 are pinned.
- [ ] `./gradlew clean test build` succeeds with at least the original 241 tests plus new port tests.
- [ ] The JAR contains `META-INF/neoforge.mods.toml`, correct dependency types/ranges, resources, and no old Forge metadata.
- [ ] No source or bytecode reference to `net.minecraftforge` or `forge.net.mca` remains outside explicitly quoted historical docs.
- [ ] No MCA, companion, Architectury, or other mod classes are shaded.
- [ ] All five packets use named NeoForge payloads, registry-aware stream codecs, strict directions, and bounded decode.
- [ ] Dedicated server starts without resolving client classes.
- [ ] Common/client config files retain every existing key/default.
- [ ] The golden 1.20.1 saved-data fixture loads without semantic loss and format remains 1.
- [ ] A copied real 1.20.1 world upgrades and retains reputation state across a subsequent restart.
- [ ] Actual post-mitigation damage, self-defense, assault/death folding, projectile/tamed attribution, and unwitnessed behavior pass.
- [ ] Commands, reload behavior, UI, key mapping, screen button, feedback, and toasts pass.
- [ ] Optional companions remain absent-safe; only available compatible ports are claimed as tested.
- [ ] README, API, migration, datapack, config, changelog, implementation notes, production matrix, and release copy describe the target accurately.
- [ ] Production client and dedicated-server artifacts, hashes, versions, logs, and sign-off are recorded.
- [ ] The PR contains no unrelated gameplay redesign, no mixin, no reflection workaround, and no downgrade claim.

---

## 22. Primary references

Repository and target dependency:

- [MCA: Reputation repository](https://github.com/otectus/MCAReputation)
- [Inspected MCA: Reputation baseline commit](https://github.com/otectus/MCAReputation/commit/8fac797bb452976ff92a97bc5266460943ee15f3)
- [MCA Reborn 1.21.1 source branch](https://github.com/Luke100000/minecraft-comes-alive/tree/1.21.1)
- [MCA Reborn 7.7.36 for 1.21.1](https://modrinth.com/mod/minecraft-comes-alive-reborn/version/S2Ln2tIn)

NeoForge and build APIs:

- [NeoForge 1.21.1 getting started and Java requirement](https://docs.neoforged.net/docs/1.21.1/gettingstarted/)
- [NeoForge mod files and metadata](https://docs.neoforged.net/docs/1.21.1/gettingstarted/modfiles)
- [NeoForge event system](https://docs.neoforged.net/docs/1.21.1/concepts/events/)
- [NeoForge payload registration](https://docs.neoforged.net/docs/1.21.1/networking/payload/)
- [NeoForge stream codecs](https://docs.neoforged.net/docs/1.21.1/networking/streamcodecs/)
- [NeoForge saved data](https://docs.neoforged.net/docs/1.21.1/datastorage/saveddata/)
- [NeoForge configuration](https://docs.neoforged.net/docs/1.21.1/misc/config/)
- [NeoForge resource locations](https://docs.neoforged.net/docs/1.21.1/misc/resourcelocation/)
- [Minecraft 1.20.6 → 1.21 NeoForge primer](https://docs.neoforged.net/primer/docs/1.21/)
- [Official 1.21.1 ModDevGradle MDK](https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle)
- [ModDevGradle documentation](https://github.com/neoforged/ModDevGradle)
- [NeoForge resources and synthetic `pack.mcmeta`](https://docs.neoforged.net/docs/1.21.1/resources/)

Use the pinned source and API documentation as the authority. Search-engine snippets, generated mappings from another Minecraft version, and Forge-only examples are not implementation evidence.
