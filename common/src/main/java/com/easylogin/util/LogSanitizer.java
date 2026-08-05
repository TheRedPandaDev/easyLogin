package com.easylogin.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.SimpleMessage;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Global Log4j filter to prevent passwords from leaking into server logs
 * or custom console mods (such as BetterFabricConsole).
 */
public class LogSanitizer {

    private static final Pattern SENSITIVE_COMMAND_PATTERN = Pattern.compile(
            "(?i)/(?:easylogin:)?(login|l|register|reg|changepassword)\\b(\\s+)(.*)"
    );

    private static final ThreadLocal<Boolean> IS_RELOGGING = ThreadLocal.withInitial(() -> false);
    private static boolean initialized = false;

    public static synchronized void init() {
        if (initialized) return;
        try {
            org.apache.logging.log4j.Logger logger = LogManager.getRootLogger();
            if (logger instanceof Logger coreLogger) {
                coreLogger.addFilter(new SensitiveCommandFilter(coreLogger));
                initialized = true;
            }
        } catch (Throwable t) {
            // Safe fallback if Log4j core is not available or structured differently
        }
    }

    private static class SensitiveCommandFilter extends AbstractFilter {
        private final Logger coreLogger;

        public SensitiveCommandFilter(Logger coreLogger) {
            this.coreLogger = coreLogger;
        }

        @Override
        public Result filter(LogEvent event) {
            if (IS_RELOGGING.get()) {
                return Result.NEUTRAL;
            }

            Message message = event.getMessage();
            if (message == null) {
                return Result.NEUTRAL;
            }

            String formatted = message.getFormattedMessage();
            if (formatted == null || formatted.isEmpty()) {
                return Result.NEUTRAL;
            }

            if (containsSensitiveCommand(formatted)) {
                String sanitized = sanitizeMessage(formatted);
                IS_RELOGGING.set(true);
                try {
                    coreLogger.log(
                            event.getLevel(),
                            event.getMarker(),
                            (Message) new SimpleMessage(sanitized),
                            event.getThrown()
                    );
                } finally {
                    IS_RELOGGING.set(false);
                }
                return Result.DENY;
            }

            return Result.NEUTRAL;
        }
    }

    public static boolean containsSensitiveCommand(String msg) {
        return SENSITIVE_COMMAND_PATTERN.matcher(msg).find();
    }

    public static String sanitizeMessage(String msg) {
        Matcher matcher = SENSITIVE_COMMAND_PATTERN.matcher(msg);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String cmdName = matcher.group(1);
            String space = matcher.group(2);
            String rest = matcher.group(3);

            String maskedArgs = maskArgs(rest);
            matcher.appendReplacement(sb, Matcher.quoteReplacement("/" + cmdName + space + maskedArgs));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String maskArgs(String args) {
        if (args == null || args.isEmpty()) return "";
        return args.replaceAll("\\S", "*");
    }
}
