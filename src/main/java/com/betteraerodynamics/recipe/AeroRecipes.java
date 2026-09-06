package com.betteraerodynamics.recipe;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;

import com.betteraerodynamics.BetterAerodynamics;

/**
 * Custom crafting recipe type (26.1.2): {@code betteraerodynamics:seal} —
 * combines a gear piece (diamond armor / elytra) with a pressure-suit piece;
 * keeps every component of the gear (existing enchantments, damage, ...) and
 * merges the Pressure Seal enchantment in, instead of letting the result's
 * enchantment component wipe the original ones.
 */
public final class AeroRecipes {

	private static final RecipeSerializer<SealRecipe> SEAL = new RecipeSerializer<>(
		SealRecipe.MAP_CODEC, SealRecipe.STREAM_CODEC);

	public static void register() {
		Registry.register(BuiltInRegistries.RECIPE_SERIALIZER,
			ResourceKey.create(Registries.RECIPE_SERIALIZER, BetterAerodynamics.id("seal")), SEAL);
	}

	private AeroRecipes() {}

	/** Gear piece + suit piece → same gear with Pressure Seal merged in. */
	public static class SealRecipe extends CustomRecipe {
		public static final MapCodec<SealRecipe> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			Ingredient.CODEC.fieldOf("material").forGetter(r -> r.material),
			Ingredient.CODEC.fieldOf("seal_source").forGetter(r -> r.sealSource),
			Enchantment.CODEC.fieldOf("enchantment").forGetter(r -> r.seal)
		).apply(i, SealRecipe::new));
		public static final StreamCodec<RegistryFriendlyByteBuf, SealRecipe> STREAM_CODEC = StreamCodec.composite(
			Ingredient.CONTENTS_STREAM_CODEC, r -> r.material,
			Ingredient.CONTENTS_STREAM_CODEC, r -> r.sealSource,
			Enchantment.STREAM_CODEC, r -> r.seal,
			SealRecipe::new);

		private final Ingredient material;
		private final Ingredient sealSource;
		private final Holder<Enchantment> seal;

		private SealRecipe(Ingredient material, Ingredient sealSource, Holder<Enchantment> seal) {
			this.material = material;
			this.sealSource = sealSource;
			this.seal = seal;
		}

		@Override
		public boolean matches(CraftingInput input, Level level) {
			boolean hasMaterial = false, hasSource = false;
			for (int i = 0; i < input.size(); i++) {
				ItemStack stack = input.getItem(i);
				if (stack.isEmpty()) continue;
				if (material.test(stack)) {
					if (hasMaterial) return false;
					hasMaterial = true;
				} else if (sealSource.test(stack)) {
					if (hasSource) return false;
					hasSource = true;
				} else {
					return false;
				}
			}
			return hasMaterial && hasSource;
		}

		@Override
		public ItemStack assemble(CraftingInput input) {
			for (int i = 0; i < input.size(); i++) {
				ItemStack stack = input.getItem(i);
				if (stack.isEmpty() || !material.test(stack)) continue;
				ItemStack out = stack.copy();
				ItemEnchantments existing = out.get(DataComponents.ENCHANTMENTS);
				ItemEnchantments.Mutable merged = new ItemEnchantments.Mutable(
					existing != null ? existing : ItemEnchantments.EMPTY);
				// Seal at level 1, never downgrading an existing stronger seal
				merged.set(seal, Math.max(1, merged.getLevel(seal)));
				out.set(DataComponents.ENCHANTMENTS, merged.toImmutable());
				return out;
			}
			return ItemStack.EMPTY;
		}

		@Override
		public CraftingBookCategory category() {
			return CraftingBookCategory.EQUIPMENT;
		}

		@Override
		public RecipeSerializer<SealRecipe> getSerializer() {
			return SEAL;
		}
	}
}
