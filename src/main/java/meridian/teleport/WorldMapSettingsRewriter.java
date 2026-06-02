package meridian.teleport;

import io.netty.channel.ChannelHandlerContext;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.packets.worldmap.UpdateWorldMapSettings;

/**
 * S2C: flips {@code allowTeleportToCoordinates} on so the in-game world map shows
 * its "teleport to coordinates" UI, even though the server (which gates it on the
 * {@code WORLD_MAP_COORDINATE_TELEPORT} permission + non-Adventure) said no.
 *
 * <p>The client only renders the button from this flag; the actual teleport the
 * client then sends ({@code TeleportToWorldMapPosition}) is intercepted by
 * {@link MapTeleportInterceptor} and never reaches the server's gated handler.
 */
final class WorldMapSettingsRewriter implements PacketHandler {

    private final TeleportModule module;

    WorldMapSettingsRewriter(TeleportModule module) {
        this.module = module;
    }

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (module.mapTeleportEnabled() && packet instanceof UpdateWorldMapSettings s) {
            boolean changed = false;
            if (!s.enabled) {
                s.enabled = true;
                changed = true;
            }
            if (!s.allowTeleportToCoordinates) {
                s.allowTeleportToCoordinates = true;
                changed = true;
            }
            if (changed) {
                return Action.MODIFIED;
            }
        }
        return Action.FORWARD;
    }
}
