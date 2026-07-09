package com.betteraerodynamics.aero;

/**
 * Boundary layer integral solver — Thwaites (laminar) + Michel criterion +
 * Head's method (turbulent). Ported from AE416 HW7/HW8 Python.
 *
 * <p>Computes skin friction distribution along an airfoil chord given
 * the external velocity distribution, chord Reynolds number, and
 * kinematic viscosity.
 *
 * <p>Usage:
 * <pre>
 *   BoundaryLayer.Result bl = BoundaryLayer.solve(
 *       airfoil, chordFt, velocityFtS, viscosityFt2S, alphaRad);
 *   double totalCf = bl.totalCf();   // integrated skin friction
 *   double deltaTe = bl.deltaTE();   // BL thickness at trailing edge
 * </pre>
 */
public class BoundaryLayer {

    // === Result record ===

    public static class BLResult {
        public final double[] x;           // chord station (0..1)
        public final double[] delta;       // BL thickness
        public final double[] deltaStar;   // displacement thickness
        public final double[] theta;       // momentum thickness
        public final double[] cf;          // local skin friction coefficient
        public final double[] H;           // shape factor
        public final double[] Ve;          // external velocity at each station
        public final int transitionIndex;  // first turbulent station (-1 if none)
        public final boolean transitioned;

        BLResult(int n) {
            x = new double[n]; delta = new double[n]; deltaStar = new double[n];
            theta = new double[n]; cf = new double[n]; H = new double[n];
            Ve = new double[n];
            transitionIndex = -1; transitioned = false;
        }

        BLResult(BLResult lam, BLResult turb, int transIdx) {
            int nLam = transIdx, nTurb = turb.x.length, n = nLam + nTurb;
            x = new double[n]; delta = new double[n]; deltaStar = new double[n];
            theta = new double[n]; cf = new double[n]; H = new double[n]; Ve = new double[n];
            System.arraycopy(lam.x, 0, x, 0, nLam);
            System.arraycopy(turb.x, 0, x, nLam, nTurb);
            System.arraycopy(lam.delta, 0, delta, 0, nLam);
            System.arraycopy(turb.delta, 0, delta, nLam, nTurb);
            System.arraycopy(lam.deltaStar, 0, deltaStar, 0, nLam);
            System.arraycopy(turb.deltaStar, 0, deltaStar, nLam, nTurb);
            System.arraycopy(lam.theta, 0, theta, 0, nLam);
            System.arraycopy(turb.theta, 0, theta, nLam, nTurb);
            System.arraycopy(lam.cf, 0, cf, 0, nLam);
            System.arraycopy(turb.cf, 0, cf, nLam, nTurb);
            System.arraycopy(lam.H, 0, H, 0, nLam);
            System.arraycopy(turb.H, 0, H, nLam, nTurb);
            System.arraycopy(lam.Ve, 0, Ve, 0, nLam);
            System.arraycopy(turb.Ve, 0, Ve, nLam, nTurb);
            transitionIndex = nLam;
            transitioned = true;
        }

        /** Total skin friction coefficient integrated over chord. */
        public double totalCf() {
            double sum = 0;
            for (int i = 1; i < x.length; i++) {
                sum += 0.5 * (cf[i] + cf[i-1]) * (x[i] - x[i-1]);
            }
            return sum;
        }

        /** Boundary layer thickness at trailing edge. */
        public double deltaTE() { return delta[delta.length - 1]; }
    }

    // === Constants ===

    /** Number of chordwise stations for BL computation. */
    private static final int N_POINTS = 81;

    /** Kinematic viscosity at sea level ISA, ft²/s. */
    public static final double NU_SL = 1.572e-4;

    /**
     * Compute kinematic viscosity from temperature using Sutherland's law
     * for air. μ ∝ T^(3/2) / (T + 110.4).  Then ν = μ/ρ.
     * Simplified: ν(T) = ν_SL * (T/T0)^1.5 with reasonable accuracy.
     */
    public static double kinematicViscosity(double temperatureR) {
        double ratio = temperatureR / 518.67; // T / T_SL
        return NU_SL * ratio * Math.sqrt(ratio);
    }

    // === Main solver ===

