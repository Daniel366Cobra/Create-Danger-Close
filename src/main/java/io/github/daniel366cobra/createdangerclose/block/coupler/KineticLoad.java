package io.github.daniel366cobra.createdangerclose.block.coupler;

import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import java.util.Map;

/** Measuring what a network a source drives asks of it. */
public final class KineticLoad {

    private KineticLoad() {}

    /**
     * The load on the network this source drives, per RPM of its own speed (su): each
     * member's stress impact times its speed relative to the source's (gearing included).
     * The members' speeds follow the source's at once when it changes, so this holds even
     * mid-change, unlike the network's stress, which is counted at the speed before.
     * While the source stands still the ratios are unknown: the last figure is kept, or
     * before any, the members' impacts as if ungeared.
     */
    public static float perRpmOf(KineticBlockEntity source, float last) {
        if (!source.hasNetwork())
            return last;
        KineticNetwork network = source.getOrCreateNetwork();
        float own = Math.abs(source.getTheoreticalSpeed());
        float load = 0;
        for (Map.Entry<KineticBlockEntity, Float> member : network.members.entrySet()) {
            if (member.getKey() == source)
                continue;
            if (own > 1e-3f)
                load += member.getValue() * Math.abs(member.getKey().getTheoreticalSpeed()) / own;
            else if (last <= 0)
                load += member.getValue();
        }
        return own > 1e-3f || last <= 0 ? load : last;
    }
}
