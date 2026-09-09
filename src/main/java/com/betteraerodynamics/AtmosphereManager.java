package com.betteraerodynamics;

import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import com.betteraerodynamics.enchantment.PressureSealEnchantment;
import com.betteraerodynamics.config.AeroConfig;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * ISA-like atmospheric model for Minecraft.
 * Maps player Y-coordinate to altitude, then computes pressure, temperature, density.
 */
public class AtmosphereManager {
    // --- ISA-like constants (imperial) ---
    public static final double R0    = 1716.554;   // ft·lbf/(slug·°R)
    public static final double T0    = 518.67;     // °R
    public static final double P0    = 2116.22;    // lbf/ft^2
    public static final double RHO0  = 0.0023769;  // slug/ft^3
    public static final double GAMMA = 1.4;

    private static final double H_TROPOPAUSE = 36089.0; // ft
    private static final double H_STRAT_20KM = 65617.0; // ft

    /**
     * Convert a raw Minecraft Y coordinate to altitude in feet using the
     * user-configured conversion (see {@link AeroConfig}).
     * Default: 0 ft at in-game sea level, 1 block = 1 m = 3.28084 ft.
     */
    public static double yToFeet(double y, int seaLevel) {
        double h = AeroConfig.yToFeet(y, seaLevel);
        return Mth.clamp(h, -2000.0, 100000.0);
    }

    /** Map Minecraft Y to altitude in feet; clamp to model range. */
    public static double altitudeFeet(ServerLevel world, Player player) {
        return yToFeet(player.getY(), world.getSeaLevel());
    }

    /** θ = T/T0 — temperature ratio */
    public static double getTheta(ServerLevel world, Player player) {
        double h = altitudeFeet(world, player);
        if (h <= H_TROPOPAUSE) {
            return 1.0 - 6.87535e-6 * h;
        } else if (h <= H_STRAT_20KM) {
            return 0.75187;
        } else {
            return 0.68246 + 1.05778e-6 * h;
        }
    }

    /** δ = P/P0 — pressure ratio */
    public static double getDelta(ServerLevel world, Player player) {
        double h = altitudeFeet(world, player);
        double theta = getTheta(world, player);
        if (h <= H_TROPOPAUSE) {
            return Math.pow(theta, 5.2561);
        } else if (h <= H_STRAT_20KM) {
            return 0.22336 * Math.exp((H_TROPOPAUSE - h) / 20806.7);
        } else {
            return 3.17176e-6 * Math.pow(theta, -34.1632);
        }
    }

    /** σ = ρ/ρ0 — density ratio */
    public static double getSigma(ServerLevel world, Player player) {
        double theta = getTheta(world, player);
        double delta = getDelta(world, player);
        return delta / theta;
    }

    // --- Physical values (imperial base units) ---

    /** Density in slug/ft^3 */
    public static double getDensity(ServerLevel world, Player player) {
        return getSigma(world, player) * RHO0;
    }

    /** Pressure in lbf/ft^2 */
    public static double getPressure(ServerLevel world, Player player) {
        return getDelta(world, player) * P0;
    }

    /** Temperature in °R (Rankine) */
    public static double getTemperature(ServerLevel world, Player player) {
        return getTheta(world, player) * T0;
    }

    // ---------- Low air pressure (altitude sickness) damage ----------

    private static final float  MAX_DPS               = 4.0f;

    /**
     * Compute low air pressure damage per second from the player's altitude
     * under the current {@link AeroConfig} conversion, so the threshold follows
     * the active height mode. Damage ramps linearly from 0 DPS at the
     * altitude-sickness threshold (default 8,200 ft ≈ 2,500 m, where altitude
     * sickness begins in real life) up to {@link #MAX_DPS} at Everest altitude
     * (29,031 ft / 8,848 m — the death zone).
     *
     * <p>Reduced by the pressure suit (per-piece + full-set bonus) and the
     * Pressure Seal enchantment, shared with the water pressure system.
     */
    public static float computeAtmosphereDamagePerSecond(ServerLevel world, Player player) {
        double altFt = altitudeFeet(world, player);
        double thresholdFt = AeroConfig.altitudeSicknessFt();
        if (altFt <= thresholdFt) return 0f;

        double spanFt = Math.max(1.0, AeroConfig.EVEREST_ALTITUDE_FT - thresholdFt);
        double rawDps = MAX_DPS * (altFt - thresholdFt) / spanFt;

        // Pressure Seal is the ONLY mitigation (damage bypasses vanilla armor;
        // the pressure suit grants no damage reduction)
        float sealReduction = 0f;
        int sealPieces = 0;

        if (player instanceof ServerPlayer sp) {
            ItemStack[] armorSlots = new ItemStack[4];
            var inventory = sp.getInventory();
            armorSlots[0] = inventory.getItem(36); // helmet
            armorSlots[1] = inventory.getItem(37); // chestplate
            armorSlots[2] = inventory.getItem(38); // leggings
            armorSlots[3] = inventory.getItem(39); // boots

            sealReduction = PressureSealEnchantment.calculateDamageReduction(world, armorSlots);
            var enchantmentGetter = world.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
            var pressureSealHolder = enchantmentGetter.getOrThrow(PressureSealEnchantment.ENCHANTMENT_KEY);
            for (ItemStack slot : armorSlots) {
                if (EnchantmentHelper.getItemEnchantmentLevel(pressureSealHolder, slot) > 0) {
                    sealPieces++;
                }
            }
        }

        double finalDps = rawDps * (1.0 - sealReduction);

        if (finalDps > 0.0) {
            BetterAerodynamics.LOGGER.info(
                "PRESSURE alt={} ft rawDps={} sealPieces={} sealRed={} finalDps={}",
                String.format("%.0f", altFt), String.format("%.2f", rawDps),
                sealPieces, String.format("%.2f", sealReduction), String.format("%.2f", finalDps));
        }

        return (float) Math.min(finalDps, MAX_DPS);
    }
}
