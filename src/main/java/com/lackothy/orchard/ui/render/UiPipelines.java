package com.lackothy.orchard.ui.render;

import com.lackothy.Orchard;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.BindGroupLayouts;

/**
 * Orchard's own GUI pipeline: one quad per shape, with the shape itself (rounded corners or soft
 * shadow) worked out per pixel from a signed distance field in {@code rounded.fsh}.
 *
 * <p>The vertex format is vanilla's ENTITY format, borrowed for its spare attributes: UV0 carries
 * the local position, UV1 the half size and UV2 the radius and mode. Nothing entity-related is
 * involved. Pipelines compile on first use, so this needs no registration.
 */
public final class UiPipelines {

    public static final RenderPipeline ROUNDED = RenderPipeline.builder()
        .withLocation(Orchard.id("pipeline/rounded"))
        .withVertexShader(Orchard.id("core/rounded"))
        .withFragmentShader(Orchard.id("core/rounded"))
        .withBindGroupLayout(BindGroupLayouts.GLOBALS)
        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
        .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
        .withCull(false)
        .withVertexBinding(0, DefaultVertexFormat.ENTITY)
        .withPrimitiveTopology(PrimitiveTopology.QUADS)
        .build();

    private UiPipelines() {
    }
}
