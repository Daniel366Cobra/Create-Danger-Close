package io.github.daniel366cobra.createdangerclose.block.rangefinder;

import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;
import rbasamoyai.createbigcannons.remix.CustomExplosion;
import rbasamoyai.ritchiesprojectilelib.projectile_burst.ProjectileBurst;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Battle damage to assembled rangefinder tubes: lenses crack from blast, and
 * from shells and shrapnel, grapeshot or flak balls striking them.
 *
 * <p>An assembled tube is a Create contraption, which nothing can hit or hurt:
 * its blocks are not in the world, and CBC's projectiles pass through
 * contraption entities. So this does the geometry itself, in world space
 * (through the ship's pose on a Sable ship):
 * <ul>
 *   <li>blast: on every explosion (CBC's shells raise the normal NeoForge
 *       event), lenses within reach and in line of sight of it crack;</li>
 *   <li>hits: every tick, each tube tests the paths of nearby CBC projectiles
 *       and burst balls against its lens blocks. The projectile itself flies
 *       on: there is no way to stop it cleanly.</li>
 * </ul>
 * Lenses that are not part of a tube are ordinary blocks, which explosions and
 * shells already destroy.
 */
@EventBusSubscriber(modid = CreateDangerClose.MOD_ID)
public final class RangefinderDamage {

    // Blast cracks lenses out to this many times the explosion's radius.
    private static final double BLAST_REACH = 1.5;
    // The line-of-sight check stops this far short of the blast centre, which
    // often lies on or inside the block the shell struck.
    private static final double BLAST_SIGHT_MARGIN = 0.75;

    // How far around the tube to look for projectiles: their paths are tested
    // a tick at a time, so this must exceed the fastest shell's speed per tick.
    private static final double PROJECTILE_SEARCH_MARGIN = 16;
    // How much bigger than its block a lens counts as, for a shell and for a ball.
    private static final double SHELL_HIT_MARGIN = 0.25;
    private static final double BALL_HIT_MARGIN = 0.05;

    // Loaded rangefinder bearings, server side. Weak, so a missed untrack cannot leak.
    private static final Set<RangefinderBearingBlockEntity> LOADED =
            Collections.newSetFromMap(new WeakHashMap<>());

    private RangefinderDamage() {}

    static void track(RangefinderBearingBlockEntity bearing) {
        LOADED.add(bearing);
    }

    static void untrack(RangefinderBearingBlockEntity bearing) {
        LOADED.remove(bearing);
    }

    // --- Blast ---

    @SubscribeEvent
    static void onExplosion(ExplosionEvent.Detonate event) {
        Level level = event.getLevel();
        if (level.isClientSide)
            return;
        Explosion explosion = event.getExplosion();
        // CBC's explosions reach entities further than they break blocks.
        float radius = explosion instanceof CustomExplosion custom
                ? Math.max(explosion.radius(), custom.getEntityRadius()) : explosion.radius();
        double reach = radius * BLAST_REACH;
        Vec3 center = explosion.center();

        // Copied: cracking a lens does not change the set, but be safe against reentry.
        for (RangefinderBearingBlockEntity bearing : new ArrayList<>(LOADED)) {
            if (bearing.isRemoved() || bearing.getLevel() != level)
                continue;
            ControlledContraptionEntity tubeEntity = bearing.getMovedContraption();
            if (tubeEntity == null || !(tubeEntity.getContraption() instanceof RangefinderContraption tube))
                continue;
            for (BlockPos lens : tube.getLenses()) {
                if (tube.isLensCracked(lens))
                    continue;
                Vec3 lensWorld = RangefinderOptics.toWorld(level, tubeEntity.toGlobalVector(lens.getCenter(), 1f));
                double distance = lensWorld.distanceTo(center);
                if (distance <= reach && hasLineOfSight(level, lensWorld, center, distance))
                    bearing.crackLens(lens);
            }
        }
    }

    /** Whether anything solid stands between a lens and a blast. */
    private static boolean hasLineOfSight(Level level, Vec3 lensWorld, Vec3 center, double distance) {
        if (distance <= BLAST_SIGHT_MARGIN)
            return true;
        Vec3 end = lensWorld.lerp(center, (distance - BLAST_SIGHT_MARGIN) / distance);
        return level.clip(new ClipContext(lensWorld, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                CollisionContext.empty())).getType() == HitResult.Type.MISS;
    }

    // --- Shells and balls ---

    /** Called by an assembled rangefinder every server tick. */
    static void checkProjectiles(RangefinderBearingBlockEntity bearing, ControlledContraptionEntity tubeEntity,
                                 RangefinderContraption tube) {
        Level level = bearing.getLevel();
        List<BlockPos> lenses = tube.getLenses();
        if (level == null || lenses.isEmpty() || tube.getCrackedLensCount() == lenses.size())
            return;

        // Projectiles fly in world space; the tube may be on a ship.
        SubLevel ship = Sable.HELPER.getContaining(level, bearing.getBlockPos());
        Vec3 tubeCenter = lenses.get(0).getCenter().add(lenses.get(1).getCenter()).scale(0.5);
        Vec3 centerWorld = RangefinderOptics.toWorld(level, tubeEntity.toGlobalVector(tubeCenter, 1f));
        AABB search = AABB.ofSize(centerWorld, 0, 0, 0)
                .inflate(tube.getBaseline() / 2.0 + 1 + PROJECTILE_SEARCH_MARGIN);

        List<Projectile> projectiles = level.getEntitiesOfClass(Projectile.class, search,
                p -> p instanceof AbstractCannonProjectile || p instanceof ProjectileBurst);
        if (projectiles.isEmpty())
            return;

        for (Projectile projectile : projectiles) {
            if (projectile instanceof ProjectileBurst burst) {
                for (ProjectileBurst.SubProjectile ball : burst.getSubProjectiles()) {
                    double[] d = ball.displacement(), v = ball.velocity();
                    // Bursts tick before block entities: the ball is where this tick took it.
                    Vec3 now = burst.position().add(d[0], d[1], d[2]);
                    Vec3 before = now.subtract(v[0], v[1], v[2]);
                    crackLensesOnPath(bearing, tubeEntity, tube, ship, before, now, BALL_HIT_MARGIN);
                }
            } else {
                Vec3 before = new Vec3(projectile.xo, projectile.yo, projectile.zo);
                crackLensesOnPath(bearing, tubeEntity, tube, ship, before, projectile.position(), SHELL_HIT_MARGIN);
            }
        }
    }

    /** Cracks each intact lens that the world-space path from {@code from} to {@code to} passes through. */
    private static void crackLensesOnPath(RangefinderBearingBlockEntity bearing, ControlledContraptionEntity tubeEntity,
                                          RangefinderContraption tube, SubLevel ship, Vec3 from, Vec3 to,
                                          double margin) {
        // World -> ship -> tube: in the tube's own frame, each block is a unit box at its local position.
        if (ship != null) {
            from = ship.logicalPose().transformPositionInverse(from);
            to = ship.logicalPose().transformPositionInverse(to);
        }
        from = tubeEntity.toLocalVector(from, 1f);
        to = tubeEntity.toLocalVector(to, 1f);

        for (BlockPos lens : tube.getLenses()) {
            if (tube.isLensCracked(lens))
                continue;
            AABB box = new AABB(lens).inflate(margin);
            if (box.contains(to) || box.clip(from, to).isPresent())
                bearing.crackLens(lens);
        }
    }
}
