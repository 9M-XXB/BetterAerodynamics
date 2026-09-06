package com.betteraerodynamics.aero;

import com.betteraerodynamics.AtmosphereManager;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.util.Mth;

/**
 * 3D wing physics — connects Minecraft entities to aerodynamic forces.
 *
 * <p>Computes lift and drag on a wing moving through the MC atmosphere.
 * Angle of attack is derived from the player's velocity relative to
 * their horizontal look direction.
 *
 * <p>Optimization: stall angle pre-converted to radians in WingConfig;
 * early zero-velocity bailout avoids all downstream computation.
 */
public class WingPhysics {

    // === Configuration (stall angle stored in radians) ===

    public record WingConfig(
        double wingArea,
        double aspectRatio,
        double oswaldEfficiency,
        double cd0,
        double clMax,
        /** Stall angle in RADIANS (pre-converted, no runtime toRadians). */
        double stallAngleRad,
        double maxLoad,
        /** Banked-turn gain: rad of bank per rad of heading error (look yaw vs velocity heading). */
        double bankGain,
        /** Maximum bank angle in RADIANS — lift tilt limit in turns. */
        double phiMaxRad
    ) {
        /** Default glider: 10 ft², AR=6. */
        public static final WingConfig GLIDER = new WingConfig(
            10.0, 6.0, 0.85, 0.008, 1.5, 0.261799, 500.0, 1.5, 0.872665  // 15° stall, 50° max bank
        );
        /** Small aircraft: 25 ft², AR=7. */
        public static final WingConfig SMALL_AIRCRAFT = new WingConfig(
            25.0, 7.0, 0.80, 0.012, 1.4, 0.244346, 1200.0, 1.5, 0.698132 // 14° stall, 40° max bank
        );
    }

    // === Result ===

    public record WingForces(
        double lift, double drag, double dynamicPressure,
        double angleOfAttack, double clWing, double cdWing,
        boolean stalled
    ) {}

    // === Computation ===

    /**
     * Compute wing forces from player flight state.
     * Hot path — called every tick for flying entities.
     */
    public static WingForces computeForces(ServerLevel world, Player player,
                                            WingConfig config, AirfoilProfile airfoil) {
        // Early exit: stationary → zero forces, zero GC
        double vx = player.getDeltaMovement().x;
        double vy = player.getDeltaMovement().y;
        double vz = player.getDeltaMovement().z;
        double v2 = vx * vx + vy * vy + vz * vz;

        if (v2 < 0.01) {
            return new WingForces(0, 0, 0, 0, 0, 0, false);
        }

        double velocity = Math.sqrt(v2);
        double density = AtmosphereManager.getDensity(world, player);

        // Angle of attack from velocity vs. horizontal look direction
        double yawRad = Math.toRadians(player.getYRot());
        double horizSpeed = vx * Math.sin(yawRad) + vz * Math.cos(yawRad);
        double alpha = Math.atan2(-vy, Math.abs(horizSpeed) + 0.001);
        alpha = Mth.clamp(alpha, -0.785398, 0.785398); // ±45° clamp (pre-computed)

        // Effective α with zero-lift correction
        double alpha0 = (airfoil != null) ? airfoil.zeroLiftAngle() : 0.0;
        double effAlpha = alpha - alpha0;

        // Stall check (uses pre-converted radian stall angle)
        boolean stalled = Math.abs(effAlpha) > config.stallAngleRad;
        double cl;
        if (stalled) {
            cl = Math.signum(effAlpha) * config.clMax * 0.7;
        } else {
            double temperatureR = AtmosphereManager.getTemperature(world, player);
            double speedOfSound = AerodynamicsEngine.speedOfSound(temperatureR);
            cl = AerodynamicsEngine.sectionCoeffs(effAlpha, 0, velocity, speedOfSound).cl();
        }
        cl = Mth.clamp(cl, -config.clMax, config.clMax);

        // 3D wing conversion
        AerodynamicsEngine.WingCoefficients wing =
            AerodynamicsEngine.wingCoefficients(
                cl, config.cd0, config.aspectRatio, config.oswaldEfficiency);

        // Forces: L/D = ½ρV²·S·CL/CD
        double q = 0.5 * density * velocity * velocity;
        double lift = q * config.wingArea * wing.clWing();
        double drag = q * config.wingArea * wing.cdWing();

        if (lift > config.maxLoad) lift = config.maxLoad;

        return new WingForces(lift, drag, q, effAlpha, wing.clWing(), wing.cdWing(), stalled);
    }

    /** Compute with default symmetric airfoil (α₀ = 0). */
    public static WingForces computeForces(ServerLevel world, Player player, WingConfig config) {
        return computeForces(world, player, config, null);
    }

    /**
     * Compute wing forces using Hess-Smith panel method with boundary layer.
     *
     * <p>Pipeline:
     * <ol>
     *   <li>Boundary layer → displacement thickness δ*(x)</li>
     *   <li>Airfoil shape displaced by δ* → effective shape</li>
     *   <li>Hess-Smith panel method on effective shape → Cl, Cd_pressure</li>
     *   <li>Cd_total = Cd_pressure + BL skin friction (totalCf)</li>
     *   <li>3D wing conversion + forces = ½ρV²·S·CL/CD</li>
     * </ol>
     */
    public static WingForces computeForcesWithBL(ServerLevel world, Player player,
                                                  WingConfig config, AirfoilProfile airfoil,
                                                  BoundaryLayer.BLResult bl, double chord) {
        // Early exit
        double vx = player.getDeltaMovement().x;
        double vy = player.getDeltaMovement().y;
        double vz = player.getDeltaMovement().z;
        double v2 = vx * vx + vy * vy + vz * vz;
        if (v2 < 0.01) return new WingForces(0, 0, 0, 0, 0, 0, false);

        double velocity = Math.sqrt(v2);
        double density = AtmosphereManager.getDensity(world, player);

        // Angle of attack
        double yawRad = Math.toRadians(player.getYRot());
        double horizSpeed = vx * Math.sin(yawRad) + vz * Math.cos(yawRad);
        double alpha = Math.atan2(-vy, Math.abs(horizSpeed) + 0.001);
        alpha = Mth.clamp(alpha, -0.785398, 0.785398);

        // Hess-Smith panel method on BL-displaced airfoil
        HessSmith.PanelResult panel = HessSmith.computeWithBL(airfoil, alpha, bl, chord);

        // Cl from panel method, Cd = pressure drag + skin friction
        double cl2d = panel.cl();
        double cd2d = panel.cd() + Math.max(bl.totalCf(), 0.003);

        // Compressibility correction
        double temperatureR = AtmosphereManager.getTemperature(world, player);
        double mach = velocity / AerodynamicsEngine.speedOfSound(temperatureR);
        if (mach > 0.0 && mach < 0.7) {
            double pg = 1.0 / Math.sqrt(1.0 - mach * mach);
            cl2d *= pg;
        }
        cl2d = Mth.clamp(cl2d, -config.clMax(), config.clMax());

        // 3D wing conversion
        AerodynamicsEngine.WingCoefficients wing = AerodynamicsEngine.wingCoefficients(
            cl2d, cd2d, config.aspectRatio(), config.oswaldEfficiency());

        double q = 0.5 * density * velocity * velocity;
        double lift = q * config.wingArea() * wing.clWing();
        double drag = q * config.wingArea() * wing.cdWing();

        boolean stalled = Math.abs(alpha) > config.stallAngleRad()
                       || bl.H[bl.H.length - 1] > 2.4;

        return new WingForces(lift, drag, q, alpha, wing.clWing(), wing.cdWing(), stalled);
    }
}
