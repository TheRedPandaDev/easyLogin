package com.easylogin.handler;

import java.util.Set;

/** Explicit allowlist: new packet types are locked until the connection authenticates. */
public final class AuthPacketPolicy {
    private static final Set<String> COMMANDS = Set.of("login", "l", "register", "reg",
            "easylogin:login", "easylogin:l", "easylogin:register", "easylogin:reg");

    private AuthPacketPolicy() {}

    public static boolean allowsCommand(String command) {
        if (command == null || command.isEmpty()) return false;
        int end = command.indexOf(' ');
        String root = end < 0 ? command : command.substring(0, end);
        // Match Brigadier's literal command root, not a prefix or arbitrary namespace.
        return COMMANDS.contains(root);
    }
}
