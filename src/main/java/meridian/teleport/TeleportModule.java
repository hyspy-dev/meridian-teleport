package meridian.teleport;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.SwingUtilities;
import meridian.api.module.ModuleContext;
import meridian.api.module.ProxyModule;
import meridian.api.module.Scheduler;
import meridian.api.settings.SettingBinding;
import meridian.api.settings.SettingsSpec;
import meridian.core.api.Block;
import meridian.core.api.Player;
import meridian.core.api.SelectionBus;
import meridian.core.api.Vec3;
import meridian.core.api.World;
import meridian.core.api.WorldMapView;
import org.slf4j.Logger;

/**
 * meridian-teleport — a Layer-2 module that moves the player through
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
 *   <li><b>Map teleport</b> — asks {@link WorldMapView} to show the in-game map's
 *       teleport control and to hand over the requests made with it. Core owns those two
 *       packets; this module only says what it wants of them, which is what keeps it clear
 *       of any one protocol build.</li>
 *   <li><b>Chain teleport</b> — a distant target reached in hops, for a server that caps
 *       how far one teleport may go, optionally settling onto the ground where it arrives.</li>
 *   <li><b>Recent jumps</b> — the last few targets, click one to go back. What it is for is a
 *       client and a server that have stopped agreeing on where the player is: the last target
 *       is the position that was meant, and jumping to it again settles that.</li>
 * </ul>
 */
public class TeleportModule implements ProxyModule {

    /** Y-column scan range when resolving a map coordinate's surface height. */
    private static final int SCAN_TOP = 400;
    private static final int SCAN_BOTTOM = -64;

    /** Default hop length, in blocks, and the pause before each hop. */
    private static final int DEFAULT_HOP_BLOCKS = 48;
    private static final int DEFAULT_HOP_DELAY_MS = 250;

    /** How long to wait for a hop's chunk to arrive before going on without it. */
    private static final int SETTLE_TRIES = 20;
    private static final Duration SETTLE_POLL = Duration.ofMillis(100);

    /** The height a chain glides at when its Y is frozen - well above any terrain. */
    private static final int FREEZE_Y = 250;

    /** How far out the safe-spot drop looks for a column to land in. */
    private static final int SAFE_SEARCH_RADIUS = 8;

    /** How many past targets the history keeps: the older ones are no use for getting back. */
    private static final int HISTORY_MAX = 10;

    private Logger log;
    private World world;
    private WorldMapView mapView;
    private Scheduler scheduler;
    private volatile boolean mapTeleport = true;

    // Chain teleport.
    private volatile boolean chain;
    private volatile int hopBlocks = DEFAULT_HOP_BLOCKS;
    private volatile int hopDelayMs = DEFAULT_HOP_DELAY_MS;
    private volatile boolean settleOnGround = true;
    /** Hold the chain's height at {@link #FREEZE_Y} while it crosses, so it glides over terrain. */
    private volatile boolean freezeY;
    /**
     * Which chain is the current one.
     *
     * <p>Every step checks it before doing anything, so a new teleport - from the map, from the
     * button, from another module - simply abandons whatever was still hopping. There is one
     * player, and two chains pulling them in different directions is not a thing to allow.
     */
    private final AtomicLong chainRun = new AtomicLong();

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

    // Where we have been — the last few teleport targets, newest first.
    private final Deque<Vec3> history = new ArrayDeque<>();
    /** The rendered rows, kept ready so the UI's poll reads a field instead of the deque. */
    private volatile List<String> historyRows = List.of();

