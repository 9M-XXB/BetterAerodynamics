package com.betteraerodynamics.aero;

/**
 * Aerodynamic coefficient calculation engine.
 *
 * <p>Computes lift, drag, and moment coefficients using thin airfoil theory
 * with Prandtl-Glauert compressibility correction. Ported from AE416
 * aero_coeff.py / hess_smith.py (Matthew Clarke, UIUC).
 *
 * <p>Optimization: all common math constants pre-computed as static finals.
 */
public class AerodynamicsEngine {

    // === Pre-computed constants (no runtime Math.* calls in hot path) ===

    /** Speed of sound at sea level ISA, ft/s. (Reference only — use speedOfSound() for real-time.) */
    public static final double SPEED_OF_SOUND_SL = 1116.45;

    /** Gas constant R for air, ft·lbf/(slug·°R) — matches AtmosphereManager. */
    private static final double R_AIR = 1716.554;

    /** Specific heat ratio γ for air. */
    private static final double GAMMA = 1.4;

    /**
     * Compute speed of sound from temperature using a = √(γ·R·T).
     * @param temperatureR temperature in Rankine (°R)
     * @return speed of sound in ft/s
     */
    public static double speedOfSound(double temperatureR) {
        return Math.sqrt(GAMMA * R_AIR * temperatureR);
    }

    /** 1/π — avoids division on every drag polar call. */
    public static final double INV_PI = 1.0 / Math.PI;

    /** 1/(2π) — for influence coefficient calculations. */
    public static final double INV_2PI = 0.5 * INV_PI;

    /** Lift-curve slope for thin airfoil theory: 2π per radian. */
    public static final double CL_ALPHA_IDEAL = 2.0 * Math.PI;

    /** Default Oswald efficiency factor (straight wings). */
    public static final double DEFAULT_OSWALD = 0.80;

    /** Default parasitic drag coefficient. */
    public static final double DEFAULT_CD0 = 0.004;

    /** Typical quarter-chord moment coefficient for cambered airfoils. */
    public static final double DEFAULT_CM_QUARTER = -0.05;

    // === Lightweight result records ===

    /** 2D section coefficients. */
    public record AeroCoefficients(double cl, double cd, double cm, double prandtlGlauert) {}

    /** 3D wing coefficients (profile + induced drag). */
    public record WingCoefficients(double clWing, double cdWing, double cl, double cd) {}

    // === 2D Airfoil ===

    /** Thin airfoil lift coefficient: Cℓ = 2π·(α − α₀). */
    public static double sectionCl(double alpha, double alpha0) {
        return CL_ALPHA_IDEAL * (alpha - alpha0);
    }

    /**
     * 2D coefficients with Prandtl-Glauert compressibility correction.
     * Uses real-time speed of sound (from atmosphere temperature) for Mach calculation.
     * Valid for M &lt; 0.7 (subsonic). At M ≥ 0.7 returns uncorrected.
     *
     * @param speedOfSound speed of sound in ft/s (use {@link #speedOfSound(double)})
     */
    public static AeroCoefficients sectionCoeffs(double alpha, double alpha0, double velocity, double speedOfSound) {
        double mach = velocity / speedOfSound;
        double cl = sectionCl(alpha, alpha0);
        double pg = 1.0;

        if (mach > 0.0 && mach < 0.7) {
            pg = 1.0 / Math.sqrt(1.0 - mach * mach);
            cl *= pg;
        }

        return new AeroCoefficients(cl, DEFAULT_CD0, DEFAULT_CM_QUARTER, pg);
    }

    /** Legacy: uses sea-level speed of sound. Prefer {@link #sectionCoeffs(double,double,double,double)}. */
    public static AeroCoefficients sectionCoeffs(double alpha, double alpha0, double velocity) {
        return sectionCoeffs(alpha, alpha0, velocity, SPEED_OF_SOUND_SL);
    }

    /**
     * Parabolic drag polar: Cd = Cd₀ + Cℓ²/(π·e·AR).
     * Uses pre-computed INV_PI to avoid division.
     */
    public static double dragPolar(double cl, double cd0, double aspectRatio, double e) {
        return cd0 + cl * cl * INV_PI / (e * aspectRatio);
    }

    // === 3D Wing (lifting-line theory) ===

    /**
     * 2D→3D conversion: CL = Cℓ / (1 + Cℓ/(π·AR)), CDi = CL²/(π·e·AR).
     */
    public static WingCoefficients wingCoefficients(double cl, double cd, double aspectRatio, double e) {
        double clWing = cl / (1.0 + cl * INV_PI / aspectRatio);
        double cdInduced = clWing * clWing * INV_PI / (e * aspectRatio);
        return new WingCoefficients(clWing, cd + cdInduced, cl, cd);
    }

    // === Forces ===

    /** Dynamic pressure: q = ½·ρ·V². */
    public static double dynamicPressure(double density, double velocity) {
        return 0.5 * density * velocity * velocity;
    }

    /** Lift force: L = q·S·CL. */
    public static double liftForce(double q, double wingArea, double cl) {
        return q * wingArea * cl;
    }

    /** Lift force direct: L = ½·ρ·V²·S·CL. */
    public static double liftForce(double density, double velocity, double wingArea, double cl) {
        return dynamicPressure(density, velocity) * wingArea * cl;
    }

    /** Drag force: D = q·S·CD. */
    public static double dragForce(double q, double wingArea, double cd) {
        return q * wingArea * cd;
    }

    /** Drag force direct: D = ½·ρ·V²·S·CD. */
    public static double dragForce(double density, double velocity, double wingArea, double cd) {
        return dynamicPressure(density, velocity) * wingArea * cd;
    }

    /** Lift-to-drag ratio. */
    public static double liftToDragRatio(double cl, double cd) {
        return cd == 0.0 ? Double.POSITIVE_INFINITY : cl / cd;
    }
}
