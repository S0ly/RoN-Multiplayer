package com.ron.common.modes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical registry of every game mode the network supports, capped at
 * {@link #MAX_PLAYERS} players. Single source of truth shared by the instance
 * (rtsmap.json validation in InstanceStateManager) and the proxy (the
 * network-wide enable/disable switchboard in ModeFilterConfig), so the two
 * can never drift.
 *
 * Modes are built programmatically rather than hand-listed:
 *   - TEAM: symmetric "<size>v<size>v..." with 2+ teams, total &le; MAX_PLAYERS
 *           (e.g. 1v1, 2v2, 3v3, 4v4, 1v1v1, 2v2v2, 2v2v2v2, ... 1v1v1v1v1v1v1v1)
 *   - FFA:  ffa_3..ffa_8 — each player is their own team
 *   - COOP: coop_2..coop_8 — everyone on one team
 */
public final class ModeCatalog {

    public static final int MAX_PLAYERS = 8;

    public enum Category { TEAM, FFA, COOP }

    public record ModeSpec(String name, Category category, int teamCount, int players) {}

    private static final List<ModeSpec> ALL = new ArrayList<>();
    private static final Map<String, ModeSpec> BY_NAME = new LinkedHashMap<>();

    static {
        // Team modes: every symmetric NvNv... grouping that fits the player cap.
        for (int size = 1; size <= MAX_PLAYERS / 2; size++) {
            for (int teams = 2; size * teams <= MAX_PLAYERS; teams++) {
                StringBuilder name = new StringBuilder(Integer.toString(size));
                for (int t = 1; t < teams; t++) {
                    name.append('v').append(size);
                }
                add(new ModeSpec(name.toString(), Category.TEAM, teams, size * teams));
            }
        }
        // FFA: each player on their own team.
        for (int n = 3; n <= MAX_PLAYERS; n++) {
            add(new ModeSpec("ffa_" + n, Category.FFA, n, n));
        }
        // Coop: everyone on a single team.
        for (int n = 2; n <= MAX_PLAYERS; n++) {
            add(new ModeSpec("coop_" + n, Category.COOP, 1, n));
        }
    }

    private static void add(ModeSpec spec) {
        ALL.add(spec);
        BY_NAME.put(spec.name(), spec);
    }

    /** All supported modes, in a stable order. */
    public static List<ModeSpec> all() {
        return Collections.unmodifiableList(ALL);
    }

    /** Look up a mode by its (case-insensitive) name, or {@code null} if unsupported. */
    public static ModeSpec get(String name) {
        if (name == null) return null;
        return BY_NAME.get(name.toLowerCase());
    }

    /** Comma-separated list of every supported mode name — for warning/help messages. */
    public static String namesHint() {
        return String.join(", ", BY_NAME.keySet());
    }

    private ModeCatalog() {}
}
