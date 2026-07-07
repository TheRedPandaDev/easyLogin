package com.easylogin.auth;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

public final class UsernameNormalizer {

    private static final Pattern MINECRAFT_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{2,16}$");

    private UsernameNormalizer() {
    }

    /**
     * Normalize the username by trimming, applying NFC normalization, and converting to lowercase.
     */
    public static String normalize(String username) {
        if (username == null) {
            return "";
        }
        String result = username.trim();
        result = Normalizer.normalize(result, Normalizer.Form.NFC);
        result = result.toLowerCase(Locale.ROOT);
        return result;
    }

    /**
     * Validate if the username matches standard Minecraft conventions (2-16 characters, alphanumeric + underscore).
     */
    public static boolean isValid(String username) {
        if (username == null || username.trim().isEmpty()) {
            return false;
        }
        String trimmed = username.trim();
        return MINECRAFT_NAME_PATTERN.matcher(trimmed).matches();
    }
}
