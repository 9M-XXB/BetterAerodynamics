package com.betteraerodynamics.config;

import com.betteraerodynamics.BetterAerodynamics;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared (client + server) settings for the Y-level → feet altitude conversion.
 *
 * <p>Two modes:
 * <ul>
 *   <li>{@link Mode#CONVENTIONAL} (default): 1 block = 1 m = 3.28084 ft, with 0 ft at
 *       the world's in-game sea level — the same scale the speed conversion uses.</li>
 *   <li>{@link Mode#EVEREST}: linear mapping defined by two custom Y levels — the
 *       configured sea-level Y reads 0 ft, and the configured "Mount Everest" Y reads
 *       8,848 m (29,031 ft). E.g. sea level Y=63, Everest Y=320 → ≈112.96 ft/block.</li>
 * </ul>
 *
 * <p>Persisted to {@code config/betteraerodynamics.json}. Fields are volatile: the
 * client config screen writes them from the render thread while the server tick
 * thread reads them.
 */
public final class AeroConfig {
    public enum Mode { CONVENTIONAL, EVEREST }

    /** Height of Mount Everest in feet (8,848 m). */
    public static final double EVEREST_ALTITUDE_FT = 29031.0;
    /** Feet per metre: 1 block = 1 m in CONVENTIONAL mode. */
    public static final double FT_PER_M = 3.28084;
    /** Default airfoil designation (NACA 2412). */
    public static final String DEFAULT_NACA = "2412";
    /** Valid NACA 4-digit designation: exactly four digits 0–9. */
    public static final String NACA_PATTERN = "\\d{4}";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile Mode mode = Mode.CONVENTIONAL;
    private static volatile int seaLevelY = 63;
    private static volatile int everestY = 320;
    private static volatile boolean hudEnabled = true;
    private static volatile String naca4 = DEFAULT_NACA;

    private AeroConfig() {}

    public static Mode mode() { return mode; }
    public static int seaLevelY() { return seaLevelY; }
    public static int everestY() { return everestY; }
    public static boolean hudEnabled() { return hudEnabled; }
    public static String naca4() { return naca4; }

    /** Apply new values from the config screen and persist them. */
    public static void set(Mode newMode, int newSeaLevelY, int newEverestY, String newNaca4) {
        mode = newMode;
        seaLevelY = newSeaLevelY;
        everestY = newEverestY;
        if (newNaca4 != null && newNaca4.matches(NACA_PATTERN)) {
            naca4 = newNaca4;
        }
        save();
    }

    /** Toggle the HUD overlay and persist the choice. */
    public static void setHudEnabled(boolean enabled) {
        hudEnabled = enabled;
        save();
    }

    /** Feet-per-block scale of the current conversion at the given world sea level. */
    public static double feetPerBlock(int worldSeaLevel) {
        if (mode == Mode.EVEREST) {
            return EVEREST_ALTITUDE_FT / everestSpan();
        }
        return FT_PER_M;
    }

    private static double everestSpan() {
        return Math.max(1.0, everestY - seaLevelY);
    }

    /**
     * Convert a raw Y coordinate to altitude in feet under the current conversion.
     * {@code worldSeaLevel} is the in-game sea level, used by CONVENTIONAL mode.
     */
    public static double yToFeet(double y, int worldSeaLevel) {
        if (mode == Mode.EVEREST) {
            return (y - seaLevelY) * (EVEREST_ALTITUDE_FT / everestSpan());
        }
        return (y - worldSeaLevel) * FT_PER_M;
    }

    // === Persistence ===

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("betteraerodynamics.json");
    }

    public static void load() {
        try {
            Path path = configPath();
            if (!Files.exists(path)) return;
            JsonObject json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            Mode loadedMode = Mode.valueOf(json.get("mode").getAsString());
            int loadedSea = json.get("seaLevelY").getAsInt();
            int loadedEverest = json.get("everestY").getAsInt();
            if (loadedEverest - loadedSea < 1) {
                BetterAerodynamics.LOGGER.warn("betteraerodynamics.json: Everest Y must be above sea level Y — using defaults");
                return;
            }
            mode = loadedMode;
            seaLevelY = loadedSea;
            everestY = loadedEverest;
            if (json.has("hud")) {
                hudEnabled = json.get("hud").getAsBoolean();
            }
            if (json.has("naca4")) {
                String n = json.get("naca4").getAsString();
                if (n.matches(NACA_PATTERN)) {
                    naca4 = n;
                } else {
                    BetterAerodynamics.LOGGER.warn("betteraerodynamics.json: invalid naca4 '{}' — using {}", n, DEFAULT_NACA);
                }
            }
            BetterAerodynamics.LOGGER.info("Aero config loaded: mode={}, seaLevelY={}, everestY={}, hud={}, naca4={}",
                mode, seaLevelY, everestY, hudEnabled, naca4);
        } catch (Exception e) {
            BetterAerodynamics.LOGGER.warn("Could not read betteraerodynamics.json, using defaults", e);
        }
    }

    public static void save() {
        try {
            JsonObject json = new JsonObject();
            json.addProperty("mode", mode.name());
            json.addProperty("seaLevelY", seaLevelY);
            json.addProperty("everestY", everestY);
            json.addProperty("hud", hudEnabled);
            json.addProperty("naca4", naca4);
            Files.createDirectories(configPath().getParent());
            Files.writeString(configPath(), GSON.toJson(json));
        } catch (Exception e) {
            BetterAerodynamics.LOGGER.warn("Could not write betteraerodynamics.json", e);
        }
    }
}
