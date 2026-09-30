package dev.hoardkeeper.gametest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Every chat line the client has displayed since the last {@link #reset()} -- recorded by
 * {@code mixin.ChatComponentMixin}, the only thing in this class besides a plain list. Production
 * code carries no chat capture at all; this class and the mixin that feeds it live only in the
 * gametest source set, alongside {@link Harness} and {@link Fixtures}.
 *
 * <p><b>Client thread only, by construction rather than by discipline.</b> The mixin fires from
 * inside {@code ChatComponent.addMessage}, which only ever runs on the client thread (packet
 * handling, or a direct {@code sendSystemMessage} call, both already on that thread). Every reader
 * below is expected to run there too: either directly, from inside a {@code Predicate<Minecraft>}
 * the test framework's own {@code waitFor}/{@code stays} already run on the client thread (see
 * {@link Harness#awaitChatContaining}), or through an explicit {@link Harness#onClient} hop for a
 * one-off read after a wait has returned (see {@link Harness#chatLinesContaining}). Nothing here
 * synchronises, on the same reasoning {@code ScanController}'s own class javadoc gives for its
 * fields: everything that touches {@link #LINES} is already serialised by being on one thread.
 */
public final class ChatCapture {
    private static final List<String> LINES = new ArrayList<>();

    private ChatCapture() {
    }

    /** Called by the mixin for every line {@code ChatComponent} is about to display. */
    public static void record(String line) {
        LINES.add(line);
    }

    /**
     * Cleared at the start of every scenario -- see {@link Harness}'s constructor -- so a chained
     * run's scenario N+1 can never read a line scenario N's chat left behind.
     */
    public static void reset() {
        LINES.clear();
    }

    /** Every line recorded so far, oldest first. */
    public static List<String> lines() {
        return Collections.unmodifiableList(new ArrayList<>(LINES));
    }

    /** The recorded lines containing {@code substring}, in the order they arrived. */
    public static List<String> linesContaining(String substring) {
        return LINES.stream().filter(line -> line.contains(substring)).collect(Collectors.toList());
    }
}
