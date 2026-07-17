package com.easylogin.fabric;

import com.easylogin.platform.PlatformHelper;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;

/**
 * Fabric implementation of PlatformHelper.
 * Uses heuristic to detect FakePlayers since Fabric has no canonical FakePlayer
 * class.
 */
public class FabricPlatformHelper implements PlatformHelper {

    @Override
    public boolean isFakePlayer(ServerPlayer player) {
        // Fabric doesn't have a canonical FakePlayer class.
        // Heuristic: check if the class is NOT the vanilla ServerPlayer.
        // Mods like Carpet, Create use subclasses.
        if (player.getClass() != ServerPlayer.class) {
            return true;
        }

        // Also check for impossible game profile UUID patterns
        // Carpet uses UUID v2 for fake players
        String uuid = player.getUUID().toString();
        if (uuid.matches(".*-0000-0000-000[0-9]-.*")) {
            return true;
        }

        return false;
    }

    @Override
    public Path getConfigDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public Path getWorldConfigDir(net.minecraft.server.MinecraftServer server) {
        return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("serverconfig");
    }

    @Override
    public boolean hasClientChannel(ServerPlayer player, ResourceLocation channel) {
        return ServerPlayNetworking.canSend(player, channel);
    }
}
