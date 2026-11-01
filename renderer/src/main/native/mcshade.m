// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// Native shading (-Dmcopt.shade=true): the global resources every render encoder gets bound, for the vertex and fragment
// stages alike (see mcopt.metal.shade.ShadeBindings and resources/mcopt/shade/shade_common.metal):
//   buffer(16)  ShadeFrame, this frame's slot of a ring of shared buffers (written by the CPU at LevelRenderer.render HEAD)
//   texture(16) the sun shadow map: depth2d_array<float>, one slice per cascade, reversed Z (1 = nearest the light)
//   sampler(12) a comparison sampler for it: linear (2x2 hardware PCF), clamp to edge, GreaterEqual
//   texture(17) the sky-view LUT (RGBA16Float), rendered here once per frame (mc_shade_lut_render, sky_lut.metal)
//   sampler(13) linear, clamp to edge, for the LUT
// M2 adds (mc_shade_set_m2):
//   buffer(17)  ShadeLook, the art layer (CPU, same ring slot as ShadeFrame, + 1024 bytes)
//   buffer(18)  ShadeGpu, GPU-written state (auto-exposure); one shared buffer, read by the next frame
//   buffer(19)  ShadeResources, an argument buffer of texture resource IDs (mc_shade_resource), made resident on every encoder
// and the generic pieces the post chain (mcopt.metal.shade.ShadePost) is built from: libraries, pipelines, textures, and
// full-screen fragment passes / compute dispatches on the frame's command buffer with the globals bound.
// Until a shadow pass provides a map, texture(16) is a 1x1x2 Depth32Float array that is never sampled (ShadeFrame says
// shadows are off), so shaders that declare the slot always have a valid binding of the right type.
// Slots 16-19 (buffers, textures) and samplers 12-15 are reserved for shade; nothing in the backend uses them, except
// mcopt 0.3's own terrain (mcown.m): its draws bind vertex buffers 16-23, vertex texture 17 and samplers 14-15 of their own
// (its fragment stage's slots sit above ours under shading, see terrain.metal OWN_FS_*), and the pass's globals are bound
// again after them (MetalRenderPass.reapplyPipeline, and mc_shade_reopen_hook inside its split passes).
// Manual retain/release (the dylib builds with -fno-objc-arc), like the other files.
#import "mcmetal.h"

typedef struct {
	id<MTLTexture> dummyShadow;
	id<MTLSamplerState> compare;
	id<MTLTexture> lut;
	id<MTLSamplerState> linear;
	id<MTLRenderPipelineState> lutPipeline;
} ShadeState;

static ShadeState *state;

static id<MTLBuffer> frameBuffer;
static uint64_t frameOffset;
static id<MTLTexture> shadowMap;
static id<MTLSamplerState> shadowSampler;
static id<MTLBuffer> lookBuffer;
static uint64_t lookOffset;
static id<MTLBuffer> gpuBuffer;

id<MTLBuffer> mc_shade_gpu_buffer(void) { return gpuBuffer; }
#define SHADE_RESOURCES 16
#define SHADE_RES_RING_MAX 8
static id<MTLBuffer> resourceBuffers[SHADE_RES_RING_MAX];
static id<MTLTexture> resourceSlots[SHADE_RES_RING_MAX][SHADE_RESOURCES];
static int resourceSlot;
#define resourceBuffer resourceBuffers[resourceSlot]
#define resourceTextures resourceSlots[resourceSlot]
static id<MTLTexture> dummyTexture;

