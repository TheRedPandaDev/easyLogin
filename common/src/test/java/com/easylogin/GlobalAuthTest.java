package com.easylogin;

import com.easylogin.auth.PasswordService;
import com.easylogin.auth.UsernameNormalizer;
import com.easylogin.data.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class GlobalAuthTest {

    @TempDir
    Path tempDir;

    private JsonAuthStorage storage;

    @BeforeEach
    public void setUp() throws Exception {
        storage = new JsonAuthStorage(tempDir);
        storage.load();
    }

    @Test
    public void testUsernameNormalization() {
        assertEquals("pedropsrei", UsernameNormalizer.normalize("PedropsRei"));
        assertEquals("pedropsrei", UsernameNormalizer.normalize("  PedropsRei  "));
        assertEquals("pedropsrei", UsernameNormalizer.normalize("pedropsrei"));
        
        assertTrue(UsernameNormalizer.isValid("PedropsRei"));
        assertTrue(UsernameNormalizer.isValid("ped123"));
        assertTrue(UsernameNormalizer.isValid("a_b"));
        
        assertFalse(UsernameNormalizer.isValid(""));
        assertFalse(UsernameNormalizer.isValid("a")); // too short (minimum 2 chars)
        assertFalse(UsernameNormalizer.isValid("invalid-chars")); // contains hyphen
    }

    @Test
    public void testGlobalUserCreation() throws Exception {
        GlobalUser user = new GlobalUser();
        user.setUsernameDisplay("PedropsRei");
        user.setUsernameNormalized(UsernameNormalizer.normalize("PedropsRei"));
        user.setPasswordDigest(PasswordService.hash("mysecret"));
        user.setStatus("active");

        storage.saveGlobalUser(user);

        Optional<GlobalUser> fetched = storage.getGlobalUser("pedropsrei");
        assertTrue(fetched.isPresent());
        assertEquals("PedropsRei", fetched.get().getUsernameDisplay());
        assertTrue(PasswordService.verify("mysecret", fetched.get().getPasswordDigest()));
    }

    @Test
    public void testServerIdentityAndUUIDs() throws Exception {
        // Register global user
        GlobalUser user = new GlobalUser();
        user.setUsernameDisplay("PedropsRei");
        user.setUsernameNormalized("pedropsrei");
        user.setPasswordDigest(PasswordService.hash("password"));
        storage.saveGlobalUser(user);

        // AllTheMons identity (UUID 1)
        UUID atmUuid = UUID.randomUUID();
        ServerIdentity atmId = new ServerIdentity();
        atmId.setAuthUserId(user.getId());
        atmId.setServerId("allthemons");
        atmId.setCurrentUuid(atmUuid);
        storage.saveServerIdentity(atmId);
        storage.recordUuidHistory(atmId.getId(), "allthemons", atmUuid, "register");

        // Cobbleverse identity (UUID 2)
        UUID cvUuid = UUID.randomUUID();
        ServerIdentity cvId = new ServerIdentity();
        cvId.setAuthUserId(user.getId());
        cvId.setServerId("cobbleverse");
        cvId.setCurrentUuid(cvUuid);
        storage.saveServerIdentity(cvId);
        storage.recordUuidHistory(cvId.getId(), "cobbleverse", cvUuid, "login");

        // Verify different UUIDs on different servers
        Optional<ServerIdentity> atmFetched = storage.getServerIdentity(user.getId(), "allthemons");
        Optional<ServerIdentity> cvFetched = storage.getServerIdentity(user.getId(), "cobbleverse");

        assertTrue(atmFetched.isPresent());
        assertTrue(cvFetched.isPresent());
        assertEquals(atmUuid, atmFetched.get().getCurrentUuid());
        assertEquals(cvUuid, cvFetched.get().getCurrentUuid());
        assertNotEquals(atmFetched.get().getCurrentUuid(), cvFetched.get().getCurrentUuid());
    }

    @Test
    public void testUuidConflictDetection() throws Exception {
        GlobalUser user1 = new GlobalUser();
        user1.setUsernameDisplay("UserOne");
        user1.setUsernameNormalized("userone");
        user1.setPasswordDigest(PasswordService.hash("pass1"));
        storage.saveGlobalUser(user1);

        ServerIdentity id1 = new ServerIdentity();
        id1.setAuthUserId(user1.getId());
        id1.setServerId("allthemons");
        id1.setCurrentUuid(UUID.randomUUID());
        storage.saveServerIdentity(id1);
        storage.recordUuidHistory(id1.getId(), "allthemons", id1.getCurrentUuid(), "register");

        // User2 trying to claim User1's UUID on the same server
        assertTrue(storage.hasUuidConflict("allthemons", id1.getCurrentUuid(), 2)); // exclude user 2
        assertFalse(storage.hasUuidConflict("allthemons", id1.getCurrentUuid(), user1.getId())); // exclude user 1 (no conflict for owner)
    }

    @Test
    public void testMigrationManager() throws Exception {
        // Create mock local JSON files
        Path atmFile = tempDir.resolve("atm_players.json");
        Path cvFile = tempDir.resolve("cv_players.json");
        Path reportFile = tempDir.resolve("migration_report.txt");

        UUID uuidShared = UUID.randomUUID();
        String passHash = PasswordService.hash("commonpass");

        // ATM player accounts
        String atmJson = "{\n" +
                "  \"" + uuidShared + "\": {\n" +
                "    \"uuid\": \"" + uuidShared + "\",\n" +
                "    \"username\": \"PedropsRei\",\n" +
                "    \"passwordHash\": \"" + passHash + "\",\n" +
                "    \"createdAt\": 1000,\n" +
                "    \"lastLogin\": 2000,\n" +
                "    \"lastIp\": \"127.0.0.1\",\n" +
                "    \"loginCount\": 5\n" +
                "  }\n" +
                "}";

        // CV player accounts (same name, same password)
        String cvJson = "{\n" +
                "  \"" + uuidShared + "\": {\n" +
                "    \"uuid\": \"" + uuidShared + "\",\n" +
                "    \"username\": \"PedropsRei\",\n" +
                "    \"passwordHash\": \"" + passHash + "\",\n" +
                "    \"createdAt\": 1000,\n" +
                "    \"lastLogin\": 2000,\n" +
                "    \"lastIp\": \"127.0.0.1\",\n" +
                "    \"loginCount\": 5\n" +
                "  }\n" +
                "}";

        Files.writeString(atmFile, atmJson);
        Files.writeString(cvFile, cvJson);

        // Dry-run migration
        MigrationManager.MigrationReport report = MigrationManager.dryRun(atmFile, cvFile, reportFile);
        assertEquals(1, report.allTheMonsCount);
        assertEquals(1, report.cobbleverseCount);
        assertEquals(1, report.uniqueGlobalUsers);
        assertEquals(1, report.autoMergeableCount);
        assertEquals(0, report.passwordConflicts.size());
        assertTrue(Files.exists(reportFile));

        // Execute migration
        MigrationManager.executeMigration(atmFile, cvFile, reportFile, storage);

        // Verify globally created user
        Optional<GlobalUser> userOpt = storage.getGlobalUser("pedropsrei");
        assertTrue(userOpt.isPresent());
        assertEquals("PedropsRei", userOpt.get().getUsernameDisplay());

        // Verify identities created on both servers
        Optional<ServerIdentity> idAtm = storage.getServerIdentity(userOpt.get().getId(), "allthemons");
        Optional<ServerIdentity> idCv = storage.getServerIdentity(userOpt.get().getId(), "cobbleverse");
        assertTrue(idAtm.isPresent());
        assertTrue(idCv.isPresent());
    }

    @Test
    public void testMigrationConflictDetection() throws Exception {
        Path atmFile = tempDir.resolve("atm_players.json");
        Path cvFile = tempDir.resolve("cv_players.json");
        Path reportFile = tempDir.resolve("migration_report.txt");

        UUID atmUuid = UUID.randomUUID();
        UUID cvUuid = UUID.randomUUID();
        String passAtm = PasswordService.hash("atm_secret");
        String passCv = PasswordService.hash("cv_secret"); // conflict password digest!

        String atmJson = "{\n" +
                "  \"" + atmUuid + "\": {\n" +
                "    \"uuid\": \"" + atmUuid + "\",\n" +
                "    \"username\": \"PedropsRei\",\n" +
                "    \"passwordHash\": \"" + passAtm + "\"\n" +
                "  }\n" +
                "}";

        String cvJson = "{\n" +
                "  \"" + cvUuid + "\": {\n" +
                "    \"uuid\": \"" + cvUuid + "\",\n" +
                "    \"username\": \"PedropsRei\",\n" +
                "    \"passwordHash\": \"" + passCv + "\"\n" +
                "  }\n" +
                "}";

        Files.writeString(atmFile, atmJson);
        Files.writeString(cvFile, cvJson);

        MigrationManager.MigrationReport report = MigrationManager.dryRun(atmFile, cvFile, reportFile);
        assertEquals(1, report.passwordConflicts.size());
        assertEquals("PedropsRei", report.passwordConflicts.get(0));

        // Execute migration
        MigrationManager.executeMigration(atmFile, cvFile, reportFile, storage);

        // Account should NOT be created due to password conflict
        Optional<GlobalUser> userOpt = storage.getGlobalUser("pedropsrei");
        assertFalse(userOpt.isPresent());

        // Resolve conflict manually by choosing ATM
        boolean resolved = MigrationManager.resolveConflictChoose("PedropsRei", "allthemons", atmFile, cvFile, storage);
        assertTrue(resolved);

        userOpt = storage.getGlobalUser("pedropsrei");
        assertTrue(userOpt.isPresent());
        assertTrue(PasswordService.verify("atm_secret", userOpt.get().getPasswordDigest()));
    }

    @Test
    public void testRateLimiterLockout() {
        com.easylogin.auth.RateLimiter limiter = new com.easylogin.auth.RateLimiter(3, 1, 15);
        String ip = "127.0.0.1";
        
        assertEquals(0, limiter.isLocked(ip));
        assertEquals(2, limiter.recordFailure(ip));
        assertEquals(1, limiter.recordFailure(ip));
        assertEquals(-1, limiter.recordFailure(ip)); // should lockout on 3rd failure
        
        assertTrue(limiter.isLocked(ip) > 0);
        
        limiter.clear(ip);
        assertEquals(0, limiter.isLocked(ip));
    }

    @Test
    public void testWebStoreSignatureHmac() throws Exception {
        String token = "webstore_secret_token";
        String body = "{\"username\":\"PedropsRei\"}";
        String timestamp = "1783478239000";
        String nonce = "abcde123";
        
        String input = timestamp + nonce + body;
        
        javax.crypto.Mac sha256HMAC = javax.crypto.Mac.getInstance("HmacSHA256");
        javax.crypto.spec.SecretKeySpec secretKey = new javax.crypto.spec.SecretKeySpec(token.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");
        sha256HMAC.init(secretKey);
        byte[] hash = sha256HMAC.doFinal(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        String calculated = hexString.toString();
        
        // Assert we calculated a valid non-empty signature of length 64
        assertEquals(64, calculated.length());
        assertNotNull(calculated);
    }
}
