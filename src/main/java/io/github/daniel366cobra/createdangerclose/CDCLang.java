package io.github.daniel366cobra.createdangerclose;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.simibubi.create.foundation.utility.FilesHelper;
import com.tterrag.registrate.providers.RegistrateLangProvider;
import dev.simulated_team.simulated.data.SimLang;
import net.createmod.catnip.lang.Lang;
import net.createmod.catnip.lang.LangBuilder;
import net.createmod.catnip.lang.LangNumberFormat;
import io.github.daniel366cobra.createdangerclose.ponder.CDCPonderPlugin;
import net.createmod.ponder.foundation.PonderIndex;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.block.state.BlockState;
import org.checkerframework.checker.units.qual.C;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

public class CDCLang {

    public static LangBuilder builder() {
        return Lang.builder(CreateDangerClose.MOD_ID);
    }

    public static LangBuilder text(final String text) {
        return builder().text(text);
    }

    public static LangBuilder translate(final String key, final Object... args) {
        return builder().translate(key, args);
    }

    public static LangBuilder number(final double number) {
        return builder().text(LangNumberFormat.format(number));
    }

    public static MutableComponent numberWithUnit(double value, String unitKey) {
        return builder().text(String.format("%.2f", value))
                .add(Component.translatable(unitKey))
                .component();
    }

    public static LangBuilder space() {
        return builder().space();
    }

    public static void emptyLine(final List<Component> tooltip) {
        builder().text("").forGoggles(tooltip);
    }

    public static LangBuilder blockName(final BlockState blockState) {
        return builder().add(blockState.getBlock().getName());
    }

    public static List<Component> translatedOptions(final String prefix, final String... keys) {
        final List<Component> result = new ArrayList<>(keys.length);
        for (final String key : keys)
            result.add(translate((prefix != null ? prefix + "." : "") + key).component());
        return result;
    }


    public static void registrateLang(final RegistrateLangProvider provider) {
        final BiConsumer<String, String> consumer = provider::add;
        //CDCKeys.provideLang(consumer);
        //CDCAdvancements.provideLang(consumer);
        //CDCSoundEvents.REGISTRY.provideLang(consumer);

        final Map<String, String> lang = getLangMap("en_us");
        lang.forEach(consumer);

        // Datagen does not run client setup: add the plugin here so its scene texts reach the lang file.
        PonderIndex.addPlugin(new CDCPonderPlugin());
        PonderIndex.getLangAccess().provideLang(CreateDangerClose.MOD_ID, consumer);
    }

    private static Map<String, String> getLangMap(final String lang) {
        final String filepath = "datagen/lang/%s.json".formatted(lang);
        final JsonObject langObject = FilesHelper.loadJsonResource(filepath).getAsJsonObject();

        final Map<String, String> langMap = new HashMap<>();
        flattenJson(langMap, langObject, null);
        return langMap;
    }

    private static void flattenJson(final Map<String, String> outputMap, final JsonElement element, final String currentPath) {
        if(element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            final String string = element.getAsJsonPrimitive().getAsString();
            outputMap.put(currentPath, string);
            return;
        }

        if(element.isJsonObject()) {
            final JsonObject object = element.getAsJsonObject();
            for (final String key : object.keySet()) {
                final JsonElement value = object.get(key);
                final String path = currentPath != null ? currentPath + "." + key : key;
                flattenJson(outputMap, value, path);
            }
        }
    }
}