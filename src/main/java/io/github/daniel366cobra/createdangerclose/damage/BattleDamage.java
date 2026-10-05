package io.github.daniel366cobra.createdangerclose.damage;

import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import io.github.daniel366cobra.createdangerclose.api.damage.BattleDamageable;
import io.github.daniel366cobra.createdangerclose.api.damage.DamagePart;
import io.github.daniel366cobra.createdangerclose.config.CDCConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
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
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;
import rbasamoyai.createbigcannons.remix.CustomExplosion;
import rbasamoyai.ritchiesprojectilelib.projectile_burst.ProjectileBurst;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Battle damage to equipment ({@link BattleDamageable}): blast, and shells and
 * shrapnel, grapeshot or flak balls striking it.
 *
 * <p>Much equipment cannot be hit by the game itself: a contraption's blocks are
 * not in the world, and CBC's projectiles pass through contraption entities; and
 * a block that survives a blast is not otherwise marked by it. So this does the
 * geometry itself, in world space:
 * <ul>
 *   <li>blast: on every explosion (CBC's shells raise the normal NeoForge event),
 *       parts within reach and in line of sight of it suffer, the more the closer;</li>
 *   <li>hits: every tick, the paths of CBC projectiles and burst balls near
 *       equipment are tested against its parts. The projectile itself flies on:
 *       there is no way to stop it cleanly.</li>
 * </ul>
 */
@EventBusSubscriber(modid = CreateDangerClose.MOD_ID)
public final class BattleDamage {

    // The line-of-sight check stops this far short of the blast centre, which
    // often lies on or inside the block the shell struck.
    private static final double BLAST_SIGHT_MARGIN = 0.75;

    // How far around equipment to look for projectiles: their paths are tested
    // a tick at a time, so this must exceed the fastest shell's speed per tick.
    private static final double PROJECTILE_SEARCH_MARGIN = 16;
    // How much bigger than its box a part counts as, for a shell and for a ball.
    private static final double SHELL_HIT_MARGIN = 0.25;
    private static final double BALL_HIT_MARGIN = 0.05;

    // Loaded equipment, server side. Weak, so a missed untrack cannot leak.
    private static final Set<BattleDamageable> LOADED = Collections.newSetFromMap(new WeakHashMap<>());

    private BattleDamage() {}

    public static void track(BattleDamageable equipment) {
        LOADED.add(equipment);
    }

    public static void untrack(BattleDamageable equipment) {
        LOADED.remove(equipment);
    }

    /** The loaded equipment in this level (a copy: damage may change the set). */
    private static List<BattleDamageable> loadedIn(Level level) {
        List<BattleDamageable> found = new ArrayList<>();
        for (BattleDamageable equipment : LOADED)
            if (equipment.damageLevel() == level)
                found.add(equipment);
        return found;
    }

    /** How far a blast reaches, in its radii (severity 0 there, 1 at its centre). */
    private static double blastReach() {
        return CDCConfig.server().battleDamage.blastReach.getF();
    }

    /** How far from a blast's centre a part it reached with this severity is, in the blast's radii. */
    public static double radiiFromCentre(double severity) {
        return blastReach() * (1 - severity);
    }

    // --- Blast ---

    @SubscribeEvent
    static void onExplosion(ExplosionEvent.Detonate event) {
        Level level = event.getLevel();
        if (level.isClientSide || !CDCConfig.server().battleDamage.allowBattleDamage.get())
            return;
        Explosion explosion = event.getExplosion();
        // CBC's explosions reach entities further than they break blocks.
        float radius = explosion instanceof CustomExplosion custom
                ? Math.max(explosion.radius(), custom.getEntityRadius()) : explosion.radius();
        double reach = radius * blastReach();
        Vec3 center = explosion.center();

        for (BattleDamageable equipment : loadedIn(level)) {
            for (DamagePart part : equipment.damageParts()) {
                double distance = part.centreWorld().distanceTo(center);
                if (distance <= reach && hasLineOfSight(level, part, center, distance))
                    equipment.onBlast(part, 1 - distance / reach);
            }
        }
    }

    /**
     * Whether anything solid stands between a part and a blast. The sight line starts
     * at the edge of the part, on the blast's side: from inside, a part that is a
     * block in the world would block its own view.
     */
    private static boolean hasLineOfSight(Level level, DamagePart part, Vec3 center, double distance) {
        Vec3 partWorld = part.centreWorld();
        // Out past the part's own box (half its diagonal, and a little).
        double clearance = part.box().getSize() * Math.sqrt(3) / 2 + 0.05;
        if (distance <= clearance + BLAST_SIGHT_MARGIN)
            return true;
        Vec3 start = partWorld.lerp(center, clearance / distance);
        Vec3 end = partWorld.lerp(center, (distance - BLAST_SIGHT_MARGIN) / distance);
        return level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                CollisionContext.empty())).getType() == HitResult.Type.MISS;
    }

    // --- Shells and balls ---

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !CDCConfig.server().battleDamage.allowBattleDamage.get()
                || !CDCConfig.server().battleDamage.allowDirectHits.get())
            return;
        List<BattleDamageable> equipment = loadedIn(level);
        if (equipment.isEmpty())
            return;
        List<Entity> projectiles = new ArrayList<>();
        for (Entity entity : level.getAllEntities())
            if (entity instanceof AbstractCannonProjectile || entity instanceof ProjectileBurst)
                projectiles.add(entity);
        if (projectiles.isEmpty())
            return;

        for (BattleDamageable item : equipment) {
            List<DamagePart> parts = item.damageParts();
            if (parts.isEmpty())
                continue;
            AABB search = null;
            for (DamagePart part : parts) {
                AABB around = AABB.ofSize(part.centreWorld(), 0, 0, 0);
                search = search == null ? around : search.minmax(around);
            }
            search = search.inflate(1 + PROJECTILE_SEARCH_MARGIN);

            for (Entity projectile : projectiles) {
                if (!search.contains(projectile.position()))
                    continue;
                if (projectile instanceof ProjectileBurst burst) {
                    for (ProjectileBurst.SubProjectile ball : burst.getSubProjectiles()) {
                        double[] d = ball.displacement(), v = ball.velocity();
                        // The ball is where this tick took it.
                        Vec3 now = burst.position().add(d[0], d[1], d[2]);
                        Vec3 before = now.subtract(v[0], v[1], v[2]);
                        hitPartsOnPath(item, parts, before, now, BALL_HIT_MARGIN);
                    }
                } else {
                    Vec3 before = new Vec3(projectile.xo, projectile.yo, projectile.zo);
                    hitPartsOnPath(item, parts, before, projectile.position(), SHELL_HIT_MARGIN);
                }
            }
        }
    }

    /** Hits each part that the world-space path from {@code from} to {@code to} passes through. */
    private static void hitPartsOnPath(BattleDamageable equipment, List<DamagePart> parts, Vec3 from, Vec3 to,
                                       double margin) {
        for (DamagePart part : parts) {
            // In the part's own frame it is a plain box.
            Vec3 localFrom = part.worldToLocal().apply(from), localTo = part.worldToLocal().apply(to);
            AABB box = part.box().inflate(margin);
            if (box.contains(localTo) || box.clip(localFrom, localTo).isPresent())
                equipment.onHit(part);
        }
    }
}
