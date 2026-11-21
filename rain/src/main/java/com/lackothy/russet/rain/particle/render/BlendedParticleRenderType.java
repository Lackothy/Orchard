package com.lackothy.russet.rain.particle.render;

import com.mojang.renderpearl.api.pipeline.*;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.TextureAtlas;
import com.lackothy.russet.rain.VersionUtil;

import static net.minecraft.client.renderer.RenderPipelines.PARTICLE_SNIPPET;

public class BlendedParticleRenderType {

    public static final RenderPipeline BLENDED_PARTICLE = RenderPipelines.register(
            RenderPipeline.builder(PARTICLE_SNIPPET)
                    .withLocation(VersionUtil.getId("pipeline/particle_blended"))
                    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                    .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN, false))
                    .build()
    );
    public static final SingleQuadParticle.Layer INSTANCE =
            new SingleQuadParticle.Layer(true, TextureAtlas.LOCATION_PARTICLES, BLENDED_PARTICLE, RenderPipelines.OIT_PARTICLE);
}
