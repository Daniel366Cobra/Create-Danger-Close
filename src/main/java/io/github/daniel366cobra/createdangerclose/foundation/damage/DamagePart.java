package io.github.daniel366cobra.createdangerclose.foundation.damage;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.function.UnaryOperator;

/**
 * A vulnerable part of a piece of equipment: a box in its own frame (a block's
 * place in the level, a lens's in its contraption), and how to get there from
 * the world and back.
 *
 * @param id          which part, as the equipment knows it (a lens's local position, ...)
 * @param kind        what sort of part: which faults it can suffer
 * @param box         the part's box, in its own frame
 * @param centreWorld the part's centre in the world, for blast distances
 * @param worldToLocal turns a world position into the part's frame (through a ship's
 *                    pose and a contraption's rotation, as it stands now)
 */
public record DamagePart(Object id, PartKind kind, AABB box, Vec3 centreWorld, UnaryOperator<Vec3> worldToLocal) {

    public enum PartKind {
        /** Glass: cracks, gathers dust. */
        LENS,
        /** Gears and linkages: jostled, broken, seized. */
        MECHANISM
    }
}
