package io.github.daniel366cobra.createdangerclose.foundation.ballistics;

import io.github.daniel366cobra.createdangerclose.foundation.ballistics.BallisticSolver.Result;
import io.github.daniel366cobra.createdangerclose.foundation.ballistics.BallisticSolver.Result.Reason;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BallisticSolverTest {

    // A shell typical of CBC: blocks/tick, blocks/tick², linear drag.
    private static final double SPEED = 3, GRAVITY = -0.05, DRAG = 0.01;
    private static final Vector3d ORIGIN = new Vector3d();
    private static final Vector3d STILL = new Vector3d();

    private static Result direct(Vector3d target, double drag) {
        return BallisticSolver.solve(ORIGIN, target, STILL, SPEED, GRAVITY, drag, false, 60, -30, false);
    }

    private static Result plunging(Vector3d target) {
        return BallisticSolver.solve(ORIGIN, target, STILL, SPEED, GRAVITY, DRAG, false, 85, -30, true);
    }

    /** Where the solved shot comes down to the target's height, flown again on its own. */
    private static double landsAt(Result result, double drag, double height) {
        return BallisticSolver.maxRange(SPEED, GRAVITY, drag, false, result.pitchDeg(), height);
    }

    /** The shot's height as it passes this horizontal range, rising or falling: CBC's step, flown here apart. */
    private static double heightAt(double pitchDeg, double drag, double range) {
        double p = Math.toRadians(pitchDeg);
        double vx = SPEED * Math.cos(p), vy = SPEED * Math.sin(p), x = 0, y = 0;
        for (int tick = 0; tick < 1200; tick++) {
            double speed = Math.hypot(vx, vy);
            double dragForce = Math.min(drag * speed, speed);
            double ax = -vx / speed * dragForce, ay = GRAVITY - vy / speed * dragForce;
            double nx = x + vx + 0.5 * ax, ny = y + vy + 0.5 * ay;
            if (nx >= range)
                return y + (ny - y) * (range - x) / (nx - x);
            x = nx;
            y = ny;
            vx += ax;
            vy += ay;
        }
        return Double.NaN;
    }

    @Test
    void vacuumMatchesTheParabola() {
        // CBC's step is exact for constant acceleration: sin 2θ = |g| R / v².
        double range = 100;
        Result result = direct(new Vector3d(0, 0, range), 0);
        assertTrue(result.valid(), result.reason().name());
        double expected = Math.toDegrees(0.5 * Math.asin(-GRAVITY * range / (SPEED * SPEED)));
        assertEquals(expected, result.pitchDeg(), 0.2);
    }

    @Test
    void solutionsLandOnTheTarget() {
        // Shares of the reach at 45° (its top pitch), level, above and below.
        for (double height : new double[]{0, 15, -15}) {
            double reach = BallisticSolver.maxRange(SPEED, GRAVITY, DRAG, false, 45, height);
            for (double share : new double[]{0.3, 0.6, 0.9}) {
                double range = reach * share;
                Result result = direct(new Vector3d(range, height, 0), DRAG);
                String at = "range " + range + ", height " + height;
                assertTrue(result.valid(), at + ": " + result.reason());
                assertEquals(height, heightAt(result.pitchDeg(), DRAG, range), 0.5, at);
            }
        }
    }

    @Test
    void yawFacesTheTarget() {
        // Minecraft yaw: 0 south (+z), 90 west (-x).
        assertEquals(0, direct(new Vector3d(0, 0, 80), DRAG).yawDeg(), 1e-6);
        assertEquals(90, direct(new Vector3d(-80, 0, 0), DRAG).yawDeg(), 1e-6);
        assertEquals(270, direct(new Vector3d(80, 0, 0), DRAG).yawDeg(), 1e-6);
    }

    @Test
    void plungingFireTakesTheSteepArc() {
        double range = 100;
        Result result = plunging(new Vector3d(0, 0, range));
        assertTrue(result.valid(), result.reason().name());
        assertTrue(result.pitchDeg() > 45, "pitch " + result.pitchDeg());
        assertEquals(range, landsAt(result, DRAG, 0), 0.5);
        assertTrue(result.timeOfFlightTicks() > direct(new Vector3d(0, 0, range), DRAG).timeOfFlightTicks());
    }

    @Test
    void tooCloseForPlungingFire() {
        assertEquals(Reason.TOO_CLOSE, plunging(new Vector3d(0, 0, 5)).reason());
    }

    @Test
    void outOfRange() {
        // In vacuum the reach is v² / |g| = 180.
        assertEquals(Reason.OUT_OF_RANGE, direct(new Vector3d(0, 0, 400), 0).reason());
    }

    @Test
    void steeplyBelowAndPastTheDepressionLimit() {
        // At its lowest (-30°) the shell still passes far above a target this steeply below.
        Result result = direct(new Vector3d(0, -60, 10), DRAG);
        assertFalse(result.valid(), "pitch " + result.pitchDeg());
        assertEquals(Reason.OUT_OF_ARC, result.reason());
    }

    @Test
    void degenerateInputs() {
        assertEquals(Reason.DEGENERATE, BallisticSolver.solve(ORIGIN, new Vector3d(0, 0, 50), STILL,
                0, GRAVITY, DRAG, false, 60, -30, false).reason());
        assertEquals(Reason.DEGENERATE, BallisticSolver.solve(ORIGIN, new Vector3d(0, 0, 50), STILL,
                SPEED, 0, DRAG, false, 60, -30, false).reason());
        // Straight overhead: no bearing to lay on.
        assertEquals(Reason.DEGENERATE, direct(new Vector3d(0, 40, 0), DRAG).reason());
    }

    @Test
    void leadsAMovingTarget() {
        Vector3d target = new Vector3d(0, 0, 100);
        Vector3d velocity = new Vector3d(0.3, 0, 0);
        Result result = BallisticSolver.solve(ORIGIN, target, velocity, SPEED, GRAVITY, DRAG, false, 60, -30, false);
        assertTrue(result.valid(), result.reason().name());
        // Aimed where the target will be when the shell arrives.
        Vector3d ahead = new Vector3d(target).fma(result.timeOfFlightTicks(), velocity);
        double expectedYaw = Math.toDegrees(Math.atan2(-ahead.x, ahead.z));
        assertEquals((expectedYaw + 360) % 360, result.yawDeg(), 0.5);
    }

    @Test
    void noInheritedVelocityIsThePlainSolve() {
        Vector3d target = new Vector3d(30, 5, 90);
        Result plain = direct(target, DRAG);
        Result inherited = BallisticSolver.solve(ORIGIN, target, STILL, new Vector3d(), SPEED, GRAVITY, DRAG,
                false, 60, -30, false);
        assertEquals(plain, inherited);
    }

    @Test
    void inheritedVelocityStillHits() {
        // A ship running across the line of fire at 0.4 blocks/tick.
        Result result = BallisticSolver.solve(ORIGIN, new Vector3d(0, 0, 100), STILL, new Vector3d(0.4, 0, 0),
                SPEED, GRAVITY, DRAG, false, 60, -30, false);
        assertTrue(result.valid(), result.reason().name());
        // Fired sideways against the drift.
        assertTrue(result.yawDeg() > 0 && result.yawDeg() < 90, "yaw " + result.yawDeg());
    }

    @Test
    void neverReturnsNaN() {
        Random random = new Random(42);
        for (int i = 0; i < 2000; i++) {
            Vector3d target = new Vector3d(random.nextGaussian() * 150, random.nextGaussian() * 40,
                    random.nextGaussian() * 150);
            double drag = random.nextDouble() * 0.05;
            Result result = BallisticSolver.solve(ORIGIN, target, STILL, 0.5 + random.nextDouble() * 6,
                    -0.01 - random.nextDouble() * 0.1, drag, random.nextBoolean(), 60, -30, random.nextBoolean());
            assertFalse(Double.isNaN(result.pitchDeg()) || Double.isNaN(result.yawDeg())
                    || Double.isNaN(result.timeOfFlightTicks()), "target " + target);
        }
    }
}