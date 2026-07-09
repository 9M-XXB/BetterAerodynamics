package com.betteraerodynamics.aero;

/**
 * Hess-Smith panel method for inviscid flow over an airfoil.
 *
 * <p>Optionally adds boundary layer displacement thickness to the
 * airfoil geometry to approximate viscous effects on pressure distribution.
 *
 * <p>Algorithm (from AE416 Hess-Smith Python):
 * <ol>
 *   <li>Displace airfoil surface outward by δ* (if BL provided)</li>
 *   <li>Compute panel geometry (length, angle, midpoint)</li>
 *   <li>Build influence coefficient matrix A</li>
 *   <li>Build RHS vector for angle of attack α</li>
 *   <li>Solve A·λ = b (Gaussian elimination)</li>
 *   <li>Compute tangential velocity Vt at each panel</li>
 *   <li>Cp = 1 − Vt²</li>
 *   <li>Integrate Cp → Cl, Cd</li>
 * </ol>
 */
public class HessSmith {

    /** Result: lift and drag coefficients. */
    public record PanelResult(double cl, double cd) {}

    /**
     * Compute Cl, Cd using the Hess-Smith panel method on the raw airfoil.
     */
    public static PanelResult compute(AirfoilProfile airfoil, double alphaRad) {
        int n = airfoil.panelCount();
        double[] x = airfoil.x.clone();
        double[] y = airfoil.y.clone();
        return panelSolve(x, y, alphaRad);
    }

    /**
     * Compute Cl, Cd with boundary layer displacement effect.
     * The airfoil shape is thickened by δ* before panel solve.
     *
     * @param bl    boundary layer result (provides δ* along chord)
     * @param chord chord length for chordwise mapping
     */
    public static PanelResult computeWithBL(AirfoilProfile airfoil, double alphaRad,
                                             BoundaryLayer.BLResult bl, double chord) {
        int nNodes = airfoil.x.length;
        int nside = nNodes / 2;  // upper surface = nside nodes

        double[] x = airfoil.x.clone();
        double[] y = airfoil.y.clone();

        // Apply δ* to upper surface nodes only (LE→TE, indices nside-1 to nNodes-1)
        // BL x runs 0..chord, airfoil x runs 0..1
        for (int i = 0; i < nside; i++) {
            int nodeIdx = nside - 1 + i;  // upper surface: LE→TE
            double xi = airfoil.x[nodeIdx]; // 0..1

            // Interpolate δ* at this chord position
            int blIdx = (int)(xi * (bl.deltaStar.length - 1));
            blIdx = Math.min(blIdx, bl.deltaStar.length - 1);
            double deltaStar = bl.deltaStar[blIdx] / chord; // normalise by chord

            // Push outward (away from camber line): upper surface → +y
            y[nodeIdx] += deltaStar;
        }

        return panelSolve(x, y, alphaRad);
    }

    // === Core panel method ===

    private static PanelResult panelSolve(double[] x, double[] y, double alphaRad) {
        int npanel = x.length - 1;

        // Step 3: panel geometry
        double[] l        = new double[npanel];
        double[] sinTheta = new double[npanel];
        double[] cosTheta = new double[npanel];
        double[] xbar     = new double[npanel];
        double[] ybar     = new double[npanel];

        for (int i = 0; i < npanel; i++) {
            double dx = x[i+1] - x[i];
            double dy = y[i+1] - y[i];
            l[i] = Math.sqrt(dx*dx + dy*dy);
            sinTheta[i] = dy / l[i];
            cosTheta[i] = dx / l[i];
            xbar[i] = 0.5 * (x[i+1] + x[i]);
            ybar[i] = 0.5 * (y[i+1] + y[i]);
        }

        // Step 4: influence coefficient matrix A (npanel+1)×(npanel+1)
        int n = npanel + 1;
        double[][] A = new double[n][n];
        buildInfluenceMatrix(x, y, xbar, ybar, sinTheta, cosTheta, npanel, A);

        // Step 5: RHS vector b
        double[] b = new double[n];
        double ca = Math.cos(alphaRad), sa = Math.sin(alphaRad);
        for (int i = 0; i < npanel; i++) {
            b[i] = sinTheta[i] * ca - sa * cosTheta[i];
        }
        b[npanel] = -(cosTheta[0] * ca + sinTheta[0] * sa)
                   - (cosTheta[npanel-1] * ca + sinTheta[npanel-1] * sa);

        // Step 6: solve A·λ = b
        double[] lambda = solveLinear(A, b);

        // Step 7: tangential velocity distribution
        double[] vt = velocityDistribution(lambda, x, y, xbar, ybar,
                                           sinTheta, cosTheta, alphaRad, npanel);

        // Step 8: Cp = 1 - Vt²
        double[] cp = new double[npanel];
        for (int i = 0; i < npanel; i++) cp[i] = 1.0 - vt[i] * vt[i];

        // Step 9: integrate Cp → Cl, Cd
        return aeroCoefficients(x, y, cp, alphaRad, npanel);
    }

    // === Influence coefficient matrix ===

