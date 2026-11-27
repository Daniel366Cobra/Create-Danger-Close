package io.github.daniel366cobra.createdangerclose.compat.goingballistic;

import io.github.daniel366cobra.createdangerclose.block.cannon_servo.CannonServoOutputBlockEntity.SlavedMount;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;
import org.hectoris919.CBCGoingBallistic.Config;
import org.hectoris919.CBCGoingBallistic.ballistics.BallisticProjectileHelper;
import org.hectoris919.CBCGoingBallistic.ballistics.ProjectilePhysicsMath;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.munitions.config.DimensionMunitionPropertiesHandler;

import java.util.Objects;

/**
 * CBC: Going Ballistic reworks CBC's projectile physics: real gravity, per-projectile quadratic drag
 * (mass, reference area, drag coefficient, air density) and Robins-based muzzle velocity from the shell,
 * the propellant and the barrel. Its estimate APIs predict exactly what the fired shell will do, so our
 * solver reads those numbers when the mod is present and its config has the realistic paths on.
 *
 * Compile-time optional: {@link #active()} gates everything, and the estimates themselves return null
 * on any trouble, in which case the mount keeps CBC's values.
 */
public final class GoingBallistics {

    public static final String MOD_ID = "cbc_going_ballistic";

    private GoingBallistics() {}

    /** The effective ballistics for one mount load: what {@link BallisticSolver} should integrate. */
    public record Params(double gravity, double drag, boolean quadratic, float muzzleVelocity) {
        /** Copies the values over the CBC ones just read from the mount. */
        public void applyTo(SlavedMount sm) {
            sm.gravity = gravity;
            sm.drag = drag;
            sm.isQuadraticDrag = quadratic;
            sm.muzzleVelocity = muzzleVelocity;
        }
    }

    public static boolean active() {
        return ModList.get().isLoaded(MOD_ID);
    }

    /**
     * What the mount is loaded with: recomputing the estimates is only worth it when this changes
     * (they scan the barrel and parse the shell's NBT). Barrel composition changes with reassembly,
     * which disassembles the contraption and clears the cached params. GB's config toggles are part
     * of the key too: flipping realistic ballistics mid-game must invalidate the cached params, or
     * the computer keeps laying guns with the old physics until the load happens to change.
     */
    public static int loadHash(SlavedMount sm) {
        return Objects.hash(sm.autocannon, sm.hasShell, sm.shellType, sm.totalChargePower, sm.addedChargePower,
                sm.fuzeTimer, Config.disableRealisticBallistics(), Config.disableRealisticProjectileDrag(),
                Config.disableRealisticProjectileGravity());
    }

    /**
     * The ballistics GB predicts for the mount's current load. Where GB's config has a realistic path
     * off (or an estimate fails), the mount's own CBC values stand: {@code muzzleVelocity} is 0 there,
     * meaning the solver's charge-power velocity as without the mod. Call only server-side, assembled.
     */
    public static Params compute(SlavedMount sm, PitchOrientedContraptionEntity entity) {
        double gravity = sm.gravity;
        double drag = sm.drag;
        boolean quadratic = sm.isQuadraticDrag;
        float muzzleVelocity = 0;
        if (Config.disableRealisticBallistics()
                || !(entity.level() instanceof ServerLevel level)
                || !(entity.getContraption() instanceof AbstractMountedCannonContraption cannon))
            return new Params(gravity, drag, quadratic, muzzleVelocity);
        try {
            // CBC's own numbers: charge power for a big cannon, barrel-derived speed for an autocannon.
            float cbcSpeed = sm.totalChargePower + sm.addedChargePower;
            BallisticProjectileHelper.RadarShotEstimate estimate = sm.autocannon
                    ? BallisticProjectileHelper.estimateAutocannonForRadar(cannon, level, cbcSpeed)
                    : BallisticProjectileHelper.estimateBigCannonForRadar(cannon, level, cbcSpeed);
            if (estimate != null) {
                if (estimate.muzzleSpeedBlocksPerTick() > 0)
                    muzzleVelocity = estimate.muzzleSpeedBlocksPerTick();
                // GB's drag is always quadratic in the speed, with a per-shell coefficient.
                if (!Config.disableRealisticProjectileDrag() && estimate.quadraticDragCoefficient() > 0) {
                    drag = estimate.quadraticDragCoefficient();
                    quadratic = true;
                }
            }
            if (!Config.disableRealisticProjectileGravity()) {
                double multiplier = DimensionMunitionPropertiesHandler.getProperties(level).gravityMultiplier();
                double g = ProjectilePhysicsMath.earthGravityBlocksPerTickSquared() * multiplier;
                if (g < 0)
                    gravity = g;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Same promise as GB's own estimates: any failure leaves the CBC values in place.
        }
        return new Params(gravity, drag, quadratic, muzzleVelocity);
    }
}
