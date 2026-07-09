package com.betteraerodynamics.aero;

/**
 * NACA 4-Series airfoil profile generator.
 *
 * Ported from AE416 naca_4series_generator.py (Matthew Clarke, UIUC).
 * Generates upper and lower surface node coordinates.
 *
 * <p>Optimization: xx bunching array is pre-computed once at construction
 * and reused for thickness/camber/assembly, eliminating redundant
 * power/log computations.
 */
public class AirfoilProfile {

    public final double maxCamber;
    public final double camberPosition;
    public final double maxThickness;

    private final int nside;
    private final int nNodes;

    /** Pre-computed bunching x-coordinates (0 to 1, LE to TE). */
    private final double[] xx;

    public final double[] x;
    public final double[] y;
    public final double[] yc;
    public final double[] yt;

    // Pre-computed zero-lift angle (cached on construction)
    private final double zeroLiftAngle;

    public AirfoilProfile(String naca4, int npanel) {
        if (naca4.length() != 4) {
            throw new IllegalArgumentException("NACA 4-series requires exactly 4 digits, got: " + naca4);
        }
        if (npanel % 2 != 0) {
            throw new IllegalArgumentException("Number of panels must be even, got: " + npanel);
        }

        int n1 = charToInt(naca4.charAt(3));
        int n2 = charToInt(naca4.charAt(2));
        int n3 = charToInt(naca4.charAt(1));
        int n4 = charToInt(naca4.charAt(0));

        this.maxCamber = n4 / 100.0;
        this.camberPosition = n3 / 10.0;
        this.maxThickness = (n2 * 10 + n1) / 100.0;

        this.nside = npanel / 2 + 1;
        this.nNodes = npanel + 1;

        this.x = new double[nNodes];
        this.y = new double[nNodes];
        this.yc = new double[nside];
        this.yt = new double[nside];
        this.xx = new double[nside];

        // Pre-compute xx with cosine-like bunching (done once)
        double an = 1.5;
        double anp = an + 1.0;
        for (int i = 0; i < nside; i++) {
            double frac = (double) i / (nside - 1);
            xx[i] = 1.0 - anp * frac * Math.pow(1.0 - frac, an)
                       - Math.pow(1.0 - frac, anp);
        }

        generateProfile();
        this.zeroLiftAngle = -maxCamber * (1.0 + camberPosition);
    }

    private static int charToInt(char c) { return c - '0'; }

    private void generateProfile() {
        double m = maxCamber, p = camberPosition, t = maxThickness;

        for (int i = 0; i < nside; i++) {
            double xi = xx[i];  // cached lookup, no recompute

            // Thickness distribution (NACA 4-series polynomial)
            double sqrtXi = Math.sqrt(xi);
            double xi2 = xi * xi;
            yt[i] = (0.29690 * sqrtXi
                   - 0.12600 * xi
                   - 0.35160 * xi2
                   + 0.28430 * xi2 * xi
                   - 0.10150 * xi2 * xi2) * t / 0.20;

            // Camber line
            if (xi < p) {
                yc[i] = m / (p * p) * (2.0 * p * xi - xi2);
            } else {
                double oneMinusP = 1.0 - p;
                yc[i] = m / (oneMinusP * oneMinusP)
                        * (1.0 - 2.0 * p + 2.0 * p * xi - xi2);
            }
        }

        // Assemble surfaces: xx goes LE→TE, upper from LE→TE, lower from TE→LE
        for (int i = 0; i < nside; i++) {
            int upper = nside + i - 1;
            int lower = nside - i - 1;
            x[upper] = xx[i];
            x[lower] = xx[i];
            y[upper] = yc[i] + yt[i];
            y[lower] = yc[i] - yt[i];
        }
    }

    /** Mean camber line slope dyc/dx at x (lazy-computed, no caching needed). */
    public double camberSlope(double xi) {
        double p = camberPosition, m = maxCamber;
        if (xi < p) {
            return m / (p * p) * (2.0 * p - 2.0 * xi);
        } else {
            double oneMinusP = 1.0 - p;
            return m / (oneMinusP * oneMinusP) * (2.0 * p - 2.0 * xi);
        }
    }

    /** Pre-computed zero-lift angle in radians. */
    public double zeroLiftAngle() { return zeroLiftAngle; }

    /** Thin airfoil lift-curve slope: 2π per radian. */
    public static double liftCurveSlope() { return 2.0 * Math.PI; }

    public int panelCount() { return nNodes - 1; }

    @Override
    public String toString() {
        return String.format("NACA %d%d%d%d (m=%.1f%%, p=%.0f%%, t=%.0f%%)",
            (int)(maxCamber * 100), (int)(camberPosition * 10),
            (int)(maxThickness * 100) / 10, (int)(maxThickness * 100) % 10,
            maxCamber * 100, camberPosition * 10, maxThickness * 100);
    }
}
