package io.github.daniel366cobra.createdangerclose.config;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/** {@link CServer} (gameplay, per world, synced) and {@link CClient} (looks), built with Catnip as Create does. */
public final class CDCConfig {

    private static CServer server;
    private static CClient client;

    private CDCConfig() {}

    public static CServer server() {
        return server;
    }

    public static CClient client() {
        return client;
    }

    public static void register(ModContainer container) {
        server = build(new CServer());
        client = build(new CClient());
        container.registerConfig(ModConfig.Type.SERVER, server.specification);
        container.registerConfig(ModConfig.Type.CLIENT, client.specification);
    }

    private static <T extends net.createmod.catnip.config.ConfigBase> T build(T config) {
        Pair<T, ModConfigSpec> built = new ModConfigSpec.Builder().configure(builder -> {
            config.registerAll(builder);
            return config;
        });
        config.specification = built.getRight();
        return config;
    }
}