package io.github.daniel366cobra.createdangerclose.block.cannon_servo;

import io.github.daniel366cobra.createdangerclose.api.sensor.ServoStatus;
import io.github.daniel366cobra.createdangerclose.block.cannon_servo.CannonServoOutputBlockEntity.SlavedMount;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedBigCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.cannon_control.fixed_cannon_mount.FixedCannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannons.big_cannons.BigCannonBehavior;
import rbasamoyai.createbigcannons.cannons.big_cannons.IBigCannonBlockEntity;
import rbasamoyai.createbigcannons.index.CBCDataComponents;
import rbasamoyai.createbigcannons.index.CBCMunitionPropertiesHandlers;
import rbasamoyai.createbigcannons.munitions.big_cannon.BigCannonMunitionBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.FuzedProjectileBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.ProjectileBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.config.BigCannonProjectilePropertiesComponent;
import rbasamoyai.createbigcannons.munitions.big_cannon.propellant.BigCannonPropellantBlock;
import rbasamoyai.createbigcannons.munitions.config.components.BallisticPropertiesComponent;
import rbasamoyai.createbigcannons.munitions.fuzes.DelayedImpactFuzeItem;
import rbasamoyai.createbigcannons.munitions.fuzes.DelayedInertiaFuzeItem;
import rbasamoyai.createbigcannons.munitions.fuzes.TimedFuzeItem;

import static io.github.daniel366cobra.createdangerclose.api.sensor.ServoAxis.PITCH;

/**
 * Reading a CBC cannon (on a cannon mount or a fixed cannon mount): what it is
 * loaded with, where its barrel points and where its shell leaves, and setting
 * its fuze.
 */
public final class CannonReading {

    private CannonReading() {}

    /**
     * Which way CBC's raw pitch angle turns this barrel: +1 if raising the pitch
     * raises the barrel, -1 if it lowers it. The pitch is a rotation about a fixed
     * world axis, so it depends on the cannon's initial orientation; this is the
     * rule CBC itself uses ({@code CannonMountBlockEntity.tick}).
     */
    static float elevationSign(PitchOrientedContraptionEntity entity) {
        Direction facing = entity.getInitialOrientation();
        boolean pitchIsUp = (facing.getAxisDirection() == Direction.AxisDirection.POSITIVE)
                == (facing.getAxis() == Direction.Axis.X);
        return pitchIsUp ? 1 : -1;
    }

    /**
     * Where the shell leaves the cannon. CBC spawns it at the centre of the
     * block just past the barrel's last block, on the line from the cannon's
     * rotation centre (the contraption origin) along the barrel.
     */
    static void readMuzzle(SlavedMount sm, PitchOrientedContraptionEntity entity) {
        sm.pivot = entity.toGlobalVector(Vec3.atCenterOf(BlockPos.ZERO), 0);
        if (!(entity.getContraption() instanceof AbstractMountedCannonContraption cannon)) {
            sm.muzzleDistance = 0;
            return;
        }
        Direction along = cannon.initialOrientation();
        int farthest = 0;
        for (BlockPos pos : cannon.getBlocks().keySet())
            farthest = Math.max(farthest, pos.getX() * along.getStepX() + pos.getY() * along.getStepY()
                    + pos.getZ() * along.getStepZ());
        sm.muzzleDistance = farthest + 1;
    }

