# Pre-login protection

This change blocks unauthenticated PLAY traffic before packet handlers can mutate
inventories, blocks, creative slots, or mod configuration. Operator privileges and
creative mode do not bypass authentication. LOGIN and CONFIGURATION traffic is unchanged.

## Packet policy

- Allowed command roots are exactly `login`, `l`, `register`, and `reg`, plus their
  `easylogin:` equivalents. Signed and unsigned command packets use the same policy.
  Prefix matches, other namespaces, and command completion are not allowed.
- Essential traffic remains allowed: keepalive, pong, client information, resource
  pack status, teleport acknowledgement, chunk batch acknowledgement, chat
  acknowledgement, chat session update, and configuration acknowledgement.
- Existing `blockMovement` and `blockChat` settings are honored for the current
  connection. Disabling either does not allow vehicle controls, other commands,
  inventory changes, or custom payloads.
- All other packets are denied until login. NeoForge PLAY custom payloads are
  blocked, including `neoforge:split`; no whole payload namespace is trusted.
- The listener checks both initial dispatch and queued vanilla execution. NeoForge
  reconstructed packets pass through the same check, and queued payload work
  rechecks authentication when it executes, including after logout.
- Item pickup has no client packet, so a NeoForge pickup event also denies it.
  The existing FakePlayer exemption remains in event protection for automation.

## Client corrections and sessions

Rejected inventory operations schedule an authoritative inventory and selected-slot
resync. The final correction stays pending during bursts. Denied block/item-use
prediction sequences are acknowledged independently, so client-predicted blocks
and fluids roll back without accepting their changes. Login and logout also resync
inventory. These corrections do not make rejected actions valid.

Authentication belongs to one listener identity, not every connection claiming its
UUID. Reconnects begin locked; an old disconnect cannot clear replacement state.
Session-generation tokens invalidate stale asynchronous completions on login,
logout, or disconnect. Main-thread callbacks also reject obsolete player entities.
Respawn retains authentication through its listener; event queries deliberately do
not require the listener's player field to have been reassigned yet.
Already-started storage calls cannot be rolled back by these session checks.

## Validation status and build

The target is Minecraft **1.21.1**. The NeoForge **21.1.42** build and all **25**
common unit tests passed. A packaged EasyLogin JAR ran without a companion guard on
an isolated NeoForge **21.1.230** server: **29** authentication/inventory/payload and
respawn checks plus **50** prediction/resync checks passed. The run covered account
migration and an actual server restart, not only reconnects. Pickup fixtures used a
solid floor so a protocol-only client's missing physics did not suppress collisions.

This does not establish support for newer Minecraft versions or other loaders,
including Forge. An unqualified local build was blocked during unrelated Forge
configuration by the unavailable `lwjgl-freetype:3.3.3:natives-macos-patch` artifact.
The targeted command below avoids that configuration without modifying build files.
The existing CI matrix also names newer version projects absent from settings.gradle;
this change does not repair that version scaffolding.

With Java 21, run the targeted tasks from the repository root:

```sh
./gradlew --configure-on-demand :common:1.21.1:test :neoforge:1.21.1:assemble
```

## Manual validation

Use a disposable test world and a registered test account with operator and creative
permissions. Reconnect and leave the account unauthenticated before each blocked test.

1. Try creative grants, slot moves, hotbar changes, Q drops, and inventory-screen
   drops. Confirm no server inventory changes, spawned items, or lost items.
2. Walk near dropped items; confirm no pickup. Exercise a mod's server configuration
   and inventory screens; confirm their PLAY payloads cannot change server state.
3. Try breaking/placing blocks, doors, beds, buckets, and rapid repeated actions.
   Confirm client ghosts disappear, then reconnect to verify authoritative world state.
4. Log in correctly and repeat normal gameplay/configuration actions; verify they work.
5. Log out while packet/mod work is queued; verify later execution remains blocked.
6. Reconnect with the same identity, test a duplicate connection, and delay an auth
   lookup across reconnect/logout. None should inherit or restore an obsolete login.
7. Respawn while authenticated, including with limbo enabled; remain authenticated
   and avoid an unintended limbo teleport. Check FakePlayer automation still works.
