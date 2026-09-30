package dev.hoardkeeper.scan;

import java.util.List;
import java.util.Locale;

/**
 * Which servers a list of addresses names — the host rule shared by the core's {@code scanServers}
 * (split spec §4) and an add-on's own server list. Pure: strings in, a boolean out.
 */
public final class ServerList {

    private ServerList() {
    }

    /**
     * Whether {@code serverAddress} — what the player typed into the server list — is one of
     * {@code servers}. Both sides are compared as hosts ({@link #host}): case, port and a trailing
     * dot do not matter. A domain entry also covers its subdomains, on a label boundary, because
     * the crew reaches one proxy as {@code play.}, {@code survival.} and {@code kingdom.beacoland.com}
     * alike; an IP address entry matches only itself. {@code "*"} matches every server. A missing
     * list, a blank entry or a blank address matches nothing.
     */
    public static boolean listed(String serverAddress, List<String> servers) {
        String host = host(serverAddress);
        if (host.isEmpty() || servers == null) {
            return false;
        }
        for (String raw : servers) {
            if (raw != null && raw.trim().equals("*")) {
                return true;
            }
            String entry = host(raw);
            if (entry.isEmpty()) {
                continue;
            }
            if (host.equals(entry)) {
                return true;
            }
            if (!isIpLiteral(entry) && host.endsWith("." + entry)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The host part of a server address, lower-cased, without port, brackets or trailing dot:
     * {@code "Play.Beacoland.com.:25565"} becomes {@code "play.beacoland.com"},
     * {@code "[2001:db8::1]:25565"} becomes {@code "2001:db8::1"}. {@code null} becomes {@code ""}.
     */
    public static String host(String address) {
        if (address == null) {
            return "";
        }
        String host = address.trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("[")) {
            int close = host.indexOf(']');
            host = close < 0 ? host.substring(1) : host.substring(1, close);
        } else if (host.indexOf(':') >= 0 && host.indexOf(':') == host.lastIndexOf(':')) {
            // Exactly one colon: host:port. Two or more is a bare IPv6 address, which has no port.
            host = host.substring(0, host.indexOf(':'));
        }
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        return host;
    }

    /** An IPv4 or IPv6 literal, which has no subdomains for an entry to cover. */
    private static boolean isIpLiteral(String host) {
        return host.indexOf(':') >= 0 || host.chars().allMatch(c -> c == '.' || (c >= '0' && c <= '9'));
    }
}
