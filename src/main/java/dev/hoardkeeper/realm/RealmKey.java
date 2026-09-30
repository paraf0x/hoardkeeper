package dev.hoardkeeper.realm;

/**
 * Which realm the client is connected to, as an opaque key the upload carries in {@code scan.realm}.
 *
 * <p><b>Why a hashed world seed.</b> On a proxy network (Velocity, BungeeCord) every backend is
 * reached through one address, so the connected address, the server brand, the level set and the
 * datapack contents are all either constant or colliding across backends — measured on Beacoland
 * across four of them. The one value that told all four apart, stayed the same across a client
 * restart, and did <em>not</em> move when walking through a nether portal, is the hashed world seed
 * the server sends in {@code ClientboundLoginPacket} and {@code ClientboundRespawnPacket}.
 *
 * <p><b>The client never names the realm.</b> It cannot: it has a number, not an identity. The key
 * is opaque on purpose and the upload server maps it to one of its configured realms. That is also why
 * {@link #current()} returns {@code null} rather than a guess when no login packet has arrived —
 * an invented realm is worse than none, because a wrong one silently overwrites another realm's
 * containers at the same coordinates, while an absent one is simply resolved server-side.
 *
 * <p>The {@code seed:} prefix names the provenance so a future server-plugin-supplied id can share
 * the field as {@code plugin:<name>} without the server having to guess which kind it received.
 */
public final class RealmKey {

    private static final RealmKey INSTANCE = new RealmKey();

    public static RealmKey get() {
        return INSTANCE;
    }

    /** Written from the packet handler thread, read when a scan starts — hence volatile. */
    private volatile String current;

    /**
     * Told when the realm changes under a live connection. Set once at startup; a field rather
     * than a list because there is exactly one thing that needs to know (the scan controller), and
     * a listener registry for one listener is machinery pretending to be a design.
     */
    private volatile java.util.function.Consumer<String> listener = key -> { };

    private RealmKey() {
    }

    /** Registers the one thing that cares when the realm changes. Client thread, at startup. */
    public void setListener(java.util.function.Consumer<String> listener) {
        this.listener = listener == null ? key -> { } : listener;
    }

    /**
     * A fresh {@code CommonPlayerSpawnInfo}. Called for both login and respawn: on a proxy network a
     * backend switch reuses the socket, so a new login packet is the only signal that the realm
     * changed, and the latest one always wins.
     *
     * <p>The listener hears about a <em>change</em>, which is narrower than "a packet arrived", in
     * two ways that both matter:
     *
     * <ul>
     *   <li>The first key of a connection is not a change. There is no scan running yet, and
     *       firing here would abort the very session the player is about to be offered a resume for.
     *   <li>The same key arriving again is not a change. A nether portal sends a respawn packet
     *       carrying the same hashed seed — measured, not assumed — so treating every packet as a
     *       change would kill a scan every time somebody walks through a portal.
     * </ul>
     */
    public void onSpawnInfo(long hashedSeed) {
        String next = format(hashedSeed);
        String previous = current;
        current = next;
        if (previous != null && !previous.equals(next)) {
            listener.accept(next);
        }
    }

    /** Forgets the realm. Stale is worse than absent — see the class javadoc. */
    public void onDisconnect() {
        current = null;
    }

    /** The current realm key, or {@code null} when no login packet has been seen. */
    public String current() {
        return current;
    }

    /** Fixed-width hex so two keys line up when a person compares them in a log or a config. */
    static String format(long hashedSeed) {
        return String.format("seed:%016x", hashedSeed);
    }
}