    /**
     * Run the full boundary layer pipeline on an airfoil at given conditions.
     *
     * @param airfoil  airfoil profile (for velocity distribution estimate)
     * @param chordFt  chord length in feet
     * @param vInfFtS  freestream velocity in ft/s
     * @param nuFt2S   kinematic viscosity in ft²/s
     * @param alphaRad angle of attack in radians
     */
    public static BLResult solve(AirfoilProfile airfoil, double chordFt,
                                  double vInfFtS, double nuFt2S, double alphaRad) {
        int n = N_POINTS;
        double[] x = new double[n];
        double[] Ve = new double[n];

        // Chordwise stations with leading-edge bunching
        for (int i = 0; i < n; i++) {
            double frac = (double) i / (n - 1);
            x[i] = 1e-12 + chordFt * frac;
            // Approximate external velocity: V∞ * (1 + camber slope effect)
            // Simplified from full panel method — uses camber slope
            double xi = frac;
            double dycDx = airfoil.camberSlope(xi);
            Ve[i] = vInfFtS * (1.0 + 0.3 * dycDx * Math.cos(alphaRad));
            Ve[i] = Math.max(Ve[i], 0.01 * vInfFtS);
        }

        // dVe/dx via central differences
        double[] dVe = new double[n];
        dVe[0] = (Ve[1] - Ve[0]) / (x[1] - x[0]);
        for (int i = 1; i < n - 1; i++) {
            dVe[i] = (Ve[i+1] - Ve[i-1]) / (x[i+1] - x[i-1]);
        }
        dVe[n-1] = (Ve[n-1] - Ve[n-2]) / (x[n-1] - x[n-2]);

        // --- Laminar: Thwaites method ---
        BLResult lam = solveLaminar(x, Ve, dVe, nuFt2S);

        // --- Transition: Michel criterion ---
        int transIdx = michelCriterion(lam, nuFt2S);
        if (transIdx < 0 || transIdx >= n - 5) {
            return lam; // fully laminar
        }

        // --- Turbulent: Head's method ---
        BLResult turb = solveTurbulent(x, Ve, dVe, nuFt2S, lam, transIdx);

        return new BLResult(lam, turb, transIdx);
    }

    // === Laminar: Thwaites method ===

    private static BLResult solveLaminar(double[] x, double[] Ve, double[] dVe, double nu) {
        int n = x.length;
        BLResult r = new BLResult(n);

        // ODE: d(θ²·Ve⁶)/dx = 0.45·ν·Ve⁵
        // Integrate via simple forward Euler
        double theta2Ve6 = 1e-20; // θ²·Ve⁶ at leading edge (≈0)
        r.theta[0] = Math.sqrt(theta2Ve6) / Math.pow(Math.max(Ve[0], 1e-6), 3);

        for (int i = 0; i < n; i++) {
            // θ from integrated quantity
            r.theta[i] = Math.sqrt(Math.max(theta2Ve6, 1e-30)) / Math.pow(Math.max(Ve[i], 1e-6), 3);

            // Thwaites λ parameter
            double lambda = r.theta[i] * r.theta[i] * dVe[i] / Math.max(nu, 1e-12);

            // Shape factor H(λ) — semi-empirical correlation
            r.H[i] = shapeFactorLaminar(lambda);

            // Skin friction cf(λ, Re_θ)
            double ReTheta = Ve[i] * r.theta[i] / Math.max(nu, 1e-12);
            r.cf[i] = skinFrictionLaminar(lambda, ReTheta);

            // Displacement thickness
            r.deltaStar[i] = r.H[i] * r.theta[i];

            // BL thickness (laminar flat-plate approximation)
            double ReX = Ve[i] * x[i] / Math.max(nu, 1e-12);
            r.delta[i] = Math.max(5.2 * x[i] / Math.sqrt(Math.max(ReX, 1.0)), r.deltaStar[i]);

            // Copy
            r.x[i] = x[i];
            r.Ve[i] = Ve[i];

            // Euler step for next station
            if (i < n - 1) {
                double dydx = 0.45 * nu * Math.pow(Ve[i], 5);
                double dx = x[i+1] - x[i];
                theta2Ve6 += dydx * dx;
                theta2Ve6 = Math.max(theta2Ve6, 1e-30);
            }
        }
        return r;
    }

    /** Shape factor for laminar BL: H = 0.0731/(0.14+λ) + 2.088 (λ<0); H = 2.61-3.75λ+5.24λ² (λ>0). */
    static double shapeFactorLaminar(double lambda) {
        if (lambda > 0) {
            return 2.61 - 3.75 * lambda + 5.24 * lambda * lambda;
        } else {
            return 0.0731 / (0.14 + lambda) + 2.088;
        }
    }

    /** Laminar cf: l = 0.22+1.402λ+0.018λ/(0.107+λ); cf = 2l/Re_θ. */
    static double skinFrictionLaminar(double lambda, double ReTheta) {
        double l;
        if (lambda > 0) {
            l = 0.22 + 1.57 * lambda - 1.8 * lambda * lambda;
        } else {
            l = 0.22 + 1.402 * lambda + 0.018 * lambda / (0.107 + lambda);
        }
        return 2.0 * l / Math.max(ReTheta, 1.0);
    }

    // === Transition: Michel criterion ===

    /** Michel criterion: Re_θ > 1.174·(1 + 22400/Re_x)·Re_x^0.46. Returns transition index or -1. */
    private static int michelCriterion(BLResult lam, double nu) {
        for (int i = 0; i < lam.x.length; i++) {
            double ReX = lam.Ve[i] * lam.x[i] / Math.max(nu, 1e-15);
            double ReTheta = lam.Ve[i] * lam.theta[i] / Math.max(nu, 1e-15);
            double crit = 1.174 * (1.0 + 22400.0 / Math.max(ReX, 1.0)) * Math.pow(ReX, 0.46);
            if (ReTheta > crit) {
                return i;
            }
        }
        return -1;
    }

