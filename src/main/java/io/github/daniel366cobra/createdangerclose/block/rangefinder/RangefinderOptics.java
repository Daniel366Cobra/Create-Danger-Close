package io.github.daniel366cobra.createdangerclose.block.rangefinder;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Geometry shared by server and client: which tube a player operates, and where it looks. */
public final class RangefinderOptics {

    /** Origin between the lenses, unit direction. */
    public record Sight(Vec3 origin, Vec3 direction) {}

    private RangefinderOptics() {}

    @Nullable
    public static AbstractContraptionEntity findOperatedContraption(@Nullable Player player) {
        if (player == null
                || !(player.getVehicle() instanceof AbstractContraptionEntity contraptionEntity)
                || !(contraptionEntity.getContraption() instanceof RangefinderContraption tube))
            return null;
        BlockPos eyepiece = tube.getEyepiecePos();
        return eyepiece != null && eyepiece.equals(tube.getSeatOf(player.getUUID())) ? contraptionEntity : null;
    }

    @Nullable
    public static RangefinderContraption findOperatedTube(@Nullable Player player) {
        AbstractContraptionEntity contraptionEntity = findOperatedContraption(player);
        return contraptionEntity != null ? (RangefinderContraption) contraptionEntity.getContraption() : null;
    }

    /** The contraption entity sits at the anchor, one block from the bearing along its facing. */
    @Nullable
    public static RangefinderBearingBlockEntity findOperatedBearing(@Nullable Player player) {
        AbstractContraptionEntity contraptionEntity = findOperatedContraption(player);
        if (contraptionEntity == null)
            return null;
        RangefinderContraption tube = (RangefinderContraption) contraptionEntity.getContraption();
        if (tube.getFacing() == null)
            return null;
        BlockPos anchor = BlockPos.containing(contraptionEntity.position());
        BlockPos bearingPos = anchor.relative(tube.getFacing().getOpposite());
        return player.level().getBlockEntity(bearingPos) instanceof RangefinderBearingBlockEntity bearing ? bearing : null;
    }

    /** World coordinates: the operator's rotation is ship-relative (Sable), so it is turned by the ship's. */
    @Nullable
    public static Sight lineOfSight(@Nullable Player player) {
        AbstractContraptionEntity contraptionEntity = findOperatedContraption(player);
        if (contraptionEntity == null)
            return null;
        Vec3 viewPoint = ((RangefinderContraption) contraptionEntity.getContraption()).getOperatorViewPoint();
        if (viewPoint == null)
            return null;

        Vec3 origin = contraptionEntity.toGlobalVector(viewPoint, 1f);
        Vec3 direction = Vec3.directionFromRotation(player.getXRot(), player.getYRot());
        SubLevel ship = Sable.HELPER.getContaining(player.level(), origin);
        if (ship != null) {
            origin = ship.logicalPose().transformPosition(origin);
            direction = ship.logicalPose().transformNormal(direction);
        }
        return new Sight(origin, direction.normalize());
    }

    /** Null when not operating or not on a ship. */
    @Nullable
    public static SubLevel operatedShip(@Nullable Player player) {
        AbstractContraptionEntity contraptionEntity = findOperatedContraption(player);
        if (contraptionEntity == null)
            return null;
        Vec3 viewPoint = ((RangefinderContraption) contraptionEntity.getContraption()).getOperatorViewPoint();
        if (viewPoint == null)
            return null;
        return Sable.HELPER.getContaining(player.level(), contraptionEntity.toGlobalVector(viewPoint, 1f));
    }

    /** From a Sable plot position, if on a ship. */
    public static Vec3 toWorld(Level level, Vec3 position) {
        SubLevel ship = Sable.HELPER.getContaining(level, position);
        return ship != null ? ship.logicalPose().transformPosition(position) : position;
    }

    /** Degrees: 0 = north, clockwise. */
    public static double compassBearing(Vec3 direction) {
        double bearing = Math.toDegrees(Math.atan2(direction.x, -direction.z));
        return bearing < 0 ? bearing + 360 : bearing;
    }
}