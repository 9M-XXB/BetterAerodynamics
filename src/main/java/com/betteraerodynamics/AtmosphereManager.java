package com.betteraerodynamics;

import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import com.betteraerodynamics.item.PressureSuitItem;
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

    // ---------- Damage thresholds (imperial base units) ----------
    private static final double MIN_PRESSURE          = 1200.0;    // lbf/ft^2
    private static final double MIN_TEMPERATURE_R     = 440.0;     // °R
    private static final double MIN_DENSITY           = 0.0012;    // slug/ft^3

    private static final double PRESSURE_DMG_PER      = 0.002;     // HP/s per lbf/ft^2
    private static final double TEMP_DMG_PER          = 0.01;      // HP/s per °R
    private static final double DENS_DMG_PER          = 900.0;     // HP/s per slug/ft^3

    private static final float  MAX_DPS               = 4.0f;

    /** Compute damage per second based on atmospheric thresholds. */
    public static float computeAtmosphereDamagePerSecond(ServerLevel world, Player player) {
        double p   = getPressure(world, player);
        double tr  = getTemperature(world, player);
        double rho = getDensity(world, player);

        double dmg = 0.0;
        if (p < MIN_PRESSURE)       dmg += (MIN_PRESSURE - p) * PRESSURE_DMG_PER;
        if (tr < MIN_TEMPERATURE_R) dmg += (MIN_TEMPERATURE_R - tr) * TEMP_DMG_PER;
        if (rho < MIN_DENSITY)      dmg += (MIN_DENSITY - rho) * DENS_DMG_PER;

        if (dmg <= 0.0) return 0f;

        // Check for atmosphere suit set bonus
        if (player instanceof ServerPlayer sp) {
            ItemStack[] armorSlots = new ItemStack[4];
            var inventory = sp.getInventory();
            armorSlots[0] = inventory.getItem(36); // helmet
            armorSlots[1] = inventory.getItem(37); // chestplate
            armorSlots[2] = inventory.getItem(38); // leggings
            armorSlots[3] = inventory.getItem(39); // boots
            
            float reduction = PressureSuitItem.calculateDamageReduction(armorSlots);
            dmg *= (1.0 - reduction);
            
            // Check for Pressure Seal enchantment on armor pieces
        var enchantmentGetter = world.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        var pressureSealHolder = enchantmentGetter.getOrThrow(PressureSealEnchantment.ENCHANTMENT_KEY);

        int enchantedPieces = 0;
        for (ItemStack slot : armorSlots) {
            int level = EnchantmentHelper.getItemEnchantmentLevel(pressureSealHolder, slot);
            if (level > 0) {
                enchantedPieces++;
            }
        }

        // Apply enchantment damage reduction
        if (enchantedPieces > 0) {
            float enchantReduction = enchantedPieces * PressureSealEnchantment.ENCHANT_REDUCTION;
            if (enchantedPieces == 4) {
                enchantReduction += PressureSealEnchantment.FULL_SET_ENCHANT_BONUS;
            }
            dmg *= (1.0 - enchantReduction);
        }
        }

        if (dmg <= 0.0) return 0f;
        return (float) Math.min(dmg, MAX_DPS);
    }
}
