package com.easylogin.platform;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.ServiceLoader;

/**
 * Platform abstraction interface. Each loader provides its own implementation
 * via Java ServiceLoader.
 */
public interface PlatformHelper {

    PlatformHelper INSTANCE = ServiceLoader.load(PlatformHelper.class)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No PlatformHelper implementation found!"));

    /**
     * Returns true if the given player is a FakePlayer (automation, carpet, etc.)
     */
    boolean isFakePlayer(ServerPlayer player);

    /**
     * Returns the platform's config directory path (e.g., config/).
     */
    Path getConfigDir();

    /**
     * Returns the world-specific config directory path (e.g., world/serverconfig/).
     */
    Path getWorldConfigDir(net.minecraft.server.MinecraftServer server);

    /**
     * Check if the player's client has registered a handler for the given network channel.
     * Used to detect if the player has a specific mod installed client-side.
     */
    default boolean hasClientChannel(ServerPlayer player, ResourceLocation channel) {
        return false;
    }
}
