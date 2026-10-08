package io.github.daniel366cobra.createdangerclose.config;

import net.createmod.catnip.config.ConfigBase;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Keeps decimals as doubles: Catnip's float setting shows 0.44 as 0.4399999976... in scientific notation. */
public abstract class CDCConfigBase extends ConfigBase {

    protected ConfigDouble d(double current, double min, double max, String name, String... comment) {
        ConfigDouble value = new ConfigDouble(name, current, min, max, comment);
        allValues.add(value);
        return value;
    }

    public class ConfigDouble extends CValue<Double, ModConfigSpec.DoubleValue> {
        public ConfigDouble(String name, double current, double min, double max, String... comment) {
            super(name, builder -> builder.defineInRange(name, current, min, max), comment);
        }

        public float getF() {
            return get().floatValue();
        }
    }
}