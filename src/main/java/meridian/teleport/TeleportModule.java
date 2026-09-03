package meridian.teleport;

import java.util.Optional;
import javax.swing.SwingUtilities;
import meridian.api.module.ModuleContext;
import meridian.api.module.ProxyModule;
import meridian.api.packet.Direction;
import meridian.api.packet.HandlerPosition;
import meridian.api.settings.SettingBinding;
import meridian.api.settings.SettingsSpec;
import meridian.core.api.Block;
import meridian.core.api.Player;
import meridian.core.api.SelectionBus;
import meridian.core.api.Vec3;
import meridian.core.api.World;
import org.slf4j.Logger;

/**
 * meridian-teleport — a Layer-1 module that moves the player through
 * meridian-core's {@link Player} building blocks, plus a map-teleport feature.
 *
 * <p>The teleport mechanism lives in core: {@link Player#teleport} sends the
 * client a {@code ClientTeleport} (repeated, so the client's own move cycle can't
 * override it) and pins the server at the target — a real teleport the player
 * sees, with no distance / permission gate. {@link Player#holdPosition} is the
 * server-side offset variant.
 *
 * <p>This module adds:
 * <ul>
 *   <li><b>Manual</b> — X/Y/Z fields + a Teleport button.</li>
 *   <li><b>A {@link meridian.core.api.Teleport} service</b> — the same teleport, offered to
 *       other modules: they name a place, this works out the height and goes.</li>
 *   <li><b>Map teleport</b> — rewrites the S2C {@code UpdateWorldMapSettings} so
 *       the in-game map shows its teleport UI ({@link WorldMapSettingsRewriter}),
 *       and intercepts the C2S {@code TeleportToWorldMapPosition}
 *       ({@link MapTeleportInterceptor}) to drive the cheat teleport from the
 *       picked coordinates — dropping the original so the server's
 *       permission-gated handler never rejects it.</li>
 * </ul>
 */
public class TeleportModule implements ProxyModule {

    /** Y-column scan range when resolving a map coordinate's surface height. */
    private static final int SCAN_TOP = 400;
    private static final int SCAN_BOTTOM = -64;

    private Logger log;
    private World world;
    private volatile boolean mapTeleport = true;

    // Live-mirrored from the X/Y/Z text fields.
    private volatile String xText = "";
    private volatile String yText = "";
    private volatile String zText = "";

    // Two-way bindings so "Fill" and the SelectionBus listener can push values
    // into the rendered X/Y/Z fields.
    private final SettingBinding<String> xBinding = new SettingBinding<>();
    private final SettingBinding<String> yBinding = new SettingBinding<>();
    private final SettingBinding<String> zBinding = new SettingBinding<>();

    private volatile String status = "Idle.";

    @Override
    public void onEnable(ModuleContext ctx) {
        this.log = ctx.getLogger();
        this.world = ctx.services().require(World.class);
        // Offered to anything that has a place but no way to get there - the world map's
        // right-click, for one. The height at a column is this module's business, not theirs.
        ctx.services().provide(meridian.core.api.Teleport.class, new Service());
        SelectionBus selectionBus = ctx.services().get(SelectionBus.class).orElse(null);

        // Map teleport: show the map's teleport UI (S2C rewrite) and intercept
        // the client's coordinate-teleport request (C2S) to drive the cheat.
        // NORMAL position — both handlers mutate / drop, so not MONITOR.
        ctx.registerHandler(Direction.S2C, HandlerPosition.NORMAL,
                (direction, session) -> new WorldMapSettingsRewriter(this));
        ctx.registerHandler(Direction.C2S, HandlerPosition.NORMAL,
                (direction, session) -> new MapTeleportInterceptor(this));

        // X/Y/Z are a per-session target — not worth persisting a random triple.
        ctx.registerSettings(SettingsSpec.builder()
                .string("x", "X", "", v -> xText = v == null ? "" : v, xBinding)
                .string("y", "Y", "", v -> yText = v == null ? "" : v, yBinding)
                .string("z", "Z", "", v -> zText = v == null ? "" : v, zBinding)
                .button("Fill from current position", this::fillFromCurrent)
                .button("Teleport", this::teleportTo)
                .bool("mapTeleport", "Map teleport (show UI + intercept)", true,
                        v -> mapTeleport = v)
                .bool("hold", "Server-side offset (client stays put)", false, this::setHold)
                .liveText("Status", () -> status)
                .build());

        // Cross-module fill: ESP's "nearest block" click (or any SelectionBus
        // publisher) snaps the X/Y/Z target. Soft — works without the bus too.
        if (selectionBus != null) {
            selectionBus.lastBlock().ifPresent(p -> applyXyz(p.x(), p.y(), p.z()));
            selectionBus.onBlockSelected(p ->
                    SwingUtilities.invokeLater(() -> applyXyz(p.x(), p.y(), p.z())));
        }

        log.info("meridian-teleport enabled — Player.teleport + map-teleport intercept");
    }

