package com.betteraerodynamics.client;

import com.betteraerodynamics.ElytraPhysics;
import com.betteraerodynamics.config.AeroConfig;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;

import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side HUD: displays altitude in action bar overlay using the
 * user-configured altitude conversion (see {@link AeroConfig}), plus a
 * keybind (default O) that opens the aerodynamics settings screen.
 */
public class BetterAerodynamicsClient implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("betteraero-hud");
    private static KeyMapping openConfigKey;
    private double lastFeet = Double.NaN;

    @Override
    public void onInitializeClient() {
        openConfigKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.betteraerodynamics.open_config",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_O,
            KeyMapping.Category.MISC));

        LOG.info("HUD client registered");
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openConfigKey.consumeClick()) {
                if (client.screen == null) {
                    client.setScreen(new AeroConfigScreen(null));
                }
            }

            if (!AeroConfig.hudEnabled()) return;
            if (client.player == null || client.level == null) {
                lastFeet = Double.NaN;
                return;
            }

            long tick = client.level.getGameTime();
            if (tick % 20 != 0) return; // once per second

            int sea = client.level.getSeaLevel();
            double y = client.player.getY();
            double feet = AeroConfig.yToFeet(y, sea);

            double vsFtmin = 0;
            if (!Double.isNaN(lastFeet)) {
                vsFtmin = (feet - lastFeet) * 60; // sampled once per second
            }
            lastFeet = feet;

            // Log full flight data to file
            double spd = ElytraPhysics.lastSpeedFtS;
            double lift = ElytraPhysics.lastLiftLbf;
            double drag = ElytraPhysics.lastDragLbf;
            boolean stalled = ElytraPhysics.lastStalled;
            if (spd > 1) {
                LOG.info("FLIGHT Alt={}ft Spd={}ft/s L={}lbf D={}lbf stall={}",
                    (int)feet, (int)spd, (int)lift, (int)drag, stalled);
            }

            // Overlay: altitude + vertical speed
            String msg = String.format("Alt %.0f ft    VS %.0f ft/min", feet, vsFtmin);
            client.player.sendOverlayMessage(Component.literal(msg));
        });
    }
}