ShadeState *mc_shade_new(Ctx *ctx) {
	@autoreleasepool {
		ShadeState *s = calloc(1, sizeof(ShadeState));
		MTLTextureDescriptor *td = [MTLTextureDescriptor new];
		td.textureType = MTLTextureType2DArray;
		td.pixelFormat = MTLPixelFormatDepth32Float;
		td.width = 1;
		td.height = 1;
		td.arrayLength = 2;
		td.storageMode = MTLStorageModePrivate;
		td.usage = MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget;
		s->dummyShadow = [ctx->device newTextureWithDescriptor:td];
		[td release];
		s->dummyShadow.label = @"mcopt shade: no shadow map";
		MTLSamplerDescriptor *sd = [MTLSamplerDescriptor new];
		sd.minFilter = MTLSamplerMinMagFilterLinear;
		sd.magFilter = MTLSamplerMinMagFilterLinear;
		sd.mipFilter = MTLSamplerMipFilterNotMipmapped;
		sd.sAddressMode = MTLSamplerAddressModeClampToEdge;
		sd.tAddressMode = MTLSamplerAddressModeClampToEdge;
		sd.rAddressMode = MTLSamplerAddressModeClampToEdge;
		sd.compareFunction = MTLCompareFunctionGreaterEqual;
		s->compare = [ctx->device newSamplerStateWithDescriptor:sd];
		sd.compareFunction = MTLCompareFunctionNever;
		s->linear = [ctx->device newSamplerStateWithDescriptor:sd];
		[sd release];
		state = s;
		return s;
	}
}

id<MTLTexture> mc_shade_dummy_shadow(ShadeState *s) {
	return s->dummyShadow;
}

id<MTLSamplerState> mc_shade_compare_sampler(ShadeState *s) {
	return s->compare;
}

id<MTLTexture> mc_shade_lut_texture(ShadeState *s) {
	return s->lut;
}

void mc_shade_free(ShadeState *s) {
	if (!s) return;
	if (state == s) state = NULL;
	[s->dummyShadow release];
	[s->compare release];
	[s->linear release];
	[s->lut release];
	[s->lutPipeline release];
	free(s);
}

int mc_shade_lut_init(ShadeState *s, Ctx *ctx, const char *source, int width, int height, char *err, int errCap) {
	@autoreleasepool {
		if (!s->lut) {
			MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA16Float width:width height:height mipmapped:NO];
			td.storageMode = MTLStorageModePrivate;
			td.usage = MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget;
			s->lut = [ctx->device newTextureWithDescriptor:td];
			s->lut.label = @"mcopt shade: sky LUT";
		}
		MTLCompileOptions *o = [[MTLCompileOptions new] autorelease];
		o.languageVersion = MTLLanguageVersion3_0;
		o.mathMode = mc_math();
		NSError *e = nil;
		id<MTLLibrary> lib = [ctx->device newLibraryWithSource:[NSString stringWithUTF8String:source] options:o error:&e];
		if (!lib) {
			snprintf(err, errCap, "%s", e.localizedDescription.UTF8String);
			return -1;
		}
		MTLRenderPipelineDescriptor *pd = [[MTLRenderPipelineDescriptor new] autorelease];
		pd.vertexFunction = [[lib newFunctionWithName:@"shade_lut_vs"] autorelease];
		pd.fragmentFunction = [[lib newFunctionWithName:@"shade_lut_fs"] autorelease];
		pd.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA16Float;
		id<MTLRenderPipelineState> pso = [ctx->device newRenderPipelineStateWithDescriptor:pd error:&e];
		[lib release];
		if (!pso) {
			snprintf(err, errCap, "%s", e.localizedDescription.UTF8String);
			return -1;
		}
		[s->lutPipeline release];
		s->lutPipeline = pso;
		return 0;
	}
}

