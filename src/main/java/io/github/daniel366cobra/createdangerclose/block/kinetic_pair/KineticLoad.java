package io.github.daniel366cobra.createdangerclose.block.kinetic_pair;

import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import java.util.Map;

/** Measuring what a network a source drives asks of it. */
public final class KineticLoad {

    private KineticLoad() {}

    /**
     * su per RPM of the source: members' impacts times their speed ratio (gearing included). Holds
     * mid-change, unlike network stress. While stopped, the last figure is kept (else ungeared impacts).
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