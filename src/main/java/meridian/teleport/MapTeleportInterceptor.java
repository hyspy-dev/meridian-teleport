package meridian.teleport;

import io.netty.channel.ChannelHandlerContext;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.packets.worldmap.TeleportToWorldMapPosition;

/**
 * C2S: intercepts the map's "teleport to coordinates" request, drives the cheat
 * teleport from its coordinates, and drops the original so the server's
 * permission-gated handler never sees it (it would otherwise disconnect with
 * {@code teleportToCoordinatesNotAllowed}).
 *
 * <p>The packet's {@code x} / {@code y} are world <b>X</b> and <b>Z</b> (the map
 * is top-down); the height is resolved by {@link TeleportModule#onMapTeleport}.
 */
final class MapTeleportInterceptor implements PacketHandler {

    private final TeleportModule module;

    MapTeleportInterceptor(TeleportModule module) {
        this.module = module;
    }

    @Override
    public Action handleC2S(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (module.mapTeleportEnabled() && packet instanceof TeleportToWorldMapPosition tp) {
            module.onMapTeleport(tp.x, tp.y);   // tp.x = world X, tp.y = world Z
            return Action.DROP;
        }
        return Action.FORWARD;
    }
}
