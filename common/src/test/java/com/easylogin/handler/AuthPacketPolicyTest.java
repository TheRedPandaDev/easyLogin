package com.easylogin.handler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthPacketPolicyTest {
    @Test
    void acceptsAuthenticationCommandsAndTheirExactAliases() {
        for (String root : new String[]{"login", "l", "register", "reg",
                "easylogin:login", "easylogin:l", "easylogin:register", "easylogin:reg"}) {
            // Commands without arguments must still reach their normal usage message.
            assertTrue(AuthPacketPolicy.allowsCommand(root), root);
            assertTrue(AuthPacketPolicy.allowsCommand(root + " secret"), root);
            assertTrue(AuthPacketPolicy.allowsCommand(root + " secret secret"), root);
        }
    }

    @Test
    void operatorCommandsNamespacesAndPrefixTricksCannotBypassTheGate() {
        for (String command : new String[]{"give @s diamond", "minecraft:give @s diamond", "op someone",
                "execute run login secret", "loginExtra secret", "registerevil x", "auth force-login me",
                "easylogin:auth force-login me", "create config", "config", "logout", "changepassword old new new",
                "other:login secret", "minecraft:login secret", "other:easylogin:login secret",
                "easylogin:loginExtra secret", "easylogin:registerevil secret"}) {
            assertFalse(AuthPacketPolicy.allowsCommand(command), command);
        }
    }

    @Test
    void matchesBrigadierLiteralRootsWithoutNormalizingMalformedInput() {
        for (String command : new String[]{"", " ", " login secret", "login\tsecret", "login\nsecret",
                "login\rsecret", "/login secret", "Login secret", "EASYLOGIN:login secret",
                "lo\u200bgin secret", "login\u00a0secret"}) {
            assertFalse(AuthPacketPolicy.allowsCommand(command), command);
        }
        assertFalse(AuthPacketPolicy.allowsCommand(null));
    }
}
