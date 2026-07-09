package com.betteraerodynamics.client;

import com.betteraerodynamics.ElytraPhysics;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side HUD: displays altitude in action bar overlay.
 */
public class BetterAerodynamicsClient implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("betteraero-hud");

    @Override
    public void onInitializeClient() {
        LOG.info("HUD client registered");
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!ElytraPhysics.hudEnabled) return;
            if (client.player == null || client.level == null) return;

            long tick = client.level.getGameTime();
            if (tick % 20 != 0) return; // once per second

            int sea = client.level.getSeaLevel();
            double y = client.player.getY();
            double feet = (y - sea) * 112.5;

            // Log full flight data to file
            double spd = ElytraPhysics.lastSpeedFtS;
            double lift = ElytraPhysics.lastLiftLbf;
            double drag = ElytraPhysics.lastDragLbf;
            boolean stalled = ElytraPhysics.lastStalled;
            if (spd > 1) {
                LOG.info("FLIGHT Alt={}ft Spd={}ft/s L={}lbf D={}lbf stall={}",
                    (int)feet, (int)spd, (int)lift, (int)drag, stalled);
            }

            // Overlay: altitude only
            String msg = String.format("Alt %.0f ft", feet);
            client.player.sendOverlayMessage(Component.literal(msg));
        });
    }
}
