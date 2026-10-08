package io.github.daniel366cobra.createdangerclose.block.cannon_servo;

import io.github.daniel366cobra.createdangerclose.block.cannon_servo.CannonServoOutputBlockEntity.SlavedMount;
import io.github.daniel366cobra.createdangerclose.foundation.sensor.ServoStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedBigCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedAutocannonContraption;
import rbasamoyai.createbigcannons.cannons.autocannon.AutocannonBlock;
import rbasamoyai.createbigcannons.cannons.autocannon.breech.AbstractAutocannonBreechBlockEntity;
import rbasamoyai.createbigcannons.cannons.autocannon.material.AutocannonMaterial;
import rbasamoyai.createbigcannons.cannons.autocannon.material.AutocannonMaterialProperties;
import rbasamoyai.createbigcannons.munitions.autocannon.AutocannonAmmoItem;
import rbasamoyai.createbigcannons.munitions.autocannon.AutocannonCartridgeItem;
import rbasamoyai.createbigcannons.munitions.autocannon.ammo_container.AutocannonAmmoContainerItem;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.cannon_control.fixed_cannon_mount.FixedCannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannons.big_cannons.BigCannonBlock;
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

import java.util.List;

import static io.github.daniel366cobra.createdangerclose.foundation.sensor.ServoAxis.PITCH;

/** Reads CBC cannons (mounted or fixed): load, barrel, launch point; sets fuzes. */
public final class CannonReading {

    private CannonReading() {}

    /** +1 if raising CBC's raw pitch raises this barrel, -1 if it lowers it (CBC's own rule, {@code CannonMountBlockEntity.tick}). */
    static float elevationSign(PitchOrientedContraptionEntity entity) {
        Direction facing = entity.getInitialOrientation();
        boolean pitchIsUp = (facing.getAxisDirection() == Direction.AxisDirection.POSITIVE)
                == (facing.getAxis() == Direction.Axis.X);
        return pitchIsUp ? 1 : -1;
    }

    /** CBC spawns the shell at the center of the block past the barrel's end, on the line from the rotation center. */
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
        // HE, fluid, smoke. gravity() == 0 means no entry for this type (every shipped shell's is negative).
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

