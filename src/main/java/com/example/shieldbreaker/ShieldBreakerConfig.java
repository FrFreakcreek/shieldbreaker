package com.example.shieldbreaker;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ShieldBreakerConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH =
            FabricLoader.getInstance().getConfigDir().resolve("shieldbreaker.json");

    /** Master toggle (also flipped by the keybind). */
    public boolean enabled = true;

    /** Ticks to wait after switching to the axe before the attack is sent. 0-3. */
    public int switchDelayTicks = 1;

    /** If true, the delay is randomly picked between 1 and switchDelayTicks each time. */
    public boolean randomizeDelay = true;

    /** Swap back to the original slot after the hit. */
    public boolean switchBack = true;

    /** Ticks after the hit before swapping back (minimum 1 is enforced). */
    public int switchBackDelayTicks = 2;

    public boolean targetPlayers = true;
    public boolean targetMobs = true;

    /** Skip axes with this many durability points or fewer left. */
    public int minAxeDurability = 2;

    // ---- singleton plumbing ----
    private static ShieldBreakerConfig instance = new ShieldBreakerConfig();

    public static ShieldBreakerConfig get() { return instance; }

    public static void load() {
        try {
            if (Files.exists(PATH)) {
                ShieldBreakerConfig loaded = GSON.fromJson(Files.readString(PATH), ShieldBreakerConfig.class);
                if (loaded != null) instance = loaded;
            }
        } catch (Exception e) {
            System.err.println("[ShieldBreaker] Failed to read config, using defaults: " + e);
        }
        instance.switchDelayTicks = Math.max(0, Math.min(3, instance.switchDelayTicks));
        instance.switchBackDelayTicks = Math.max(1, instance.switchBackDelayTicks);
        save();
    }

    public static void save() {
        try {
            Files.writeString(PATH, GSON.toJson(instance));
        } catch (IOException e) {
            System.err.println("[ShieldBreaker] Failed to save config: " + e);
        }
    }
}
