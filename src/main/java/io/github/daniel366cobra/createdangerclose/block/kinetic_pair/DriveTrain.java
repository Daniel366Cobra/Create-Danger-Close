package io.github.daniel366cobra.createdangerclose.block.kinetic_pair;

import com.simibubi.create.content.kinetics.base.DirectionalShaftHalvesBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.gearbox.GearboxBlockEntity;
import com.simibubi.create.content.kinetics.speedController.SpeedControllerBlockEntity;
import com.simibubi.create.content.kinetics.transmission.ClutchBlockEntity;
import com.simibubi.create.content.kinetics.transmission.GearshiftBlockEntity;
import io.github.daniel366cobra.createdangerclose.mixin.RotationPropagatorInvoker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a source drives, traced by Create's own connection rules. Unlike its network this works while
 * the source stands still, when Create keeps no network at all.
 */
public final class DriveTrain {

    /**
     * A block it drives, turning {@code ratio} RPM per RPM of the source (sign: sense of rotation).
     * Governed: past a speed controller, turning {@code ratio} RPM whenever the source turns at all.
     */
    public record Link(KineticBlockEntity be, float ratio, boolean governed) {}

    private static final int MAX_LINKS = 256;

    private DriveTrain() {}

    /** The source first, then outward. */
    public static List<Link> trace(KineticBlockEntity source) {
        Map<KineticBlockEntity, Link> links = new LinkedHashMap<>();
        // The side each block was reached from.
        Map<KineticBlockEntity, Direction> entries = new HashMap<>();
        ArrayDeque<KineticBlockEntity> queue = new ArrayDeque<>();
        links.put(source, new Link(source, 1, false));
        queue.add(source);
        while (!queue.isEmpty() && links.size() < MAX_LINKS) {
            KineticBlockEntity from = queue.poll();
            Link reached = links.get(from);
            for (KineticBlockEntity to : RotationPropagatorInvoker.createdangerclose$getConnectedNeighbours(from)) {
                if (links.containsKey(to))
                    continue;
                BlockPos diff = to.getBlockPos().subtract(from.getBlockPos());
                Direction towards = Direction.getNearest(diff.getX(), diff.getY(), diff.getZ());
                float setting = controllerSetting(from, to);
                if (!Float.isNaN(setting)) {
                    links.put(to, new Link(to, setting, true));
                } else {
                    float modifier = modifier(from, entries.get(from), to, towards);
                    if (modifier == 0)
                        continue;
                    links.put(to, new Link(to, reached.ratio() * modifier, reached.governed()));
                }
                entries.put(to, towards.getOpposite());
                queue.add(to);
            }
        }
        return new ArrayList<>(links.values());
    }

    /** su per RPM of the source: each block's impact at its own speed (gearing included). */
    public static float perRpm(List<Link> links) {
        float load = 0;
        for (int i = 1; i < links.size(); i++)
            if (!links.get(i).governed())
                load += links.get(i).be().calculateStressApplied() * Math.abs(links.get(i).ratio());
        return load;
    }

    /** su whenever the source turns at all: what runs past speed controllers, at their settings. */
    public static float governedLoad(List<Link> links) {
        float load = 0;
        for (Link link : links)
            if (link.governed())
                load += link.be().calculateStressApplied() * Math.abs(link.ratio());
        return load;
    }

    /** Between a speed controller and the large cog on it, the far one turns at the setting (RPM); else NaN. */
    private static float controllerSetting(KineticBlockEntity from, KineticBlockEntity to) {
        BlockState fromState = from.getBlockState(), toState = to.getBlockState();
        KineticBlockEntity controller;
        if (RotationPropagatorInvoker.createdangerclose$isLargeCogToSpeedController(fromState, toState,
                to.getBlockPos().subtract(from.getBlockPos())))
            controller = to;
        else if (RotationPropagatorInvoker.createdangerclose$isLargeCogToSpeedController(toState, fromState,
                from.getBlockPos().subtract(to.getBlockPos())))
            controller = from;
        else
            return Float.NaN;
        return controller instanceof SpeedControllerBlockEntity speedController
                ? speedController.targetSpeed.getValue() : Float.NaN;
    }

    private static float modifier(KineticBlockEntity from, Direction fromEntry, KineticBlockEntity to, Direction towards) {
        float modifier = RotationPropagatorInvoker.createdangerclose$getRotationSpeedModifier(from, to);
        if (modifier == 0
                || !(from instanceof DirectionalShaftHalvesBlockEntity || to instanceof DirectionalShaftHalvesBlockEntity))
            return modifier;
        // Create turns these by their source, which a still network lacks: use the side the trace came in by.
        // The side a block is entered by turns as it does, so only the far one counts.
        return from instanceof DirectionalShaftHalvesBlockEntity && fromEntry != null
                ? halvesModifier(from, fromEntry, towards) : 1;
    }

    private static float halvesModifier(KineticBlockEntity be, Direction entry, Direction towards) {
        if (be instanceof GearboxBlockEntity)
            return towards.getAxis() == entry.getAxis() ? towards == entry ? 1 : -1
                    : towards.getAxisDirection() == entry.getAxisDirection() ? -1 : 1;
        BlockState state = be.getBlockState();
        boolean powered = state.hasProperty(BlockStateProperties.POWERED) && state.getValue(BlockStateProperties.POWERED);
        if (towards == entry || !powered)
            return 1;
        if (be instanceof GearshiftBlockEntity)
            return -1;
        if (be instanceof ClutchBlockEntity)
            return 0;
        return 1;
    }
}