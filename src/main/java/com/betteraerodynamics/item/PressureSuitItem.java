package com.betteraerodynamics.item;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.Identifier;

import com.betteraerodynamics.BetterAerodynamics;

/**
 * Utility class for pressure suit detection and damage reduction calculation.
 * In MC 26.1.2, armor items are created via Item.Properties.humanoidArmor(),
 * so we detect suit pieces by their registered Identifier.
 *
 * Provides damage reduction against both low pressure (high altitude)
 * and high pressure (deep water) hazards.
 */
public class PressureSuitItem {
    // Single piece reduction: 10%
    public static final float PIECE_REDUCTION = 0.1f;
    // Full set bonus: +30% (total 70%)
    public static final float FULL_SET_BONUS_REDUCTION = 0.3f;

    /**
     * Check if an ItemStack is a pressure suit piece by its registered ID.
     */
    public static boolean isPressureSuitPiece(ItemStack stack) {
        if (stack.isEmpty()) return false;
        var item = stack.getItem();
        var key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
        return key != null && (
            key.equals(BetterAerodynamics.PRESSURE_SUIT_HELMET) ||
            key.equals(BetterAerodynamics.PRESSURE_SUIT_CHESTPLATE) ||
            key.equals(BetterAerodynamics.PRESSURE_SUIT_LEGGINGS) ||
            key.equals(BetterAerodynamics.PRESSURE_SUIT_BOOTS)
        );
    }

    /**
     * Calculate damage reduction based on pressure suit pieces worn.
     * @param armorSlots array of 4 ItemStacks (helmet, chestplate, leggings, boots)
     */
    public static float calculateDamageReduction(ItemStack[] armorSlots) {
        int piecesWorn = 0;
        for (ItemStack slot : armorSlots) {
            if (isPressureSuitPiece(slot)) {
                piecesWorn++;
            }
        }

        // Base: 10% per piece
        float reduction = piecesWorn * PIECE_REDUCTION;

        // Full set bonus: +30%
        if (piecesWorn == 4) {
            reduction += FULL_SET_BONUS_REDUCTION;
        }

        return Math.min(reduction, 0.95f); // Cap at 95%
    }
}
