package com.easylogin.fabric;

import com.easylogin.EasyLoginConstants;
import com.easylogin.auth.AuthManager;
import com.easylogin.command.*;
import com.easylogin.config.EasyLoginConfig;
import com.easylogin.data.PlayerDataStore;
import com.easylogin.handler.ProtectionHandler;
import com.easylogin.util.MessageFormatter;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public class EasyLoginFabric implements DedicatedServerModInitializer {

    private EasyLoginConfig config;
    private PlayerDataStore dataStore;
    private AuthManager authManager;
    private ProtectionHandler protectionHandler;

    @Override
    public void onInitializeServer() {
        EasyLoginConstants.LOGGER.info("EasyLogin (Fabric) initializing...");

        config = new EasyLoginConfig(com.easylogin.platform.PlatformHelper.INSTANCE.getConfigDir());
        config.load();

        dataStore = new PlayerDataStore(com.easylogin.platform.PlatformHelper.INSTANCE.getConfigDir());
        authManager = new AuthManager(config, dataStore);
        protectionHandler = new ProtectionHandler(authManager);

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            dataStore.setStoragePath(com.easylogin.platform.PlatformHelper.INSTANCE.getWorldConfigDir(server));
            dataStore.migrateIfNeeded(com.easylogin.platform.PlatformHelper.INSTANCE.getConfigDir());
            dataStore.load();

            // Register event handlers
            FabricEventHandler.register(authManager, protectionHandler, config);

            EasyLoginConstants.LOGGER.info("EasyLogin (Fabric) started successfully!");
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (dataStore != null) {
                dataStore.save();
            }
            EasyLoginConstants.LOGGER.info("EasyLogin (Fabric) shutting down.");
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            if (authManager == null)
                return;
            LoginCommand.register(dispatcher, authManager);
            RegisterCommand.register(dispatcher, authManager);
            ChangePasswordCommand.register(dispatcher, authManager);
            LogoutCommand.register(dispatcher, authManager);
            AuthCommand.register(dispatcher, authManager, config, () -> config.load());
            ModMessageCommand.registerAll(dispatcher, config.mods, authManager.getFormatter());
            EasyLoginConstants.LOGGER.info("EasyLogin commands registered.");
        });
    }
}