void mc_shade_lut_render(Enc *enc, ShadeState *s) {
	if (!s->lutPipeline || !frameBuffer) return;
	@autoreleasepool {
		id<MTLCommandBuffer> cb = mc_frame_cmd(enc);
		MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
		rp.colorAttachments[0].texture = s->lut;
		rp.colorAttachments[0].loadAction = MTLLoadActionDontCare;
		rp.colorAttachments[0].storeAction = MTLStoreActionStore;
		id<MTLRenderCommandEncoder> r = [cb renderCommandEncoderWithDescriptor:rp];
		r.label = @"mcopt shade: sky LUT";
		[r setRenderPipelineState:s->lutPipeline];
		[r setFragmentBuffer:frameBuffer offset:frameOffset atIndex:16];
		[r setFragmentBuffer:lookBuffer ? lookBuffer : frameBuffer offset:lookBuffer ? lookOffset : frameOffset + 1024 atIndex:17];
		[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
		[r endEncoding];
	}
}

void mc_shade_set(id<MTLBuffer> frame, uint64_t offset, id<MTLTexture> shadow, id<MTLSamplerState> sampler) {
	frameBuffer = frame;
	frameOffset = offset;
	shadowMap = shadow;
	shadowSampler = sampler;
}

int mc_shade_bind_render(id<MTLRenderCommandEncoder> r);

void mc_shade_bind(Enc *enc) {
	mc_shade_bind_render(enc->render);
}

void mc_water_bind(Enc *enc);
void mc_dl_bind(Enc *enc);

static void reopenBind(Enc *enc) {
	mc_shade_bind(enc);
	mc_water_bind(enc);
	mc_dl_bind(enc);
}

void mc_shade_reopen_hook(int on) {
	mc_reopen_hook = on ? reopenBind : NULL;
}

static void useResources(id<MTLRenderCommandEncoder> r, MTLRenderStages stages) {
	id<MTLResource> list[SHADE_RESOURCES];
	NSUInteger n = 0;
	for (int i = 0; i < SHADE_RESOURCES; i++) {
		id<MTLTexture> t = resourceTextures[i] ? resourceTextures[i] : dummyTexture;
		if (t) list[n++] = t;
	}
	if (n) [r useResources:list count:n usage:MTLResourceUsageRead stages:stages];
}

int mc_shade_bind_render(id<MTLRenderCommandEncoder> r) {
	if (!r || !frameBuffer) return 0;
	[r setVertexBuffer:frameBuffer offset:frameOffset atIndex:16];
	[r setFragmentBuffer:frameBuffer offset:frameOffset atIndex:16];
	if (shadowMap) {
		[r setVertexTexture:shadowMap atIndex:16];
		[r setFragmentTexture:shadowMap atIndex:16];
	}
	if (shadowSampler) {
		[r setVertexSamplerState:shadowSampler atIndex:12];
		[r setFragmentSamplerState:shadowSampler atIndex:12];
	}
	if (state && state->lut) {
		[r setVertexTexture:state->lut atIndex:17];
		[r setFragmentTexture:state->lut atIndex:17];
		[r setVertexSamplerState:state->linear atIndex:13];
		[r setFragmentSamplerState:state->linear atIndex:13];
	}
	if (lookBuffer) {
		[r setVertexBuffer:lookBuffer offset:lookOffset atIndex:17];
		[r setFragmentBuffer:lookBuffer offset:lookOffset atIndex:17];
	}
	if (gpuBuffer) {
		[r setVertexBuffer:gpuBuffer offset:0 atIndex:18];
		[r setFragmentBuffer:gpuBuffer offset:0 atIndex:18];
	}
	if (resourceBuffer) {
		[r setVertexBuffer:resourceBuffer offset:0 atIndex:19];
		[r setFragmentBuffer:resourceBuffer offset:0 atIndex:19];
		useResources(r, MTLRenderStageVertex | MTLRenderStageFragment);
	}
	return 1;
}

int mc_shade_bind_compute(id<MTLComputeCommandEncoder> c) {
	if (!c || !frameBuffer) return 0;
	[c setBuffer:frameBuffer offset:frameOffset atIndex:16];
	if (lookBuffer) [c setBuffer:lookBuffer offset:lookOffset atIndex:17];
	if (gpuBuffer) [c setBuffer:gpuBuffer offset:0 atIndex:18];
	if (shadowMap) [c setTexture:shadowMap atIndex:16];
	if (state && state->lut) [c setTexture:state->lut atIndex:17];
	return 1;
}

void mc_shade_set_m2(Ctx *ctx, id<MTLBuffer> look, uint64_t lookOff, id<MTLBuffer> gpu) {
	lookBuffer = look;
	lookOffset = lookOff;
	gpuBuffer = gpu;
	if (!resourceBuffer) {
		@autoreleasepool {
			MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA16Float width:1 height:1 mipmapped:NO];
			td.storageMode = MTLStorageModePrivate;
			td.usage = MTLTextureUsageShaderRead;
			dummyTexture = [ctx->device newTextureWithDescriptor:td];
			dummyTexture.label = @"mcopt shade: dummy";
			for (int k = 0; k < SHADE_RES_RING_MAX; k++) {
				resourceBuffers[k] = [ctx->device newBufferWithLength:SHADE_RESOURCES * 8 options:MTLResourceStorageModeShared];
				resourceBuffers[k].label = @"mcopt shade: ShadeResources";
				uint64_t *ids = resourceBuffers[k].contents;
				for (int i = 0; i < SHADE_RESOURCES; i++) ids[i] = dummyTexture.gpuResourceID._impl;
			}
		}
	}
}

