package meridian.teleport;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.SwingUtilities;
import meridian.api.module.ModuleContext;
import meridian.api.module.ProxyModule;
import meridian.api.module.Scheduler;
import meridian.api.settings.SettingBinding;
import meridian.api.settings.SettingsSpec;
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
 * server-side offset variant. Where to stand when it is there is core's too:
 * {@link World#surfaceAt the surface} of a column, {@link World#safeSpotNear a safe spot}
 * near a point, {@link World#hurtsAt what hurts}.
 *
 * <p>Two modes:
 * <ul>
 *   <li><b>tp</b> — to the point; with landing on, once the ground there has arrived, onto the
 *       surface of its column.</li>
 *   <li><b>safe tp</b> — to the point, then onto the safe spot nearest it: solid ground with room
 *       for the body, out of water and lava.</li>
 * </ul>
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
 *       how far one teleport may go, landing where it arrives.</li>
 *   <li><b>Recent jumps</b> — the last few targets, click one to go back. What it is for is a
 *       client and a server that have stopped agreeing on where the player is: the last target
 *       is the position that was meant, and jumping to it again settles that.</li>
 * </ul>
 */
public class TeleportModule implements ProxyModule {

    private static final String USAGE = "tp <x y z> | safe <x y z> | column <x z> | drop";

    /** How high a chain hop that would land in lava may be lifted to clear it. */
    private static final int LAVA_RISE_TOP = 400;

    /** Default hop length, in blocks, and the pause before each hop. */
    private static final int DEFAULT_HOP_BLOCKS = 48;
    private static final int DEFAULT_HOP_DELAY_MS = 250;

    /** How long to wait for the ground where the player arrived before going on without it. */
    private static final Duration LAND_WAIT = Duration.ofSeconds(2);

    /** The height a chain glides at when its Y is frozen - well above any terrain. */
    private static final int FREEZE_Y = 250;

    /** How far out the safe-spot search looks for a column to land in. */
    private static final int SAFE_SEARCH_RADIUS = 8;

    /** How many past targets the history keeps: the older ones are no use for getting back. */
    private static final int HISTORY_MAX = 10;

    private Logger log;
    private World world;
    private WorldMapView mapView;
    private Scheduler scheduler;
    private volatile boolean mapTeleport = true;

    /** Land when the player arrives: wait for the ground, stand on it. Off: the coordinates as given. */
    private volatile boolean land = true;
    /** The mode: {@code safe tp} (the nearest safe spot) rather than {@code tp} (the surface). */
    private volatile boolean safeTp;

    // Chain teleport.
    private volatile boolean chain;
    private volatile int hopBlocks = DEFAULT_HOP_BLOCKS;
    private volatile int hopDelayMs = DEFAULT_HOP_DELAY_MS;
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
    /** Where the player was last sent - the landing, once a teleport is over. */
    private volatile Vec3 lastSent;

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
                // Both ride above the offset: they apply to every teleport, single or chained.
                .bool("land", "Land on arrival (wait for the chunk, stand on the surface)",
                        true, v -> land = v)
                .bool("safeTp", "Safe tp (stand on the nearest safe spot instead: solid ground, "
                        + "room, no water or lava)", false, v -> safeTp = v)
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
                .persistent("x", "y", "z", "mapTeleport", "land", "safeTp",
                        "chain", "hopBlocks", "hopDelayMs", "freezeY")
                .build());

        // Cross-module fill: ESP's "nearest block" click (or any SelectionBus
        // publisher) snaps the X/Y/Z target. Soft — works without the bus too.
        if (selectionBus != null) {
            selectionBus.lastBlock().ifPresent(p -> applyXyz(p.x(), p.y(), p.z()));
            selectionBus.onBlockSelected(p ->
                    SwingUtilities.invokeLater(() -> applyXyz(p.x(), p.y(), p.z())));
        }

        // The same teleports on the proxy's command channel - teleport <conn> ... - answered once
        // the player has landed.
        try {
            ctx.registerCommand("teleport", USAGE, (args, json) -> command(args));
        } catch (NoSuchMethodError olderProxy) {
            // no command channel there
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
     * column when core knows it, else the player's current Y.
     *
     * @return whether it was carried out, and so must not be passed to the server
     */
    private boolean onMapTeleport(int worldX, int worldZ) {
        return toColumn(worldX, worldZ, safeTp) != null;
    }

    /** The map's teleport, in the given mode: off when there is no player. */
    private CompletableFuture<Void> toColumn(int worldX, int worldZ, boolean safe) {
        Optional<Player> maybe = world.player();
        if (maybe.isEmpty()) {
            log.warn("teleport: map teleport but no session");
            return null;
        }
        Player player = maybe.get();
        double y = resolveY(worldX, worldZ, player);
        log.info("teleport: map teleport -> ({}, {}, {})", worldX, y, worldZ);
        return goTo(player, new Vec3(worldX, y, worldZ), "Map teleport", true, safe);
    }

    // ------------------------------------------------------------------
    // Going there — in one jump, or in as many as the server will allow
    // ------------------------------------------------------------------

    /**
     * Takes the player to a point, by whatever route the settings ask for, and lands as the mode
     * says.
     *
     * <p>Everything that moves the player goes through here: the button, the map, and the
     * {@link meridian.core.api.Teleport} service other modules use. A server that caps how far
     * one teleport may go caps all of them equally, so there is no sense in one of them knowing
     * about hops and the others not.
     *
     * @param keep {@code false} for a jump made <em>from</em> the history: a trip back is not a
     *             place we have newly been, and letting it write would push the older entries out
     *             with copies of themselves
     * @param safe {@code safe tp}: onto the safe spot nearest the target once there
     * @return done when the player has landed, or the trip was given up or overtaken by another
     */
    private CompletableFuture<Void> goTo(Player player, Vec3 target, String what, boolean keep, boolean safe) {
        if (keep) {
            remember(target);
        }
        CompletableFuture<Void> done = new CompletableFuture<>();
        long run = chainRun.incrementAndGet();
        Vec3 from = player.position();
        if (!chain || from == null) {
            send(player, target);
            status = String.format("%s → (%.0f, %.0f, %.0f)",
                    what, target.x(), target.y(), target.z());
            arrive(run, target, what, safe, () -> done.complete(null));
            return done;
        }
        List<Vec3> hops = hopsFrom(from, target);
        log.info("teleport: {} in {} hop(s) of at most {} block(s)", what, hops.size(), hopBlocks);
        hop(run, hops, 0, what, safe, done);
        return done;
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
        Optional<Vec3> spot = world.safeSpotNear(px, py, pz, SAFE_SEARCH_RADIUS);
        if (spot.isEmpty()) {
            status = "No safe spot within " + SAFE_SEARCH_RADIUS + " blocks - nothing loaded there.";
            log.warn("teleport: no safe spot near ({}, {}, {})", px, py, pz);
            return;
        }
        Vec3 to = spot.get();
        send(player, to);
        status = String.format("Dropped to safe spot (%.0f, %.0f, %.0f).", to.x(), to.y(), to.z());
        log.info("teleport: dropped to safe spot ({}, {}, {})", to.x(), to.y(), to.z());
    }

    /** One hop, then the next - each on the scheduler, so nothing here blocks a caller. */
    private void hop(long run, List<Vec3> hops, int index, String what, boolean safe, CompletableFuture<Void> done) {
        if (run != chainRun.get()) {
            done.complete(null);            // a newer teleport has taken over
            return;
        }
        if (index >= hops.size()) {
            status = String.format("%s → arrived in %d hop(s).", what, hops.size());
            done.complete(null);
            return;
        }
        Optional<Player> maybe = world.player();
        if (maybe.isEmpty()) {
            status = what + " stopped — no session.";
            done.complete(null);
            return;
        }
        Player player = maybe.get();
        Vec3 to = hops.get(index);
        boolean lastHop = index == hops.size() - 1;

        // Fool-proofing: never drop an intermediate hop into lava. (The last hop lands as the mode
        // says, which lifts clear of a pool on its own.) If the hop point is in lava, hop instead to
        // a lava-free point and carry the chain on from there; if there is no such point, stop the
        // chain where the player safely stands rather than cook them.
        if (!lastHop && world.hurtsAt(to)) {
            Vec3 clear = lavaFreeAlternative(player.position(), to);
            if (clear == null) {
                chainRun.incrementAndGet();                 // panic stop - no further hops fire
                status = String.format("%s stopped: lava ahead and nowhere clear to go.", what);
                log.warn("teleport: {} stopped - hop {} was lava and no safe point near it",
                        what, index + 1);
                done.complete(null);
                return;
            }
            Vec3 target = hops.get(hops.size() - 1);
            send(player, clear);
            status = String.format("%s: hop %d was lava, rerouted to (%.0f, %.0f, %.0f)",
                    what, index + 1, clear.x(), clear.y(), clear.z());
            log.info("teleport: {} rerouted around lava to ({}, {}, {})",
                    what, clear.x(), clear.y(), clear.z());
            // Rebuild the rest of the chain from where we actually are now.
            List<Vec3> rest = hopsFrom(clear, target);
            scheduler.schedule(() -> hop(run, rest, 0, what, safe, done), Duration.ofMillis(hopDelayMs));
            return;
        }

        send(player, to);
        status = String.format("%s: hop %d/%d → (%.0f, %.0f, %.0f)",
                what, index + 1, hops.size(), to.x(), to.y(), to.z());
        Runnable next = () -> scheduler.schedule(() -> hop(run, hops, index + 1, what, safe, done),
                Duration.ofMillis(hopDelayMs));
        // Only where the player is actually going to stay. The hops in between are a way of
        // getting past a distance limit, not places anybody stands: waiting for each one's chunk
        // would add seconds a hop to a journey whose whole point is to be over quickly.
        if (lastHop) {
            arrive(run, to, what, safe, next);
        } else {
            next.run();
        }
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
        for (int y = (int) Math.floor(to.y()); y <= LAVA_RISE_TOP; y++) {
            Vec3 lifted = new Vec3(to.x(), y, to.z());
            if (!world.hurtsAt(lifted)) {
                return lifted;                          // lowest: lifted just clear of the lava
            }
        }
        double dx = from.x() - to.x();
        double dy = from.y() - to.y();
        double dz = from.z() - to.z();
        int steps = (int) Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz));
        for (int i = 1; i <= steps; i++) {             // nearest clear point back toward the player
            double f = (double) i / steps;
            Vec3 p = new Vec3(to.x() + dx * f, to.y() + dy * f, to.z() + dz * f);
            if (!world.hurtsAt(p)) {
                return p;
            }
        }
        return null;                                    // no safe point: the caller panic-stops
    }

    /**
     * Lands where the player arrived, as the mode says, then runs {@code then}.
     *
     * <p>Arriving means landing in a chunk the client has never seen: for a moment there is
     * nothing there at all, and a height guessed from the last known ground can be underneath a
     * hill or a long way above it. So core is asked to say when the ground there is known, and
     * only then is the player put on it. If it never comes, this gives up rather than waiting -
     * being stuck mid-air is better than being stuck entirely.
     *
     * <ul>
     *   <li><b>tp</b>, landing on: onto the surface of the column.</li>
     *   <li><b>tp</b>, landing off: nothing - the coordinates as given.</li>
     *   <li><b>safe tp</b>: onto the safe spot nearest the target.</li>
     * </ul>
     */
    private void arrive(long run, Vec3 at, String what, boolean safe, Runnable then) {
        if (!safe && !land) {
            then.run();
            return;
        }
        int x = (int) Math.floor(at.x());
        int z = (int) Math.floor(at.z());
        world.awaitSurface(x, z, LAND_WAIT).whenComplete((surface, error) -> {
            try {
                if (run != chainRun.get()) {
                    return;                 // a newer teleport has taken over
                }
                if (safe) {
                    standSafe(at, what);
                } else {
                    standOnSurface(at, x, z, surface == null ? OptionalInt.empty() : surface);
                }
            } finally {
                then.run();
            }
        });
    }

    /** {@code tp}: onto the surface, when the column has told it. */
    private void standOnSurface(Vec3 at, int x, int z, OptionalInt surface) {
        if (surface.isEmpty()) {
            log.info("teleport: no ground known at ({}, {}) after {} ms - left in place",
                    x, z, LAND_WAIT.toMillis());
            return;
        }
        int y = surface.getAsInt();
        world.player().ifPresent(player -> {
            if (Math.abs(y - at.y()) > 0.5) {
                send(player, new Vec3(at.x(), y, at.z()));
            }
        });
    }

    /** {@code safe tp}: onto the safe spot nearest the target, or left there when there is none. */
    private void standSafe(Vec3 at, String what) {
        int x = (int) Math.floor(at.x());
        int y = (int) Math.floor(at.y());
        int z = (int) Math.floor(at.z());
        Optional<Vec3> spot = world.safeSpotNear(x, y, z, SAFE_SEARCH_RADIUS);
        if (spot.isEmpty()) {
            status = String.format("%s: no safe spot within %d blocks of (%d, %d, %d) - left there.",
                    what, SAFE_SEARCH_RADIUS, x, y, z);
            log.info("teleport: no safe spot near ({}, {}, {}) - left in place", x, y, z);
            return;
        }
        Vec3 to = spot.get();
        world.player().ifPresent(player -> send(player, to));
        status = String.format("%s → safe spot (%.1f, %.0f, %.1f)", what, to.x(), to.y(), to.z());
        log.info("teleport: safe spot ({}, {}, {}) for ({}, {}, {})", to.x(), to.y(), to.z(), x, y, z);
    }

    /** Sends the player to a point, remembering it as the last place sent to. */
    private void send(Player player, Vec3 to) {
        lastSent = to;
        player.teleport(to);
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
            go(where, safeTp);
        }

        @Override
        public void safeTo(Vec3 where) {
            go(where, true);
        }

        private void go(Vec3 where, boolean safe) {
            Optional<Player> maybe = world.player();
            if (maybe.isEmpty()) {
                log.warn("teleport: asked to go to {} but there is no session", where);
                return;
            }
            goTo(maybe.get(), where, "Teleport", true, safe);
        }
    }

    /** The confirmed surface at the column, or the player's current Y when it cannot be confirmed. */
    private double resolveY(int x, int z, Player player) {
        OptionalInt surface = world.surfaceAt(x, z);
        if (surface.isPresent()) {
            return surface.getAsInt();
        }
        Vec3 pos = player.position();
        if (pos != null) {
            return pos.y();
        }
        return 128;   // last-resort default
    }

    // ------------------------------------------------------------------
    // The command channel
    // ------------------------------------------------------------------

    /**
     * {@code tp <x y z>} and {@code safe <x y z>} go in that mode, {@code column <x z>} as the map
     * does, {@code drop} drops to the safe spot nearest; each answers with where the player is
     * once it has landed.
     */
    private CompletableFuture<?> command(List<String> args) {
        if (args.isEmpty()) {
            throw new IllegalArgumentException(USAGE);
        }
        String what = args.get(0).toLowerCase(Locale.ROOT);
        CompletableFuture<Void> done = switch (what) {
            case "tp", "safe" -> {
                if (args.size() < 4) {
                    throw new IllegalArgumentException(USAGE);
                }
                Player player = world.player().orElseThrow(() -> new IllegalStateException("no player yet"));
                Vec3 to = new Vec3(Double.parseDouble(args.get(1)), Double.parseDouble(args.get(2)),
                        Double.parseDouble(args.get(3)));
                yield goTo(player, to, what.equals("safe") ? "Safe tp" : "Teleport", true, what.equals("safe"));
            }
            case "column" -> {
                if (args.size() < 3) {
                    throw new IllegalArgumentException(USAGE);
                }
                CompletableFuture<Void> f = toColumn(Integer.parseInt(args.get(1)), Integer.parseInt(args.get(2)), safeTp);
                if (f == null) {
                    throw new IllegalStateException("no player yet");
                }
                yield f;
            }
            case "drop" -> {
                dropToSafe();
                yield CompletableFuture.completedFuture(null);
            }
            default -> throw new IllegalArgumentException(USAGE);
        };
        // The last teleport is sent; the player's position follows once the client has moved.
        return done.thenCompose(v -> CompletableFuture.supplyAsync(() -> null,
                        CompletableFuture.delayedExecutor(300, java.util.concurrent.TimeUnit.MILLISECONDS)))
                .thenApply(v -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("status", status);
                    if (lastSent != null) {
                        out.put("landed", lastSent);
                    }
                    world.player().map(Player::position).ifPresent(p -> out.put("position", p));
                    return out;
                });
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
        goTo(player, to, "Back", false, safeTp);
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
        goTo(player, t, "Teleport", true, safeTp);
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
