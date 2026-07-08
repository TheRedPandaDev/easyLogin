# Operations: EasyLogin Shared MySQL Migration Guide

This guide details the procedure for migrating legacy player JSON datastores from `allthemons` and `cobbleverse` to the shared MySQL global accounts database.

## Prerequisites

1. MySQL Server running with a database named `bigbang_auth`.
2. MySQL credentials configured in EasyLogin's `config.toml` (specifically `mode = "mysql"` under `[storage]`).
3. Set the MySQL password directly in `config.toml` with `password = "..."` under `[storage]`.
4. Have the legay JSON files (`players.json` or `players_global.json`) from both servers.

---

## Migration Steps

The migration is divided into a dry-run phase (validation) and an execution phase.

### Step 1: Run dry-run validation
Execute the dry-run command to analyze the JSON files, search for conflicts, and produce a report without modifying the MySQL database:

```text
/auth migration dryrun <atmJsonPath> <cvJsonPath>
```

Example:
```text
/auth migration dryrun /servers/allthemons/config/easylogin/players.json /servers/cobbleverse/config/easylogin/players.json
```

This generates `migration_report.txt` in the same directory as the first argument, containing statistics, auto-merged accounts, exclusive accounts, and password mismatch conflicts.

### Step 2: Execute migration
After confirming there are no unexpected conflicts or corruption, execute the migration to import accounts into the database:

```text
/auth migration execute <atmJsonPath> <cvJsonPath>
```

This will:
1. Initialize the database schema if not present.
2. Safe-merge accounts that have matching passwords on both servers.
3. Skip and log conflicting accounts to `auth_migration_conflicts` for operator resolution.

---

## Conflict Resolution

If a nickname exists on both servers with different password hashes, the automatic migration skips it to prevent silent credential choices. Follow one of these administrative commands to resolve conflicts:

### Option A: Choose one server's credentials
Keep the password hash from a specific server as the global password:

```text
/auth migration resolve <nickname> choose <allthemons|cobbleverse> <atmJsonPath> <cvJsonPath>
```

### Option B: Reset global password
Force-reset the global account password to a temporary string:

```text
/auth migration resolve <nickname> reset <temporaryPassword> <atmJsonPath> <cvJsonPath>
```
Once resolved, the accounts are created globally and server identities are linked for both servers.