void mc_shade_resource(int index, id<MTLTexture> texture) {
	if (!resourceBuffer || index < 0 || index >= SHADE_RESOURCES) return;
	if (resourceTextures[index] == texture) return;
	[texture retain];
	[resourceTextures[index] release];
	resourceTextures[index] = texture;
	((uint64_t *)resourceBuffer.contents)[index] = (texture ? texture : dummyTexture).gpuResourceID._impl;
}

void mc_shade_resources_advance(int ring) {
	if (!resourceBuffers[0]) return;
	if (ring < 2) ring = 2;
	if (ring > SHADE_RES_RING_MAX) ring = SHADE_RES_RING_MAX;
	int from = resourceSlot, to = (resourceSlot + 1) % ring;
	for (int i = 0; i < SHADE_RESOURCES; i++) {
		id<MTLTexture> t = resourceSlots[from][i];
		[t retain];
		[resourceSlots[to][i] release];
		resourceSlots[to][i] = t;
	}
	memcpy(resourceBuffers[to].contents, resourceBuffers[from].contents, SHADE_RESOURCES * 8);
	resourceSlot = to;
}

id<MTLLibrary> mc_shade_library(Ctx *ctx, const char *source, char *err, int errCap) {
	@autoreleasepool {
		MTLCompileOptions *o = [[MTLCompileOptions new] autorelease];
		o.languageVersion = MTLLanguageVersion3_0;
		o.mathMode = mc_math();
		NSError *e = nil;
		id<MTLLibrary> lib = [ctx->device newLibraryWithSource:[NSString stringWithUTF8String:source] options:o error:&e];
		if (!lib) snprintf(err, errCap, "%s", e.localizedDescription.UTF8String);
		return lib;
	}
}

id<MTLRenderPipelineState> mc_shade_pso(Ctx *ctx, id<MTLLibrary> lib, const char *vs, const char *fs, int format, int blend, char *err, int errCap) {
	@autoreleasepool {
		MTLRenderPipelineDescriptor *pd = [[MTLRenderPipelineDescriptor new] autorelease];
		pd.vertexFunction = [[lib newFunctionWithName:[NSString stringWithUTF8String:vs]] autorelease];
		pd.fragmentFunction = [[lib newFunctionWithName:[NSString stringWithUTF8String:fs]] autorelease];
		if (!pd.vertexFunction || !pd.fragmentFunction) {
			snprintf(err, errCap, "no function %s / %s", vs, fs);
			return nil;
		}
		pd.colorAttachments[0].pixelFormat = (MTLPixelFormat)format;
		if (blend) {
			MTLRenderPipelineColorAttachmentDescriptor *c = pd.colorAttachments[0];
			c.blendingEnabled = YES;
			c.sourceRGBBlendFactor = c.sourceAlphaBlendFactor = blend == 2 ? MTLBlendFactorSourceAlpha : MTLBlendFactorOne;
			c.destinationRGBBlendFactor = c.destinationAlphaBlendFactor = blend == 1 ? MTLBlendFactorOne : MTLBlendFactorOneMinusSourceAlpha;
		}
		NSError *e = nil;
		id<MTLRenderPipelineState> pso = [ctx->device newRenderPipelineStateWithDescriptor:pd error:&e];
		if (!pso) snprintf(err, errCap, "%s", e.localizedDescription.UTF8String);
		return pso;
	}
}

