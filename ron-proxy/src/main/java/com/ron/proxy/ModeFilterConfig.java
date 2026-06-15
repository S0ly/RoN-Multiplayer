package com.ron.proxy;

import com.google.gson.JsonObject;
import com.ron.common.modes.ModeCatalog;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Network-wide game-mode switchboard, read from the "gameModes" block of
 * config.json:
 * <pre>
 *   "gameModes": { "1v1": true, "2v2": true, "2v2v2": false, ... }
 * </pre>
 *
 * Strict allow-list: a mode is enabled only if it is present and set to
 * {@code true}. Any mode an instance reports that is not listed (or listed
 * {@code false}) is disabled network-wide.
 *
 * If the whole block is absent (a legacy config), {@link #present()} is false;
 * callers treat every mode as enabled and rewrite the file with
 * {@link #defaultStub()} so the operator sees the full switchboard.
 */
public final class ModeFilterConfig {

    private final boolean present;
    private final Set<String> enabledModes;

    private ModeFilterConfig(boolean present, Set<String> enabledModes) {
        this.present = present;
        this.enabledModes = enabledModes;
    }

    /** Whether a "gameModes" block existed in the config. */
    public boolean present() {
        return present;
    }

    /**
     * Names of enabled modes, or {@code null} when the block is absent — in
     * which case the filter is inactive and every mode is allowed.
     */
    public Set<String> enabledModes() {
        return present ? enabledModes : null;
    }

    public static ModeFilterConfig fromJson(JsonObject root) {
        if (root == null || !root.has("gameModes")) {
            return new ModeFilterConfig(false, Set.of());
        }
        JsonObject obj = root.getAsJsonObject("gameModes");
        Set<String> enabled = new LinkedHashSet<>();
        for (var entry : obj.entrySet()) {
            if (entry.getValue().getAsBoolean()) {
                enabled.add(entry.getKey());
            }
        }
        return new ModeFilterConfig(true, enabled);
    }

    /** Default config block: every catalog mode enabled. */
    public static JsonObject defaultStub() {
        JsonObject stub = new JsonObject();
        for (ModeCatalog.ModeSpec spec : ModeCatalog.all()) {
            stub.addProperty(spec.name(), true);
        }
        return stub;
    }
}
