package io.github.daniel366cobra.createdangerclose.damage;

import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderLensBlock;
import io.github.daniel366cobra.createdangerclose.foundation.damage.BattleDamageable;
import io.github.daniel366cobra.createdangerclose.foundation.damage.DamagePart;
import io.github.daniel366cobra.createdangerclose.registries.CDCTriggers;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** "Something's Not Right..." for a player looking at faulty equipment up close, in plain sight; a cracked lens is also Dioptric Distress. */
@EventBusSubscriber(modid = CreateDangerClose.MOD_ID)
final class MalfunctionSpotting {

    private static final double LOOK_RANGE = 3;
    private static final int INTERVAL_TICKS = 20;
    // Short of the part: its own block must not hide it.
    private static final double SIGHT_MARGIN = 0.05;

    private MalfunctionSpotting() {}

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getGameTime() % INTERVAL_TICKS != 0
                || level.players().isEmpty())
            return;
        List<DamagePart> faulty = new ArrayList<>();
        for (BattleDamageable equipment : BattleDamage.loadedIn(level))
            faulty.addAll(equipment.faultyParts());
        for (ServerPlayer player : level.players()) {
            Set<DamagePart.PartKind> seen = player.isSpectator()
                    ? Set.of() : seenMalfunctions(level, player, faulty);
            if (!seen.isEmpty())
                CDCTriggers.EQUIPMENT_MALFUNCTION.get().trigger(player);
            if (seen.contains(DamagePart.PartKind.LENS))
                CDCTriggers.LENS_CRACKED.get().trigger(player);
        }
    }

    private static Set<DamagePart.PartKind> seenMalfunctions(ServerLevel level, ServerPlayer player, List<DamagePart> faulty) {
        Set<DamagePart.PartKind> seen = EnumSet.noneOf(DamagePart.PartKind.class);
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1);
        Vec3 end = eye.add(look.scale(LOOK_RANGE));
        // A cracked lens of a tube standing as blocks.
        BlockHitResult block = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (block.getType() == HitResult.Type.BLOCK) {
            BlockState state = level.getBlockState(block.getBlockPos());
            if (state.getBlock() instanceof RangefinderLensBlock && state.getValue(RangefinderLensBlock.CRACKED))
                seen.add(DamagePart.PartKind.LENS);
        }
        for (DamagePart part : faulty) {
            if (seen.contains(part.kind()))
                continue;
            if (part.centerWorld().distanceToSqr(eye) > (LOOK_RANGE + 2) * (LOOK_RANGE + 2))
                continue;
            // Poses are rigid, so distances carry over from the part's frame.
            Vec3 localEye = part.worldToLocal().apply(eye);
            AABB box = part.box();
            Optional<Vec3> hit = box.clip(localEye, part.worldToLocal().apply(end));
            if (box.contains(localEye)) {
                seen.add(part.kind());
                continue;
            }
            if (hit.isEmpty())
                continue;
            double distance = hit.get().distanceTo(localEye);
            if (distance <= SIGHT_MARGIN || level.clip(new ClipContext(eye, eye.add(look.scale(distance - SIGHT_MARGIN)),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)).getType() == HitResult.Type.MISS)
                seen.add(part.kind());
        }
        return seen;
    }
}