id<MTLComputePipelineState> mc_shade_cpso(Ctx *ctx, id<MTLLibrary> lib, const char *name, char *err, int errCap) {
	@autoreleasepool {
		id<MTLFunction> fn = [[lib newFunctionWithName:[NSString stringWithUTF8String:name]] autorelease];
		if (!fn) {
			snprintf(err, errCap, "no function %s", name);
			return nil;
		}
		NSError *e = nil;
		id<MTLComputePipelineState> pso = [ctx->device newComputePipelineStateWithFunction:fn error:&e];
		if (!pso) snprintf(err, errCap, "%s", e.localizedDescription.UTF8String);
		return pso;
	}
}

id<MTLTexture> mc_shade_texture(Ctx *ctx, int format, int width, int height, int lossy, const char *label) {
	@autoreleasepool {
		MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:(MTLPixelFormat)format width:width height:height mipmapped:NO];
		td.storageMode = MTLStorageModePrivate;
		td.usage = MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget;
		if (lossy && [ctx->device supportsFamily:MTLGPUFamilyApple8] && format != MTLPixelFormatRG11B10Float && format != MTLPixelFormatRGB9E5Float) td.compressionType = MTLTextureCompressionTypeLossy;
		id<MTLTexture> t = [ctx->device newTextureWithDescriptor:td];
		if (t && label) t.label = [NSString stringWithUTF8String:label];
		return t;
	}
}

id<MTLTexture> mc_shade_texture_cpu(Ctx *ctx, int width, int height, int mips, const char *label) {
	@autoreleasepool {
		MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:width height:height mipmapped:NO];
		td.mipmapLevelCount = mips < 1 ? 1 : mips;
		td.storageMode = MTLStorageModeShared;
		td.usage = MTLTextureUsageShaderRead;
		id<MTLTexture> t = [ctx->device newTextureWithDescriptor:td];
		if (t && label) t.label = [NSString stringWithUTF8String:label];
		return t;
	}
}

void mc_shade_texture_upload(id<MTLTexture> t, int mip, int x, int y, int w, int h, const void *bytes, int rowBytes) {
	if (!t || !bytes || w <= 0 || h <= 0) return;
	[t replaceRegion:MTLRegionMake2D(x, y, w, h) mipmapLevel:mip withBytes:bytes bytesPerRow:rowBytes];
}

void mc_shade_texture_release(id<MTLTexture> t) {
	[t release];
}

id<MTLBuffer> mc_shade_buffer(Ctx *ctx, uint64_t size) {
	id<MTLBuffer> b = [ctx->device newBufferWithLength:size options:MTLResourceStorageModeShared];
	memset(b.contents, 0, size);
	return b;
}

static void bindGlobalsRender(id<MTLRenderCommandEncoder> r) {
	if (frameBuffer) [r setFragmentBuffer:frameBuffer offset:frameOffset atIndex:16];
	if (lookBuffer) [r setFragmentBuffer:lookBuffer offset:lookOffset atIndex:17];
	if (gpuBuffer) [r setFragmentBuffer:gpuBuffer offset:0 atIndex:18];
	if (resourceBuffer) {
		[r setFragmentBuffer:resourceBuffer offset:0 atIndex:19];
		useResources(r, MTLRenderStageFragment);
	}
	if (state && state->lut) {
		[r setFragmentTexture:state->lut atIndex:17];
		[r setFragmentSamplerState:state->linear atIndex:13];
	}
	if (shadowMap) [r setFragmentTexture:shadowMap atIndex:16];
	if (shadowSampler) [r setFragmentSamplerState:shadowSampler atIndex:12];
}

