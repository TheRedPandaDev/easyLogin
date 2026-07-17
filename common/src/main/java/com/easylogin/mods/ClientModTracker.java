package com.easylogin.mods;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Tracks per-player data related to optional mod detection.
 */
public class ClientModTracker {

    private static final ConcurrentMap<UUID, Boolean> warnedOnJoin = new ConcurrentHashMap<>();

    public static boolean wasWarnedOnJoin(UUID playerId) {
        return warnedOnJoin.containsKey(playerId);
    }

    public static void markWarnedOnJoin(UUID playerId) {
        warnedOnJoin.put(playerId, true);
    }

    public static void removePlayer(UUID playerId) {
        warnedOnJoin.remove(playerId);
    }
}
