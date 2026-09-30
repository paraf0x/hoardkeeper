package dev.hoardkeeper.realm;

/**
 * Whether a stored session belongs to the realm the player is on now.
 *
 * <p><b>Why this exists.</b> The resume offer matches on the dimension, which on a proxy network
 * decides nothing: every backend has a {@code minecraft:overworld}, and the connected address is
 * identical across all of them. Without this check, stepping from survival to kingdom gets you
 * offered the survival session you just left — and continuing it would scan kingdom's containers
 * into survival's session and upload them under survival's realm.
 *
 * <p>Pure and free of Minecraft, which is what lets the rule be tested rather than reasoned about.
 */
public final class RealmMatch {

    private RealmMatch() {
    }

    /**
     * True unless both realms are known and they differ.
     *
     * <p>The asymmetry is deliberate. Excluding a session whose realm is unknown would make every
     * session scanned before the realm field existed permanently unresumable, and "I do not know
     * where I am" (singleplayer, or no login packet yet) is not evidence that a session belongs
     * somewhere else. Only a positive contradiction — two known realms that disagree — is enough
     * to withhold the offer, and withholding it costs a rescan rather than corrupting a session.
     */
    public static boolean resumable(String currentRealm, String snapshotRealm) {
        if (currentRealm == null || snapshotRealm == null) {
            return true;
        }
        return currentRealm.equals(snapshotRealm);
    }
}