    @Override
    public void onEnable(ModuleContext ctx) {
        this.log = ctx.getLogger();
        this.world = ctx.services().require(World.class);
        this.scheduler = ctx.scheduler();
        // Offered to anything that has a place but no way to get there - the world map's
        // right-click, for one. The height at a column is this module's business, not theirs.
        ctx.services().provide(meridian.core.api.Teleport.class, new Service());
        SelectionBus selectionBus = ctx.services().get(SelectionBus.class).orElse(null);

        // Map teleport, through core rather than through the wire. Core owns the vanilla map -
        // the settings that decide whether its teleport control is drawn, and the request the
        // client sends when it is used - so this module says what it wants and stays clear of
        // any one protocol build.
        this.mapView = ctx.services().require(WorldMapView.class);
        applyMapTeleport(mapTeleport);

        ctx.registerSettings(SettingsSpec.builder()
                .string("x", "X", "", v -> xText = v == null ? "" : v, xBinding)
                .string("y", "Y", "", v -> yText = v == null ? "" : v, yBinding)
                .string("z", "Z", "", v -> zText = v == null ? "" : v, zBinding)
                .button("Fill from current position", this::fillFromCurrent)
                .button("Teleport", this::teleportTo)
                .bool("mapTeleport", "Map teleport (show UI + intercept)", true,
                        this::applyMapTeleport)
                // Safe teleport rides above the offset: it applies to every teleport, single or
                // chained - land on the surface once the chunk is there rather than in the air.
                .bool("safeTeleport", "Safe teleport (wait for the chunk, land on the surface)",
                        true, v -> settleOnGround = v)
                .bool("hold", "Server-side offset (client stays put)", false, this::setHold)
                .section("Chain teleport", SettingsSpec.builder()
                        .bool("chain", "Chain teleport (hop to distant targets)", false,
                                v -> chain = v)
                        .int_("hopBlocks", "Max distance per hop (blocks)", 1, 8192,
                                DEFAULT_HOP_BLOCKS, v -> hopBlocks = Math.max(1, v))
                        .int_("hopDelayMs", "Delay before each hop (ms)", 0, 60000,
                                DEFAULT_HOP_DELAY_MS, v -> hopDelayMs = Math.max(0, v))
                        .bool("freezeY",
                                "Freeze Y at " + FREEZE_Y + " while chaining (glide over terrain)",
                                false, v -> freezeY = v)
                        .button("Abort chain, drop to nearest safe spot", this::dropToSafe)
                        .build())
                .liveList("Recent jumps (newest first, click to go back)",
                        () -> historyRows, this::goBack)
                .liveText("Status", () -> status)
                .persistent("x", "y", "z", "mapTeleport", "safeTeleport",
                        "chain", "hopBlocks", "hopDelayMs", "freezeY")
                .build());

        // Cross-module fill: ESP's "nearest block" click (or any SelectionBus
        // publisher) snaps the X/Y/Z target. Soft — works without the bus too.
        if (selectionBus != null) {
            selectionBus.lastBlock().ifPresent(p -> applyXyz(p.x(), p.y(), p.z()));
            selectionBus.onBlockSelected(p ->
                    SwingUtilities.invokeLater(() -> applyXyz(p.x(), p.y(), p.z())));
        }

        log.info("meridian-teleport enabled — Player.teleport + map teleport via core");
    }

    @Override
    public void onDisable() {
        chainRun.incrementAndGet();         // whatever was hopping stops here
        world.player().ifPresent(Player::clearHold);
        if (mapView != null) {
            mapView.onTeleportRequest(null);    // the control goes with the module
        }
    }

    /**
     * Turns the map's teleport on or off.
     *
     * <p>Taking the requests is what shows the control, so one call does both - and letting go of
     * them takes it away again, which matters: a control left on screen with nobody behind it
     * sends the player's next click to a server that answers it with a disconnect.
     */
    private void applyMapTeleport(boolean on) {
        mapTeleport = on;
        if (mapView == null) {
            return;                             // set before the module was given the service
        }
        mapView.onTeleportRequest(on ? this::onMapTeleport : null);
    }

    // ------------------------------------------------------------------
    // Map teleport — called by core, on its own thread
    // ------------------------------------------------------------------

    /**
     * Drives the cheat teleport from a map-picked coordinate. The height is the surface at that
     * column (mirroring the server's own {@code height + 2}) when the chunk is loaded, else the
     * player's current Y.
     *
     * @return whether it was carried out, and so must not be passed to the server
     */
    private boolean onMapTeleport(int worldX, int worldZ) {
        Optional<Player> maybe = world.player();
        if (maybe.isEmpty()) {
            log.warn("teleport: map teleport but no session");
            return false;
        }
        Player player = maybe.get();
        double y = resolveY(worldX, worldZ, player);
        log.info("teleport: map teleport -> ({}, {}, {})", worldX, y, worldZ);
        goTo(player, new Vec3(worldX, y, worldZ), "Map teleport");
        return true;
    }

    // ------------------------------------------------------------------
    // Going there — in one jump, or in as many as the server will allow
    // ------------------------------------------------------------------

