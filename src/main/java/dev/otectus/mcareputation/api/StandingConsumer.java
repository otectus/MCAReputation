package dev.otectus.mcareputation.api;

import net.minecraft.resources.ResourceLocation;

/** Runtime capture callback. Its immutable result is persisted beside the source change. */
public interface StandingConsumer {
    ResourceLocation id();

    CaptureResult capture(StandingEnvelope envelope);
}
