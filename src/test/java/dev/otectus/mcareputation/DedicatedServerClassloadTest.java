package dev.otectus.mcareputation;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Spec §16.3 #3: every common class must load <em>and initialise</em> on a dedicated server, where no
 * client class exists.
 *
 * <p>{@link OptionalClassloadTest} proves no common class <em>names</em> a client type. That is the
 * static half. This is the dynamic half: a class can pass the constant-pool scan and still die on a
 * dedicated server, because a static initialiser, a lambda's captured type, or a bridge method can
 * pull a client class in through a route the scan does not model. The failure mode being defended
 * against is a {@code NoClassDefFoundError} on somebody else's server at the first packet.
 *
 * <p><b>The loader is child-first, and that is load-bearing.</b> A parent-first loader would find
 * every {@code dev.otectus.mcareputation} class already defined in the test's own classloader,
 * return it, and never consult this filter at all — the test would pass while asserting nothing.
 * Defining the mod's classes here ourselves is the only way the ban list is reachable from inside
 * their verification and initialisation.
 */
class DedicatedServerClassloadTest {

    /**
     * What a dedicated server does not have. The last entry is a class, not a package: it is the one
     * explicitly allowlisted client-only file in a common package, and common code must not reach it.
     */
    private static final List<String> ABSENT_ON_A_SERVER = List.of(
            "net.minecraft.client.",
            "com.mojang.blaze3d.",
            "dev.otectus.mcareputation.client.",
            "dev.otectus.mcareputation.compat.McaScreenCompat");

    /**
     * Every common class that must survive initialisation with the client half missing.
     *
     * <p>{@code McaReputationMod} is deliberately absent: its static {@code DeferredRegister} fields
     * need mod-loading context that no unit test has, so its failure here would say nothing about the
     * dist split. Everything below is reachable from a packet, an event, or the public API.
     */
    private static final List<String> COMMON_CLASSES = List.of(
            "dev.otectus.mcareputation.network.ReputationNetwork",
            "dev.otectus.mcareputation.network.ReputationNetwork$RequestSnapshotC2S",
            "dev.otectus.mcareputation.network.ReputationNetwork$SnapshotS2C",
            "dev.otectus.mcareputation.network.ReputationNetwork$OpenScreenS2C",
            "dev.otectus.mcareputation.network.ReputationNetwork$ChangeS2C",
            "dev.otectus.mcareputation.network.ReputationNetwork$TierToastS2C",
            "dev.otectus.mcareputation.network.ClientPacketHandler",
            "dev.otectus.mcareputation.network.ReputationFeedback",
            "dev.otectus.mcareputation.event.ReputationGameplayEvents",
            "dev.otectus.mcareputation.event.CoreIncidentAuthorities",
            "dev.otectus.mcareputation.api.McaReputationApi",
            "dev.otectus.mcareputation.compat.McaReflect",
            "dev.otectus.mcareputation.compat.McaCompat",
            "dev.otectus.mcareputation.reputation.ReputationService",
            "dev.otectus.mcareputation.state.ReputationSavedData");

    /**
     * Registries have to exist before a payload's stream codec can be initialised. Done in the parent
     * loader, once, so the child loader inherits a bootstrapped game rather than trying to boot one.
     */
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /**
     * A child-first loader that defines this mod's classes itself and refuses every client name.
     *
     * <p>Only {@code dev.otectus.mcareputation} classes are defined here. Minecraft, NeoForge and the
     * JDK come from the parent, because loading two copies of those would defeat the assertions with
     * {@code ClassCastException}s that have nothing to do with the dist split.
     */
    private static final class DedicatedServerLoader extends ClassLoader {

        private DedicatedServerLoader(ClassLoader parent) {
            super("dedicated-server", parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                if (isAbsentOnAServer(name)) {
                    throw new ClassNotFoundException(name + " does not exist on a dedicated server; "
                            + "some common class asked for it");
                }
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = name.startsWith("dev.otectus.mcareputation.")
                            ? define(name)
                            : getParent().loadClass(name);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        private Class<?> define(String name) throws ClassNotFoundException {
            String resource = name.replace('.', '/') + ".class";
            try (InputStream bytes = getParent().getResourceAsStream(resource)) {
                if (bytes == null) {
                    throw new ClassNotFoundException(name);
                }
                byte[] content = bytes.readAllBytes();
                return defineClass(name, content, 0, content.length);
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }

        private static boolean isAbsentOnAServer(String name) {
            return ABSENT_ON_A_SERVER.stream()
                    .anyMatch(absent -> absent.endsWith(".") ? name.startsWith(absent) : name.equals(absent));
        }
    }

    /** The filter must actually bite, or every other assertion here is vacuous. */
    @Test
    void theLoaderRefusesClientClasses() {
        DedicatedServerLoader loader = new DedicatedServerLoader(getClass().getClassLoader());
        for (String clientClass : List.of("net.minecraft.client.Minecraft",
                "com.mojang.blaze3d.systems.RenderSystem",
                "dev.otectus.mcareputation.client.ReputationClient",
                "dev.otectus.mcareputation.compat.McaScreenCompat")) {
            assertThrows(ClassNotFoundException.class,
                    () -> Class.forName(clientClass, false, loader),
                    clientClass + " must be unreachable through this loader");
        }
    }

    /** And it must be child-first, or it would hand back the test loader's already-defined copies. */
    @Test
    void theLoaderDefinesTheModsClassesItself() throws ClassNotFoundException {
        DedicatedServerLoader loader = new DedicatedServerLoader(getClass().getClassLoader());
        Class<?> api = Class.forName("dev.otectus.mcareputation.api.McaReputationApi", false, loader);
        assertSame(loader, api.getClassLoader(),
                "parent-first delegation would defeat the whole filter");
    }

    /** The actual gate: static initialisation of every common class, with no client half present. */
    @Test
    void everyCommonClassInitialisesWithoutTheClientHalf() {
        DedicatedServerLoader loader = new DedicatedServerLoader(getClass().getClassLoader());
        for (String name : COMMON_CLASSES) {
            try {
                assertNotNull(Class.forName(name, true, loader));
            } catch (Throwable t) {
                fail(name + " does not initialise on a dedicated server: " + t, t);
            }
        }
    }
}
