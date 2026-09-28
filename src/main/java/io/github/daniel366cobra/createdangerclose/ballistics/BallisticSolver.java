package io.github.daniel366cobra.createdangerclose.ballistics;

import org.joml.Vector3d;

/**
 * Pure-math ballistic solver for indirect fire.
 *
 * <p>Units are CBC-native: blocks, blocks/tick, blocks/tick². This matches
 * {@code AbstractCannonProjectile} so the predicted trajectory is identical
 * to what the fired shell actually follows.
 *
 * <p>Drag model mirrors CBC's {@code getForces}:
 * <pre>
 *   speed = |vel|
 *   dragForce = formDrag * density * speed
 *   if (quadratic) dragForce *= speed
 *   dragForce = min(dragForce, speed)
 *   accel = -normalize(vel) * dragForce + (0, gravity, 0)
 * </pre>
 * Integrated with CBC's kinematic step:
 * <pre>
 *   pos += vel + 0.5 * accel
 *   vel += accel
 * </pre>
 *
 * <p>Atmospheric density is assumed to be 1.0 (overworld air). Fluid drag is
 * ignored — the shell is assumed to fly through air.
 */
public final class BallisticSolver {

    private BallisticSolver() {}

    public static boolean DEBUG_LOG = false;

    private static final double DENSITY = 1.0;
    private static final int MAX_TICKS = 1200;          // 60 seconds
    private static final int BISECTION_ITERATIONS = 40; // ~1e-8 precision
    private static final double BISECTION_TOLERANCE = 1e-6;
    private static final double MOVING_TARGET_ITERATIONS = 10;
    private static final double MOVING_TARGET_TOLERANCE_TICKS = 0.5;

    public record Result(
            boolean valid,
            Reason reason,
            double pitchDeg,
            double yawDeg,
            double timeOfFlightTicks,
            double range,
            int fuzeTicks
    ) {
        public enum Reason {
            OK,
            OUT_OF_RANGE,
            OUT_OF_ARC,
            NO_CONVERGENCE,
            NO_TARGET,
            NO_SHELL,
            NO_ORIENTATION,
            INSUFFICIENT_CHARGE,
            DEGENERATE
        }

        public static Result invalid(Reason reason) {
            return new Result(false, reason, 0, 0, 0, 0, 0);
        }
    }

    /**
     * Solves for the firing solution to hit a target.
     *
     * @param muzzleWorld        muzzle position, world frame
     * @param targetWorld        target position, world frame
     * @param targetVelocity     target velocity per tick, world frame
     * @param muzzleVelocity     initial projectile speed, blocks/tick
     * @param gravity            CBC gravity, negative, blocks/tick²
     * @param drag               CBC formDrag coefficient
     * @param isQuadraticDrag    CBC drag mode
     * @param maxElevationDeg    upper pitch limit, degrees (positive)
     * @param maxDepressionDeg   lower pitch limit, degrees (negative)
     */
    public static Result solve(
            Vector3d muzzleWorld,
            Vector3d targetWorld,
            Vector3d targetVelocity,
            double muzzleVelocity,
            double gravity,
            double drag,
            boolean isQuadraticDrag,
            double maxElevationDeg,
            double maxDepressionDeg
    ) {
        if (muzzleVelocity <= 0 || gravity >= 0) {
            return Result.invalid(Result.Reason.DEGENERATE);
        }

        Vector3d predictedTarget = new Vector3d(targetWorld);
        double prevTof = Double.NaN;
        Result lastResult = null;

        for (int i = 0; i < MOVING_TARGET_ITERATIONS; i++) {
            lastResult = solveStatic(
                    muzzleWorld, predictedTarget,
                    muzzleVelocity, gravity, drag, isQuadraticDrag,
                    maxElevationDeg, maxDepressionDeg);

            if (!lastResult.valid()) return lastResult;

            double tof = lastResult.timeOfFlightTicks();
            predictedTarget.set(targetWorld).fma(tof, targetVelocity);

            if (!Double.isNaN(prevTof)
                    && Math.abs(tof - prevTof) < MOVING_TARGET_TOLERANCE_TICKS) {
                return solveStatic(
                        muzzleWorld, predictedTarget,
                        muzzleVelocity, gravity, drag, isQuadraticDrag,
                        maxElevationDeg, maxDepressionDeg);
            }
            prevTof = tof;
        }

        if (lastResult != null && lastResult.valid()) {
            return new Result(false, Result.Reason.NO_CONVERGENCE,
                    lastResult.pitchDeg(), lastResult.yawDeg(),
                    lastResult.timeOfFlightTicks(), lastResult.range(),
                    lastResult.fuzeTicks());
        }
        return Result.invalid(Result.Reason.NO_CONVERGENCE);
    }

