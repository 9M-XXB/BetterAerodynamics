package com.betteraerodynamics.mixin;

import com.betteraerodynamics.ElytraPhysics;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public class ElytraMixin {

    @Inject(method = "travelFallFlying", at = @At("HEAD"), cancellable = true)
    private void replaceElytraPhysics(CallbackInfo ci) {
        LivingEntity self = (LivingEntity)(Object)this;

        // Only take over for survival/adventure players on the server;
        // everything else (mobs, creative, client prediction) keeps vanilla.
        if (!(self instanceof Player player)) return;
        if (!(player.level() instanceof ServerLevel world)) return;
        if (player.isCreative() || player.isSpectator()) return;

        if (ElytraPhysics.applyToPlayer(world, player)) {
            self.move(MoverType.SELF, self.getDeltaMovement());
            ci.cancel();
        }
    }

    @Inject(method = "knockback", at = @At("HEAD"), cancellable = true)
    private void cancelKnockbackWhileFlying(double strength, double x, double z, CallbackInfo ci) {
        LivingEntity self = (LivingEntity)(Object)this;
        if (self instanceof Player player && player.isFallFlying()) {
            ci.cancel();
        }
    }
}