    // === Turbulent: Head's method ===

    private static BLResult solveTurbulent(double[] xFull, double[] VeFull, double[] dVeFull,
                                            double nu, BLResult lam, int transIdx) {
        int nFull = xFull.length;
        int n = nFull - transIdx;
        BLResult r = new BLResult(n);

        // Initial conditions from laminar end
        double theta0 = lam.theta[transIdx];
        double H0 = lam.H[transIdx];
        double deltaStar0 = lam.deltaStar[transIdx];
        double delta0 = lam.delta[transIdx];
        double H1_0 = (delta0 - deltaStar0) / Math.max(theta0, 1e-12);

        // Extract turbulent segment
        double[] x = new double[n], Ve = new double[n], dVe = new double[n];
        System.arraycopy(xFull, transIdx, x, 0, n);
        System.arraycopy(VeFull, transIdx, Ve, 0, n);
        System.arraycopy(dVeFull, transIdx, dVe, 0, n);

        // Initialize arrays
        double[] theta = new double[n], H = new double[n], H1 = new double[n];
        double[] cf = new double[n], deltaStar = new double[n], delta = new double[n];
        theta[0] = theta0; H[0] = H0; H1[0] = H1_0;

        // Cf from Head's correlation
        double ReTheta0 = Ve[0] * theta0 / Math.max(nu, 1e-12);
        cf[0] = 0.246 * Math.pow(10, -0.678 * H0) * Math.pow(Math.max(ReTheta0, 1.0), -0.268);
        double VeThetaH1_0 = Ve[0] * theta[0] * H1[0];

        // RK4 integration
        double VeThetaH1 = VeThetaH1_0;
        for (int i = 0; i < n - 1; i++) {
            double dx = x[i+1] - x[i];
            double Cf = cf[i], Hi = H[i], Thetai = theta[i], vi = Math.max(Ve[i], 0.01);
            double dvi = dVe[i];

            // Iterate to convergence within step
            for (int iter = 0; iter < 20; iter++) {
                // dθ/dx = 0.5·Cf - (θ/V)·(2+H)·dV/dx
                double dThetaDx = 0.5 * Cf - (Thetai / vi) * (2.0 + Hi) * dvi;

                // d(V·θ·H1)/dx = V · 0.0306 · ((V·θ·H1)/(V·θ) - 3)^(-0.6169)
                double ratio = (VeThetaH1 / (vi * Thetai)) - 3.0;
                ratio = Math.max(ratio, 1e-6);
                double dVTHDx = vi * 0.0306 * Math.pow(ratio, -0.6169);

                // Euler step
                double thetaNew = Thetai + dThetaDx * dx;
                VeThetaH1 = VeThetaH1 + dVTHDx * dx;

                thetaNew = Math.max(thetaNew, 1e-12);
                VeThetaH1 = Math.max(VeThetaH1, 1e-12);

                // H1 from definition
                double H1New = VeThetaH1 / (Math.max(Ve[i+1], 0.01) * thetaNew);

                // H from H1 (piecewise)
                double HNew;
                if (H1New < 5.39142) {
                    HNew = 0.6778 + 1.153793 * Math.pow(Math.max(H1New - 3.3, 0.01), -0.32637);
                } else {
                    HNew = 1.1 + 0.8598636 * Math.pow(Math.max(H1New - 3.3, 0.01), -0.777);
                }

                // Cf from Head's correlation
                double ReTheta = Ve[i+1] * thetaNew / Math.max(nu, 1e-12);
                double CfNew = 0.246 * Math.pow(10, -0.678 * HNew) * Math.pow(Math.max(ReTheta, 1.0), -0.268);

                // Check convergence
                double errH = Math.abs((HNew - Hi) / Math.max(HNew, 0.01));
                double errTheta = Math.abs((thetaNew - Thetai) / Math.max(thetaNew, 1e-12));
                double errCf = Math.abs((CfNew - Cf) / Math.max(CfNew, 1e-12));

                Hi = HNew; Thetai = thetaNew; Cf = CfNew;

                if (errH < 0.0001 && errTheta < 0.0001 && errCf < 0.0001) break;
            }

            theta[i+1] = Math.max(Thetai, 1e-12);
            H[i+1] = Hi;
            H1[i+1] = VeThetaH1 / (Math.max(Ve[i+1], 0.01) * theta[i+1]);
            cf[i+1] = Cf;
            deltaStar[i+1] = H[i+1] * theta[i+1];
            delta[i+1] = theta[i+1] * H1[i+1] + deltaStar[i+1];
        }

        System.arraycopy(theta, 0, r.theta, 0, n);
        System.arraycopy(H, 0, r.H, 0, n);
        System.arraycopy(cf, 0, r.cf, 0, n);
        System.arraycopy(deltaStar, 0, r.deltaStar, 0, n);
        System.arraycopy(delta, 0, r.delta, 0, n);
        System.arraycopy(x, 0, r.x, 0, n);
        System.arraycopy(Ve, 0, r.Ve, 0, n);
        return r;
    }
}