void mc_shade_pass(Enc *enc, id<MTLRenderPipelineState> pso, id<MTLTexture> target, int load, id<MTLTexture> const *textures, int count,
                   const void *bytes, int length, const char *label) {
	if (!pso || !target) return;
	@autoreleasepool {
		id<MTLCommandBuffer> cb = mc_frame_cmd(enc);
		MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
		rp.colorAttachments[0].texture = target;
		rp.colorAttachments[0].loadAction = load == 1 ? MTLLoadActionLoad : load == 2 ? MTLLoadActionClear : MTLLoadActionDontCare;
		rp.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 0);
		rp.colorAttachments[0].storeAction = MTLStoreActionStore;
		if (enc->samples && enc->sampleCount + 4 <= (int) enc->samples.sampleCount) {
			MTLRenderPassSampleBufferAttachmentDescriptor *s = rp.sampleBufferAttachments[0];
			s.sampleBuffer = enc->samples;
			s.startOfVertexSampleIndex = enc->sampleCount;
			s.endOfVertexSampleIndex = enc->sampleCount + 1;
			s.startOfFragmentSampleIndex = enc->sampleCount + 2;
			s.endOfFragmentSampleIndex = enc->sampleCount + 3;
			enc->sampleCount += 4;
		}
		id<MTLRenderCommandEncoder> r = [cb renderCommandEncoderWithDescriptor:rp];
		if (label) r.label = [NSString stringWithUTF8String:label];
		[r setRenderPipelineState:pso];
		bindGlobalsRender(r);
		for (int i = 0; i < count; i++) if (textures[i]) [r setFragmentTexture:textures[i] atIndex:i];
		if (bytes && length > 0) [r setFragmentBytes:bytes length:length atIndex:0];
		[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
		[r endEncoding];
	}
}

void mc_shade_dispatch(Enc *enc, id<MTLComputePipelineState> pso, int width, int height, int tw, int th, id<MTLTexture> const *textures, int count,
                       const void *bytes, int length, const char *label) {
	if (!pso) return;
	@autoreleasepool {
		id<MTLCommandBuffer> cb = mc_frame_cmd(enc);
		id<MTLComputeCommandEncoder> c = [cb computeCommandEncoder];
		if (label) c.label = [NSString stringWithUTF8String:label];
		[c setComputePipelineState:pso];
		if (frameBuffer) [c setBuffer:frameBuffer offset:frameOffset atIndex:16];
		if (lookBuffer) [c setBuffer:lookBuffer offset:lookOffset atIndex:17];
		if (gpuBuffer) [c setBuffer:gpuBuffer offset:0 atIndex:18];
		for (int i = 0; i < count; i++) if (textures[i]) [c setTexture:textures[i] atIndex:i];
		if (bytes && length > 0) [c setBytes:bytes length:length atIndex:0];
		[c dispatchThreads:MTLSizeMake(width, height, 1) threadsPerThreadgroup:MTLSizeMake(tw, th, 1)];
		[c endEncoding];
	}
}

void mc_shade_bake(Ctx *ctx, id<MTLRenderPipelineState> pso, id<MTLTexture> target) {
	@autoreleasepool {
		id<MTLCommandBuffer> cb = [ctx->queue commandBuffer];
		MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
		rp.colorAttachments[0].texture = target;
		rp.colorAttachments[0].loadAction = MTLLoadActionDontCare;
		rp.colorAttachments[0].storeAction = MTLStoreActionStore;
		id<MTLRenderCommandEncoder> r = [cb renderCommandEncoderWithDescriptor:rp];
		r.label = @"mcopt shade: bake";
		[r setRenderPipelineState:pso];
		[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
		[r endEncoding];
		[cb commit];
		[cb waitUntilCompleted];
	}
}

int mc_shade_texture_width(id<MTLTexture> t) { return t ? (int)t.width : 0; }
int mc_shade_texture_height(id<MTLTexture> t) { return t ? (int)t.height : 0; }
int mc_shade_texture_format(id<MTLTexture> t) { return t ? (int)t.pixelFormat : 0; }
