package com.easylogin.forge;

import com.easylogin.platform.PlatformHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * Forge implementation of PlatformHelper.
 * Uses heuristic to detect FakePlayers since Forge 1.21.1
 * removed the FakePlayer utility class.
 */
public class ForgePlatformHelper implements PlatformHelper {

    @Override
    public boolean isFakePlayer(ServerPlayer player) {
        return player.getClass() != ServerPlayer.class;
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
        return false;
    }
}