    /** Reads the loaded shell, fuze and charge into this mount's record. */
    static void readShell(SlavedMount sm, PitchOrientedContraptionEntity entity, HolderLookup.Provider registries) {
        sm.hasShell = false;
        sm.shellType = null;
        sm.fuzeStack = ItemStack.EMPTY;
        sm.fuzeTimer = 0;
        sm.isTimedFuze = false;
        sm.isDelayedImpactFuze = false;
        sm.totalChargePower = 0f;

        if (!(entity.getContraption() instanceof MountedBigCannonContraption mounted)) return;

        for (var entry : mounted.presentBlockEntities.entrySet()) {
            if (!(entry.getValue() instanceof IBigCannonBlockEntity cbe)) continue;
            BigCannonBehavior behavior = cbe.cannonBehavior();
            StructureBlockInfo info = behavior.block();
            if (info.state().isAir()) continue;

            var block = info.state().getBlock();

            if (block instanceof BigCannonPropellantBlock propellant) {
                sm.totalChargePower += Math.max(0f, propellant.getChargePower(info));
                continue;
            }

            if (block instanceof ProjectileBlock<?> projBlock && !sm.hasShell) {
                if (!(block instanceof BigCannonMunitionBlock munition)) continue;

                ItemStack shellStack = munition.getExtractedItem(info, registries);
                ItemStack fuze = FuzedProjectileBlock.getFuzeFromItemStack(shellStack);

                sm.hasShell = true;
                sm.shellType = BuiltInRegistries.BLOCK.getKey(block);
                sm.fuzeStack = fuze;
                sm.isTimedFuze = fuze.getItem() instanceof TimedFuzeItem;
                sm.isDelayedImpactFuze = isDelayedImpactFuze(fuze);
                sm.fuzeTimer = sm.isTimedFuze || sm.isDelayedImpactFuze
                        ? fuze.getOrDefault(CBCDataComponents.FUZE_TIMER, sm.isTimedFuze ? 20 : -1)
                        : 0;

                EntityType<?> projectileType = projBlock.getAssociatedEntityType();
                BallisticPropertiesComponent ballistics = resolveBallistics(projectileType);
                BigCannonProjectilePropertiesComponent bigCannon = resolveBigCannonProperties(projectileType);

                sm.gravity = ballistics.gravity();
                sm.drag = ballistics.drag();
                sm.isQuadraticDrag = ballistics.isQuadraticDrag();
                sm.addedChargePower = bigCannon.addedChargePower();
                sm.minimumChargePower = bigCannon.minimumChargePower();
            }
        }
    }

    static BallisticPropertiesComponent resolveBallistics(EntityType<?> projectileType) {
        // Common shells: HE, fluid, smoke.
        // Sentinel: gravity() != 0 because every shipped shell has a negative
        // gravity value in its JSON. The default is 0.0, which means "no entry
        // for this entity type in this handler."
        BallisticPropertiesComponent common = CBCMunitionPropertiesHandlers.COMMON_SHELL_BIG_CANNON_PROJECTILE
                .getPropertiesOf(projectileType).ballistics();
        if (common.gravity() != 0) return common;

        BallisticPropertiesComponent inert = CBCMunitionPropertiesHandlers.INERT_BIG_CANNON_PROJECTILE
                .getPropertiesOf(projectileType).ballistics();
        if (inert.gravity() != 0) return inert;

        BallisticPropertiesComponent shrapnel = CBCMunitionPropertiesHandlers.SHRAPNEL_SHELL
                .getPropertiesOf(projectileType).ballistics();
        if (shrapnel.gravity() != 0) return shrapnel;

        BallisticPropertiesComponent fluid = CBCMunitionPropertiesHandlers.FLUID_SHELL
                .getPropertiesOf(projectileType).ballistics();
        if (fluid.gravity() != 0) return fluid;

        BallisticPropertiesComponent smoke = CBCMunitionPropertiesHandlers.SMOKE_SHELL
                .getPropertiesOf(projectileType).ballistics();
        if (smoke.gravity() != 0) return smoke;

        BallisticPropertiesComponent mortar = CBCMunitionPropertiesHandlers.DROP_MORTAR_SHELL
                .getPropertiesOf(projectileType).ballistics();
        if (mortar.gravity() != 0) return mortar;

        BallisticPropertiesComponent stone = CBCMunitionPropertiesHandlers.MORTAR_STONE
                .getPropertiesOf(projectileType).ballistics();
        if (stone.gravity() != 0) return stone;

        // Nothing matched. Return the common default so callers get a consistent
        // zeroed value and the ballistic solver can fall back.
        return common;
    }

