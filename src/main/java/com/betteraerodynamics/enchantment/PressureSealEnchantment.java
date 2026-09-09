package com.betteraerodynamics.enchantment;

import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantment.Cost;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.core.Registry;

import com.betteraerodynamics.BetterAerodynamics;

/**
 * Pressure Seal enchantment helper for MC 26.1.2.
 * 
 * NOTE: In 26.1.2, Enchantment is a final record class — cannot be subclassed.
 * Enchantments must be defined via DataPack JSON or Enchantment.Builder.
 * This class provides the registration helper and detection utilities.
 */
public class PressureSealEnchantment {
    public static final Identifier ENCHANTMENT_ID = BetterAerodynamics.id("pressure_seal");
    public static final ResourceKey<Enchantment> ENCHANTMENT_KEY = 
        ResourceKey.create(Registries.ENCHANTMENT, ENCHANTMENT_ID);

    // Single piece enchantment reduction: 15%
    public static final float ENCHANT_REDUCTION = 0.15f;
    // Full set bonus: +20% (total 35%)
    public static final float FULL_SET_ENCHANT_BONUS = 0.20f;

    /**
     * Total pressure-damage reduction from Pressure Seal pieces worn.
     * Per piece −15%, full set +20% (total 65%), capped at 100%.
     * This is the ONLY mitigation applied to pressure damage — the pressure
     * damage types bypass vanilla armor via the data/minecraft/tags/damage_type/bypasses_armor.json
     * tag, and the pressure suit grants no damage reduction.
     *
     * @param armorSlots array of 4 ItemStacks (helmet, chestplate, leggings, boots)
     */
    public static float calculateDamageReduction(net.minecraft.server.level.ServerLevel world,
                                                 net.minecraft.world.item.ItemStack[] armorSlots) {
        var holder = world.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(ENCHANTMENT_KEY);
        int pieces = 0;
        for (net.minecraft.world.item.ItemStack slot : armorSlots) {
            if (net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(holder, slot) > 0) {
                pieces++;
            }
        }
        float reduction = pieces * ENCHANT_REDUCTION;
        if (pieces == 4) {
            reduction += FULL_SET_ENCHANT_BONUS;
        }
        return Math.min(reduction, 1.0f);
    }

    /**
     * Build the Pressure Seal enchantment using the new Builder API.
     * This creates an Enchantment definition for armor slots.
     */
    public static Enchantment build() {
        // Get all items that can be enchanted (use all registered items as supported)
        var allItemHolders = BuiltInRegistries.ITEM.stream()
            .map(BuiltInRegistries.ITEM::wrapAsHolder)
            .toList();

        var supportedItems = HolderSet.direct(allItemHolders);

        var definition = Enchantment.definition(
            supportedItems,     // supported items
            1,                  // weight (rarity)
            1,                  // max level
            Enchantment.constantCost(10), // min cost
            Enchantment.constantCost(30), // max cost
            1,                  // anvil cost
            EquipmentSlotGroup.ARMOR  // applicable slots
        );

        return new Enchantment.Builder(definition).build(ENCHANTMENT_ID);
    }
}
