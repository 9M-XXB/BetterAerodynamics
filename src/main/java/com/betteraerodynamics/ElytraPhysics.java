package com.betteraerodynamics;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import com.betteraerodynamics.aero.AerodynamicsEngine;
import com.betteraerodynamics.aero.AirfoilProfile;
import com.betteraerodynamics.aero.BoundaryLayer;
import com.betteraerodynamics.aero.HessSmith;
import com.betteraerodynamics.aero.WingPhysics;
import com.betteraerodynamics.config.AeroConfig;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges aerodynamics into Minecraft elytra flight.
 *
 * <p>Optimization: Hess-Smith panel method is expensive (~O(n³)).
 * Instead of running it every tick, we precompute a Cl(α) / Cd(α)
 * curve when boundary layer changes, then interpolate at runtime.
 */
public class ElytraPhysics {

    private static final Logger LOG = LoggerFactory.getLogger("betteraero-elytra");

    // Wing
    private static final WingPhysics.WingConfig CONFIG = new WingPhysics.WingConfig(
        15.0, 5.0, 0.75, 0.010, 1.3, 0.261799, 800.0, 1.5, 0.872665);  // 50° max bank
    /** Rebuilt from {@link AeroConfig#naca4()} whenever the user changes it (default NACA 2412). */
    private static AirfoilProfile AIRFOIL = new AirfoilProfile(AeroConfig.DEFAULT_NACA, 80);
    private static String currentNaca = AeroConfig.DEFAULT_NACA;
    private static final double MIN_RE = 10_000;
    private static final double GRAVITY_LBF = 200.0;
    private static final double LBM_TO_KG = 0.453592;
    private static final double PLAYER_MASS_KG = GRAVITY_LBF * LBM_TO_KG;
    private static final double LBF_TO_N = 4.44822;
    private static final double LBF_TO_MC = LBF_TO_N / (PLAYER_MASS_KG * 20 * 20);  // convert to Minecraft units (m/tick²)
    private static final double CHORD = Math.sqrt(15.0 / 5.0);
    private static final double RIGGING_ANGLE = 0.10;

    // BL cache
    private static final int BL_TICKS = 10;
    private static BoundaryLayer.BLResult cachedBL;
    private static double cachedAlphaBL;
    private static int tickCount;

    // Cl/Cd curve cache: precomputed at 26 α points (-25°..+25° step 2°)
    private static final double[] ALPHA_SWEEP = {
        -0.436, -0.401, -0.366, -0.332, -0.297, -0.262, -0.227, -0.192,
        -0.157, -0.122, -0.087, -0.052, -0.017,
         0.017, 0.052, 0.087, 0.122, 0.157, 0.192, 0.227,
         0.262, 0.297, 0.332, 0.366, 0.401, 0.436
    };
    private static double[] clCurve, cdCurve;
    private static boolean curveDirty = true;

    // HUD
    public static double lastLiftLbf, lastDragLbf;
    public static boolean lastStalled;
    public static double lastSpeedFtS, lastCd0;
    public static double lastBankDeg, lastTurnRateDps;

    // Smoother for display speed (damps oscillations)
    private static double smoothSpeedFtS;

    // --- Banked lift: turn dynamics (per-player roll state) ---
    private static final Map<UUID, TurnState> BANK_BY_PLAYER = new WeakHashMap<>();
    /** Fraction of commanded bank applied per tick (~0.15 s roll time constant at 20 tps). */
    private static final double BANK_EMA = 0.30;
    /** Min horizontal speed (blocks/tick, ≈2 ft/s) below which the wings are leveled. */
    private static final double MIN_HEADING_SPEED = 0.03;

    /** Per-player banked-turn state: roll attitude + turn-rate estimate. */
    private static final class TurnState {
        double bank;                      // smoothed bank angle, rad
        double prevHeading = Double.NaN;  // velocity heading last tick, rad (MC yaw convention)
        double turnRateDps;               // smoothed heading change rate, deg/s
    }