    /**
     * Takes the player to a point, by whatever route the settings ask for.
     *
     * <p>Everything that moves the player goes through here: the button, the map, and the
     * {@link meridian.core.api.Teleport} service other modules use. A server that caps how far
     * one teleport may go caps all of them equally, so there is no sense in one of them knowing
     * about hops and the others not.
     */
    private void goTo(Player player, Vec3 target, String what) {
        goTo(player, target, what, true);
    }

    /**
     * As above, with a say in whether the target joins the history.
     *
     * @param keep {@code false} for a jump made <em>from</em> the history: a trip back is not a
     *             place we have newly been, and letting it write would push the older entries out
     *             with copies of themselves
     */
    private void goTo(Player player, Vec3 target, String what, boolean keep) {
        if (keep) {
            remember(target);
        }
        long run = chainRun.incrementAndGet();
        Vec3 from = player.position();
        if (!chain || from == null) {
            player.teleport(target);
            status = String.format("%s → (%.0f, %.0f, %.0f)",
                    what, target.x(), target.y(), target.z());
            if (settleOnGround) {
                settle(run, target, () -> { });
            }
            return;
        }
        List<Vec3> hops = hopsFrom(from, target);
        log.info("teleport: {} in {} hop(s) of at most {} block(s)", what, hops.size(), hopBlocks);
        hop(run, hops, 0, what);
    }

    /**
     * The straight line from here to there, cut into pieces no longer than one hop.
     *
     * <p>Measured in three dimensions, because the limit a server puts on a teleport is a
     * distance and not a ground distance. The last piece is the target itself rather than
     * something rounded near it.
     */
    private List<Vec3> hopsFrom(Vec3 from, Vec3 to) {
        List<Vec3> hops = new ArrayList<>();
        if (!freezeY) {
            appendHops(hops, from, to);     // a straight line, cut to the hop length
            return hops;
        }
        // Freeze the height: climb to the glide height above where we stand, cross at that height,
        // then drop to the target. Each leg is still cut to the hop length, so the climb and the
        // drop respect the server's distance cap the same as the crossing - a single leap from the
        // ground up to two hundred and fifty would be past it.
        Vec3 up = new Vec3(from.x(), FREEZE_Y, from.z());
        Vec3 over = new Vec3(to.x(), FREEZE_Y, to.z());
        appendHops(hops, from, up);
        appendHops(hops, up, over);
        appendHops(hops, over, to);
        if (hops.isEmpty()) {
            hops.add(to);                   // from was already the target: nothing to cut
        }
        return hops;
    }

    /** Adds the points from {@code from} to {@code to}, excluding {@code from}, including {@code to}. */
    private void appendHops(List<Vec3> hops, Vec3 from, Vec3 to) {
        double dx = to.x() - from.x();
        double dy = to.y() - from.y();
        double dz = to.z() - from.z();
        double span = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (span < 1e-6) {
            return;                         // the two ends are the same point
        }
        int count = Math.max(1, (int) Math.ceil(span / Math.max(1, hopBlocks)));
        for (int i = 1; i <= count; i++) {
            double f = (double) i / count;
            hops.add(new Vec3(from.x() + dx * f, from.y() + dy * f, from.z() + dz * f));
        }
    }

    /**
     * Stops the chain and drops the player onto the nearest place they can stand.
     *
     * <p>For getting unstuck: a chain frozen at height, or one that left the player in the air or
     * inside a hill. The current column is tried first - that is a straight drop down - and only
     * if there is no ground there does it spread outwards to the nearest column that has some.
     */
    private void dropToSafe() {
        chainRun.incrementAndGet();         // whatever was hopping stops here
        Optional<Player> maybe = world.player();
        if (maybe.isEmpty()) {
            status = "No session - join a world first.";
            return;
        }
        Player player = maybe.get();
        Vec3 pos = player.position();
        if (pos == null) {
            status = "No position yet.";
            return;
        }
        int px = (int) Math.floor(pos.x());
        int py = (int) Math.floor(pos.y());
        int pz = (int) Math.floor(pos.z());
        Optional<Vec3> spot = nearestSafeSpot(px, py, pz);
        if (spot.isEmpty()) {
            status = "No safe spot within " + SAFE_SEARCH_RADIUS + " blocks - nothing loaded there.";
            log.warn("teleport: no safe spot near ({}, {}, {})", px, py, pz);
            return;
        }
        Vec3 to = spot.get();
        player.teleport(to);
        status = String.format("Dropped to safe spot (%.0f, %.0f, %.0f).", to.x(), to.y(), to.z());
        log.info("teleport: dropped to safe spot ({}, {}, {})", to.x(), to.y(), to.z());
    }