    /**
     * Solves the static-target problem by bisecting the elevation angle.
     */
    public static Result solveStatic(
            Vector3d muzzleWorld,
            Vector3d targetWorld,
            double muzzleVelocity,
            double gravity,
            double drag,
            boolean isQuadraticDrag,
            double maxElevationDeg,
            double maxDepressionDeg
    ) {
        double dx = targetWorld.x - muzzleWorld.x;
        double dy = targetWorld.y - muzzleWorld.y;
        double dz = targetWorld.z - muzzleWorld.z;

        double horizontalRange = Math.sqrt(dx * dx + dz * dz);
        if (horizontalRange < 1e-4) {
            return Result.invalid(Result.Reason.DEGENERATE);
        }

        double yawRad = Math.atan2(-dx, dz);
        double yawDeg = Math.toDegrees(yawRad);
        if (yawDeg < 0) yawDeg += 360;

        double cosYaw = dx / horizontalRange;
        double sinYaw = dz / horizontalRange;

        double maxElevRad = Math.min(Math.toRadians(maxElevationDeg), Math.toRadians(45));
        double maxDepRad = Math.toRadians(maxDepressionDeg);

        if (DEBUG_LOG) {
            System.out.printf("[BallisticSolver] range=%.2f dy=%.2f v0=%.3f g=%.5f drag=%.5f " +
                            "maxElev=%.2f maxDep=%.2f%n",
                    horizontalRange, dy, muzzleVelocity, gravity, drag,
                    maxElevationDeg, maxDepressionDeg);
        }

        // Max-elevation reachability check.
        IntegrationResult highTraj = integrate(cosYaw, sinYaw, maxElevRad,
                muzzleVelocity, gravity, drag, isQuadraticDrag,
                horizontalRange, dy);

        if (DEBUG_LOG && highTraj != null) {
            System.out.printf("[BallisticSolver] high traj: landingRange=%.2f vertical=%.2f ticks=%.0f%n",
                    highTraj.horizontalRange(), highTraj.vertical(), highTraj.ticks());
        }

        if (highTraj == null || highTraj.vertical() < dy - 1.0) {
            if (DEBUG_LOG) System.out.println("[BallisticSolver] OUT_OF_RANGE at max elevation");
            return Result.invalid(Result.Reason.OUT_OF_RANGE);
        }

        double lo = maxDepRad;
        double hi = maxElevRad;
        double theta = hi;
        IntegrationResult finalTraj = highTraj;

        for (int i = 0; i < BISECTION_ITERATIONS; i++) {
            theta = (lo + hi) * 0.5;
            IntegrationResult traj = integrate(cosYaw, sinYaw, theta,
                    muzzleVelocity, gravity, drag, isQuadraticDrag,
                    horizontalRange, dy);
            if (traj == null) {
                return Result.invalid(Result.Reason.NO_CONVERGENCE);
            }

            if (DEBUG_LOG) {
                System.out.printf("[BallisticSolver] iter %2d: theta=%.4f° " +
                                "landing=%.2f vertical=%.2f targetY=%.2f %s%n",
                        i, Math.toDegrees(theta),
                        traj.horizontalRange(), traj.vertical(), dy,
                        traj.vertical() < dy ? "(under)" : "(over)");
            }

            if (traj.vertical() < dy) {
                lo = theta;
            } else {
                hi = theta;
            }
            finalTraj = traj;
            if (hi - lo < BISECTION_TOLERANCE) break;
        }

        double pitchDeg = Math.toDegrees(theta);
        if (pitchDeg > maxElevationDeg || pitchDeg < maxDepressionDeg) {
            if (DEBUG_LOG) System.out.printf("[BallisticSolver] OUT_OF_ARC pitch=%.2f%n", pitchDeg);
            return Result.invalid(Result.Reason.OUT_OF_ARC);
        }

        int fuzeTicks = (int) Math.round(finalTraj.ticks);

        if (DEBUG_LOG) {
            System.out.printf("[BallisticSolver] OK pitch=%.2f° yaw=%.2f° tof=%d t%n",
                    pitchDeg, yawDeg, fuzeTicks);
        }

        return new Result(true, Result.Reason.OK,
                pitchDeg, yawDeg, finalTraj.ticks(), horizontalRange, fuzeTicks);
    }

    // ------------------------------------------------------------------
    // Integration
    // ------------------------------------------------------------------

    private record IntegrationResult(double horizontalRange, double vertical, double ticks) {}

    /**
     * CBC-compatible per-tick trajectory integration. Stops when the
     * horizontal range crosses {@code targetHorizontalRange} or the max tick
     * budget is exhausted.
     */
    private static IntegrationResult integrate(
            double cosYaw, double sinYaw, double pitchRad,
            double v0, double gravity, double drag, boolean isQuadratic,
            double targetHorizontalRange, double targetRelativeY
    ) {
        double vHoriz = v0 * Math.cos(pitchRad);
        double vVert = v0 * Math.sin(pitchRad);

        double px = 0, py = 0, pz = 0;
        double vx = cosYaw * vHoriz;
        double vz = sinYaw * vHoriz;
        double vy = vVert;

        for (int tick = 1; tick <= MAX_TICKS; tick++) {
            double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);

            double dragForce = drag * DENSITY * speed;
            if (isQuadratic) dragForce *= speed;
            dragForce = Math.min(dragForce, speed);

            double ax = 0, ay = gravity, az = 0;
            if (speed > 1e-6) {
                double invSpeed = 1.0 / speed;
                ax -= vx * invSpeed * dragForce;
                ay -= vy * invSpeed * dragForce;
                az -= vz * invSpeed * dragForce;
            }

            // CBC's kinematic step.
            double oldVx = vx, oldVy = vy, oldVz = vz;
            vx += ax;
            vy += ay;
            vz += az;
            px += oldVx + 0.5 * ax;
            py += oldVy + 0.5 * ay;
            pz += oldVz + 0.5 * az;

            double hRange = Math.sqrt(px * px + pz * pz);

            if (hRange >= targetHorizontalRange) {
                return new IntegrationResult(hRange, py, tick);
            }

            if (py < targetRelativeY - 200) {
                // Fallen well below the target — it's not going to make it.
                return new IntegrationResult(hRange, py, tick);
            }
        }

        // Exhausted the tick budget.
        double hRange = Math.sqrt(px * px + pz * pz);
        return new IntegrationResult(hRange, py, MAX_TICKS);
    }
}