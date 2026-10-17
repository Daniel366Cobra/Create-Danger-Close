package io.github.daniel366cobra.createdangerclose.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.Util;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Function;

/** Extends RenderType only to reach its state shards. */
public final class CDCRenderTypes extends RenderType {

    /**
     * As {@link RenderType#text} but opaque. Iris replays block entities' drawing grouped opaque first,
     * then translucent, in no set order within a group; maps must come before the {@link #OVERLAY}.
     */
    private static final Function<ResourceLocation, RenderType> OPAQUE_TEXT = Util.memoize(texture ->
            create("createdangerclose_opaque_text", DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP,
                    VertexFormat.Mode.QUADS, 786432, false, false,
                    CompositeState.builder()
                            .setShaderState(RENDERTYPE_TEXT_SHADER)
                            .setTextureState(new TextureStateShard(texture, false, false))
                            .setTransparencyState(NO_TRANSPARENCY)
                            .setLightmapState(LIGHTMAP)
                            .createCompositeState(false)));

    /** As {@link RenderType#textBackground}, which Iris counts as opaque; this one stays translucent. */
    private static final RenderType OVERLAY = create("createdangerclose_overlay",
            DefaultVertexFormat.POSITION_COLOR_LIGHTMAP, VertexFormat.Mode.QUADS, 1536, false, true,
            CompositeState.builder()
                    .setShaderState(RENDERTYPE_TEXT_BACKGROUND_SHADER)
                    .setTextureState(NO_TEXTURE)
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setLightmapState(LIGHTMAP)
                    .createCompositeState(false));

    private CDCRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize,
                           boolean affectsCrumbling, boolean sortOnUpload, Runnable setup, Runnable clear) {
        super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setup, clear);
    }

    public static RenderType opaqueText(ResourceLocation texture) {
        return OPAQUE_TEXT.apply(texture);
    }

    /** Untextured translucent quads drawn over the maps: plot lines and safe zones. */
    public static RenderType overlay() {
        return OVERLAY;
    }
}