package io.github.daniel366cobra.createdangerclose.api.damage;

import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Equipment that battle can damage: it says where its vulnerable parts are, and
 * what blast and hits do to them. Finding the explosions and projectiles that
 * reach those parts is done for it (see {@code BattleDamage}), through ships'
 * poses and moving contraptions alike.
 *
 * <p>Implemented by a block entity, which registers itself with
 * {@code BattleDamage.track} once loaded on the server and unregisters when
 * removed.
 */
public interface BattleDamageable {

    /** The level it is in, or null if it is gone (it is then dropped). */
    @Nullable
    Level damageLevel();

    /**
     * Its parts that battle can still damage, as they stand now. A part that is
     * already as damaged as it can get may be left out.
     */
    List<DamagePart> damageParts();

    /**
     * A blast reached a part, in line of sight.
     *
     * @param severity 1 at the blast's centre, falling to 0 at the edge of its reach
     */
    void onBlast(DamagePart part, double severity);

    /** A shell or a burst's ball passed through a part. */
    void onHit(DamagePart part);
}
