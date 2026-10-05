package io.github.daniel366cobra.createdangerclose.config;

import net.createmod.catnip.config.ConfigBase;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Catnip's config base, with a decimal setting kept as a double. Catnip's own
 * ({@code f}) goes through a float, so 0.44 is stored as 0.4399999976... and the
 * config screen shows such values in scientific notation.
 */
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
