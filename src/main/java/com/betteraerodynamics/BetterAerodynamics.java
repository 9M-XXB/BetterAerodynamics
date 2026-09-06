package com.betteraerodynamics;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BetterAerodynamics implements ModInitializer {
	public static final String MOD_ID = "betteraerodynamics";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// Item Identifiers (kept for reference)
	public static final Identifier PRESSURE_SUIT_HELMET = id("atmosphere_suit_helmet");
	public static final Identifier PRESSURE_SUIT_CHESTPLATE = id("atmosphere_suit_chestplate");
	public static final Identifier PRESSURE_SUIT_LEGGINGS = id("atmosphere_suit_leggings");
	public static final Identifier PRESSURE_SUIT_BOOTS = id("atmosphere_suit_boots");
	public static final Identifier ENCHANTMENT_ID = id("pressure_seal");
	public static final Identifier CARBON_FIBER = id("carbon_fiber");
	public static final Identifier CARBON_FIBER_PLATE = id("carbon_fiber_plate");

	@Override
	public void onInitialize() {
		LOGGER.info("Better Aerodynamics initialized (aero + HUD only)!");

		com.betteraerodynamics.config.AeroConfig.load();

		/*
		// ======== COMMENTED: Items, Damage, Loot, CreativeTab ========

		var armorMaterial = new ArmorMaterial(5, java.util.Map.of(
			ArmorType.HELMET, 1, ArmorType.CHESTPLATE, 3,
			ArmorType.LEGGINGS, 2, ArmorType.BOOTS, 1),
			10, net.minecraft.sounds.SoundEvents.ARMOR_EQUIP_LEATHER,
			0.0f, 0.0f,
			net.minecraft.tags.ItemTags.REPAIRS_LEATHER_ARMOR,
			EquipmentAssets.LEATHER);

		pressureSuitHelmet = Registry.register(BuiltInRegistries.ITEM, PRESSURE_SUIT_HELMET,
			new Item(new Item.Properties().humanoidArmor(armorMaterial, ArmorType.HELMET)
				.setId(ResourceKey.create(Registries.ITEM, PRESSURE_SUIT_HELMET))));
		pressureSuitChestplate = Registry.register(...);
		pressureSuitLeggings = Registry.register(...);
		pressureSuitBoots = Registry.register(...);
		carbonFiberItem = Registry.register(...);
		carbonFiberPlateItem = Registry.register(...);

		registerCreativeTabs();
		registerLootInjection();

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			... pressure damage + water pressure handler ...
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, ...) -> {
			... /aerohud command ...
		});
		*/

		// /aerohud command — toggle the aero HUD display (persisted, same as settings screen)
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(Commands.literal("aerohud")
				.executes(ctx -> {
					boolean newState = !com.betteraerodynamics.config.AeroConfig.hudEnabled();
					com.betteraerodynamics.config.AeroConfig.setHudEnabled(newState);
					String msg = newState ? "HUD ON" : "HUD OFF";
					ctx.getSource().sendSuccess(() -> Component.literal("Aero HUD: " + msg), true);
					return 1;
				}));
		});

		LOGGER.info("Better Aerodynamics: elytra physics active!");
	}

	/*
	private void registerCreativeTabs() { ... }
	private void registerLootInjection() { ... }
	private DamageSource lowPressureDamage(ServerLevel world) { ... }
	private DamageSource highPressureDamage(ServerLevel world) { ... }
	*/

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
