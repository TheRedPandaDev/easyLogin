package com.easylogin.handler;

import com.easylogin.auth.AuthManager;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.*;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/** Gates PLAY packets before either vanilla or mod handlers can change server state. */
public final class AuthPacketGate {
    private AuthPacketGate() {}

    public static boolean authenticated(ServerGamePacketListenerImpl listener) {
        AuthManager auth = AuthManager.INSTANCE;
        return auth != null && listener.isAcceptingMessages()
                && listener.player.connection == listener
                && auth.isAuthenticated(listener.player);
    }

    /** Configuration/login-phase work is untouched; queued PLAY payload work requires auth. */
    public static boolean allowsPayloadWork(PacketListener listener) {
        return !(listener instanceof ServerGamePacketListenerImpl game) || authenticated(game);
    }

    public static boolean allows(ServerGamePacketListenerImpl listener, Packet<?> packet) {
        if (authenticated(listener)) return true;
        AuthManager auth = AuthManager.INSTANCE;
        boolean current = auth != null && listener.player.connection == listener
                && auth.isCurrentConnection(listener.player);
        if (packet instanceof ServerboundChatCommandPacket || packet instanceof ServerboundChatCommandSignedPacket) {
            return current && allowsWhileLocked(packet);
        }
        // Retain the existing optional movement/chat restrictions. Neither permits
        // commands, vehicle controls, inventory changes, or mod payloads.
        if (current) {
            if (packet instanceof ServerboundMovePlayerPacket && !auth.getConfig().blockMovement) return true;
            if (packet instanceof ServerboundChatPacket && !auth.getConfig().blockChat) return true;
        }
        return allowsWhileLocked(packet);
    }

    public static boolean allowsWhileLocked(Packet<?> packet) {
        if (packet instanceof ServerboundChatCommandPacket command) {
            return AuthPacketPolicy.allowsCommand(command.command());
        }
        if (packet instanceof ServerboundChatCommandSignedPacket command) {
            return AuthPacketPolicy.allowsCommand(command.command());
        }
        // Unknown/new packets are denied. In particular, do not allow an entire
        // custom-payload namespace: a bundle may reconstruct gameplay packets.
        return packet instanceof ServerboundKeepAlivePacket
                || packet instanceof ServerboundPongPacket
                || packet instanceof ServerboundClientInformationPacket
                || packet instanceof ServerboundResourcePackPacket
                || packet instanceof ServerboundAcceptTeleportationPacket
                || packet instanceof ServerboundChunkBatchReceivedPacket
                || packet instanceof ServerboundChatAckPacket
                || packet instanceof ServerboundChatSessionUpdatePacket
                || packet instanceof ServerboundConfigurationAcknowledgedPacket;
    }

    public static boolean needsInventoryResync(Packet<?> packet) {
        return packet instanceof ServerboundSetCreativeModeSlotPacket
                || packet instanceof ServerboundContainerClickPacket
                || packet instanceof ServerboundContainerClosePacket
                || packet instanceof ServerboundSetCarriedItemPacket
                || packet instanceof ServerboundPlayerActionPacket
                || packet instanceof ServerboundUseItemOnPacket
                || packet instanceof ServerboundUseItemPacket;
    }

    /** Sequence of client-predicted world changes that a rejected packet must undo. */
    public static int predictionSequence(Packet<?> packet) {
        if (packet instanceof ServerboundUseItemOnPacket placement) return placement.getSequence();
        if (packet instanceof ServerboundUseItemPacket use) return use.getSequence();
        if (packet instanceof ServerboundPlayerActionPacket action) {
            return switch (action.getAction()) {
                case START_DESTROY_BLOCK, STOP_DESTROY_BLOCK, ABORT_DESTROY_BLOCK -> action.getSequence();
                default -> -1;
            };
        }
        return -1;
    }
}
