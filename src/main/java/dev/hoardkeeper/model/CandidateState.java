package dev.hoardkeeper.model;

/**
 * The persisted state of one discovered container candidate within a scan session — whether it has
 * been scanned yet, and why it failed if it has not.
 *
 * <p>Plain data holder for JSON (de)serialisation — no behaviour, no Minecraft imports.
 */
public class CandidateState {
    public int[] pos;
    public int[] secondaryPos;
    public String kind;
    public String status;
    public int attempts;
    public String failReason;
    /**
     * ISO-8601 instant this candidate was last observed — scanned or failed — matching
     * {@code ScannedContainer.scannedAt}'s format, or {@code null} if it has never been observed.
     */
    public String observedAt;

    public CandidateState() {
    }
}
