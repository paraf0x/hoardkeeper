package dev.hoardkeeper.chat;

/**
 * Who gets the one action bar line, when several writers want it.
 *
 * <p>Four of them now do, and before this class the rule was an {@code if} chain repeated in each
 * of them — {@code ScanController} checking {@code SearchService.ownsActionBar()} in two places was
 * the whole of it, and adding a third writer meant editing every existing one and hoping they
 * agreed. The order lives here instead, once, so a fifth writer is one enum constant and one row
 * of a test rather than an archaeology exercise.
 *
 * <p><b>The order, and why.</b>
 *
 * <ol>
 *   <li>{@link Writer#SEARCH} — transient, explicitly asked for a moment ago, and carrying a
 *       countdown the player is actively reading.</li>
 *   <li>{@link Writer#AUTO_FINISH} — transient, and the one signal that a scan is about to end by
 *       itself. Missing it means being surprised by a finished scan.</li>
 *   <li>{@link Writer#SITE} — how far the gathering has got. Nowhere else on screen.</li>
 *   <li>{@link Writer#SCAN_PROGRESS} — loses to everything, because those numbers are on the HUD
 *       panel anyway and the bar is the only place the other three have.</li>
 * </ol>
 *
 * <p><b>Pure.</b> Every input is a boolean the caller already knows. Nothing here reads a service,
 * a config or a clock, which is what makes the whole ordering testable in one file.
 */
public final class ActionBarOwner {

    /** The writers, in descending priority. The declaration order <em>is</em> the rule. */
    public enum Writer {
        SEARCH,
        AUTO_FINISH,
        SITE,
        SCAN_PROGRESS,
    }

    private ActionBarOwner() {
    }

    /**
     * The writer that gets the bar, or {@code null} when none of them wants it.
     *
     * <p>Each flag is "this writer has something to say right now" — not "this writer is enabled".
     * A writer that is silent never wins, which is what keeps the bar free for the one below it
     * instead of blanking it.
     */
    public static Writer resolve(boolean search, boolean autoFinish, boolean site, boolean progress) {
        if (search) {
            return Writer.SEARCH;
        }
        if (autoFinish) {
            return Writer.AUTO_FINISH;
        }
        if (site) {
            return Writer.SITE;
        }
        if (progress) {
            return Writer.SCAN_PROGRESS;
        }
        return null;
    }

    /**
     * Whether {@code who} may write the bar right now — the form every call site actually wants,
     * so that no caller has to know where it sits in the order or who else exists.
     */
    public static boolean mayWrite(Writer who, boolean search, boolean autoFinish, boolean site,
                                    boolean progress) {
        return resolve(search, autoFinish, site, progress) == who;
    }
}
