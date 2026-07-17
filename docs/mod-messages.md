# Mod Messages

Custom commands that show a message when a player is missing an optional client-side mod (e.g., Simple Voice Chat, Xaero's Minimap).

## Config

Add `[mods]` section to `config/easylogin/config.toml`:

```toml
[mods]
# Format: mod_<id>_command = "/<command>"
#         mod_<id>_message = "<message>"
#         mod_<id>_channel = "<mod:channel>" (optional)
#
# channel: network channel the mod registers on the client.
#   If set, the command only shows the message when the player
#   does NOT have the channel registered (i.e., missing the mod).
#   If empty/unset, the message is always shown.

voice_command = "/voice"
voice_message = "&cInstall Simple Voice Chat to use voice chat!"
voice_channel = "voicechat:voicechat"
```

## How It Works

1. Plugin parses `[mods]` section on startup/reload
2. For each entry, registers the command via Brigadier
3. When a player runs the command:
   - If `channel` is set and player's client has that channel → silent return (player has the mod)
   - Otherwise → show the configured message

## Detection Per Loader

| Loader | Method | Status |
|--------|--------|--------|
| Fabric | `ServerPlayNetworking.canSend()` | Full support |
| NeoForge | `NetworkRegistry.hasChannel()` | Stub (client-channel detection) |
| Forge | TBD | Stub |

### Fabric

Uses `ServerPlayNetworking.canSend(player, channel)`. If the client has registered the channel (meaning the mod is installed), the command returns silently. Otherwise, the message is shown.

### NeoForge / Forge

Client-channel detection pending access to `ServerGamePacketListenerImpl.connection` (protected field in 1.21.1). For now, `hasClientChannel()` returns `false` and the message is always shown.

## Example

```toml
[mods]
voice_command = "/voice"
voice_message = "&e[EasyLogin] &7Voice chat not detected!\n&fInstall &bSimple Voice Chat&f: &9https://modrinth.com/mod/simple-voice-chat"
voice_channel = "voicechat:voicechat"

vip_command = "/vip"
vip_message = "&6✨ You are a VIP! Enjoy your perks."

mapinfo_command = "/mapinfo"
mapinfo_message = "&eInstall Xaero's Minimap for a map overlay!"
mapinfo_channel = "xaerominimap:main"
```

## Files

| File | Purpose |
|------|---------|
| `.../config/EasyLoginConfig.java` | `[mods]` section + `ModEntry` |
| `.../command/ModMessageCommand.java` | Brigadier command registration |
| `.../mods/ClientModTracker.java` | Per-player state |
| `.../platform/PlatformHelper.java` | `hasClientChannel()` interface |
| `.../fabric/FabricPlatformHelper.java` | Fabric impl via `ServerPlayNetworking.canSend()` |
| `.../neoforge/NeoForgePlatformHelper.java` | NeoForge impl (stub) |
| `.../forge/ForgePlatformHelper.java` | Forge impl (stub) |
