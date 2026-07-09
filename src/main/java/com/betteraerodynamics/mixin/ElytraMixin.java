package com.betteraerodynamics.mixin;

import com.betteraerodynamics.ElytraPhysics;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts vanilla elytra flight to replace with pure aerodynamic physics.
 */
@Mixin(LivingEntity.class)
public class ElytraMixin {

    /**
     * After vanilla computes elytra velocity, replace it entirely with aero forces.
     * Only fires for Player instances (server-side).
     */
    @Inject(method = "travelFallFlying", at = @At("TAIL"))
    private void replaceElytraPhysics(CallbackInfo ci) {
        LivingEntity self = (LivingEntity)(Object)this;
        if (!(self instanceof Player player)) return;
        if (!(player.level() instanceof ServerLevel)) return;

        ServerLevel world = (ServerLevel) player.level();
        if (player.isCreative() || player.isSpectator()) return;

        ElytraPhysics.applyToPlayer(world, player);
    }

    /**
     * Cancel damage knockback while elytra-flying.
     * Vanilla knockback would wipe out aero-calculated velocity.
     */
    @Inject(method = "knockback", at = @At("HEAD"), cancellable = true)
    private void cancelKnockbackWhileFlying(double strength, double x, double z, CallbackInfo ci) {
        LivingEntity self = (LivingEntity)(Object)this;
        if (self instanceof Player player && player.isFallFlying()) {
            ci.cancel();
        }
    }
}