    static BigCannonProjectilePropertiesComponent resolveBigCannonProperties(EntityType<?> projectileType) {
        // Sentinel: minimumChargePower() != 0 because every shipped shell has a
        // minimum charge of at least 1.0 in its JSON. The default is 0.
        BigCannonProjectilePropertiesComponent common = CBCMunitionPropertiesHandlers.COMMON_SHELL_BIG_CANNON_PROJECTILE
                .getPropertiesOf(projectileType).bigCannonProperties();
        if (common.minimumChargePower() != 0) return common;

        BigCannonProjectilePropertiesComponent inert = CBCMunitionPropertiesHandlers.INERT_BIG_CANNON_PROJECTILE
                .getPropertiesOf(projectileType).bigCannonProperties();
        if (inert.minimumChargePower() != 0) return inert;

        BigCannonProjectilePropertiesComponent shrapnel = CBCMunitionPropertiesHandlers.SHRAPNEL_SHELL
                .getPropertiesOf(projectileType).bigCannonProperties();
        if (shrapnel.minimumChargePower() != 0) return shrapnel;

        BigCannonProjectilePropertiesComponent grapeshot = CBCMunitionPropertiesHandlers.BAG_OF_GRAPESHOT
                .getPropertiesOf(projectileType).bigCannonProperties();
        if (grapeshot.minimumChargePower() != 0) return grapeshot;

        BigCannonProjectilePropertiesComponent fluid = CBCMunitionPropertiesHandlers.FLUID_SHELL
                .getPropertiesOf(projectileType).bigCannonProperties();
        if (fluid.minimumChargePower() != 0) return fluid;

        BigCannonProjectilePropertiesComponent smoke = CBCMunitionPropertiesHandlers.SMOKE_SHELL
                .getPropertiesOf(projectileType).bigCannonProperties();
        if (smoke.minimumChargePower() != 0) return smoke;

        BigCannonProjectilePropertiesComponent mortar = CBCMunitionPropertiesHandlers.DROP_MORTAR_SHELL
                .getPropertiesOf(projectileType).bigCannonProperties();
        if (mortar.minimumChargePower() != 0) return mortar;

        BigCannonProjectilePropertiesComponent stone = CBCMunitionPropertiesHandlers.MORTAR_STONE
                .getPropertiesOf(projectileType).bigCannonProperties();
        if (stone.minimumChargePower() != 0) return stone;

        return common;
    }

    /** Delayed impact and delayed inertia fuzes: their timer counts the delay after impact. */
    public static boolean isDelayedImpactFuze(ItemStack fuze) {
        return fuze.getItem() instanceof DelayedImpactFuzeItem || fuze.getItem() instanceof DelayedInertiaFuzeItem;
    }

    /**
     * Sets the loaded shell's fuze timer to this many ticks: a timed fuze's time,
     * or a delayed impact or inertia fuze's delay. Returns true if such a fuze was
     * found and updated.
     */
    static boolean setLoadedFuzeTime(@Nullable PitchOrientedContraptionEntity entity, int ticks, HolderLookup.Provider registries) {
        if (entity == null) return false;
        if (!(entity.getContraption() instanceof MountedBigCannonContraption mounted)) return false;

        for (var entry : mounted.presentBlockEntities.entrySet()) {
            if (!(entry.getValue() instanceof IBigCannonBlockEntity cbe)) continue;
            BlockPos cannonLocalPos = entry.getKey();  // the cannon block's local position
            BigCannonBehavior behavior = cbe.cannonBehavior();
            StructureBlockInfo loadedInfo = behavior.block();
            if (loadedInfo.state().isAir()) continue;
            if (!(loadedInfo.state().getBlock() instanceof ProjectileBlock<?>)) continue;

            // Modify the loaded shell's NBT
            CompoundTag shellNbt = loadedInfo.nbt() != null ? loadedInfo.nbt().copy() : new CompoundTag();
            ItemStack fuzeStack = ItemStack.parseOptional(registries, shellNbt.getCompound("Fuze"));
            if (!(fuzeStack.getItem() instanceof TimedFuzeItem) && !isDelayedImpactFuze(fuzeStack)) return false;

            fuzeStack.set(CBCDataComponents.FUZE_TIMER, ticks);
            shellNbt.put("Fuze", fuzeStack.saveOptional(registries));

            // Replace the loaded block in the behavior
            behavior.loadBlock(new StructureBlockInfo(
                    loadedInfo.pos(), loadedInfo.state(), shellNbt));
            behavior.blockEntity.setChanged();

            // Update the contraption's blocks map entry for the CANNON BLOCK,
            // using the cannon block's own position and state.
            StructureBlockInfo cannonInfo = mounted.getBlocks().get(cannonLocalPos);
            if (cannonInfo == null) return true;  // behavior updated, but map not synced

            CompoundTag beTag = behavior.blockEntity.saveWithFullMetadata(registries);
            beTag.remove("x"); beTag.remove("y"); beTag.remove("z");
            mounted.getBlocks().put(cannonLocalPos, new StructureBlockInfo(
                    cannonInfo.pos(), cannonInfo.state(), beTag));

            return true;
        }
        return false;
    }