    @Override
    public void onDisable() {
        world.player().ifPresent(Player::clearHold);
    }

    // ------------------------------------------------------------------
    // Map teleport — called by the handlers (Netty event loop)
    // ------------------------------------------------------------------

    boolean mapTeleportEnabled() {
        return mapTeleport;
    }

    /**
     * Drives the cheat teleport from a map-picked coordinate. {@code worldX} /
     * {@code worldZ} come straight from {@code TeleportToWorldMapPosition}; the
     * height is the surface at that column (mirroring the server's own
     * {@code height + 2}) when the chunk is loaded, else the player's current Y.
     */
    void onMapTeleport(int worldX, int worldZ) {
        Optional<Player> maybe = world.player();
        if (maybe.isEmpty()) {
            log.warn("teleport: map teleport but no session");
            return;
        }
        Player player = maybe.get();
        double y = resolveY(worldX, worldZ, player);
        player.teleport(new Vec3(worldX, y, worldZ));
        status = String.format("Map teleport → (%d, %.0f, %d)", worldX, y, worldZ);
        log.info("teleport: map teleport -> ({}, {}, {})", worldX, y, worldZ);
    }

    /**
     * What other modules see of this one: somewhere to go, and the height worked out here.
     */
    private final class Service implements meridian.core.api.Teleport {

        @Override
        public void toColumn(int blockX, int blockZ) {
            onMapTeleport(blockX, blockZ);
        }

        @Override
        public void to(Vec3 where) {
            Optional<Player> maybe = world.player();
            if (maybe.isEmpty()) {
                log.warn("teleport: asked to go to {} but there is no session", where);
                return;
            }
            maybe.get().teleport(where);
            status = String.format("Teleport → (%.0f, %.0f, %.0f)",
                    where.x(), where.y(), where.z());
        }
    }

    /** Highest solid block at the column + 1, or the player's current Y if unloaded. */
    private double resolveY(int x, int z, Player player) {
        for (int y = SCAN_TOP; y >= SCAN_BOTTOM; y--) {
            Block b = world.blockAt(x, y, z);
            if (b != null && !b.isAir()) {
                return y + 1;
            }
        }
        Vec3 pos = player.position();
        if (pos != null) {
            return pos.y();
        }
        return 128;   // last-resort default
    }

    // ------------------------------------------------------------------
    // UI actions (EDT)
    // ------------------------------------------------------------------

    private void fillFromCurrent() {
        Optional<Player> p = world.player();
        if (p.isEmpty() || p.get().position() == null) {
            status = "No position yet — join a world and move once.";
            log.warn("teleport: no player position yet");
            return;
        }
        Vec3 pos = p.get().position();
        applyXyz(pos.x(), pos.y(), pos.z());
    }

    private void teleportTo() {
        Player player = player();
        if (player == null) {
            return;
        }
        Vec3 t = parseTarget();
        if (t == null) {
            return;
        }
        player.teleport(t);
        status = String.format("Teleported → (%.2f, %.2f, %.2f)", t.x(), t.y(), t.z());
        log.info("teleport: -> ({}, {}, {})", t.x(), t.y(), t.z());
    }

    private void setHold(boolean on) {
        Player player = player();
        if (player == null) {
            return;
        }
        if (on) {
            Vec3 t = parseTarget();
            if (t == null) {
                return;
            }
            player.holdPosition(t);
            status = String.format("Offset ON → server sees (%.2f, %.2f, %.2f)",
                    t.x(), t.y(), t.z());
            log.info("teleport: server-side offset ON -> ({}, {}, {})", t.x(), t.y(), t.z());
        } else {
            player.clearHold();
            status = "Offset OFF — real position reported again.";
            log.info("teleport: server-side offset OFF");
        }
    }

    private Player player() {
        Optional<Player> p = world.player();
        if (p.isEmpty()) {
            status = "No session — join a world first.";
            log.warn("teleport: no session yet");
            return null;
        }
        return p.get();
    }

    private void applyXyz(double x, double y, double z) {
        xBinding.set(trim(x));
        yBinding.set(trim(y));
        zBinding.set(trim(z));
    }

    /** Whole numbers render without a trailing ".0"; fractions keep two places. */
    private static String trim(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format("%.2f", v);
    }

    private Vec3 parseTarget() {
        try {
            return new Vec3(
                    Double.parseDouble(xText.trim()),
                    Double.parseDouble(yText.trim()),
                    Double.parseDouble(zText.trim()));
        } catch (NumberFormatException ex) {
            status = "Enter numeric X / Y / Z first.";
            log.warn("teleport: enter numeric X / Y / Z first");
            return null;
        }
    }
}
