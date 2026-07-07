package com.easylogin.auth;

import com.easylogin.EasyLoginConstants;
import com.easylogin.config.EasyLoginConfig;
import com.easylogin.data.GlobalUser;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

public class WebStoreSyncService {
    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "EasyLogin-WebStoreSync");
        thread.setDaemon(true);
        return thread;
    });

    private static final Queue<SyncTask> retryQueue = new ConcurrentLinkedQueue<>();

    static {
        // Run retry tasks periodically every 30 seconds
        EXECUTOR.scheduleAtFixedRate(WebStoreSyncService::processRetries, 30, 30, TimeUnit.SECONDS);
    }

    private static class SyncTask {
        final String eventId;
        final String globalAccountId;
        final String username;
        final String canonicalUsername;
        final String serverId;
        final String minecraftUuid;
        final long verifiedAt;
        int attempts = 0;

        SyncTask(GlobalUser user, String serverId, UUID minecraftUuid) {
            this.eventId = UUID.randomUUID().toString();
            this.globalAccountId = user.getPublicId();
            this.username = user.getUsernameDisplay();
            this.canonicalUsername = user.getUsernameNormalized();
            this.serverId = serverId;
            this.minecraftUuid = minecraftUuid.toString();
            this.verifiedAt = System.currentTimeMillis();
        }

        String toJson() {
            return "{"
                    + "\"event_id\":\"" + eventId + "\","
                    + "\"global_account_id\":\"" + globalAccountId + "\","
                    + "\"username\":\"" + escapeJson(username) + "\","
                    + "\"canonical_username\":\"" + escapeJson(canonicalUsername) + "\","
                    + "\"server_id\":\"" + escapeJson(serverId) + "\","
                    + "\"minecraft_uuid\":\"" + minecraftUuid + "\","
                    + "\"verified_at\":" + verifiedAt
                    + "}";
        }

        private String escapeJson(String str) {
            if (str == null) return "";
            return str.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }

    public static void syncAccount(EasyLoginConfig config, GlobalUser user, UUID minecraftUuid) {
        if (!config.webstoreEnabled) {
            return;
        }
        SyncTask task = new SyncTask(user, config.serverId, minecraftUuid);
        EXECUTOR.submit(() -> sendSync(config, task));
    }

    private static void sendSync(EasyLoginConfig config, SyncTask task) {
        task.attempts++;
        try {
            URL url = new URL(config.webstoreUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            String body = task.toJson();
            String timestamp = String.valueOf(task.verifiedAt);
            String nonce = UUID.randomUUID().toString().substring(0, 8); // short nonce representation
            
            // Sign: timestamp + nonce + body
            String signature = calculateHmac(timestamp + nonce + body, config.webstoreToken);

            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-EasyLogin-Timestamp", timestamp);
            conn.setRequestProperty("X-EasyLogin-Nonce", nonce);
            conn.setRequestProperty("X-EasyLogin-Signature", signature);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            if (responseCode >= 200 && responseCode < 300) {
                // Log sync success, omitting full nonce/body/secrets
                EasyLoginConstants.LOGGER.info("WebStore identity sync succeeded for user (response: {})", responseCode);
            } else {
                EasyLoginConstants.LOGGER.warn("WebStore identity sync failed with status {}. Queuing for retry.", responseCode);
                queueForRetry(task);
            }
        } catch (Exception e) {
            EasyLoginConstants.LOGGER.warn("WebStore identity sync encountered an error. Queuing for retry.");
            queueForRetry(task);
        }
    }

    private static void queueForRetry(SyncTask task) {
        if (task.attempts < 10) {
            retryQueue.offer(task);
        } else {
            EasyLoginConstants.LOGGER.error("WebStore identity sync failed after maximum attempts.");
        }
    }

    private static void processRetries() {
        SyncTask task;
        // Drain and retry up to 5 tasks at once to prevent thread hogging
        int count = 0;
        while ((task = retryQueue.poll()) != null && count++ < 5) {
            final SyncTask currentTask = task;
            // Get config from AuthManager instance safely
            if (AuthManager.INSTANCE != null && AuthManager.INSTANCE.getConfig() != null) {
                EasyLoginConfig config = AuthManager.INSTANCE.getConfig();
                EXECUTOR.submit(() -> sendSync(config, currentTask));
            } else {
                // Return to queue if AuthManager not initialized
                retryQueue.offer(currentTask);
                break;
            }
        }
    }

    private static String calculateHmac(String data, String key) throws Exception {
        Mac sha256HMAC = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        sha256HMAC.init(secretKey);
        byte[] hash = sha256HMAC.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return bytesToHex(hash);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
