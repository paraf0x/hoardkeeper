package dev.hoardkeeper.gametest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one scenario leaves behind, serialised by Gson to gametest/result.json. Public fields on
 * purpose: the mod's model classes use the same style and Gson needs no configuration for it.
 * The runner reads exactly these eight keys; scripts/testdata/result.golden.json pins them.
 */
public final class Result {
    public String scenario;
    public boolean pass;
    public long durationMs;
    public boolean chained;
    public List<Check> checks = new ArrayList<>();
    public Map<String, Object> numbers = new LinkedHashMap<>();
    public List<String> screenshots = new ArrayList<>();
    public ErrorInfo error;

    public static final class Check {
        public String name;
        public boolean pass;
        public String detail;

        public Check(String name, boolean pass, String detail) {
            this.name = name;
            this.pass = pass;
            this.detail = detail;
        }
    }

    public static final class ErrorInfo {
        public String type;
        public String message;
        public List<String> frames = new ArrayList<>();
        /**
         * The cause chain below {@code t}, outermost first, one "&lt;class name&gt;: &lt;message&gt;"
         * entry per cause. Without this a scenario that fails because a predicate threw a real bug
         * (an NPE, say) is indistinguishable in result.json from a plain timeout: the outer
         * AssertionError's type and message are all a reader without this field would see.
         */
        public List<String> causes = new ArrayList<>();

        public static ErrorInfo of(Throwable t) {
            ErrorInfo info = new ErrorInfo();
            info.type = t.getClass().getName();
            info.message = String.valueOf(t.getMessage());
            StackTraceElement[] stack = t.getStackTrace();
            for (int i = 0; i < Math.min(10, stack.length); i++) {
                info.frames.add(stack[i].toString());
            }
            Throwable root = null;
            Throwable current = t.getCause();
            int guard = 0;
            while (current != null && guard++ < 20) {
                info.causes.add(current.getClass().getName() + ": " + current.getMessage());
                root = current;
                current = current.getCause();
            }
            if (root != null) {
                info.frames.add("caused by: " + root.getClass().getName());
                StackTraceElement[] rootStack = root.getStackTrace();
                for (int i = 0; i < Math.min(5, rootStack.length); i++) {
                    info.frames.add(rootStack[i].toString());
                }
            }
            return info;
        }
    }
}
