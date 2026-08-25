package dev.otectus.mcareputation;

import dev.otectus.mcareputation.command.CommunityArgument;
import dev.otectus.mcareputation.command.ReputationCommand;
import dev.otectus.mcareputation.data.ReputationReloadListener;
import dev.otectus.mcareputation.event.AssaultTracker;
import dev.otectus.mcareputation.event.ReputationGameplayEvents;
import dev.otectus.mcareputation.incident.IncidentRegistry;
import dev.otectus.mcareputation.network.ReputationFeedback;
import dev.otectus.mcareputation.network.ReputationNetwork;
import dev.otectus.mcareputation.reputation.CoreIncidentAuthorityRegistry;
import dev.otectus.mcareputation.reputation.ReputationTiers;
import dev.otectus.mcareputation.reputation.Titles;
import net.minecraft.commands.synchronization.ArgumentTypeInfo;
import net.minecraft.commands.synchronization.ArgumentTypeInfos;
import net.minecraft.commands.synchronization.SingletonArgumentInfo;
import net.minecraft.core.registries.Registries;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.registries.DeferredRegister;

/**
 * The Forge entry point (spec §9, §11).
 *
 * <p>Registration order matters and is fixed here: config specs before anything reads them, the
 * network channel during common setup, and the datapack listener on every reload including world
 * load. Nothing in this class touches a client type — the client half lives entirely in
 * {@code dev.otectus.mcareputation.client} and is reached only through {@code Dist.CLIENT} event
 * subscribers, so a dedicated server never loads it (§36.5).
 *
 * <p>Optional companions are not referenced at all. MCA: Quests and MCA: Conversations each own their
 * side of the integration and register with this mod's public API when they load; nothing here knows
 * whether they exist (§9.2).
 */
@Mod(McaReputation.MOD_ID)
public final class McaReputationMod {

    /** The custom command argument types; synced to clients, so registration is mandatory (§24). */
    private static final DeferredRegister<ArgumentTypeInfo<?, ?>> COMMAND_ARGUMENT_TYPES =
            DeferredRegister.create(Registries.COMMAND_ARGUMENT_TYPE, McaReputation.MOD_ID);

    static {
        COMMAND_ARGUMENT_TYPES.register("community", () -> ArgumentTypeInfos.registerByClass(
                CommunityArgument.class, SingletonArgumentInfo.contextFree(CommunityArgument::community)));
    }

    public McaReputationMod() {
        ModLoadingContext context = ModLoadingContext.get();
        context.registerConfig(ModConfig.Type.COMMON, McaReputationConfig.COMMON_SPEC,
                "mcareputation-common.toml");
        context.registerConfig(ModConfig.Type.CLIENT, McaReputationConfig.CLIENT_SPEC,
                "mcareputation-client.toml");

        net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus()
                .addListener(this::onCommonSetup);
        COMMAND_ARGUMENT_TYPES.register(
                net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
        MinecraftForge.EVENT_BUS.register(this);

        // Seed the registries with the built-in fallback ladder so that tier lookups are meaningful
        // even before the first datapack reload — for example while another mod's setup runs.
        ReputationTiers.replaceAll(java.util.Map.of(ReputationTiers.DEFAULT_ID,
                ReputationTiers.BUILTIN_DEFAULT));
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(ReputationNetwork::register);
        McaReputation.LOGGER.info("[MCA: Reputation] {} ready (API v{})", McaReputation.MOD_ID,
                dev.otectus.mcareputation.api.McaReputationApi.getApiVersion());
    }

    @SubscribeEvent
    public void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new ReputationReloadListener());
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        ReputationCommand.register(event.getDispatcher());
    }

    /**
     * Drops every piece of in-memory per-world state so a second world in the same JVM — a
     * singleplayer player returning to the menu and loading another save — starts clean rather than
     * inheriting the previous world's recent scuffles, pending feedback, or rate-limit stamps.
     */
    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        AssaultTracker.clear();
        ReputationNetwork.clearAll();
        ReputationFeedback.clearAll();
        ReputationGameplayEvents.resetTickCounter();
        // Authority claims are per-JVM, not per-world, but a companion that failed to withdraw would
        // otherwise leave the next world's native detection switched off with nobody producing.
        CoreIncidentAuthorityRegistry.clear();
        McaReputation.LOGGER.debug("[MCA: Reputation] server stopped; {} incident type(s), {} ladder(s), "
                        + "{} title(s) were live", IncidentRegistry.size(), ReputationTiers.ids().size(),
                Titles.ids().size());
    }
}
