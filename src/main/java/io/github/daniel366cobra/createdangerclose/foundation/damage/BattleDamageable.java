package io.github.daniel366cobra.createdangerclose.foundation.damage;

import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Equipment battle can damage: it gives its parts and what blast and hits do; {@code BattleDamage}
 * finds what reaches them. Block entities register with {@code BattleDamage.track} on the server.
 */
public interface BattleDamageable {

    /** Null if gone (then dropped). */
    @Nullable
    Level damageLevel();

    /** As they stand now; fully damaged parts may be left out. */
    List<DamagePart> damageParts();

    /**
     * In line of sight.
     *
     * @param severity 1 at the center, 0 at the edge of its reach
     */
    void onBlast(DamagePart part, double severity);

    /** A shell or a burst's ball passed through. */
    void onHit(DamagePart part);

    /** Parts out of order or out of true, for players to notice up close. */
    default List<DamagePart> faultyParts() {
        return List.of();
    }
}