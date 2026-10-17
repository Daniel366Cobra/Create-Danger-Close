package io.github.daniel366cobra.createdangerclose.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.daniel366cobra.createdangerclose.block.map_table.MapTableMaps;
import io.github.daniel366cobra.createdangerclose.client.CDCRenderTypes;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** On a map table, the map itself is drawn opaque (see {@link CDCRenderTypes#opaqueText}); markers are left as they are. */
@Mixin(targets = "net.minecraft.client.gui.MapRenderer$MapInstance", remap = false)
public abstract class MapInstanceMixin {

    @Unique
    private ResourceLocation createdangerclose$texture;

    @ModifyExpressionValue(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/texture/TextureManager;register(Ljava/lang/String;Lnet/minecraft/client/renderer/texture/DynamicTexture;)Lnet/minecraft/resources/ResourceLocation;"))
    private ResourceLocation createdangerclose$keepTexture(ResourceLocation texture) {
        createdangerclose$texture = texture;
        return texture;
    }

    @WrapOperation(method = "draw", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/client/renderer/MultiBufferSource;getBuffer(Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"))
    private VertexConsumer createdangerclose$opaqueOnTable(MultiBufferSource buffers, RenderType type,
                                                            Operation<VertexConsumer> original) {
        return original.call(buffers, MapTableMaps.isDrawingOnTable() && createdangerclose$texture != null
                ? CDCRenderTypes.opaqueText(createdangerclose$texture) : type);
    }
}