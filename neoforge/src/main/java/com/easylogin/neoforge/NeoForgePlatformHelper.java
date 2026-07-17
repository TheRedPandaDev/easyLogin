package com.easylogin.neoforge;

import com.easylogin.platform.PlatformHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.util.FakePlayer;

import java.nio.file.Path;

/**
 * NeoForge implementation of PlatformHelper.
 */
public class NeoForgePlatformHelper implements PlatformHelper {

    @Override
    public boolean isFakePlayer(ServerPlayer player) {
        return player instanceof FakePlayer;
    }

    @Override
    public Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public Path getWorldConfigDir(net.minecraft.server.MinecraftServer server) {
        return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("serverconfig");
    }

    @Override
    public boolean hasClientChannel(ServerPlayer player, ResourceLocation channel) {
        // ponytail: player.connection.connection is protected in 1.21.1.
        // NeoForge AT may expose it; if so, replace with:
        // NetworkRegistry.hasChannel(player.connection.connection, channel)
        return false;
    }
}
