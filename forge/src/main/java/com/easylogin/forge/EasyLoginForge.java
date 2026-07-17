package com.easylogin.forge;

import com.easylogin.EasyLoginConstants;
import com.easylogin.auth.AuthManager;
import com.easylogin.command.*;
import com.easylogin.config.EasyLoginConfig;
import com.easylogin.data.PlayerDataStore;
import com.easylogin.handler.ProtectionHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;

@Mod(EasyLoginConstants.MOD_ID)
public class EasyLoginForge {

    private EasyLoginConfig config;
    private PlayerDataStore dataStore;
    private AuthManager authManager;
    private ProtectionHandler protectionHandler;

    public EasyLoginForge() {
        EasyLoginConstants.LOGGER.info("EasyLogin (Forge) initializing...");

        config = new EasyLoginConfig(com.easylogin.platform.PlatformHelper.INSTANCE.getConfigDir());
        config.load();

        dataStore = new PlayerDataStore(com.easylogin.platform.PlatformHelper.INSTANCE.getConfigDir());
        authManager = new AuthManager(config, dataStore);
        protectionHandler = new ProtectionHandler(authManager);

        MinecraftForge.EVENT_BUS.addListener(this::onServerStarting);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands);
    }

    private void onServerStarting(ServerStartingEvent event) {
        dataStore.setStoragePath(com.easylogin.platform.PlatformHelper.INSTANCE.getWorldConfigDir(event.getServer()));
        dataStore.migrateIfNeeded(com.easylogin.platform.PlatformHelper.INSTANCE.getConfigDir());
        dataStore.load();

        ForgeEventHandler handler = new ForgeEventHandler(authManager, protectionHandler, config);
        MinecraftForge.EVENT_BUS.register(handler);

        EasyLoginConstants.LOGGER.info("EasyLogin (Forge) started successfully!");
    }

    private void onServerStopping(ServerStoppingEvent event) {
        if (dataStore != null) {
            dataStore.save();
        }
        EasyLoginConstants.LOGGER.info("EasyLogin (Forge) shutting down.");
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        if (authManager == null)
            return;
        LoginCommand.register(event.getDispatcher(), authManager);
        RegisterCommand.register(event.getDispatcher(), authManager);
        ChangePasswordCommand.register(event.getDispatcher(), authManager);
        LogoutCommand.register(event.getDispatcher(), authManager);
        AuthCommand.register(event.getDispatcher(), authManager, config, () -> config.load());
        ModMessageCommand.registerAll(event.getDispatcher(), config.mods, authManager.getFormatter());
        EasyLoginConstants.LOGGER.info("EasyLogin commands registered.");
    }
}
