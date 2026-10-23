package io.github.daniel366cobra.createdangerclose.foundation.ballistics;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Ballistic solver in CBC-native units (blocks, ticks), mirroring {@code AbstractCannonProjectile}'s
 * drag ({@code getForces}) and kinematic step, so predictions match the fired shell.
 * Assumes air at density 1.0; fluid drag is ignored.
 */
public final class BallisticSolver {

    private BallisticSolver() {}

    private static final double DENSITY = 1.0;
    private static final int MAX_TICKS = 1200;          // 60 seconds
    // 1e-4 rad: far finer than a servo holds a gun.
    private static final int BISECTION_ITERATIONS = 20;
    private static final double BISECTION_TOLERANCE = 1e-4;
    private static final int MOVING_TARGET_ITERATIONS = 10;
    private static final double MOVING_TARGET_TOLERANCE_TICKS = 0.5;
    private static final int LAUNCH_OFFSET_ITERATIONS = 10;
    private static final double LAUNCH_OFFSET_TOLERANCE = 0.25; // blocks
    // A non-converging aim correction is still accepted within this miss.
    private static final double LAUNCH_OFFSET_ACCEPTABLE_MISS = 1.0; // blocks
    // A solution passes this close above or below the target, blocks (also the reach check's slack).
    private static final double HIT_TOLERANCE = 1.0;
    // Near-vertical shots are useless.
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
            // A disassembled mount, as between shots while a big cannon is reloaded.
            NOT_ASSEMBLED,
            NO_ORIENTATION,
            INSUFFICIENT_CHARGE,
            DEGENERATE,
            // Plunging fire: even the steepest shot lands beyond the target.
            TOO_CLOSE,
            // Ship gun without an Altitude Sensor, absolute target well above or below.
            NO_ALTITUDE,
            // Two drives about the same axis, or more than two.
            REDUNDANT_DRIVES,
            ROUND_EXPIRES
        }

        public static Result invalid(Reason reason) {
            return new Result(false, reason, 0, 0, 0, 0, 0);
        }
    }

    /**
     * @param targetVelocity   per tick
     * @param muzzleVelocity   blocks/tick
     * @param gravity          CBC gravity, negative, blocks/tick²
     * @param maxElevationDeg  positive
     * @param maxDepressionDeg negative
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
     * For a shell with extra launch velocity (from a moving ship). Drag then pulls the shot out of
     * the barrel's vertical plane, so the aim point is corrected iteratively against the simulated miss.
     * Returns the barrel direction, world frame.
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

    /** Static target: bisects the elevation. */
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

        IntegrationResult highTraj = integrate(cosYaw, sinYaw, maxElevRad,
                muzzleVelocity, gravity, drag, isQuadraticDrag,
                horizontalRange, dy);

        if (highTraj == null || highTraj.vertical() < dy - HIT_TOLERANCE) {
            return Result.invalid(Result.Reason.OUT_OF_RANGE);
        }

        // Direct: the flat arc (lowest pitch to 45°). Plunging: the steep arc (45° to the steepest).
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
        // Pinned against a limit that no pitch in the arc gets past: a target steeply below, say, that
        // even the lowest depression overshoots.
        if (pitchDeg > maxElevationDeg || pitchDeg < maxDepressionDeg
                || Math.abs(finalTraj.vertical() - dy) > HIT_TOLERANCE) {
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
     * Horizontal range at which a shell fired at {@code pitchDeg} comes down to {@code relativeY}.
     * At {@link #solveStatic}'s top pitch, the {@link Result.Reason#OUT_OF_RANGE} boundary.
     *
     * @return 0 if the shell never reaches that height
     */
    public static double maxRange(
            double muzzleVelocity, double gravity, double drag, boolean isQuadraticDrag,
            double pitchDeg, double relativeY
    ) {
        return maxRange(muzzleVelocity, gravity, drag, isQuadraticDrag, pitchDeg, relativeY, MAX_TICKS);
    }

    /** For a round that expires after this many ticks (an autocannon's). */
    public static double maxRange(
            double muzzleVelocity, double gravity, double drag, boolean isQuadraticDrag,
            double pitchDeg, double relativeY, int maxTicks
    ) {
        if (muzzleVelocity <= 0 || gravity >= 0) return 0;
        double pitchRad = Math.toRadians(pitchDeg);
        double vx = muzzleVelocity * Math.cos(pitchRad);
        double vy = muzzleVelocity * Math.sin(pitchRad);
        double px = 0, py = 0;

        for (int tick = 1; tick <= Math.min(maxTicks, MAX_TICKS); tick++) {
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

    // --- Integration ---

    private record IntegrationResult(double horizontalRange, double vertical, double ticks) {}

    /**
     * Integrates until the horizontal range crosses {@code targetHorizontalRange}. The height is
     * interpolated within the crossing tick, so iterative solves on top see no jumps.
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

        double hRange = Math.sqrt(px * px + pz * pz);
        return new IntegrationResult(hRange, py, MAX_TICKS);
    }

    /** Shell position relative to the muzzle when it reaches the target's range. */
    private record Impact(Vector3d relativePos, double ticks) {}

    /**
     * 3D integration from any launch velocity, until progress along {@code alongTrack} reaches
     * {@code targetRange}; interpolated within that tick.
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