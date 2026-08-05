package com.easylogin.neoforge;

import com.easylogin.EasyLoginConstants;
import com.easylogin.auth.AuthManager;
import com.easylogin.command.*;
import com.easylogin.config.EasyLoginConfig;
import com.easylogin.data.PlayerDataStore;
import com.easylogin.handler.ProtectionHandler;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

@Mod(EasyLoginConstants.MOD_ID)
public class EasyLoginNeoForge {

    private EasyLoginConfig config;
    private PlayerDataStore dataStore;
    private AuthManager authManager;
    private ProtectionHandler protectionHandler;

    public EasyLoginNeoForge(IEventBus modBus) {
        EasyLoginConstants.LOGGER.info("EasyLogin (NeoForge) initializing...");
        com.easylogin.util.LogSanitizer.init();

        // Initialize early so authManager is ready for command registration
        config = new EasyLoginConfig(com.easylogin.platform.PlatformHelper.INSTANCE.getConfigDir());
        config.load();

        // dataStore initialized with default path first, will be relocated on server
        // start
        dataStore = new PlayerDataStore(com.easylogin.platform.PlatformHelper.INSTANCE.getConfigDir());
        authManager = new AuthManager(config, dataStore);
        protectionHandler = new ProtectionHandler(authManager);

        // Register event handler
        NeoForgeEventHandler handler = new NeoForgeEventHandler(authManager, protectionHandler, config);
        NeoForge.EVENT_BUS.register(handler);

        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);

        EasyLoginConstants.LOGGER.info("EasyLogin (NeoForge) initialized.");
    }

    private void onServerStarting(ServerStartingEvent event) {
        dataStore.setStoragePath(com.easylogin.platform.PlatformHelper.INSTANCE.getWorldConfigDir(event.getServer()));
        dataStore.migrateIfNeeded(com.easylogin.platform.PlatformHelper.INSTANCE.getConfigDir());
        dataStore.load();
        EasyLoginConstants.LOGGER.info("EasyLogin (NeoForge) started successfully!");
    }

    private void onServerStopping(ServerStoppingEvent event) {
        if (dataStore != null) {
            dataStore.save();
        }
        EasyLoginConstants.LOGGER.info("EasyLogin (NeoForge) shutting down.");
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
