package io.github.daniel366cobra.createdangerclose.foundation.plot;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * What the Ballistic Computer knows, for plotting on a map table. World
 * space, blocks. Built by the computer each poll and carried to connected map
 * tables by network interfaces.
 *
 * @param fixes rangefinder solutions: where each one puts the target
 * @param guns  how far each armed mount can reach, and where it points
 */
public record BattlePlot(List<RangefinderFix> fixes, List<GunRange> guns) {

    public static final BattlePlot EMPTY = new BattlePlot(List.of(), List.of());

    /**
     * One rangefinder's solution, carried forward to the poll on its tracked
     * velocity, and the rangefinder it came from.
     *
     * @param sigma            1σ range error, blocks
     * @param originX, originZ where the rangefinder stands, for its line of sight
     */
    public record RangefinderFix(double x, double y, double z, double sigma, double originX, double originZ) {}

    /**
     * Maximum range of a mount with its current shell and charge, down to the
     * target's height (or the ground under the mount without a target), and
     * the way its barrel points.
     *
     * @param aimX, aimZ horizontal unit vector of the barrel's bearing; both 0 if unknown
     *                   (no yaw servo on the mount)
     * @param minRadius  the edge of the dead zone around the gun, for a level ship: the closest
     *                   the steep arc reaches (plunging fire) or the lowest barrel reaches
     *                   (direct fire at a target below the gun); 0 if there is none
     * @param arcFrom    where the traverse it may turn through starts, as a world bearing
     *                   (Minecraft yaw, degrees): the arc runs clockwise from it
     * @param arcLength  how wide that arc is, degrees; 360 if it may turn all round
     */
    public record GunRange(double x, double z, double radius, double aimX, double aimZ, double minRadius,
                           double arcFrom, double arcLength) {
        public boolean hasAim() {
            return aimX != 0 || aimZ != 0;
        }

        public boolean limitsTraverse() {
            return arcLength < 360;
        }
    }

    public boolean isEmpty() {
        return fixes.isEmpty() && guns.isEmpty();
    }

    // A gun or fix in two plots is the same one if it moved less than this between them, blocks
    // (a ship can cover a fair distance between polls).
    private static final double SAME_ITEM_DISTANCE = 24;

    /**
     * This plot partway to another, {@code t} from 0 (this) to 1 ({@code to}),
     * for drawing it moving smoothly between updates. Guns are paired by where
     * they stand and fixes by the rangefinder they came from; anything without a
     * partner is shown as in {@code to}.
     */
    public BattlePlot blendTowards(BattlePlot to, double t) {
        if (t >= 1 || this.equals(to))
            return to;
        List<GunRange> blendedGuns = new ArrayList<>(to.guns.size());
        List<GunRange> unpairedGuns = new ArrayList<>(guns);
        for (GunRange gun : to.guns) {
            GunRange from = nearest(unpairedGuns, gun.x, gun.z, GunRange::x, GunRange::z);
            if (from == null) {
                blendedGuns.add(gun);
                continue;
            }
            unpairedGuns.remove(from);
            double aimX = gun.aimX, aimZ = gun.aimZ;
            if (from.hasAim() && gun.hasAim()) {
                // Turn the bearing the short way round.
                double fromAngle = Math.atan2(from.aimZ, from.aimX);
                double toAngle = Math.atan2(gun.aimZ, gun.aimX);
                double turn = Math.IEEEremainder(toAngle - fromAngle, 2 * Math.PI);
                double angle = fromAngle + turn * t;
                aimX = Math.cos(angle);
                aimZ = Math.sin(angle);
            }
            // The arc turns the short way round too.
            double arcFrom = from.arcFrom + Math.IEEEremainder(gun.arcFrom - from.arcFrom, 360) * t;
            blendedGuns.add(new GunRange(lerp(from.x, gun.x, t), lerp(from.z, gun.z, t),
                    lerp(from.radius, gun.radius, t), aimX, aimZ, lerp(from.minRadius, gun.minRadius, t),
                    arcFrom, lerp(from.arcLength, gun.arcLength, t)));
        }
        List<RangefinderFix> blendedFixes = new ArrayList<>(to.fixes.size());
        List<RangefinderFix> unpairedFixes = new ArrayList<>(fixes);
        for (RangefinderFix fix : to.fixes) {
            RangefinderFix from = nearest(unpairedFixes, fix.originX, fix.originZ,
                    RangefinderFix::originX, RangefinderFix::originZ);
            if (from == null) {
                blendedFixes.add(fix);
                continue;
            }
            unpairedFixes.remove(from);
            blendedFixes.add(new RangefinderFix(lerp(from.x, fix.x, t), lerp(from.y, fix.y, t), lerp(from.z, fix.z, t),
                    lerp(from.sigma, fix.sigma, t), lerp(from.originX, fix.originX, t), lerp(from.originZ, fix.originZ, t)));
        }
        return new BattlePlot(blendedFixes, blendedGuns);
    }

    @Nullable
    private static <T> T nearest(List<T> items, double x, double z, ToDoubleFunction<T> getX, ToDoubleFunction<T> getZ) {
        T best = null;
        double bestDistance = SAME_ITEM_DISTANCE * SAME_ITEM_DISTANCE;
        for (T item : items) {
            double dx = getX.applyAsDouble(item) - x, dz = getZ.applyAsDouble(item) - z;
            double distance = dx * dx + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = item;
            }
        }
        return best;
    }

    private static double lerp(double from, double to, double t) {
        return from + (to - from) * t;
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
            t.putDouble("OriginX", fix.originX());
            t.putDouble("OriginZ", fix.originZ());
            fixList.add(t);
        }
        tag.put("Fixes", fixList);
        ListTag gunList = new ListTag();
        for (GunRange gun : guns) {
            CompoundTag t = new CompoundTag();
            t.putDouble("X", gun.x());
            t.putDouble("Z", gun.z());
            t.putDouble("Radius", gun.radius());
            t.putDouble("AimX", gun.aimX());
            t.putDouble("AimZ", gun.aimZ());
            t.putDouble("MinRadius", gun.minRadius());
            t.putDouble("ArcFrom", gun.arcFrom());
            t.putDouble("ArcLength", gun.arcLength());
            gunList.add(t);
        }
        tag.put("Guns", gunList);
        return tag;
    }

    public static BattlePlot read(CompoundTag tag) {
        List<RangefinderFix> fixes = new ArrayList<>();
        for (Tag entry : tag.getList("Fixes", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) entry;
            fixes.add(new RangefinderFix(t.getDouble("X"), t.getDouble("Y"), t.getDouble("Z"), t.getDouble("Sigma"),
                    t.getDouble("OriginX"), t.getDouble("OriginZ")));
        }
        List<GunRange> guns = new ArrayList<>();
        for (Tag entry : tag.getList("Guns", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) entry;
            guns.add(new GunRange(t.getDouble("X"), t.getDouble("Z"), t.getDouble("Radius"),
                    t.getDouble("AimX"), t.getDouble("AimZ"), t.getDouble("MinRadius"),
                    t.getDouble("ArcFrom"), t.contains("ArcLength") ? t.getDouble("ArcLength") : 360));
        }
        return fixes.isEmpty() && guns.isEmpty() ? EMPTY : new BattlePlot(fixes, guns);
    }
}
