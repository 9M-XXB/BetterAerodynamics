package com.betteraerodynamics;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.commands.Commands;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.ArmorMaterial;
import net.minecraft.world.item.equipment.ArmorType;
import net.minecraft.world.item.equipment.EquipmentAssets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BetterAerodynamics implements ModInitializer {
	public static final String MOD_ID = "betteraerodynamics";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// Item Identifiers
	public static final Identifier PRESSURE_SUIT_HELMET = id("atmosphere_suit_helmet");
	public static final Identifier PRESSURE_SUIT_CHESTPLATE = id("atmosphere_suit_chestplate");
	public static final Identifier PRESSURE_SUIT_LEGGINGS = id("atmosphere_suit_leggings");
	public static final Identifier PRESSURE_SUIT_BOOTS = id("atmosphere_suit_boots");
	public static final Identifier ENCHANTMENT_ID = id("pressure_seal");
	public static final Identifier CARBON_FIBER = id("carbon_fiber");
	public static final Identifier CARBON_FIBER_PLATE = id("carbon_fiber_plate");

	// Low air pressure damage
	public static final ResourceKey<DamageType> LOW_PRESSURE_DAMAGE_TYPE =
		ResourceKey.create(Registries.DAMAGE_TYPE, id("low_pressure"));
	/** Damage is applied once per this many ticks (20 = 1 s), sized in DPS. */
	private static final int DAMAGE_INTERVAL_TICKS = 20;

	// Registered item instances
	public static Item pressureSuitHelmet;
	public static Item pressureSuitChestplate;
	public static Item pressureSuitLeggings;
	public static Item pressureSuitBoots;
	public static Item carbonFiberItem;
	public static Item carbonFiberPlateItem;

	@Override
	public void onInitialize() {
		LOGGER.info("Better Aerodynamics initialized (aero + HUD + items)!");

		com.betteraerodynamics.config.AeroConfig.load();

		registerItems();
		registerCreativeTab();
		com.betteraerodynamics.recipe.AeroRecipes.register();

	/*
	// ======== STILL DISABLED (kept for later): water pressure damage + loot injection ========

	registerLootInjection();
	*/

	// Low air pressure damage — altitude sickness model. Threshold/ramp live in AeroConfig and
	// follow the active height conversion; pressure suit + Pressure Seal reductions are applied
	// inside AtmosphereManager.computeAtmosphereDamagePerSecond.
	ServerTickEvents.END_SERVER_TICK.register(server -> {
		if (!com.betteraerodynamics.config.AeroConfig.lowPressureDamage()) return;
		if (server.getTickCount() % DAMAGE_INTERVAL_TICKS != 0) return; // once per second

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player.isCreative() || player.isSpectator() || !player.isAlive()) continue;
			ServerLevel level = player.level();
			float dps = AtmosphereManager.computeAtmosphereDamagePerSecond(level, player);
			if (dps <= 0f) continue;
			DamageSource source = level.damageSources().source(LOW_PRESSURE_DAMAGE_TYPE);
			if (player.isInvulnerableTo(level, source)) continue;
			player.hurtServer(level, source, dps);
		}
	});

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

	/** Pressure suit + carbon fiber items (26.1.2: armor via Item.Properties#humanoidArmor). */
	private static void registerItems() {
		// Leather-grade stats: durability multiplier 5, defense 1/3/2/1, enchantability 10
		ArmorMaterial suit = new ArmorMaterial(5, java.util.Map.of(
			ArmorType.HELMET, 1, ArmorType.CHESTPLATE, 3,
			ArmorType.LEGGINGS, 2, ArmorType.BOOTS, 1),
			10, SoundEvents.ARMOR_EQUIP_LEATHER,
			0.0f, 0.0f,
			ItemTags.REPAIRS_LEATHER_ARMOR,
			EquipmentAssets.LEATHER);

		pressureSuitHelmet = Registry.register(BuiltInRegistries.ITEM, PRESSURE_SUIT_HELMET,
			new Item(new Item.Properties().humanoidArmor(suit, ArmorType.HELMET)
				.setId(ResourceKey.create(Registries.ITEM, PRESSURE_SUIT_HELMET))));
		pressureSuitChestplate = Registry.register(BuiltInRegistries.ITEM, PRESSURE_SUIT_CHESTPLATE,
			new Item(new Item.Properties().humanoidArmor(suit, ArmorType.CHESTPLATE)
				.setId(ResourceKey.create(Registries.ITEM, PRESSURE_SUIT_CHESTPLATE))));
		pressureSuitLeggings = Registry.register(BuiltInRegistries.ITEM, PRESSURE_SUIT_LEGGINGS,
			new Item(new Item.Properties().humanoidArmor(suit, ArmorType.LEGGINGS)
				.setId(ResourceKey.create(Registries.ITEM, PRESSURE_SUIT_LEGGINGS))));
		pressureSuitBoots = Registry.register(BuiltInRegistries.ITEM, PRESSURE_SUIT_BOOTS,
			new Item(new Item.Properties().humanoidArmor(suit, ArmorType.BOOTS)
				.setId(ResourceKey.create(Registries.ITEM, PRESSURE_SUIT_BOOTS))));
		carbonFiberItem = Registry.register(BuiltInRegistries.ITEM, CARBON_FIBER,
			new Item(new Item.Properties().setId(ResourceKey.create(Registries.ITEM, CARBON_FIBER))));
		carbonFiberPlateItem = Registry.register(BuiltInRegistries.ITEM, CARBON_FIBER_PLATE,
			new Item(new Item.Properties().setId(ResourceKey.create(Registries.ITEM, CARBON_FIBER_PLATE))));
	}

	/** Dedicated "Air Dynamics" creative tab so the items are findable in the creative inventory. */
	private static void registerCreativeTab() {
		CreativeModeTab tab = CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
			.title(Component.translatable("itemGroup.betteraerodynamics"))
			.icon(() -> new ItemStack(carbonFiberPlateItem))
			.displayItems((params, output) -> {
				output.accept(pressureSuitHelmet);
				output.accept(pressureSuitChestplate);
				output.accept(pressureSuitLeggings);
				output.accept(pressureSuitBoots);
				output.accept(carbonFiberItem);
				output.accept(carbonFiberPlateItem);
			})
			.build();
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB,
			ResourceKey.create(Registries.CREATIVE_MODE_TAB, id("air_dynamics")), tab);
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