    /**
     * The nearest column to stand in, searched in rings outwards.
     *
     * <p>A spot is ground with two blocks of clear air on top and no lava beneath the feet. The
     * player's own column is ring zero, so a plain drop down wins when it can; the search widens
     * only when the column under the player is empty or unloaded.
     */
    private Optional<Vec3> nearestSafeSpot(int px, int py, int pz) {
        for (int r = 0; r <= SAFE_SEARCH_RADIUS; r++) {
            Vec3 best = null;
            double bestAway = Double.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;           // only the new ring, not the ones already searched
                    }
                    int x = px + dx;
                    int z = pz + dz;
                    int y = safeStandY(x, z, py);
                    if (y == Integer.MIN_VALUE) {
                        continue;
                    }
                    double away = dx * dx + dz * dz + (y - py) * (double) (y - py);
                    if (away < bestAway) {
                        bestAway = away;
                        best = new Vec3(x + 0.5, y, z + 0.5);
                    }
                }
            }
            if (best != null) {
                return Optional.of(best);
            }
        }
        return Optional.empty();
    }

    /**
     * The height to stand at in a column: on top of the nearest solid block that has room above it,
     * looked for below the player first and then above.
     *
     * @return the standing Y, or {@link Integer#MIN_VALUE} when the column has no such place
     */
    private int safeStandY(int x, int z, int fromY) {
        int top = Math.min(SCAN_TOP, fromY);
        for (int y = top; y >= SCAN_BOTTOM; y--) {   // straight down from the player: a drop
            if (standable(x, y, z)) {
                return y + 1;
            }
        }
        for (int y = fromY + 1; y <= SCAN_TOP; y++) { // nothing below: look up instead
            if (standable(x, y, z)) {
                return y + 1;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** Whether {@code (x,y,z)} is solid ground, not lava, with two clear blocks above it. */
    private boolean standable(int x, int y, int z) {
        Block ground = world.blockAt(x, y, z);
        if (ground == null || ground.type() == null || !ground.type().isSolid()) {
            return false;                   // must be solid ground - water and lava are not
        }
        return clear(x, y + 1, z) && clear(x, y + 2, z);
    }

    /** Whether a block is out of the way - air (which has no type) or anything not solid. */
    private boolean clear(int x, int y, int z) {
        Block b = world.blockAt(x, y, z);
        boolean solid = b != null && b.type() != null && b.type().isSolid();
        return !solid && world.fluidAt(x, y, z) == null;
    }


    /** One hop, then the next - each on the scheduler, so nothing here blocks a caller. */
    private void hop(long run, List<Vec3> hops, int index, String what) {
        if (run != chainRun.get()) {
            return;                         // a newer teleport has taken over
        }
        if (index >= hops.size()) {
            status = String.format("%s → arrived in %d hop(s).", what, hops.size());
            return;
        }
        Optional<Player> maybe = world.player();
        if (maybe.isEmpty()) {
            status = what + " stopped — no session.";
            return;
        }
        Player player = maybe.get();
        Vec3 to = hops.get(index);
        boolean lastHop = index == hops.size() - 1;

        // Fool-proofing: never drop an intermediate hop into lava. (The last hop lands on the
        // surface through settle, which lifts clear of a pool on its own.) If the hop point is in
        // lava, hop instead to a lava-free point and carry the chain on from there; if there is no
        // such point, stop the chain where the player safely stands rather than cook them.
        if (!lastHop && inLava(to)) {
            Vec3 safe = lavaFreeAlternative(player.position(), to);
            if (safe == null) {
                chainRun.incrementAndGet();                 // panic stop - no further hops fire
                status = String.format("%s stopped: lava ahead and nowhere clear to go.", what);
                log.warn("teleport: {} stopped - hop {} was lava and no safe point near it",
                        what, index + 1);
                return;
            }
            Vec3 target = hops.get(hops.size() - 1);
            player.teleport(safe);
            status = String.format("%s: hop %d was lava, rerouted to (%.0f, %.0f, %.0f)",
                    what, index + 1, safe.x(), safe.y(), safe.z());
            log.info("teleport: {} rerouted around lava to ({}, {}, {})",
                    what, safe.x(), safe.y(), safe.z());
            // Rebuild the rest of the chain from where we actually are now.
            List<Vec3> rest = hopsFrom(safe, target);
            scheduler.schedule(() -> hop(run, rest, 0, what), Duration.ofMillis(hopDelayMs));
            return;
        }

        player.teleport(to);
        status = String.format("%s: hop %d/%d → (%.0f, %.0f, %.0f)",
                what, index + 1, hops.size(), to.x(), to.y(), to.z());
        Runnable next = () -> scheduler.schedule(() -> hop(run, hops, index + 1, what),
                Duration.ofMillis(hopDelayMs));
        // Only where the player is actually going to stay. The hops in between are a way of
        // getting past a distance limit, not places anybody stands: waiting for each one's chunk
        // would add seconds a hop to a journey whose whole point is to be over quickly.
        if (settleOnGround && lastHop) {
            settle(run, to, next);
        } else {
            next.run();
        }
    }

    /** Whether the two-block space a player would fill at {@code p} is lava. */
    private boolean inLava(Vec3 p) {
        int x = (int) Math.floor(p.x());
        int y = (int) Math.floor(p.y());
        int z = (int) Math.floor(p.z());
        return lavaAt(x, y, z) || lavaAt(x, y + 1, z);
    }

    private boolean lavaAt(int x, int y, int z) {
        String fluid = world.fluidAt(x, y, z);
        return fluid != null && fluid.toLowerCase(java.util.Locale.ROOT).contains("lava");
    }

    /**
     * A lava-free point to hop to instead of one that landed in lava, or {@code null} when there
     * is none.
     *
     * <p>First it rises straight up over the lava at the hop's own column - the far point kept,
     * made safe by height, which is the cheapest way across a lava sea. If the column will not
     * clear (it runs to the sky, which real lava does not), it steps back toward the player and
     * takes the nearest clear point on the way. Null when neither finds one.
     */
    private Vec3 lavaFreeAlternative(Vec3 from, Vec3 to) {
        int x = (int) Math.floor(to.x());
        int z = (int) Math.floor(to.z());
        for (int y = (int) Math.floor(to.y()); y <= SCAN_TOP; y++) {
            if (!lavaAt(x, y, z) && !lavaAt(x, y + 1, z)) {
                return new Vec3(to.x(), y, to.z());     // highest: lifted just clear of the lava
            }
        }
        double dx = from.x() - to.x();
        double dy = from.y() - to.y();
        double dz = from.z() - to.z();
        int steps = (int) Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz));
        for (int i = 1; i <= steps; i++) {             // nearest clear point back toward the player
            double f = (double) i / steps;
            Vec3 p = new Vec3(to.x() + dx * f, to.y() + dy * f, to.z() + dz * f);
            if (!inLava(p)) {
                return p;
            }
        }
        return null;                                    // no safe point: the caller panic-stops
    }

    /**
     * Waits for the ground under a hop to arrive, then stands on it.
     *
     * <p>Arriving means landing in a chunk the client has never seen: for a moment there is
     * nothing there at all, and a height guessed from the last known ground can be underneath a
     * hill or a long way above it. So the chunk is given a moment to turn up, and once it has,
     * the height is worked out properly and the player is put on the surface. If it never turns
     * up, this gives up rather than waiting - being stuck mid-air is better than being stuck
     * entirely.
     */
    private void settle(long run, Vec3 at, Runnable then) {
        settle(run, at, then, 0);
    }

    private void settle(long run, Vec3 at, Runnable then, int tries) {
        if (run != chainRun.get()) {
            return;
        }
        int x = (int) Math.floor(at.x());
        int z = (int) Math.floor(at.z());
        int surface = surfaceOf(x, z);
        if (surface == Integer.MIN_VALUE) {
            // No block loaded in the column yet - the chunk that the teleport asked the server for
            // has not arrived. Wait and look again; the player is already at the target, so the
            // server is streaming it. Only once the ground is actually there do we drop onto it.
            if (tries >= SETTLE_TRIES) {
                log.info("teleport: no chunk at ({}, {}) after {} ms - left in place",
                        x, z, SETTLE_TRIES * SETTLE_POLL.toMillis());
                then.run();
                return;
            }
            scheduler.schedule(() -> settle(run, at, then, tries + 1), SETTLE_POLL);
            return;
        }
        world.player().ifPresent(player -> {
            if (Math.abs(surface - at.y()) > 0.5) {
                player.teleport(new Vec3(at.x(), surface, at.z()));
            }
        });
        then.run();
    }

    /**
     * The height to stand at in a column, or {@link Integer#MIN_VALUE} when there is no place we
     * can be sure of.
     *
     * <p>The first solid block from the top is the surface - if the column is loaded there. What
     * makes it a place to stand rather than a guess is the two blocks above it: they have to be
     * loaded and open. Loaded, because a block with no type at all is a chunk that has not arrived,
     * not open air - reading it as open is how a landing happened before the surface was there and
     * ended up in whatever was loaded deeper. Open, so the player is not dropped inside a block.
     *
     * <p>It stops at that first solid either way. A solid with unconfirmed air above means the
     * chunk is still loading, and the answer is to wait, not to go looking deeper and find a cave.
     */
    /**
     * The height to stand on in a column, or {@link Integer#MIN_VALUE} when none is loaded.
     *
     * <p>The highest block that is not air is the surface, and everything above it is air by that
     * very definition - so standing one above it is standing in the open, never inside a block.
     *
     * <p>This is the loaded check and the surface finder in one. Air and an unloaded chunk both
     * read the same here (no block), so the only thing that answers is a real block turning up:
     * when one does, the chunk has arrived and this is its top. Until then it says "nothing yet",
     * and the caller waits.
     */
    private int surfaceOf(int x, int z) {
        for (int y = SCAN_TOP; y >= SCAN_BOTTOM; y--) {
            Block b = world.blockAt(x, y, z);
            boolean solid = b != null && !b.isAir();
            boolean fluid = world.fluidAt(x, y, z) != null;
            if (solid || fluid) {
                // On solid ground, stand one above it. On a pool - water or lava, which live in
                // the fluid layer and read as air in the block layer - two above, so the drop
                // clears the surface rather than landing the player in it.
                return fluid ? y + 2 : y + 1;
            }
        }
        return Integer.MIN_VALUE;
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
            goTo(maybe.get(), where, "Teleport");
        }
    }

    /** The confirmed surface at the column, or the player's current Y when it cannot be confirmed. */
    private double resolveY(int x, int z, Player player) {
        int surface = surfaceOf(x, z);
        if (surface != Integer.MIN_VALUE) {
            return surface;
        }
        Vec3 pos = player.position();
        if (pos != null) {
            return pos.y();
        }
        return 128;   // last-resort default
    }

    // ------------------------------------------------------------------
    // Where we have been
    // ------------------------------------------------------------------

    /**
     * Puts a target at the head of the history, keeping the last {@link #HISTORY_MAX}.
     *
     * <p>This is the place to come back to when the client and the server stop agreeing on where
     * the player is: the position they were last sent to is the one that was meant, and a jump
     * back to it settles the argument.
     *
     * <p>Called from whichever thread asked for the teleport - the UI's, core's map thread, the
     * scheduler's - so the deque is held while it is read or written, and the rows the UI polls
     * are a finished list published under the same lock.
     */
    private void remember(Vec3 target) {
        synchronized (history) {
            Vec3 newest = history.peekFirst();
            if (newest != null && same(newest, target)) {
                return;                     // the same place twice running is one entry, not two
            }
            history.addFirst(target);
            while (history.size() > HISTORY_MAX) {
                history.removeLast();
            }
            List<String> rows = new ArrayList<>(history.size());
            for (Vec3 p : history) {
                rows.add(String.format("(%s, %s, %s)", trim(p.x()), trim(p.y()), trim(p.z())));
            }
            historyRows = List.copyOf(rows);
        }
    }

    /** History click - back to that target, without the trip itself joining the list. */
    private void goBack(int rowIndex) {
        Vec3 to;
        synchronized (history) {
            if (rowIndex < 0 || rowIndex >= history.size()) {
                return;                     // the list moved under the click
            }
            to = List.copyOf(history).get(rowIndex);
        }
        Player player = player();
        if (player == null) {
            return;
        }
        log.info("teleport: back to ({}, {}, {})", to.x(), to.y(), to.z());
        goTo(player, to, "Back", false);
    }

    /** Whether two targets are the same place, to the block. */
    private static boolean same(Vec3 a, Vec3 b) {
        return Math.abs(a.x() - b.x()) < 0.01
                && Math.abs(a.y() - b.y()) < 0.01
                && Math.abs(a.z() - b.z()) < 0.01;
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
        log.info("teleport: -> ({}, {}, {})", t.x(), t.y(), t.z());
        goTo(player, t, "Teleport");
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