    /**
     * Reads a cannon's angles, barrel and launch point into this mount's record,
     * and for a pitch axis what it is loaded with.
     */
    static void readCannon(SlavedMount sm, PitchOrientedContraptionEntity entity, HolderLookup.Provider registries) {
        sm.elevationSign = elevationSign(entity);
        sm.currentAngle = (sm.axis == PITCH) ? sm.elevationSign * entity.pitch : entity.yaw;
        sm.mountYaw = entity.yaw;
        sm.facingYaw = entity.getInitialOrientation().toYRot();
        readMuzzle(sm, entity);
        sm.barrel = barrel(entity);
        if (sm.axis == PITCH)
            readShell(sm, entity, registries);
    }

    /**
     * Where the barrel points, in the frame the cannon stands in (its sub-level's,
     * or the world's): a unit vector from its rotation centre along the barrel.
     */
    static Vec3 barrel(PitchOrientedContraptionEntity entity) {
        Direction along = entity.getInitialOrientation();
        Vec3 centre = Vec3.atCenterOf(BlockPos.ZERO);
        Vec3 pivot = entity.toGlobalVector(centre, 1);
        return entity.toGlobalVector(centre.add(along.getStepX(), along.getStepY(), along.getStepZ()), 1)
                .subtract(pivot).normalize();
    }

    /**
     * The record of a cannon as the computer gets it.
     *
     * @param mountLimits whether the mount's elevation limits apply: not for a fixed
     *                    mount, whose gun the bearings carrying it elevate
     */
    static ServoStatus.MountSnapshot snapshot(SlavedMount sm, @Nullable PitchOrientedContraptionEntity entity, boolean mountLimits) {
        float facingYaw = 0f;
        float maxElevation = 90f;
        float maxDepression = -90f;
        if (entity != null) {
            facingYaw = entity.getInitialOrientation().toYRot();
            if (mountLimits) {
                maxElevation = entity.maximumElevation();
                maxDepression = -entity.maximumDepression();
            }
        }
        return new ServoStatus.MountSnapshot(
                sm.mountPos, facingYaw, sm.currentAngle, sm.mountYaw,
                maxElevation, maxDepression,
                sm.hasShell, sm.shellType,
                sm.fuzeStack, sm.fuzeTimer, sm.isTimedFuze,
                sm.gravity, sm.drag, sm.isQuadraticDrag,
                sm.addedChargePower, sm.minimumChargePower, sm.totalChargePower,
                sm.pivot, sm.muzzleDistance, sm.barrel);
    }

    /**
     * A gun on a fixed cannon mount, as the network interface facing the mount
     * reads it: like a cannon mount's pitch servo would. Null if unassembled.
     */
    @Nullable
    public static ServoStatus.MountSnapshot readFixedGun(FixedCannonMountBlockEntity mount, HolderLookup.Provider registries) {
        PitchOrientedContraptionEntity entity = mount.getContraption();
        if (entity == null)
            return null;
        SlavedMount sm = new SlavedMount(mount.getBlockPos(), null, null, PITCH);
        readCannon(sm, entity, registries);
        return snapshot(sm, entity, false);
    }

    /** Sets the fuze of the shell loaded in a fixed cannon mount's gun (see {@link #setLoadedFuzeTime}). */
    public static boolean setFixedGunFuze(FixedCannonMountBlockEntity mount, int ticks, HolderLookup.Provider registries) {
        return setLoadedFuzeTime(mount.getContraption(), ticks, registries);
    }
}
