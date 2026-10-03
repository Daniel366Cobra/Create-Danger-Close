package io.github.daniel366cobra.createdangerclose.ballistics;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

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

    private static final double DENSITY = 1.0;
    private static final int MAX_TICKS = 1200;          // 60 seconds
    // Elevation search: 1e-4 rad (0.006°) is still far finer than a servo holds a gun
    // (0.2°); the cap only matters if the bracket starts unusually wide.
    private static final int BISECTION_ITERATIONS = 20;
    private static final double BISECTION_TOLERANCE = 1e-4;
    private static final double MOVING_TARGET_ITERATIONS = 10;
    private static final double MOVING_TARGET_TOLERANCE_TICKS = 0.5;
    private static final int LAUNCH_OFFSET_ITERATIONS = 10;
    private static final double LAUNCH_OFFSET_TOLERANCE = 0.25; // blocks
    // If the aim correction does not converge, the best attempt is still
    // accepted when it lands this close.
    private static final double LAUNCH_OFFSET_ACCEPTABLE_MISS = 1.0; // blocks
    // Steepest pitch searched for plunging fire: near-vertical shots are useless.
    private static final double MAX_HIGH_ARC_DEG = 85;

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
            DEGENERATE,
            // Plunging fire: even the steepest shot lands beyond the target.
            TOO_CLOSE
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
            double maxDepressionDeg,
            boolean highArc
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
                    maxElevationDeg, maxDepressionDeg, highArc);

            if (!lastResult.valid()) return lastResult;

            double tof = lastResult.timeOfFlightTicks();
            predictedTarget.set(targetWorld).fma(tof, targetVelocity);

            if (!Double.isNaN(prevTof)
                    && Math.abs(tof - prevTof) < MOVING_TARGET_TOLERANCE_TICKS) {
                return solveStatic(
                        muzzleWorld, predictedTarget,
                        muzzleVelocity, gravity, drag, isQuadraticDrag,
                        maxElevationDeg, maxDepressionDeg, highArc);
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
     * Solves for a shell that inherits extra launch velocity, e.g. from the
     * moving ship it is fired from: the shell starts with
     * {@code muzzleVelocity * barrelDir + launchVelocityOffset}.
     *
     * <p>Drag acts on the combined velocity, so the trajectory leaves the
     * vertical plane of the barrel and cannot be solved by pitch bisection
     * alone. Instead the aim point is corrected iteratively: solve for the
     * aim point, simulate the real shot, and shift the aim point against the
     * miss until the simulated shot lands on the target.
     *
     * <p>The returned pitch and yaw are the barrel direction, world frame.
     *
     * @param launchVelocityOffset extra launch velocity, blocks/tick, world frame
     */
    public static Result solve(
            Vector3d muzzleWorld,
            Vector3d targetWorld,
            Vector3d targetVelocity,
            Vector3dc launchVelocityOffset,
            double muzzleVelocity,
            double gravity,
            double drag,
            boolean isQuadraticDrag,
            double maxElevationDeg,
            double maxDepressionDeg,
            boolean highArc
    ) {
        if (launchVelocityOffset.lengthSquared() < 1e-12) {
            return solve(muzzleWorld, targetWorld, targetVelocity,
                    muzzleVelocity, gravity, drag, isQuadraticDrag,
                    maxElevationDeg, maxDepressionDeg, highArc);
        }

        Vector3d aim = new Vector3d(targetWorld);
        Result aimResult = null;
        Result best = null;
        double bestMiss = Double.MAX_VALUE;

        for (int i = 0; i < LAUNCH_OFFSET_ITERATIONS; i++) {
            aimResult = solve(muzzleWorld, aim, targetVelocity,
                    muzzleVelocity, gravity, drag, isQuadraticDrag,
                    maxElevationDeg, maxDepressionDeg, highArc);
            if (!aimResult.valid()) return aimResult;

            // Where the target will be when the shell arrives.
            Vector3d predictedTarget = new Vector3d(targetWorld)
                    .fma(aimResult.timeOfFlightTicks(), targetVelocity);
            Vector3d toTarget = predictedTarget.sub(muzzleWorld, new Vector3d());
            double horizontalRange = Math.sqrt(toTarget.x * toTarget.x + toTarget.z * toTarget.z);
            if (horizontalRange < 1e-4) {
                return Result.invalid(Result.Reason.DEGENERATE);
            }
            Vector3d alongTrack = new Vector3d(toTarget.x / horizontalRange, 0, toTarget.z / horizontalRange);

            // Simulate the real shot: barrel direction plus inherited velocity.
            double p = Math.toRadians(aimResult.pitchDeg());
            double y = Math.toRadians(aimResult.yawDeg());
            Vector3d launchVelocity = new Vector3d(
                    -Math.sin(y) * Math.cos(p),
                    Math.sin(p),
                    Math.cos(y) * Math.cos(p))
                    .mul(muzzleVelocity)
                    .add(launchVelocityOffset);

            Impact impact = integrateToRange(launchVelocity, gravity, drag, isQuadraticDrag,
                    alongTrack, horizontalRange, toTarget.y);
            if (impact == null) {
                return Result.invalid(Result.Reason.OUT_OF_RANGE);
            }

            Vector3d miss = impact.relativePos().sub(toTarget, new Vector3d());

            Result attempt = new Result(true, Result.Reason.OK,
                    aimResult.pitchDeg(), aimResult.yawDeg(),
                    impact.ticks(), horizontalRange, (int) Math.round(impact.ticks()));

            double missLength = miss.length();
            if (missLength < LAUNCH_OFFSET_TOLERANCE) {
                return attempt;
            }
            if (missLength < bestMiss) {
                bestMiss = missLength;
                best = attempt;
            }

            aim.sub(miss);
        }

        if (best != null && bestMiss < LAUNCH_OFFSET_ACCEPTABLE_MISS) {
            return best;
        }

        return new Result(false, Result.Reason.NO_CONVERGENCE,
                aimResult.pitchDeg(), aimResult.yawDeg(),
                aimResult.timeOfFlightTicks(), aimResult.range(), aimResult.fuzeTicks());
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
            double maxDepressionDeg,
            boolean highArc
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

        // Max-elevation reachability check.
        IntegrationResult highTraj = integrate(cosYaw, sinYaw, maxElevRad,
                muzzleVelocity, gravity, drag, isQuadraticDrag,
                horizontalRange, dy);

        if (highTraj == null || highTraj.vertical() < dy - 1.0) {
            return Result.invalid(Result.Reason.OUT_OF_RANGE);
        }

        // Direct fire: the flat arc, between the lowest pitch and 45°, where a
        // steeper barrel lands further. Plunging fire: the steep arc, between
        // 45° and the steepest pitch, where a steeper barrel lands shorter.
        double lo, hi;
        if (highArc) {
            double steepestRad = Math.toRadians(steepestPlungingPitch(maxElevationDeg));
            if (steepestRad <= maxElevRad) {
                return Result.invalid(Result.Reason.OUT_OF_ARC);
            }
            IntegrationResult steepest = integrate(cosYaw, sinYaw, steepestRad,
                    muzzleVelocity, gravity, drag, isQuadraticDrag,
                    horizontalRange, dy);
            if (steepest != null && steepest.vertical() >= dy) {
                // Even the steepest shot comes down beyond the target.
                return Result.invalid(Result.Reason.TOO_CLOSE);
            }
            lo = maxElevRad;
            hi = steepestRad;
        } else {
            lo = maxDepRad;
            hi = maxElevRad;
        }
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

            // Short of the target: steepen the flat arc, flatten the steep one.
            boolean under = traj.vertical() < dy;
            if (under != highArc) {
                lo = theta;
            } else {
                hi = theta;
            }
            finalTraj = traj;
            if (hi - lo < BISECTION_TOLERANCE) break;
        }

        double pitchDeg = Math.toDegrees(theta);
        if (pitchDeg > maxElevationDeg || pitchDeg < maxDepressionDeg) {
            return Result.invalid(Result.Reason.OUT_OF_ARC);
        }

        int fuzeTicks = (int) Math.round(finalTraj.ticks);

        return new Result(true, Result.Reason.OK,
                pitchDeg, yawDeg, finalTraj.ticks(), horizontalRange, fuzeTicks);
    }

    /** The steepest pitch plunging fire uses, for a barrel that elevates this far (degrees). */
    public static double steepestPlungingPitch(double maxElevationDeg) {
        return Math.min(maxElevationDeg, MAX_HIGH_ARC_DEG);
    }

    /**
     * How far a shell fired at {@code pitchDeg} travels before coming down to
     * {@code relativeY} (relative to the muzzle), horizontally. With the pitch
     * {@link #solveStatic} tops out at (45° or the mount's limit), this is the
     * range beyond which it reports {@link Result.Reason#OUT_OF_RANGE}.
     *
     * @return the range, or 0 if the shell never reaches that height
     */
    public static double maxRange(
            double muzzleVelocity, double gravity, double drag, boolean isQuadraticDrag,
            double pitchDeg, double relativeY
    ) {
        if (muzzleVelocity <= 0 || gravity >= 0) return 0;
        double pitchRad = Math.toRadians(pitchDeg);
        double vx = muzzleVelocity * Math.cos(pitchRad);
        double vy = muzzleVelocity * Math.sin(pitchRad);
        double px = 0, py = 0;

        for (int tick = 1; tick <= MAX_TICKS; tick++) {
            double speed = Math.sqrt(vx * vx + vy * vy);
            double dragForce = drag * DENSITY * speed;
            if (isQuadraticDrag) dragForce *= speed;
            dragForce = Math.min(dragForce, speed);

            double ax = 0, ay = gravity;
            if (speed > 1e-6) {
                ax -= vx / speed * dragForce;
                ay -= vy / speed * dragForce;
            }

            // CBC's kinematic step.
            double prevPx = px, prevPy = py;
            px += vx + 0.5 * ax;
            py += vy + 0.5 * ay;
            vx += ax;
            vy += ay;

            // Coming down through the height: interpolate within the tick.
            if (vy < 0 && py <= relativeY) {
                if (prevPy <= relativeY) return 0; // was never above it
                double t = (prevPy - relativeY) / (prevPy - py);
                return prevPx + t * (px - prevPx);
            }
        }
        return px; // still in the air after the tick budget
    }

    // ------------------------------------------------------------------
    // Integration
    // ------------------------------------------------------------------

    private record IntegrationResult(double horizontalRange, double vertical, double ticks) {}

    /**
     * CBC-compatible per-tick trajectory integration. Stops when the
     * horizontal range crosses {@code targetHorizontalRange} or the max tick
     * budget is exhausted.
     *
     * <p>The height is interpolated to exactly the target range within the
     * crossing tick. Reading it at the first whole tick past the range would
     * make the result jump whenever that tick changes, which breaks the
     * iterative solves built on top of this.
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
            double prevHRange = Math.sqrt(px * px + pz * pz);
            double prevPy = py;
            double oldVx = vx, oldVy = vy, oldVz = vz;
            vx += ax;
            vy += ay;
            vz += az;
            px += oldVx + 0.5 * ax;
            py += oldVy + 0.5 * ay;
            pz += oldVz + 0.5 * az;

            double hRange = Math.sqrt(px * px + pz * pz);

            if (hRange >= targetHorizontalRange) {
                double t = (targetHorizontalRange - prevHRange) / (hRange - prevHRange);
                return new IntegrationResult(targetHorizontalRange,
                        prevPy + t * (py - prevPy), tick - 1 + t);
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

    /** Shell position relative to the muzzle when it reaches the target's range. */
    private record Impact(Vector3d relativePos, double ticks) {}

    /**
     * Full 3D CBC-compatible integration from an arbitrary launch velocity.
     * Stops when the shell's progress along {@code alongTrack} (a horizontal
     * unit vector towards the target) reaches {@code targetRange}, and
     * interpolates the position within that tick.
     *
     * @return null if the shell never gets there
     */
    @Nullable
    private static Impact integrateToRange(
            Vector3dc launchVelocity, double gravity, double drag, boolean isQuadratic,
            Vector3dc alongTrack, double targetRange, double targetRelativeY
    ) {
        Vector3d pos = new Vector3d();
        Vector3d vel = new Vector3d(launchVelocity);
        Vector3d accel = new Vector3d();
        Vector3d prevPos = new Vector3d();

        for (int tick = 1; tick <= MAX_TICKS; tick++) {
            double speed = vel.length();

            double dragForce = drag * DENSITY * speed;
            if (isQuadratic) dragForce *= speed;
            dragForce = Math.min(dragForce, speed);

            accel.set(0, gravity, 0);
            if (speed > 1e-6) {
                accel.fma(-dragForce / speed, vel);
            }

            // CBC's kinematic step.
            prevPos.set(pos);
            pos.add(vel).fma(0.5, accel);
            vel.add(accel);

            double along = pos.dot(alongTrack);
            if (along >= targetRange) {
                double prevAlong = prevPos.dot(alongTrack);
                double t = (targetRange - prevAlong) / (along - prevAlong);
                Vector3d at = prevPos.lerp(pos, t, new Vector3d());
                return new Impact(at, tick - 1 + t);
            }

            if (pos.y < targetRelativeY - 200) {
                // Fallen well below the target — it's not going to make it.
                return null;
            }
        }

        return null;
    }
}