        // Nothing matched: the zeroed default lets the solver fall back.
        return common;
    }

    static BigCannonProjectilePropertiesComponent resolveBigCannonProperties(EntityType<?> projectileType) {
        // minimumChargePower() == 0 means no entry (every shipped shell's is at least 1).
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

    /** Their timer counts the delay after impact. */
    public static boolean isDelayedImpactFuze(ItemStack fuze) {
        return fuze.getItem() instanceof DelayedImpactFuzeItem || fuze.getItem() instanceof DelayedInertiaFuzeItem;
    }

    /** A timed fuze's time, or a delayed fuze's delay. Returns true if such a fuze was updated. */
    static boolean setLoadedFuzeTime(@Nullable PitchOrientedContraptionEntity entity, int ticks, HolderLookup.Provider registries) {
        if (entity == null) return false;
        if (entity.getContraption() instanceof MountedAutocannonContraption autocannon)
            return setAutocannonFuzeTime(entity, autocannon, ticks);
        if (!(entity.getContraption() instanceof MountedBigCannonContraption mounted)) return false;

        for (var entry : mounted.presentBlockEntities.entrySet()) {
            if (!(entry.getValue() instanceof IBigCannonBlockEntity cbe)) continue;
            BlockPos cannonLocalPos = entry.getKey();  // the cannon block's local position
            BigCannonBehavior behavior = cbe.cannonBehavior();
            StructureBlockInfo loadedInfo = behavior.block();
            if (loadedInfo.state().isAir()) continue;
            if (!(loadedInfo.state().getBlock() instanceof ProjectileBlock<?>)) continue;

            CompoundTag shellNbt = loadedInfo.nbt() != null ? loadedInfo.nbt().copy() : new CompoundTag();
            ItemStack fuzeStack = ItemStack.parseOptional(registries, shellNbt.getCompound("Fuze"));
            if (!(fuzeStack.getItem() instanceof TimedFuzeItem) && !isDelayedImpactFuze(fuzeStack)) return false;

            fuzeStack.set(CBCDataComponents.FUZE_TIMER, ticks);
            shellNbt.put("Fuze", fuzeStack.saveOptional(registries));

            behavior.loadBlock(new StructureBlockInfo(
                    loadedInfo.pos(), loadedInfo.state(), shellNbt));
            behavior.blockEntity.setChanged();

            // Also update the contraption's block map entry for the cannon block.
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

    /** Every round in the breech queue and ammo containers (main and tracer). True if any had a timed or delayed fuze. */
    private static boolean setAutocannonFuzeTime(PitchOrientedContraptionEntity entity,
                                                 MountedAutocannonContraption cannon, int ticks) {
        for (var entry : cannon.presentBlockEntities.entrySet()) {
            if (!(entry.getValue() instanceof AbstractAutocannonBreechBlockEntity breech))
                continue;
            boolean set = false;
            for (ItemStack queued : breech.getInputBuffer())
                set |= setRoundFuzeTime(queued, ticks);
            ItemStack magazine = breech.getMagazine();
            if (!magazine.isEmpty()) {
                ItemStack main = AutocannonAmmoContainerItem.getMainAmmoStack(magazine);
                if (setRoundFuzeTime(main, ticks)) {
                    magazine.set(CBCDataComponents.AMMO, ItemContainerContents.fromItems(List.of(main)));
                    set = true;
                }
                ItemStack tracers = AutocannonAmmoContainerItem.getTracerAmmoStack(magazine);
                if (setRoundFuzeTime(tracers, ticks)) {
                    magazine.set(CBCDataComponents.TRACERS, ItemContainerContents.fromItems(List.of(tracers)));
                    set = true;
                }
            }
            if (set) {
                breech.setChanged();
                StructureBlockInfo info = cannon.getBlocks().get(entry.getKey());
                if (info != null)
                    BigCannonBlock.writeAndSyncSingleBlockData(breech, info, entity, cannon);
            }
            return set;
        }
        return false;
    }

    /**
     * Orders the breech to set fuzes on each round it fires ({@link FuzeOrderedBreech}). Values below
     * 0 (timed: 0 or less) leave fuzes as loaded. Renewed while the gun is laid.
     */
    public static void orderAutocannonFuzes(@Nullable PitchOrientedContraptionEntity entity,
                                            int timedTicks, int delayTicks, long gameTime) {
        if (entity == null || !(entity.getContraption() instanceof MountedAutocannonContraption cannon))
            return;
        for (var be : cannon.presentBlockEntities.values())
            if (be instanceof FuzeOrderedBreech breech)
                breech.createdangerclose$orderFuzes(timedTicks, delayTicks, gameTime);
    }

    public static void applyFuzeOrder(ItemStack ammo, int timedTicks, int delayTicks) {
        if (ammo == null || ammo.isEmpty())
            return;
        ItemStack fuze = FuzedProjectileBlock.getFuzeFromItemStack(roundOf(ammo));
        if (fuze.getItem() instanceof TimedFuzeItem && timedTicks > 0)
            setRoundFuzeTime(ammo, timedTicks);
        else if (isDelayedImpactFuze(fuze) && delayTicks >= 0)
            setRoundFuzeTime(ammo, delayTicks);
    }

    /** A cartridge holds the round (the fuze is on the round). */
    private static ItemStack roundOf(ItemStack ammo) {
        return ammo.getItem() instanceof AutocannonCartridgeItem
                ? AutocannonCartridgeItem.getProjectileStack(ammo) : ammo;
    }

    /** In place. False if it has no such fuze, or already reads that. */
    private static boolean setRoundFuzeTime(ItemStack ammo, int ticks) {
        if (ammo.isEmpty())
            return false;
        ItemStack round = roundOf(ammo);
        ItemStack fuze = FuzedProjectileBlock.getFuzeFromItemStack(round);
        if (!(fuze.getItem() instanceof TimedFuzeItem) && !isDelayedImpactFuze(fuze))
            return false;
        if (fuze.getOrDefault(CBCDataComponents.FUZE_TIMER, -1) == ticks)
            return false;
        fuze.set(CBCDataComponents.FUZE_TIMER, ticks);
        round.set(CBCDataComponents.FUZE, ItemContainerContents.fromItems(List.of(fuze)));
        if (round != ammo)
            AutocannonCartridgeItem.writeProjectile(round, ammo);  // (round, cartridge)
        return true;
    }

    /** Angles, barrel and launch point, and the load for a pitch axis. */
    static void readCannon(SlavedMount sm, PitchOrientedContraptionEntity entity, HolderLookup.Provider registries) {
        sm.elevationSign = elevationSign(entity);
        sm.currentAngle = (sm.axis == PITCH) ? sm.elevationSign * entity.pitch : entity.yaw;
        sm.mountYaw = entity.yaw;
        sm.facingYaw = entity.getInitialOrientation().toYRot();
        readMuzzle(sm, entity);
        sm.barrel = barrel(entity);
        sm.autocannon = entity.getContraption() instanceof MountedAutocannonContraption;
        if (sm.axis == PITCH) {
            if (sm.autocannon)
                readAutocannon(sm, entity);
            else
                readShell(sm, entity, registries);
        }
    }

    /**
     * CBC's round speed comes from the barrel material (base plus per barrel block, capped) and expires
     * after a lifetime. The next round is the breech queue's head, else the ammo container's.
     */
    static void readAutocannon(SlavedMount sm, PitchOrientedContraptionEntity entity) {
        sm.hasShell = false;
        sm.shellType = null;
        sm.fuzeStack = ItemStack.EMPTY;
        sm.fuzeTimer = 0;
        sm.isTimedFuze = false;
        sm.isDelayedImpactFuze = false;
        sm.totalChargePower = 0;
        sm.addedChargePower = 0;
        sm.minimumChargePower = 0;
        sm.maxFlightTicks = 0;
        if (!(entity.getContraption() instanceof MountedAutocannonContraption cannon))
            return;

        Direction along = cannon.initialOrientation();
        AbstractAutocannonBreechBlockEntity breech = null;
        BlockPos breechPos = null;
        for (var entry : cannon.presentBlockEntities.entrySet())
            if (entry.getValue() instanceof AbstractAutocannonBreechBlockEntity found) {
                breech = found;
                breechPos = entry.getKey();
            }
        if (breech == null)
            return;

        AutocannonMaterial material = null;
        int barrels = 0;
        for (BlockPos pos = breechPos.relative(along); ; pos = pos.relative(along)) {
            StructureBlockInfo info = cannon.getBlocks().get(pos);
            if (info == null || !(info.state().getBlock() instanceof AutocannonBlock block))
                break;
            material = block.getAutocannonMaterial();
            barrels++;
        }
        if (material == null && cannon.getBlocks().get(breechPos) != null
                && cannon.getBlocks().get(breechPos).state().getBlock() instanceof AutocannonBlock block)
            material = block.getAutocannonMaterial();
        if (material == null)
            return;
        AutocannonMaterialProperties properties = material.properties();
        sm.totalChargePower = properties.baseSpeed()
                + properties.speedIncreasePerBarrel() * Math.min(barrels, properties.maxSpeedIncreases());
        sm.maxFlightTicks = properties.projectileLifetime();

        ItemStack round = breech.getInputBuffer().isEmpty() ? ItemStack.EMPTY : breech.getInputBuffer().peek();
        if (round.isEmpty() && !breech.getMagazine().isEmpty()
                && AutocannonAmmoContainerItem.getTotalAmmoCount(breech.getMagazine()) > 0)
            round = AutocannonAmmoContainerItem.getMainAmmoStack(breech.getMagazine());
        if (round.isEmpty() || !(round.getItem() instanceof AutocannonAmmoItem ammo))
            return;

        EntityType<?> projectileType = ammo.getEntityType(round);
        BallisticPropertiesComponent ballistics = CBCMunitionPropertiesHandlers.FLAK_AUTOCANNON
                .getPropertiesOf(projectileType).ballistics();
        if (ballistics.gravity() == 0)
            ballistics = CBCMunitionPropertiesHandlers.INERT_AUTOCANNON_PROJECTILE.getPropertiesOf(projectileType).ballistics();
        sm.hasShell = true;
        // The cartridges all look alike: name by what they fire.
        sm.shellType = BuiltInRegistries.ENTITY_TYPE.getKey(projectileType);
        sm.gravity = ballistics.gravity();
        sm.drag = ballistics.drag();
        sm.isQuadraticDrag = ballistics.isQuadraticDrag();
        ItemStack fuze = FuzedProjectileBlock.getFuzeFromItemStack(roundOf(round));
        sm.fuzeStack = fuze;
        sm.isTimedFuze = fuze.getItem() instanceof TimedFuzeItem;
        sm.isDelayedImpactFuze = isDelayedImpactFuze(fuze);
        sm.fuzeTimer = sm.isTimedFuze || sm.isDelayedImpactFuze
                ? fuze.getOrDefault(CBCDataComponents.FUZE_TIMER, sm.isTimedFuze ? 20 : -1)
                : 0;
    }

    /** Unit vector from the rotation center along the barrel, in the cannon's own frame. */
    static Vec3 barrel(PitchOrientedContraptionEntity entity) {
        Direction along = entity.getInitialOrientation();
        Vec3 center = Vec3.atCenterOf(BlockPos.ZERO);
        Vec3 pivot = entity.toGlobalVector(center, 1);
        return entity.toGlobalVector(center.add(along.getStepX(), along.getStepY(), along.getStepZ()), 1)
                .subtract(pivot).normalize();
    }

    /**
     * @param mountLimits false for a fixed mount, which the bearings carrying it elevate
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
                sm.pivot, sm.muzzleDistance, sm.barrel, sm.autocannon, sm.maxFlightTicks);
    }

    /** A fixed mount's gun, read like a pitch servo would. Null if unassembled. */
    @Nullable
    public static ServoStatus.MountSnapshot readFixedGun(FixedCannonMountBlockEntity mount, HolderLookup.Provider registries) {
        PitchOrientedContraptionEntity entity = mount.getContraption();
        if (entity == null)
            return null;
        SlavedMount sm = new SlavedMount(mount.getBlockPos(), null, null, PITCH);
        readCannon(sm, entity, registries);
        return snapshot(sm, entity, false);
    }

    public static boolean setFixedGunFuze(FixedCannonMountBlockEntity mount, int ticks, HolderLookup.Provider registries) {
        return setLoadedFuzeTime(mount.getContraption(), ticks, registries);
    }
}