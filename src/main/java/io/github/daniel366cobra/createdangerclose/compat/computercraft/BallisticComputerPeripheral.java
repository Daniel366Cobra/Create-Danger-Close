package io.github.daniel366cobra.createdangerclose.compat.computercraft;

import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import io.github.daniel366cobra.createdangerclose.block.ballistic_computer.BallisticComputerBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.ballistic_computer.ComputerModule;
import io.github.daniel366cobra.createdangerclose.foundation.ballistics.BallisticSolver;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read-only: artillery stays a player-run job, so scripts can watch the fire control but never set
 * targets, lay guns or fire. Angles in degrees, times in ticks, distances in blocks.
 */
public class BallisticComputerPeripheral implements IPeripheral {

    private final BallisticComputerBlockEntity computer;

    public BallisticComputerPeripheral(BallisticComputerBlockEntity computer) {
        this.computer = computer;
    }

    @Override
    public String getType() {
        return "ballistic_computer";
    }

    @Override
    public boolean equals(@Nullable IPeripheral other) {
        return other instanceof BallisticComputerPeripheral peripheral && peripheral.computer == computer;
    }

    @LuaFunction(mainThread = true)
    public final boolean isPowered() {
        return computer.isPowered();
    }

    /** Two computers in one bank: neither works. */
    @LuaFunction(mainThread = true)
    public final boolean hasBankConflict() {
        return computer.hasBankConflict();
    }

    @LuaFunction(mainThread = true)
    public final List<String> getModules() {
        List<String> modules = new ArrayList<>();
        for (ComputerModule module : ComputerModule.values())
            if (computer.has(module))
                modules.add(module.id());
        return modules;
    }

    /** "direct" or "plunging". */
    @LuaFunction(mainThread = true)
    public final String getFireMode() {
        return computer.isPlungingFire() ? "plunging" : "direct";
    }

    /** {x, y, z, source, sigma, live, velocity?} in world space, or nil. */
    @LuaFunction(mainThread = true)
    @Nullable
    public final Map<String, Object> getTarget() {
        Vec3 target = computer.getTargetPos();
        if (target == null)
            return null;
        Map<String, Object> table = vector(target);
        table.put("source", computer.getTargetSource().name().toLowerCase(Locale.ROOT));
        table.put("sigma", computer.getFixSigma());
        table.put("live", computer.isTargetLive());
        Vec3 velocity = computer.getTargetVelocity();
        if (velocity != null)
            table.put("velocity", vector(velocity));
        return table;
    }

    /**
     * One entry per reporting servo: {servo, mount?, name?, autocannon, valid, reason, onTarget, danger?},
     * with {pitch, yaw, timeOfFlight, range, fuze} when it has a solution.
     */
    @LuaFunction(mainThread = true)
    public final List<Map<String, Object>> getGuns() {
        List<Map<String, Object>> guns = new ArrayList<>();
        for (BallisticComputerBlockEntity.GunReport report : computer.getGunReports()) {
            Map<String, Object> gun = new HashMap<>();
            gun.put("servo", position(report.servo()));
            if (report.mount() != null)
                gun.put("mount", position(report.mount()));
            if (report.name() != null)
                gun.put("name", report.name());
            gun.put("autocannon", report.autocannon());
            BallisticSolver.Result solution = report.solution();
            gun.put("valid", solution.valid());
            gun.put("reason", solution.valid() ? "ok" : solution.reason().name().toLowerCase(Locale.ROOT));
            if (solution.valid()) {
                gun.put("pitch", solution.pitchDeg());
                gun.put("yaw", solution.yawDeg());
                gun.put("timeOfFlight", solution.timeOfFlightTicks());
                gun.put("range", solution.range());
                gun.put("fuze", solution.fuzeTicks());
            }
            gun.put("onTarget", report.onTarget());
            if (report.danger() != null)
                gun.put("danger", report.danger().name().toLowerCase(Locale.ROOT));
            guns.add(gun);
        }
        return guns;
    }

    private static Map<String, Object> vector(Vec3 vector) {
        Map<String, Object> table = new HashMap<>();
        table.put("x", vector.x);
        table.put("y", vector.y);
        table.put("z", vector.z);
        return table;
    }

    private static Map<String, Object> position(BlockPos pos) {
        Map<String, Object> table = new HashMap<>();
        table.put("x", pos.getX());
        table.put("y", pos.getY());
        table.put("z", pos.getZ());
        return table;
    }
}