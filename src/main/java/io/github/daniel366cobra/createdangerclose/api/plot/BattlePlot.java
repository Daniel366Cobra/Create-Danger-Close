package io.github.daniel366cobra.createdangerclose.api.plot;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * What the fire control solver knows, for plotting on a map table. World
 * space, blocks. Built by the solver each poll and carried to connected map
 * tables by network interfaces.
 *
 * @param fixes rangefinder solutions: where each one puts the target
 * @param guns  how far each armed mount can reach
 */
public record BattlePlot(List<RangefinderFix> fixes, List<GunRange> guns) {

    public static final BattlePlot EMPTY = new BattlePlot(List.of(), List.of());

    /**
     * One rangefinder's solution, carried forward to the poll on its tracked
     * velocity.
     *
     * @param sigma 1σ range error, blocks
     */
    public record RangefinderFix(double x, double y, double z, double sigma) {}

    /**
     * Maximum range of a mount with its current shell and charge, down to the
     * target's height (or the ground under the mount without a target).
     */
    public record GunRange(double x, double z, double radius) {}

    public boolean isEmpty() {
        return fixes.isEmpty() && guns.isEmpty();
    }

    public CompoundTag write() {
        CompoundTag tag = new CompoundTag();
        ListTag fixList = new ListTag();
        for (RangefinderFix fix : fixes) {
            CompoundTag t = new CompoundTag();
            t.putDouble("X", fix.x());
            t.putDouble("Y", fix.y());
            t.putDouble("Z", fix.z());
            t.putDouble("Sigma", fix.sigma());
            fixList.add(t);
        }
        tag.put("Fixes", fixList);
        ListTag gunList = new ListTag();
        for (GunRange gun : guns) {
            CompoundTag t = new CompoundTag();
            t.putDouble("X", gun.x());
            t.putDouble("Z", gun.z());
            t.putDouble("Radius", gun.radius());
            gunList.add(t);
        }
        tag.put("Guns", gunList);
        return tag;
    }

    public static BattlePlot read(CompoundTag tag) {
        List<RangefinderFix> fixes = new ArrayList<>();
        for (Tag entry : tag.getList("Fixes", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) entry;
            fixes.add(new RangefinderFix(t.getDouble("X"), t.getDouble("Y"), t.getDouble("Z"), t.getDouble("Sigma")));
        }
        List<GunRange> guns = new ArrayList<>();
        for (Tag entry : tag.getList("Guns", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) entry;
            guns.add(new GunRange(t.getDouble("X"), t.getDouble("Z"), t.getDouble("Radius")));
        }
        return fixes.isEmpty() && guns.isEmpty() ? EMPTY : new BattlePlot(fixes, guns);
    }
}
