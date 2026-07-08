package com.easylogin.data;

import com.easylogin.EasyLoginConstants;
import com.easylogin.auth.UsernameNormalizer;
import com.easylogin.auth.PasswordService;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

public class MigrationManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type ACCOUNTS_TYPE = new TypeToken<Map<String, PlayerAccount>>() {}.getType();

    public static class MigrationReport {
        public int allTheMonsCount = 0;
        public int cobbleverseCount = 0;
        public int uniqueGlobalUsers = 0;
        public int autoMergeableCount = 0; // same nick + same pass on both
        public List<String> exclusiveAllTheMons = new ArrayList<>();
        public List<String> exclusiveCobbleverse = new ArrayList<>();
        public List<String> passwordConflicts = new ArrayList<>();
        public List<String> caseConflicts = new ArrayList<>();
        public List<String> corruptedRecords = new ArrayList<>();
    }

    public static MigrationReport dryRun(Path atmPath, Path cvPath, Path reportOut) throws Exception {
        MigrationReport report = new MigrationReport();
        Map<String, PlayerAccount> atmData = loadJson(atmPath, report);
        Map<String, PlayerAccount> cvData = loadJson(cvPath, report);

        report.allTheMonsCount = atmData.size();
        report.cobbleverseCount = cvData.size();

        // Group accounts by normalized username
        Map<String, List<IndexedAccount>> grouped = new HashMap<>();

        for (Map.Entry<String, PlayerAccount> entry : atmData.entrySet()) {
            addAccountToGroup(grouped, "allthemons", entry.getKey(), entry.getValue(), report);
        }
        for (Map.Entry<String, PlayerAccount> entry : cvData.entrySet()) {
            addAccountToGroup(grouped, "cobbleverse", entry.getKey(), entry.getValue(), report);
        }

        report.uniqueGlobalUsers = grouped.size();

        for (Map.Entry<String, List<IndexedAccount>> entry : grouped.entrySet()) {
            List<IndexedAccount> list = entry.getValue();
            if (list.size() == 1) {
                IndexedAccount acc = list.get(0);
                if ("allthemons".equals(acc.source)) {
                    report.exclusiveAllTheMons.add(acc.account.getUsername());
                } else {
                    report.exclusiveCobbleverse.add(acc.account.getUsername());
                }
            } else {
                // Same normalized nickname on both servers
                IndexedAccount acc1 = list.get(0);
                IndexedAccount acc2 = list.get(1);

                // Case conflict
                if (!acc1.account.getUsername().equals(acc2.account.getUsername())) {
                    report.caseConflicts.add(acc1.account.getUsername() + " vs " + acc2.account.getUsername());
                }

                // Password digest conflict
                if (!acc1.account.getPasswordHash().equals(acc2.account.getPasswordHash())) {
                    report.passwordConflicts.add(acc1.account.getUsername());
                } else {
                    report.autoMergeableCount++;
                }
            }
        }

        // Save report to disk
        writeReportFile(report, reportOut);
        return report;
    }

    public static MigrationReport importSingleJson(Path jsonPath, Path reportOut, AuthStorage storage) throws Exception {
        MigrationReport report = new MigrationReport();
        Map<String, PlayerAccount> data = loadJson(jsonPath, report);
        report.allTheMonsCount = data.size();
        report.uniqueGlobalUsers = data.size();

        String sourceServer = inferSourceServer(jsonPath);
        int migratedUsers = 0;
        int skippedUsers = 0;

        for (Map.Entry<String, PlayerAccount> entry : data.entrySet()) {
            PlayerAccount acc = entry.getValue();
            String normalized = UsernameNormalizer.normalize(acc.getUsername());

            try {
                if (storage.getGlobalUser(normalized).isPresent()) {
                    report.passwordConflicts.add(acc.getUsername());
                    skippedUsers++;
                    continue;
                }

                migrateSingleAccount(new IndexedAccount(sourceServer, entry.getKey(), acc), storage);
                migratedUsers++;
            } catch (Exception e) {
                report.corruptedRecords.add(acc.getUsername() + ": " + e.getMessage());
                skippedUsers++;
            }
        }

        storage.recordMigrationRun(UUID.randomUUID().toString(), "single_json_import",
                String.format("Imported accounts: %d. Skipped: %d.", migratedUsers, skippedUsers));
        writeReportFile(report, reportOut);
        return report;
    }

    public static MigrationReport executeMigration(Path atmPath, Path cvPath, Path reportOut, AuthStorage storage) throws Exception {
        MigrationReport report = dryRun(atmPath, cvPath, reportOut);

        Map<String, PlayerAccount> atmData = loadJson(atmPath, report);
        Map<String, PlayerAccount> cvData = loadJson(cvPath, report);

        // Group accounts
        Map<String, List<IndexedAccount>> grouped = new HashMap<>();
        for (Map.Entry<String, PlayerAccount> entry : atmData.entrySet()) {
            addAccountToGroup(grouped, "allthemons", entry.getKey(), entry.getValue(), report);
        }
        for (Map.Entry<String, PlayerAccount> entry : cvData.entrySet()) {
            addAccountToGroup(grouped, "cobbleverse", entry.getKey(), entry.getValue(), report);
        }

        String runId = UUID.randomUUID().toString();
        storage.recordMigrationRun(runId, "started", "Migration execution started.");

        int migratedUsers = 0;
        int conflictCount = 0;

        for (Map.Entry<String, List<IndexedAccount>> entry : grouped.entrySet()) {
            String normalized = entry.getKey();
            List<IndexedAccount> list = entry.getValue();

            if (list.size() == 1) {
                // Merge-free account
                IndexedAccount acc = list.get(0);
                migrateSingleAccount(acc, storage);
                migratedUsers++;
            } else {
                IndexedAccount acc1 = list.get(0);
                IndexedAccount acc2 = list.get(1);

                if (acc1.account.getPasswordHash().equals(acc2.account.getPasswordHash())) {
                    // Password matches -> safe auto-merge
                    // We preserve capitalization of the first seen (atm)
                    GlobalUser user = new GlobalUser();
                    user.setUsernameDisplay(acc1.account.getUsername());
                    user.setUsernameNormalized(normalized);
                    user.setPasswordDigest(acc1.account.getPasswordHash());
                    user.setCreatedAt(Math.min(acc1.account.getCreatedAt(), acc2.account.getCreatedAt()));
                    storage.saveGlobalUser(user);

                    // Server identities
                    ServerIdentity idAtm = new ServerIdentity();
                    idAtm.setAuthUserId(user.getId());
                    idAtm.setServerId(acc1.source);
                    idAtm.setCurrentUuid(UUID.fromString(acc1.uuid));
                    idAtm.setFirstSeenAt(acc1.account.getCreatedAt());
                    storage.saveServerIdentity(idAtm);
                    storage.recordUuidHistory(idAtm.getId(), acc1.source, idAtm.getCurrentUuid(), "migration");

                    ServerIdentity idCv = new ServerIdentity();
                    idCv.setAuthUserId(user.getId());
                    idCv.setServerId(acc2.source);
                    idCv.setCurrentUuid(UUID.fromString(acc2.uuid));
                    idCv.setFirstSeenAt(acc2.account.getCreatedAt());
                    storage.saveServerIdentity(idCv);
                    storage.recordUuidHistory(idCv.getId(), acc2.source, idCv.getCurrentUuid(), "migration");

                    migratedUsers++;
                } else {
                    // Password digest conflict!
                    conflictCount++;
                    // Save both to conflicts table to keep evidence safe
                    storage.recordMigrationConflict(acc1.account.getUsername(), acc1.source, UUID.fromString(acc1.uuid), "password_mismatch", "Atm Pass Hash: [MASKED], Cv Pass Hash: [MASKED]");
                    storage.recordMigrationConflict(acc2.account.getUsername(), acc2.source, UUID.fromString(acc2.uuid), "password_mismatch", "Atm Pass Hash: [MASKED], Cv Pass Hash: [MASKED]");
                }
            }
        }

        String summary = String.format("Completed migration. Migrated users: %d. Blocked due to conflicts: %d.", migratedUsers, conflictCount);
        storage.recordMigrationRun(runId, conflictCount > 0 ? "completed_with_conflicts" : "success", summary);

        return report;
    }

    public static boolean resolveConflictChoose(String nickname, String sourceServer, Path atmPath, Path cvPath, AuthStorage storage) throws Exception {
        String normalized = UsernameNormalizer.normalize(nickname);
        MigrationReport dummy = new MigrationReport();
        Map<String, PlayerAccount> atmData = loadJson(atmPath, dummy);
        Map<String, PlayerAccount> cvData = loadJson(cvPath, dummy);

        IndexedAccount chosen = null;
        IndexedAccount alternative = null;

        for (Map.Entry<String, PlayerAccount> entry : atmData.entrySet()) {
            if (UsernameNormalizer.normalize(entry.getValue().getUsername()).equals(normalized)) {
                IndexedAccount ia = new IndexedAccount("allthemons", entry.getKey(), entry.getValue());
                if ("allthemons".equals(sourceServer)) chosen = ia; else alternative = ia;
            }
        }
        for (Map.Entry<String, PlayerAccount> entry : cvData.entrySet()) {
            if (UsernameNormalizer.normalize(entry.getValue().getUsername()).equals(normalized)) {
                IndexedAccount ia = new IndexedAccount("cobbleverse", entry.getKey(), entry.getValue());
                if ("cobbleverse".equals(sourceServer)) chosen = ia; else alternative = ia;
            }
        }

        if (chosen == null || alternative == null) {
            return false;
        }

        // Migrate using chosen password
        GlobalUser user = new GlobalUser();
        user.setUsernameDisplay(chosen.account.getUsername());
        user.setUsernameNormalized(normalized);
        user.setPasswordDigest(chosen.account.getPasswordHash());
        user.setCreatedAt(chosen.account.getCreatedAt());
        storage.saveGlobalUser(user);

        // Save server identity for chosen
        ServerIdentity siChosen = new ServerIdentity();
        siChosen.setAuthUserId(user.getId());
        siChosen.setServerId(chosen.source);
        siChosen.setCurrentUuid(UUID.fromString(chosen.uuid));
        siChosen.setFirstSeenAt(chosen.account.getCreatedAt());
        storage.saveServerIdentity(siChosen);
        storage.recordUuidHistory(siChosen.getId(), chosen.source, siChosen.getCurrentUuid(), "conflict_resolved");

        // Save server identity for alternative
        ServerIdentity siAlt = new ServerIdentity();
        siAlt.setAuthUserId(user.getId());
        siAlt.setServerId(alternative.source);
        siAlt.setCurrentUuid(UUID.fromString(alternative.uuid));
        siAlt.setFirstSeenAt(alternative.account.getCreatedAt());
        storage.saveServerIdentity(siAlt);
        storage.recordUuidHistory(siAlt.getId(), alternative.source, siAlt.getCurrentUuid(), "conflict_resolved");

        storage.logAudit("CONFLICT_RESOLVED", nickname, null, "global", "admin", "Resolved conflict by choosing password from " + sourceServer);
        return true;
    }

    public static boolean resolveConflictReset(String nickname, String tempPassword, Path atmPath, Path cvPath, AuthStorage storage) throws Exception {
        String normalized = UsernameNormalizer.normalize(nickname);
        MigrationReport dummy = new MigrationReport();
        Map<String, PlayerAccount> atmData = loadJson(atmPath, dummy);
        Map<String, PlayerAccount> cvData = loadJson(cvPath, dummy);

        IndexedAccount atmAcc = null;
        IndexedAccount cvAcc = null;

        for (Map.Entry<String, PlayerAccount> entry : atmData.entrySet()) {
            if (UsernameNormalizer.normalize(entry.getValue().getUsername()).equals(normalized)) {
                atmAcc = new IndexedAccount("allthemons", entry.getKey(), entry.getValue());
            }
        }
        for (Map.Entry<String, PlayerAccount> entry : cvData.entrySet()) {
            if (UsernameNormalizer.normalize(entry.getValue().getUsername()).equals(normalized)) {
                cvAcc = new IndexedAccount("cobbleverse", entry.getKey(), entry.getValue());
            }
        }

        if (atmAcc == null && cvAcc == null) {
            return false;
        }

        GlobalUser user = new GlobalUser();
        user.setUsernameDisplay(atmAcc != null ? atmAcc.account.getUsername() : cvAcc.account.getUsername());
        user.setUsernameNormalized(normalized);
        user.setPasswordDigest(PasswordService.hash(tempPassword));
        user.setCreatedAt(System.currentTimeMillis());
        storage.saveGlobalUser(user);

        if (atmAcc != null) {
            ServerIdentity si = new ServerIdentity();
            si.setAuthUserId(user.getId());
            si.setServerId("allthemons");
            si.setCurrentUuid(UUID.fromString(atmAcc.uuid));
            si.setFirstSeenAt(atmAcc.account.getCreatedAt());
            storage.saveServerIdentity(si);
            storage.recordUuidHistory(si.getId(), "allthemons", si.getCurrentUuid(), "conflict_resolved_reset");
        }
        if (cvAcc != null) {
            ServerIdentity si = new ServerIdentity();
            si.setAuthUserId(user.getId());
            si.setServerId("cobbleverse");
            si.setCurrentUuid(UUID.fromString(cvAcc.uuid));
            si.setFirstSeenAt(cvAcc.account.getCreatedAt());
            storage.saveServerIdentity(si);
            storage.recordUuidHistory(si.getId(), "cobbleverse", si.getCurrentUuid(), "conflict_resolved_reset");
        }

        storage.logAudit("CONFLICT_RESOLVED", nickname, null, "global", "admin", "Resolved conflict by resetting password");
        return true;
    }

    private static void migrateSingleAccount(IndexedAccount acc, AuthStorage storage) throws Exception {
        String normalized = UsernameNormalizer.normalize(acc.account.getUsername());

        GlobalUser user = new GlobalUser();
        user.setUsernameDisplay(acc.account.getUsername());
        user.setUsernameNormalized(normalized);
        user.setPasswordDigest(acc.account.getPasswordHash());
        user.setCreatedAt(acc.account.getCreatedAt());
        storage.saveGlobalUser(user);

        ServerIdentity identity = new ServerIdentity();
        identity.setAuthUserId(user.getId());
        identity.setServerId(acc.source);
        identity.setCurrentUuid(UUID.fromString(acc.uuid));
        identity.setFirstSeenAt(acc.account.getCreatedAt());
        storage.saveServerIdentity(identity);

        storage.recordUuidHistory(identity.getId(), acc.source, identity.getCurrentUuid(), "migration");
    }

    private static String inferSourceServer(Path jsonPath) {
        String lower = jsonPath.toString().toLowerCase(Locale.ROOT);
        if (lower.contains("allthemons")) {
            return "allthemons";
        }
        if (lower.contains("cobbleverse")) {
            return "cobbleverse";
        }
        return "local";
    }

    private static Map<String, PlayerAccount> loadJson(Path path, MigrationReport report) {
        if (!Files.exists(path)) {
            return Collections.emptyMap();
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            Map<String, PlayerAccount> data = GSON.fromJson(reader, ACCOUNTS_TYPE);
            if (data == null) return Collections.emptyMap();
            return data;
        } catch (Exception e) {
            report.corruptedRecords.add(path.getFileName().toString() + ": failed to parse JSON");
            return Collections.emptyMap();
        }
    }

    private static void addAccountToGroup(Map<String, List<IndexedAccount>> grouped, String source, String uuid, PlayerAccount acc, MigrationReport report) {
        if (acc.getUsername() == null || acc.getPasswordHash() == null) {
            report.corruptedRecords.add(source + "/" + uuid + ": null fields");
            return;
        }
        String normalized = UsernameNormalizer.normalize(acc.getUsername());
        grouped.computeIfAbsent(normalized, k -> new ArrayList<>()).add(new IndexedAccount(source, uuid, acc));
    }

    private static void writeReportFile(MigrationReport report, Path reportOut) throws IOException {
        Files.createDirectories(reportOut.getParent());
        try (Writer writer = Files.newBufferedWriter(reportOut)) {
            writer.write("=====================================================\n");
            writer.write("EasyLogin Shared MySQL Migration Dry-Run Report\n");
            writer.write("=====================================================\n\n");
            writer.write("Summary:\n");
            writer.write(String.format("  - AllTheMons accounts: %d\n", report.allTheMonsCount));
            writer.write(String.format("  - Cobbleverse accounts: %d\n", report.cobbleverseCount));
            writer.write(String.format("  - Unique Global Usernames: %d\n", report.uniqueGlobalUsers));
            writer.write(String.format("  - Auto-mergeable (Same nick/pass on both): %d\n", report.autoMergeableCount));
            writer.write(String.format("  - Password digest conflicts (Same name, different password): %d\n", report.passwordConflicts.size()));
            writer.write(String.format("  - Case sensitivity mismatches (Same name, different casing): %d\n", report.caseConflicts.size()));
            writer.write(String.format("  - Corrupted records: %d\n\n", report.corruptedRecords.size()));

            writer.write("Password Conflicts:\n");
            for (String nick : report.passwordConflicts) {
                writer.write("  - " + nick + " (Evidences safely protected - hashes masked)\n");
            }
            writer.write("\nCase Conflicts:\n");
            for (String conflict : report.caseConflicts) {
                writer.write("  - " + conflict + "\n");
            }
            writer.write("\nExclusive AllTheMons:\n");
            for (String nick : report.exclusiveAllTheMons) {
                writer.write("  - " + nick + "\n");
            }
            writer.write("\nExclusive Cobbleverse:\n");
            for (String nick : report.exclusiveCobbleverse) {
                writer.write("  - " + nick + "\n");
            }
        }
    }

    private static class IndexedAccount {
        final String source;
        final String uuid;
        final PlayerAccount account;

        IndexedAccount(String source, String uuid, PlayerAccount account) {
            this.source = source;
            this.uuid = uuid;
            this.account = account;
        }
    }
}