    private static void buildInfluenceMatrix(double[] x, double[] y,
            double[] xbar, double[] ybar, double[] st, double[] ct,
            int npanel, double[][] A) {
        double pi2inv = 1.0 / (2.0 * Math.PI);
        int n = npanel + 1;

        for (int i = 0; i < npanel; i++) {
            for (int j = 0; j < npanel; j++) {
                double dx1 = xbar[i] - x[j];
                double dy1 = ybar[i] - y[j];
                double dx2 = xbar[i] - x[j+1];
                double dy2 = ybar[i] - y[j+1];

                double r1 = Math.sqrt(dx1*dx1 + dy1*dy1);
                double r2 = Math.sqrt(dx2*dx2 + dy2*dy2);

                double beta;
                if (i == j) {
                    beta = Math.PI;
                } else {
                    double cross = dx1 * dy2 - dy1 * dx2;
                    double dot   = dx1 * dx2 + dy1 * dy2;
                    beta = Math.atan2(cross, dot);
                }

                double uss = -pi2inv * Math.log(Math.max(r2 / r1, 1e-12));
                double vss = pi2inv * beta;

                A[i][j] = -uss * (ct[j]*st[i] - st[j]*ct[i])
                         + vss * (st[j]*st[i] + ct[j]*ct[i]);

                A[i][npanel] += pi2inv * (
                    (ct[i]*ct[j] + st[i]*st[j]) * Math.log(Math.max(r2 / r1, 1e-12))
                  - (st[i]*ct[j] - ct[i]*st[j]) * beta);

                if (i == 0 || i == npanel - 1) {
                    A[npanel][j] += pi2inv * (
                        (st[i]*ct[j] - ct[i]*st[j]) * beta
                      - (ct[i]*ct[j] + st[i]*st[j]) * Math.log(Math.max(r2 / r1, 1e-12)));
                    A[npanel][npanel] += A[i][j];
                }
            }
        }
    }

    // === Velocity distribution ===

    private static double[] velocityDistribution(double[] lambda,
            double[] x, double[] y, double[] xbar, double[] ybar,
            double[] st, double[] ct, double alpha, int npanel) {
        double pi2inv = 1.0 / (2.0 * Math.PI);
        double ca = Math.cos(alpha), sa = Math.sin(alpha);
        double[] vt = new double[npanel];

        for (int i = 0; i < npanel; i++) {
            vt[i] = ct[i] * ca + st[i] * sa;
            for (int j = 0; j < npanel; j++) {
                double dx1 = xbar[i] - x[j];
                double dy1 = ybar[i] - y[j];
                double dx2 = xbar[i] - x[j+1];
                double dy2 = ybar[i] - y[j+1];
                double r1 = Math.sqrt(dx1*dx1 + dy1*dy1);
                double r2 = Math.sqrt(dx2*dx2 + dy2*dy2);

                double beta;
                if (i == j) {
                    beta = Math.PI;
                } else {
                    double cross = dx1 * dy2 - dy1 * dx2;
                    double dot   = dx1 * dx2 + dy1 * dy2;
                    beta = Math.atan2(cross, dot);
                }

                vt[i] += lambda[j] * pi2inv * (
                    (st[i]*ct[j] - ct[i]*st[j]) * beta
                  - (ct[i]*ct[j] + st[i]*st[j]) * Math.log(Math.max(r2 / r1, 1e-12)))
                  + lambda[npanel] * pi2inv * (
                    (st[i]*ct[j] - ct[i]*st[j]) * Math.log(Math.max(r2 / r1, 1e-12))
                  + (ct[i]*ct[j] + st[i]*st[j]) * beta);
            }
        }
        return vt;
    }

    // === Aero coefficients from Cp integration ===

    private static PanelResult aeroCoefficients(double[] x, double[] y,
            double[] cp, double alpha, int npanel) {
        double cn = 0, ca = 0;
        for (int i = 0; i < npanel; i++) {
            double dx = x[i+1] - x[i];
            double dy = y[i+1] - y[i];
            cn += -cp[i] * dx;
            ca +=  cp[i] * dy;
        }
        double cl = cn * Math.cos(alpha) - ca * Math.sin(alpha);
        double cd = cn * Math.sin(alpha) + ca * Math.cos(alpha);
        return new PanelResult(cl, cd);
    }

    // === Gaussian elimination with partial pivoting ===

    private static double[] solveLinear(double[][] A, double[] b) {
        int n = b.length;
        double[][] aug = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(A[i], 0, aug[i], 0, n);
            aug[i][n] = b[i];
        }

        for (int col = 0; col < n; col++) {
            // Partial pivot
            int maxRow = col;
            double maxVal = Math.abs(aug[col][col]);
            for (int row = col + 1; row < n; row++) {
                if (Math.abs(aug[row][col]) > maxVal) {
                    maxVal = Math.abs(aug[row][col]);
                    maxRow = row;
                }
            }
            double[] tmp = aug[col]; aug[col] = aug[maxRow]; aug[maxRow] = tmp;

            if (Math.abs(aug[col][col]) < 1e-12) continue;

            // Eliminate below
            for (int row = col + 1; row < n; row++) {
                double factor = aug[row][col] / aug[col][col];
                for (int j = col; j <= n; j++) {
                    aug[row][j] -= factor * aug[col][j];
                }
            }
        }

        // Back substitution
        double[] x = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            double sum = aug[i][n];
            for (int j = i + 1; j < n; j++) sum -= aug[i][j] * x[j];
            x[i] = Math.abs(aug[i][i]) > 1e-12 ? sum / aug[i][i] : 0;
        }
        return x;
    }
}
