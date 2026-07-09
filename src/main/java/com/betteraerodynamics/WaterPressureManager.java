package com.betteraerodynamics;

import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import com.betteraerodynamics.item.PressureSuitItem;
import com.betteraerodynamics.enchantment.PressureSealEnchantment;

/**
 * Underwater pressure model — uses the same feet scale as
 * {@link AtmosphereManager#yToFeet(double, int)} so that
 * 1 ft of altitude in air equals 1 ft of depth in water.
 *
 * <pre>
 *   surfaceY  ──► yToFeet() ──► surface altitude (ft)
 *   eyeY      ──► yToFeet() ──► player altitude (ft)
 *   depthFt   =  surfaceAlt - playerAlt
 *   pressure  =  1.0 atm + depthFt / 33.0   (33 ft seawater per atm)
 * </pre>
 *
 * Damage starts at 7 atm (~198 ft / ~1.8 blocks below surface).
 */
public class WaterPressureManager {
    /** Feet of seawater column per 1 atmosphere of hydrostatic pressure. */
    private static final double FT_PER_ATM = 33.0;

    /** Surface ambient pressure in atm. */
    private static final double SURFACE_ATM = 1.0;

    /** Pressure threshold (atm) at which damage begins. 7 atm ≈ 198 ft depth. */
    private static final double DAMAGE_THRESHOLD_ATM = 7.0;

    /** HP/s damage per atm above threshold.
     *  Calibrated so max DPS (4.0) is reached at ~15 blocks below surface. */
    private static final double DMG_PER_ATM = 0.089;

    /** Cap on damage per second (2 hearts/s). */
    private static final float MAX_DPS = 4.0f;

    /**
     * Scan upward from the player's block position to find the water surface Y.
     * Returns the Y-level of the first non-water block above the player
     * (the top of the water column).
     */
    private static double findWaterSurfaceY(ServerLevel world, Player player) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        pos.set(player.getBlockX(), player.getBlockY(), player.getBlockZ());

        int maxScan = 64;
        for (int i = 0; i < maxScan; i++) {
            pos.move(0, 1, 0);
            if (!world.getFluidState(pos).is(Fluids.WATER)) {
                return pos.getY();
            }
        }
        return world.getSeaLevel();
    }

    /**
     * Compute depth in feet below the water surface.
     * Uses the same {@code yToFeet} mapping as the atmosphere system,
     * so 1 block of depth = the same number of feet as 1 block of altitude.
     */
    public static double depthFeet(ServerLevel world, Player player) {
        int seaLevel = world.getSeaLevel();
        double surfaceY = findWaterSurfaceY(world, player);
        double eyeY = player.getEyeY();

        double surfaceAlt = AtmosphereManager.yToFeet(surfaceY, seaLevel);
        double playerAlt  = AtmosphereManager.yToFeet(eyeY, seaLevel);

        return surfaceAlt - playerAlt;  // positive = below surface
    }

    /**
     * Compute hydrostatic pressure at the player's depth, in atmospheres.
     * Uses eye height (where the oxygen bar appears).
     */
    public static double getPressureATM(ServerLevel world, Player player) {
        double depthFt = depthFeet(world, player);
        if (depthFt <= 0.0) return SURFACE_ATM;
        return SURFACE_ATM + depthFt / FT_PER_ATM;
    }

    /**
     * Compute water pressure damage per second.
     * Only applies when the player is fully submerged (oxygen bar visible).
     * Uses the same damage reduction from the pressure suit and Pressure Retention
     * enchantment as the atmosphere (low-pressure) system.
     *
     * @return damage per second in half-hearts (HP)
     */
    public static float computeWaterPressureDamage(ServerLevel world, Player player) {
        if (!player.isUnderWater()) return 0f;

        double pressureATM = getPressureATM(world, player);
        if (pressureATM <= DAMAGE_THRESHOLD_ATM) return 0f;

        double dmg = (pressureATM - DAMAGE_THRESHOLD_ATM) * DMG_PER_ATM;

        // --- damage reduction (shared with atmosphere system) ---
        if (player instanceof ServerPlayer sp) {
            ItemStack[] armorSlots = new ItemStack[4];
            var inventory = sp.getInventory();
            armorSlots[0] = inventory.getItem(36); // helmet
            armorSlots[1] = inventory.getItem(37); // chestplate
            armorSlots[2] = inventory.getItem(38); // leggings
            armorSlots[3] = inventory.getItem(39); // boots

            float suitReduction = PressureSuitItem.calculateDamageReduction(armorSlots);
            dmg *= (1.0 - suitReduction);

            var enchantmentGetter = world.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
            var pressureSealHolder = enchantmentGetter.getOrThrow(PressureSealEnchantment.ENCHANTMENT_KEY);

            int enchantedPieces = 0;
            for (ItemStack slot : armorSlots) {
                int level = EnchantmentHelper.getItemEnchantmentLevel(pressureSealHolder, slot);
                if (level > 0) enchantedPieces++;
            }

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
