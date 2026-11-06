package io.github.daniel366cobra.createdangerclose.registries;

import com.simibubi.create.api.registry.CreateBuiltInRegistries;
import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import io.github.daniel366cobra.createdangerclose.block.munitions_chute.MunitionsChuteArmPoint;
import net.minecraft.core.Registry;

/** Registered into Create's arm interaction point registry during its RegisterEvent, like the contraption types. */
public class CDCArmInteractionPointTypes {

    public static void init() {
        Registry.register(CreateBuiltInRegistries.ARM_INTERACTION_POINT_TYPE,
                CreateDangerClose.asResource("munitions_chute"), new MunitionsChuteArmPoint.Type());
    }
}