    public static boolean applyToPlayer(ServerLevel world, Player player) {
        if (!player.isFallFlying()) {
            lastLiftLbf = lastDragLbf = lastSpeedFtS = 0;
            lastStalled = false;
            lastBankDeg = lastTurnRateDps = 0;
            BANK_BY_PLAYER.remove(player.getUUID());
            return false;
        }

        Vec3 vel = player.getDeltaMovement();
        double rawSpeed = vel.length() * 3.28084 * 20.0;  // real SI conversion
        smoothSpeedFtS = smoothSpeedFtS * 0.85 + rawSpeed * 0.15;  // EMA smoothing
        double speedFtS = rawSpeed;  // use raw for aero (immediate response)

        // Skip if Reynolds number too low (BL won't converge)
        double tR0 = AtmosphereManager.getTemperature(world, player);
        double nu0 = BoundaryLayer.kinematicViscosity(tR0);
        double re = speedFtS * CHORD / Math.max(nu0, 1e-12);
        if (re < MIN_RE) {
            if (tickCount % 20 == 0) LOG.info("SKIP low Re={} spd={}ft/s", (int)re, (int)speedFtS);
            lastLiftLbf = lastDragLbf = lastSpeedFtS = 0;
            lastBankDeg = lastTurnRateDps = 0;
            BANK_BY_PLAYER.remove(player.getUUID());
            smoothSpeedFtS = 0;
            return false;
        }

        // α = velocity pitch − look pitch (angle between wing chord and airflow)
        double horiz = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        double velPitch  = Math.atan2(-vel.y, Math.max(horiz, 1e-6));
        double lookPitch = Math.asin(-player.getLookAngle().y);
        double alpha = velPitch - lookPitch + RIGGING_ANGLE; 
        alpha = Mth.clamp(alpha, -1.4, 1.4);                        // hard sanity bound, ~±80°

        // --- Airfoil swap: rebuild profile and invalidate caches when the NACA setting changes ---
        String naca = AeroConfig.naca4();
        if (!naca.equals(currentNaca)) {
            AIRFOIL = new AirfoilProfile(naca, 80);
            currentNaca = naca;
            cachedBL = null;
            clCurve = null;
            cdCurve = null;
            curveDirty = true;
            LOG.info("Airfoil switched to NACA {}", naca);
        }

        // --- BL update (periodic or on large α change) ---
        tickCount++;
        if (tickCount % BL_TICKS == 0 || cachedBL == null
                || Math.abs(alpha - cachedAlphaBL) > 0.04) {
            double tR = AtmosphereManager.getTemperature(world, player);
            double nu = BoundaryLayer.kinematicViscosity(tR);
            cachedBL = BoundaryLayer.solve(AIRFOIL, CHORD, speedFtS, nu, alpha);
            cachedAlphaBL = alpha;
            curveDirty = true;
        }

        // --- Cl/Cd curve regeneration (only when BL changes) ---
        if (curveDirty && cachedBL != null) {
            clCurve = new double[ALPHA_SWEEP.length];
            cdCurve = new double[ALPHA_SWEEP.length];
            for (int i = 0; i < ALPHA_SWEEP.length; i++) {
                HessSmith.PanelResult pr = HessSmith.computeWithBL(
                    AIRFOIL, ALPHA_SWEEP[i], cachedBL, CHORD);
                clCurve[i] = pr.cl();
                //cdCurve[i] = pr.cd() + Math.max(cachedBL.totalCf(), 0.003);
                cdCurve[i] = pr.cd() + cachedBL.totalCf();
            }
            curveDirty = false;
        }

        // --- Fast lookup: linear interpolation on precomputed curve ---
        double cl2d, cd2d;
        if (clCurve != null) {
            cl2d = interpolate(ALPHA_SWEEP, clCurve, alpha);
            cd2d = interpolate(ALPHA_SWEEP, cdCurve, alpha);
        } else {
            // Fallback: thin airfoil theory
            double a0 = AIRFOIL.zeroLiftAngle();
            cl2d = 2.0 * Math.PI * (alpha - a0);
            cd2d = 0.01;
        }

        // Compressibility (real-time speed of sound from ISA temperature)
        double tR = AtmosphereManager.getTemperature(world, player);
        double mach = speedFtS / AerodynamicsEngine.speedOfSound(tR);
        if (mach > 0 && mach < 0.7) cl2d /= Math.sqrt(1 - mach * mach);
        cl2d = Mth.clamp(cl2d, -CONFIG.clMax(), CONFIG.clMax());

        // Stall: BL separation only (H > 2.4 at trailing edge)
        boolean stalled = cachedBL != null
            && cachedBL.H[cachedBL.H.length - 1] > 2.4;

        // Post-stall: blend model coefficients toward flat-plate behavior
        double absA = Math.abs(alpha);
        if (absA > CONFIG.stallAngleRad()) {
                // how far past stall we are, 0 → just stalled, 1 → fully separated (~2× stall angle)
                double t = Mth.clamp((absA - CONFIG.stallAngleRad()) / CONFIG.stallAngleRad(), 0.0, 1.0);

                double clFlat = Math.sin(alpha) * Math.cos(alpha) * 2.0;   // flat-plate lift
                double cdFlat = 2.0 * Math.sin(absA) * Math.sin(absA);     // flat-plate drag

                cl2d = cl2d * (1.0 - t) + clFlat * t;
                cd2d = cd2d * (1.0 - t) + cdFlat * t;
        }
        // 3D wing
        var w = com.betteraerodynamics.aero.AerodynamicsEngine.wingCoefficients(
            cl2d, cd2d, CONFIG.aspectRatio(), CONFIG.oswaldEfficiency());

        double rho = AtmosphereManager.getDensity(world, player);
        double q = 0.5 * rho * speedFtS * speedFtS;
        double lift = q * CONFIG.wingArea() * w.clWing();
        double drag = q * CONFIG.wingArea() * w.cdWing();

        // Apply
        lastLiftLbf = lift; lastDragLbf = drag; lastStalled = stalled;
        //lastSpeedFtS = smoothSpeedFtS;  // HUD gets smoothed speed
        lastSpeedFtS = rawSpeed;
        lastCd0 = cd2d;

        if (tickCount % 20 == 0) LOG.info("FORCE L={} D={}lbf spd={}ft/s alpha={}", (int)lift, (int)drag, (int)speedFtS, String.format("%.2f", alpha));

        // --- Apply forces in wind axes ---
        Vec3 curVel = vel;
        double speed = curVel.length();

        if (speed > 1e-4) {
            Vec3 vHat = curVel.scale(1.0 / speed);

            Vec3 liftHat;
            if (curVel.x * curVel.x + curVel.z * curVel.z > 1e-8) {
                Vec3 side = vHat.cross(new Vec3(0, 1, 0)).normalize();
                liftHat = side.cross(vHat).normalize();
            } else {
                liftHat = new Vec3(0, 0, 1);   // pure vertical fall: degenerate case
            }

            // --- Banked lift: bank follows the look-vs-heading error, tilting lift around v̂ ---
            double phi = 0;
            TurnState st = BANK_BY_PLAYER.computeIfAbsent(player.getUUID(), k -> new TurnState());
            if (horiz > MIN_HEADING_SPEED) {
                double velYaw = Math.atan2(-curVel.x, curVel.z);   // velocity heading, MC yaw convention (rad)
                double beta = wrapPi(Math.toRadians(player.getYRot()) - velYaw);
                double phiTarget = Mth.clamp(CONFIG.bankGain() * beta,
                    -CONFIG.phiMaxRad(), CONFIG.phiMaxRad());
                phi = st.bank + (phiTarget - st.bank) * BANK_EMA;  // roll inertia
                st.bank = phi;

                if (!Double.isNaN(st.prevHeading)) {
                    double dps = Math.toDegrees(wrapPi(velYaw - st.prevHeading)) * 20.0;
                    st.turnRateDps = st.turnRateDps * 0.8 + dps * 0.2;
                }
                st.prevHeading = velYaw;
                lastTurnRateDps = st.turnRateDps;

                if (Math.abs(phi) > 1e-4) {
                    // Rodrigues (lift ⊥ v̂): lift' = lift·cosφ + (v̂×lift)·sinφ
                    // positive φ tilts lift toward the right of motion → clockwise turn
                    Vec3 right = vHat.cross(liftHat);
                    liftHat = liftHat.scale(Math.cos(phi)).add(right.scale(Math.sin(phi))).normalize();
                }
            } else {
                phi = st.bank * 0.8;                               // no usable heading: relax bank
                st.bank = phi;
                st.prevHeading = Double.NaN;
                lastTurnRateDps = 0;
            }
            lastBankDeg = Math.toDegrees(phi);

            Vec3 accel = liftHat.scale(lift * LBF_TO_MC)
                     .add(vHat.scale(-drag * LBF_TO_MC))
                     .add(0, -GRAVITY_LBF * LBF_TO_MC, 0);

            Vec3 newVel = curVel.add(accel);

            player.setDeltaMovement(newVel);
        } else {
            // no airspeed: gravity only
            player.setDeltaMovement(curVel.add(0, -GRAVITY_LBF * LBF_TO_MC, 0));
        }
    if (player instanceof net.minecraft.server.level.ServerPlayer sp) sp.hurtMarked = true;
    return true;
    }

    /** Linear interpolation on sorted arrays. */
    private static double interpolate(double[] xs, double[] ys, double x) {
        if (x <= xs[0]) return ys[0];
        if (x >= xs[xs.length - 1]) return ys[ys.length - 1];
        int i = 0;
        while (i < xs.length - 1 && xs[i + 1] < x) i++;
        double t = (x - xs[i]) / (xs[i + 1] - xs[i]);
        return ys[i] + t * (ys[i + 1] - ys[i]);
    }

    /** Wrap an angle to (−π, π]. */
    private static double wrapPi(double a) {
        return Math.atan2(Math.sin(a), Math.cos(a));
    }
}
