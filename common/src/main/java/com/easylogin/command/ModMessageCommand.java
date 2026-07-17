package com.easylogin.command;

import com.easylogin.config.EasyLoginConfig;
import com.easylogin.platform.PlatformHelper;
import com.easylogin.util.MessageFormatter;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

public class ModMessageCommand {

    public static void registerAll(CommandDispatcher<CommandSourceStack> dispatcher,
                                   List<EasyLoginConfig.ModEntry> mods,
                                   MessageFormatter formatter) {
        for (EasyLoginConfig.ModEntry entry : mods) {
            String cmdName = entry.getCommandName();
            if (cmdName.isEmpty()) continue;

            ResourceLocation channelId = null;
            if (!entry.channel.isEmpty()) {
                try { channelId = ResourceLocation.parse(entry.channel); }
                catch (Exception ignored) {}
            }
            final ResourceLocation checkChannel = channelId;

            dispatcher.register(Commands.literal(cmdName)
                    .executes(ctx -> {
                        CommandSourceStack source = ctx.getSource();
                        if (source.getPlayer() instanceof ServerPlayer player) {
                            if (checkChannel != null && PlatformHelper.INSTANCE.hasClientChannel(player, checkChannel)) {
                                return 1;
                            }
                            player.sendSystemMessage(Component.literal(formatter.colorize(entry.message)));
                        } else {
                            source.sendFailure(Component.literal("Only players can use this command."));
                        }
                        return 1;
                    }));
        }
    }
}
