#import "mcmetal.h"
#include <string.h>
#include "lodmesh.h"

#define LOD_MAX_LAYOUTS 8

extern int gSeamTaaOn;
extern id<MTLTexture> gSeamTaaHist;
extern uint8_t gSeamTaaBytes[96];

static void lodErr(NSError *e, const char *what, char *err, int cap) {
	if (err && cap > 0) snprintf(err, cap, "%s: %s", what, e ? e.description.UTF8String : "unknown error");
}

int mcl_attachments(Enc *enc) {
	return enc->render ? enc->colorCount : 0;
}

id<MTLBuffer> mcl_buffer(Ctx *ctx, uint64_t size) {
	return [ctx->device newBufferWithLength:size options:MTLResourceStorageModeShared | MTLResourceHazardTrackingModeUntracked];
}

void *mcl_contents(id<MTLBuffer> b) {
	return b.contents;
}

void mcl_release(id obj) {
	[obj release];
}

#define LOD_PSO_COMP 0
#define LOD_PSO_MESH 1
#define LOD_PSO_PLANT 2

typedef struct {
	MTLPixelFormat colors[MAX_COLORS];
	MTLPixelFormat depth;
	int colorCount;
	int gbuffer;
	int kind;
	id<MTLRenderPipelineState> pso;
} ColLayout;

typedef struct {
	Ctx *ctx;
	id<MTLLibrary> library;
	id<MTLComputePipelineState> march, segMax, segFill, rows;
	id<MTLComputePipelineState> meshReset, meshCull, meshFinish, meshQuads, meshFinish2, hzClear, hzPrefix, meshEmit, meshFinishList;
	id<MTLFunction> meshVs, meshFsVanilla, meshFsGbuffer, plantVs, plantFsVanilla, plantFsGbuffer;
	id<MTLBuffer> meshIdx, plantIdx;
	id<MTLDepthStencilState> depthTest;
	id<MTLFence> cullDone, drawDone;
	id<MTLFunction> vs, fsVanilla, fsGbuffer;
	id<MTLDepthStencilState> depth;
	id<MTLSamplerState> atlasSampler;
	ColLayout layouts[LOD_MAX_LAYOUTS];
	int layoutCount;
	id<MTLCounterSampleBuffer> cullTimes;
	int cullSlot, cullTimesTried;
	MTLTimestamp cullCpu0, cullGpu0;
	uint64_t cullLast[16];
} LodCols;

LodCols *mcl_cols_new(Ctx *ctx, const char *source, char *err, int errCap) {
	@autoreleasepool {
		MTLCompileOptions *o = [[MTLCompileOptions new] autorelease];
		o.languageVersion = MTLLanguageVersion3_1;
		o.mathMode = MTLMathModeSafe;
		NSError *e = nil;
		id<MTLLibrary> lib = [ctx->device newLibraryWithSource:[NSString stringWithUTF8String:source] options:o error:&e];
		if (!lib) {
			lodErr(e, "columns.metal", err, errCap);
			return NULL;
		}
		id<MTLFunction> march = [[lib newFunctionWithName:@"lod_columns"] autorelease];
		if (!march) {
			snprintf(err, errCap, "columns.metal: no lod_columns");
			return NULL;
		}
		id<MTLComputePipelineState> pso = [ctx->device newComputePipelineStateWithFunction:march error:&e];
		if (!pso) {
			lodErr(e, "lod_columns pipeline", err, errCap);
			return NULL;
		}
		LodCols *l = calloc(1, sizeof(LodCols));
		l->ctx = ctx;
		l->cullSlot = -1;
		l->library = lib;
		l->march = pso;
		id<MTLFunction> f1 = [[lib newFunctionWithName:@"lod_seg_max"] autorelease], f2 = [[lib newFunctionWithName:@"lod_seg_fill"] autorelease];
		l->segMax = f1 ? [ctx->device newComputePipelineStateWithFunction:f1 error:&e] : nil;
		l->segFill = f2 ? [ctx->device newComputePipelineStateWithFunction:f2 error:&e] : nil;
		if (!l->segMax || !l->segFill) {
			lodErr(e, "lod_seg pipelines", err, errCap);
			return NULL;
		}
		id<MTLFunction> f3 = [[lib newFunctionWithName:@"lod_rows"] autorelease];
		l->rows = f3 ? [ctx->device newComputePipelineStateWithFunction:f3 error:&e] : nil;
		if (!l->rows) {
			lodErr(e, "lod_rows pipeline", err, errCap);
			return NULL;
		}
		l->vs = [lib newFunctionWithName:@"lod_comp_vs"];
		l->fsVanilla = [lib newFunctionWithName:@"lod_comp_vanilla"];
		l->fsGbuffer = [lib newFunctionWithName:@"lod_comp_gbuffer"];
		MTLDepthStencilDescriptor *dd = [[MTLDepthStencilDescriptor new] autorelease];
		dd.depthCompareFunction = MTLCompareFunctionGreaterEqual;
		dd.depthWriteEnabled = YES;
		l->depth = [ctx->device newDepthStencilStateWithDescriptor:dd];
		MTLSamplerDescriptor *sd = [[MTLSamplerDescriptor new] autorelease];
		sd.minFilter = MTLSamplerMinMagFilterNearest;
		sd.magFilter = MTLSamplerMinMagFilterNearest;
		sd.mipFilter = MTLSamplerMipFilterLinear;
		sd.sAddressMode = MTLSamplerAddressModeClampToEdge;
		sd.tAddressMode = MTLSamplerAddressModeClampToEdge;
		l->atlasSampler = [ctx->device newSamplerStateWithDescriptor:sd];
		if (!l->vs || !l->fsVanilla || !l->fsGbuffer) {
			snprintf(err, errCap, "columns.metal: missing entry points");
			return NULL;
		}
		const char *kernels[] = {"lod_mesh_reset", "lod_mesh_cull", "lod_mesh_finish", "lod_mesh_quads", "lod_mesh_finish2", "lod_mesh_hz_clear", "lod_mesh_hz_prefix",
			"lod_mesh_emit", "lod_mesh_finish_list"};
		id<MTLComputePipelineState> *slots[] = {&l->meshReset, &l->meshCull, &l->meshFinish, &l->meshQuads, &l->meshFinish2, &l->hzClear, &l->hzPrefix,
			&l->meshEmit, &l->meshFinishList};
		for (int i = 0; i < 9; i++) {
			id<MTLFunction> fn = [[lib newFunctionWithName:[NSString stringWithUTF8String:kernels[i]]] autorelease];
			*slots[i] = fn ? [ctx->device newComputePipelineStateWithFunction:fn error:&e] : nil;
			if (!*slots[i]) {
				lodErr(e, kernels[i], err, errCap);
				return NULL;
			}
		}
		l->meshVs = [lib newFunctionWithName:@"lod_mesh_vs2"];
		l->meshFsVanilla = [lib newFunctionWithName:@"lod_mesh_vanilla"];
		l->meshFsGbuffer = [lib newFunctionWithName:@"lod_mesh_gbuffer"];
		l->plantVs = [lib newFunctionWithName:@"lod_mesh_plant_vs"];
		l->plantFsVanilla = [lib newFunctionWithName:@"lod_mesh_plant_vanilla"];
		l->plantFsGbuffer = [lib newFunctionWithName:@"lod_mesh_plant_gbuffer"];
		if (!l->meshVs || !l->meshFsVanilla || !l->meshFsGbuffer || !l->plantVs || !l->plantFsVanilla || !l->plantFsGbuffer) {
			snprintf(err, errCap, "columns.metal: missing mesh entry points");
			return NULL;
		}
		uint16_t idx[64 * 6];
		for (int q = 0; q < 64; q++) {
			uint16_t b = (uint16_t) (q * 4);
			uint16_t k[6] = {b, (uint16_t) (b + 1), (uint16_t) (b + 2), (uint16_t) (b + 2), (uint16_t) (b + 3), b};
			memcpy(idx + q * 6, k, sizeof k);
		}
		l->meshIdx = [ctx->device newBufferWithBytes:idx length:sizeof idx options:MTLResourceStorageModeShared];
		l->cullDone = [ctx->device newFence];
		l->drawDone = [ctx->device newFence];
		l->plantIdx = [ctx->device newBufferWithBytes:idx length:32 * 6 * sizeof(uint16_t) options:MTLResourceStorageModeShared];
		return l;
	}
}

static id<MTLRenderPipelineState> colPipeline(LodCols *l, Enc *enc, int gbuffer, int kind, char *err, int errCap) {
	for (int i = 0; i < l->layoutCount; i++) {
		ColLayout *k = &l->layouts[i];
		if (k->colorCount != enc->colorCount || k->gbuffer != gbuffer || k->kind != kind) continue;
		if ((enc->depth ? enc->depth.pixelFormat : MTLPixelFormatInvalid) != k->depth) continue;
		int same = 1;
		for (int c = 0; c < enc->colorCount; c++)
			if ((enc->colors[c] ? enc->colors[c].pixelFormat : MTLPixelFormatInvalid) != k->colors[c]) same = 0;
		if (same) return k->pso;
	}
	@autoreleasepool {
		MTLRenderPipelineDescriptor *pd = [[MTLRenderPipelineDescriptor new] autorelease];
		pd.label = gbuffer ? @"lod columns gbuffer" : @"lod columns vanilla";
		pd.vertexFunction = kind == LOD_PSO_MESH ? l->meshVs : kind == LOD_PSO_PLANT ? l->plantVs : l->vs;
		if (kind == LOD_PSO_MESH) pd.fragmentFunction = gbuffer ? l->meshFsGbuffer : l->meshFsVanilla;
		else if (kind == LOD_PSO_PLANT) pd.fragmentFunction = gbuffer ? l->plantFsGbuffer : l->plantFsVanilla;
		else pd.fragmentFunction = gbuffer ? l->fsGbuffer : l->fsVanilla;
		for (int c = 0; c < enc->colorCount; c++) {
			if (!enc->colors[c]) continue;
			pd.colorAttachments[c].pixelFormat = enc->colors[c].pixelFormat;
			int written = gbuffer ? (c >= 1 && c <= 4) : c == 0;
			pd.colorAttachments[c].writeMask = written ? MTLColorWriteMaskAll : MTLColorWriteMaskNone;
		}
		pd.depthAttachmentPixelFormat = enc->depth ? enc->depth.pixelFormat : MTLPixelFormatInvalid;
		pd.inputPrimitiveTopology = MTLPrimitiveTopologyClassTriangle;
		NSError *e = nil;
		id<MTLRenderPipelineState> pso = [l->ctx->device newRenderPipelineStateWithDescriptor:pd error:&e];
		if (!pso) {
			lodErr(e, "lod columns pipeline", err, errCap);
			return nil;
		}
		if (l->layoutCount == LOD_MAX_LAYOUTS) {
			[l->layouts[0].pso release];
			memmove(&l->layouts[0], &l->layouts[1], sizeof(ColLayout) * (LOD_MAX_LAYOUTS - 1));
			l->layoutCount--;
		}
		ColLayout *k = &l->layouts[l->layoutCount++];
		memset(k, 0, sizeof *k);
		k->colorCount = enc->colorCount;
		for (int c = 0; c < enc->colorCount; c++) k->colors[c] = enc->colors[c] ? enc->colors[c].pixelFormat : MTLPixelFormatInvalid;
		k->depth = pd.depthAttachmentPixelFormat;
		k->gbuffer = gbuffer;
		k->kind = kind;
		k->pso = pso;
		return pso;
	}
}

void mcl_cols_march(LodCols *l, Enc *enc, const void *frame, int frameLength, id<MTLBuffer> geom, id<MTLBuffer> color, id<MTLBuffer> maxmip,
	id<MTLBuffer> mask, id<MTLBuffer> out, id<MTLBuffer> dbg, id<MTLBuffer> crowns, int columns, int segments, id<MTLBuffer> segMax,
	id<MTLBuffer> texWords, id<MTLBuffer> palette) {
	if (columns <= 0) return;
	mc_pre_end_encoders(enc);
	@autoreleasepool {
		id<MTLComputeCommandEncoder> c = [mc_pre(enc) computeCommandEncoder];
		c.label = @"lod columns";
		[c setBytes:frame length:frameLength atIndex:0];
		[c setBuffer:geom offset:0 atIndex:1];
		[c setBuffer:color offset:0 atIndex:2];
		[c setBuffer:maxmip offset:0 atIndex:3];
		[c setBuffer:mask offset:0 atIndex:4];
		[c setBuffer:dbg offset:0 atIndex:6];
		[c setBuffer:crowns offset:0 atIndex:7];
		[c setBuffer:texWords offset:0 atIndex:9];
		[c setBuffer:palette offset:0 atIndex:10];
		if (segments < 0) {
			[c setComputePipelineState:l->rows];
			[c setBuffer:out offset:0 atIndex:5];
			NSUInteger n = (NSUInteger) columns * (NSUInteger) (-segments);
			[c dispatchThreads:MTLSizeMake(n, 1, 1) threadsPerThreadgroup:MTLSizeMake(MIN((NSUInteger) 64, l->rows.maxTotalThreadsPerThreadgroup), 1, 1)];
		} else if (segments == 0) {
			[c setComputePipelineState:l->march];
			[c setBuffer:out offset:0 atIndex:5];
			NSUInteger tg = MIN((NSUInteger) 64, l->march.maxTotalThreadsPerThreadgroup);
			[c dispatchThreads:MTLSizeMake(columns, 1, 1) threadsPerThreadgroup:MTLSizeMake(tg, 1, 1)];
		} else {
			NSUInteger n = (NSUInteger) columns * segments;
			[c setComputePipelineState:l->segMax];
			[c setBuffer:segMax offset:0 atIndex:5];
			[c dispatchThreads:MTLSizeMake(n, 1, 1) threadsPerThreadgroup:MTLSizeMake(MIN((NSUInteger) 64, l->segMax.maxTotalThreadsPerThreadgroup), 1, 1)];
			[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
			[c setComputePipelineState:l->segFill];
			[c setBuffer:out offset:0 atIndex:5];
			[c setBuffer:segMax offset:0 atIndex:8];
			[c dispatchThreads:MTLSizeMake(n, 1, 1) threadsPerThreadgroup:MTLSizeMake(MIN((NSUInteger) 64, l->segFill.maxTotalThreadsPerThreadgroup), 1, 1)];
		}
		[c endEncoding];
	}
}

int mcl_cols_composite(LodCols *l, Enc *enc, int gbuffer, const void *frame, int frameLength, id<MTLBuffer> out, id<MTLBuffer> geom,
	id<MTLBuffer> color, id<MTLBuffer> crowns, id<MTLBuffer> texWords, id<MTLBuffer> palette, id<MTLTexture> atlas, char *err, int errCap) {
	id<MTLRenderCommandEncoder> r = enc->render;
	if (!r) return 0;
	id<MTLRenderPipelineState> pso = colPipeline(l, enc, gbuffer, LOD_PSO_COMP, err, errCap);
	if (!pso) return -1;
	[r pushDebugGroup:@"lod columns"];
	[r setRenderPipelineState:pso];
	[r setDepthStencilState:l->depth];
	[r setCullMode:MTLCullModeNone];
	[r setTriangleFillMode:MTLTriangleFillModeFill];
	[r setDepthBias:0 slopeScale:0 clamp:0];
	[r setVertexBytes:frame length:frameLength atIndex:22];
	[r setFragmentBytes:frame length:frameLength atIndex:22];
	[r setFragmentBuffer:out offset:0 atIndex:23];
	[r setFragmentBuffer:geom offset:0 atIndex:24];
	[r setFragmentBuffer:color offset:0 atIndex:25];
	[r setFragmentBuffer:crowns offset:0 atIndex:26];
	[r setFragmentBuffer:texWords offset:0 atIndex:27];
	[r setFragmentBuffer:palette offset:0 atIndex:28];
	[r setFragmentTexture:atlas atIndex:20];
	[r setFragmentSamplerState:l->atlasSampler atIndex:14];
	[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:6];
	[r popDebugGroup];
	return 0;
}

id<MTLBuffer> mcl_private_buffer(Ctx *ctx, uint64_t size) {
	return [ctx->device newBufferWithLength:size options:MTLResourceStorageModePrivate];
}

id<MTLBuffer> mcl_private_untracked_buffer(Ctx *ctx, uint64_t size) {
	return [ctx->device newBufferWithLength:size options:MTLResourceStorageModePrivate | MTLResourceHazardTrackingModeUntracked];
}

int mcl_enc_size(Enc *enc) {
	return enc->render ? (enc->width << 16) | (enc->height & 0xFFFF) : 0;
}

int mcl_mesh_tile(const uint32_t *geom, const uint32_t *crown, const uint32_t *runs, const uint32_t *plantA, const uint32_t *plantB, int logN, int level,
	int tx, int tz, int neighbors, int block, uint32_t *header, uint32_t *quads, int cap) {
	LmIn in = {geom, crown, runs, plantA, plantB, logN, level, tx, tz, neighbors, block};
	return lm_mesh(&in, header, quads, cap);
}

static id<MTLComputePipelineState> pkPso(LodCols *l, const char *name);

#define CULL_SLOTS 16

static id<MTLComputeCommandEncoder> cullEncoder(LodCols *l, Enc *enc) {
	id<MTLCommandBuffer> cb = mc_pre(enc);
	int slot = l->cullSlot;
	l->cullSlot = -1;
	if (slot < 0 || !l->cullTimes) return [cb computeCommandEncoder];
	MTLComputePassDescriptor *d = [MTLComputePassDescriptor computePassDescriptor];
	d.sampleBufferAttachments[0].sampleBuffer = l->cullTimes;
	d.sampleBufferAttachments[0].startOfEncoderSampleIndex = (NSUInteger) (2 * slot);
	d.sampleBufferAttachments[0].endOfEncoderSampleIndex = (NSUInteger) (2 * slot + 1);
	return [cb computeCommandEncoderWithDescriptor:d];
}

int mcl_cull_time_slot(LodCols *l, int slot) {
	if (!l->cullTimesTried) {
		l->cullTimesTried = 1;
		id<MTLDevice> device = l->ctx->device;
		if ([device supportsCounterSampling:MTLCounterSamplingPointAtStageBoundary]) {
			@autoreleasepool {
				for (id<MTLCounterSet> set in device.counterSets) {
					if (![set.name isEqualToString:MTLCommonCounterSetTimestamp]) continue;
					MTLCounterSampleBufferDescriptor *d = [[MTLCounterSampleBufferDescriptor new] autorelease];
					d.counterSet = set;
					d.storageMode = MTLStorageModeShared;
					d.sampleCount = CULL_SLOTS * 2;
					l->cullTimes = [device newCounterSampleBufferWithDescriptor:d error:nil];
					[device sampleTimestamps:&l->cullCpu0 gpuTimestamp:&l->cullGpu0];
					break;
				}
			}
		}
	}
	if (!l->cullTimes || slot < 0 || slot >= CULL_SLOTS) return -1;
	l->cullSlot = slot;
	return 0;
}

double mcl_cull_time_read(LodCols *l, int slot) {
	if (!l->cullTimes || slot < 0 || slot >= CULL_SLOTS) return -1;
	MTLTimestamp cpu1, gpu1;
	[l->ctx->device sampleTimestamps:&cpu1 gpuTimestamp:&gpu1];
	if (gpu1 <= l->cullGpu0 || cpu1 <= l->cullCpu0) return -1;
	double nsPerTick = (double) (cpu1 - l->cullCpu0) / (double) (gpu1 - l->cullGpu0);
	double ms = -1;
	@autoreleasepool {
		NSData *data = [l->cullTimes resolveCounterRange:NSMakeRange((NSUInteger) (2 * slot), 2)];
		if (data.length >= 2 * sizeof(MTLCounterResultTimestamp)) {
			const MTLCounterResultTimestamp *ts = data.bytes;
			uint64_t a = ts[0].timestamp, b = ts[1].timestamp;
			if (a != MTLCounterErrorValue && b != MTLCounterErrorValue && a != 0 && b > a && a != l->cullLast[slot]) {
				l->cullLast[slot] = a;
				ms = (double) (b - a) * nsPerTick / 1e6;
			}
		}
	}
	return ms;
}

void mcl_mesh_cull(LodCols *l, Enc *enc, const void *frame, int frameLength, const void *comp, int compLength, id<MTLBuffer> table, id<MTLBuffer> arena,
	id<MTLBuffer> mask, id<MTLBuffer> args, id<MTLBuffer> inst, id<MTLBuffer> plantInst, id<MTLBuffer> survivors, id<MTLBuffer> horizon, id<MTLBuffer> list,
	id<MTLBuffer> geom, id<MTLBuffer> crowns, int blocks, int fences) {
	mc_pre_end_encoders(enc);
	@autoreleasepool {
		id<MTLComputeCommandEncoder> c = cullEncoder(l, enc);
		c.label = @"lod mesh cull";
		if (fences) [c waitForFence:l->drawDone];
		[c setBytes:frame length:frameLength atIndex:0];
		[c setBuffer:table offset:0 atIndex:1];
		[c setBuffer:arena offset:0 atIndex:2];
		[c setBuffer:mask offset:0 atIndex:3];
		[c setBuffer:args offset:0 atIndex:4];
		[c setBuffer:inst offset:0 atIndex:5];
		[c setBuffer:plantInst offset:0 atIndex:6];
		[c setBuffer:survivors offset:0 atIndex:7];
		[c setBytes:comp length:compLength atIndex:22];
		[c setBuffer:inst offset:0 atIndex:23];
		[c setBuffer:arena offset:0 atIndex:24];
		[c setBuffer:horizon offset:0 atIndex:8];
		[c setBuffer:list offset:0 atIndex:9];
		[c setBuffer:geom offset:0 atIndex:12];
		[c setBuffer:crowns offset:0 atIndex:13];
		[c setComputePipelineState:l->meshReset];
		[c dispatchThreads:MTLSizeMake(1, 1, 1) threadsPerThreadgroup:MTLSizeMake(1, 1, 1)];
		[c setComputePipelineState:l->hzClear];
		[c dispatchThreads:MTLSizeMake(4096 * 128, 1, 1) threadsPerThreadgroup:MTLSizeMake(256, 1, 1)];
		[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
		id<MTLComputePipelineState> farMin = (((const int32_t *) frame)[98] & 16) ? pkPso(l, "lod_mesh_far_min") : nil;
		if (farMin) {
			[c setComputePipelineState:farMin];
			[c dispatchThreads:MTLSizeMake((NSUInteger) blocks, 1, 1) threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
			[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
		}
		[c setComputePipelineState:l->meshCull];
		[c dispatchThreads:MTLSizeMake((NSUInteger) blocks, 1, 1) threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
		[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
		[c setComputePipelineState:l->meshFinishList];
		[c dispatchThreads:MTLSizeMake(1, 1, 1) threadsPerThreadgroup:MTLSizeMake(1, 1, 1)];
		[c setComputePipelineState:l->hzPrefix];
		[c dispatchThreads:MTLSizeMake(4096, 1, 1) threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
		[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
		[c setComputePipelineState:l->meshEmit];
		[c dispatchThreadgroupsWithIndirectBuffer:args indirectBufferOffset:19 * 4 threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
		[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
		[c setComputePipelineState:l->meshFinish];
		[c dispatchThreads:MTLSizeMake(1, 1, 1) threadsPerThreadgroup:MTLSizeMake(1, 1, 1)];
		[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
		[c setComputePipelineState:l->meshQuads];
		[c dispatchThreadgroupsWithIndirectBuffer:args indirectBufferOffset:16 * 4 threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
		[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
		[c setComputePipelineState:l->meshFinish2];
		[c dispatchThreads:MTLSizeMake(1, 1, 1) threadsPerThreadgroup:MTLSizeMake(1, 1, 1)];
		if (fences) [c updateFence:l->cullDone];
		[c endEncoding];
	}
}

int mcl_mesh_draw(LodCols *l, Enc *enc, int gbuffer, const void *comp, int compLength, id<MTLBuffer> arena, id<MTLBuffer> args, id<MTLBuffer> inst,
	id<MTLBuffer> plantInst, id<MTLBuffer> survivors, id<MTLBuffer> geom, id<MTLBuffer> color, id<MTLBuffer> crowns, id<MTLBuffer> texWords,
	id<MTLBuffer> palette, id<MTLTexture> atlas, id<MTLBuffer> table, const void *meshFrame, int meshFrameLength, int fences, char *err, int errCap) {
	id<MTLRenderCommandEncoder> r = enc->render;
	if (!r) return 0;
	id<MTLRenderPipelineState> pso = colPipeline(l, enc, gbuffer, LOD_PSO_MESH, err, errCap);
	id<MTLRenderPipelineState> ppso = pso ? colPipeline(l, enc, gbuffer, LOD_PSO_PLANT, err, errCap) : nil;
	if (!pso || !ppso) return -1;
	[r pushDebugGroup:@"lod mesh"];
	if (fences) [r waitForFence:l->cullDone beforeStages:MTLRenderStageVertex];
	[r setDepthStencilState:l->depth];
	[r setTriangleFillMode:MTLTriangleFillModeFill];
	[r setDepthBias:0 slopeScale:0 clamp:0];
	[r setVertexBytes:comp length:compLength atIndex:22];
	[r setFragmentBytes:comp length:compLength atIndex:22];
	[r setVertexBuffer:arena offset:0 atIndex:24];
	[r setVertexBuffer:args offset:0 atIndex:25];
	[r setVertexBuffer:survivors offset:0 atIndex:26];
	[r setFragmentBuffer:geom offset:0 atIndex:24];
	[r setFragmentBuffer:color offset:0 atIndex:25];
	[r setFragmentBuffer:crowns offset:0 atIndex:26];
	[r setFragmentBuffer:texWords offset:0 atIndex:27];
	[r setFragmentBuffer:palette offset:0 atIndex:28];
	[r setFragmentTexture:atlas atIndex:20];
	[r setFragmentSamplerState:l->atlasSampler atIndex:14];
	[r setVertexBuffer:table offset:0 atIndex:27];
	[r setFragmentBuffer:table offset:0 atIndex:29];
	if (gSeamTaaOn && gSeamTaaHist) {
		[r setFragmentTexture:gSeamTaaHist atIndex:21];
		[r setFragmentBytes:gSeamTaaBytes length:sizeof(gSeamTaaBytes) atIndex:30];
	}
	[r setVertexBuffer:geom offset:0 atIndex:28];
	[r setVertexBuffer:crowns offset:0 atIndex:29];
	[r setVertexBytes:meshFrame length:meshFrameLength atIndex:30];
	[r setRenderPipelineState:pso];
	[r setCullMode:MTLCullModeBack];
	[r setVertexBuffer:inst offset:0 atIndex:23];
	[r drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexType:MTLIndexTypeUInt16 indexBuffer:l->meshIdx indexBufferOffset:0 indirectBuffer:args
		indirectBufferOffset:0];
	[r setRenderPipelineState:ppso];
	[r setCullMode:MTLCullModeNone];
	[r setVertexBuffer:plantInst offset:0 atIndex:23];
	[r drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexType:MTLIndexTypeUInt16 indexBuffer:l->plantIdx indexBufferOffset:0 indirectBuffer:args
		indirectBufferOffset:5 * 4];
	if (fences) [r updateFence:l->drawDone afterStages:MTLRenderStageVertex];
	[r popDebugGroup];
	return 0;
}

static id<MTLComputePipelineState> pkPso(LodCols *l, const char *name) {
	static id<MTLComputePipelineState> cache[24];
	static const char *names[24];
	static int count;
	for (int i = 0; i < count; i++)
		if (!strcmp(names[i], name)) return cache[i];
	@autoreleasepool {
		NSError *e = nil;
		id<MTLFunction> fn = [[l->library newFunctionWithName:[NSString stringWithUTF8String:name]] autorelease];
		id<MTLComputePipelineState> p = fn ? [l->ctx->device newComputePipelineStateWithFunction:fn error:&e] : nil;
		if (!p) {
			fprintf(stderr, "mcopt-lod: %s: %s\n", name, e ? e.description.UTF8String : "missing");
			return nil;
		}
		if (count < 24) {
			cache[count] = p;
			names[count++] = name;
		}
		return p;
	}
}

id<MTLBuffer> mcl_shared_tracked_buffer(Ctx *ctx, uint64_t size) {
	return [ctx->device newBufferWithLength:size options:MTLResourceStorageModeShared];
}

int mcl_pk_cull(LodCols *l, Enc *enc, const void *frame, int frameLength, const void *comp, int compLength, const void *params, int paramsLength,
	const int64_t *bufs, int rebuild, int draw, int blocks) {
	id<MTLComputePipelineState> reset = pkPso(l, "lod_pk_reset"), hzClear = pkPso(l, "lod_pk_hz_clear"), cull = pkPso(l, "lod_pk_cull"),
		emit = pkPso(l, "lod_pk_emit"), quads = pkPso(l, "lod_pk_quads"), select = pkPso(l, "lod_pk_select"), framePass = pkPso(l, "lod_pk_frame"),
		plants = pkPso(l, "lod_pk_plants"), finish = pkPso(l, "lod_pk_finish"), live = pkPso(l, "lod_pk_live"), prefix = pkPso(l, "lod_pk_hz_prefix");
	if (!reset || !hzClear || !cull || !emit || !quads || !select || !framePass || !plants || !finish || !live || !prefix) return -1;
	int realOcc = (((const int32_t *) frame)[98] & 16) != 0;
	id<MTLComputePipelineState> pkFarMin = realOcc ? pkPso(l, "lod_pk_far_min") : nil, liveFarMin = realOcc ? pkPso(l, "lod_pk_live_far_min") : nil;
	if (realOcc && (!pkFarMin || !liveFarMin)) return -1;
#define B(i) ((id<MTLBuffer>) (void *) bufs[i])
	mc_pre_end_encoders(enc);
	@autoreleasepool {
		id<MTLComputeCommandEncoder> c = cullEncoder(l, enc);
		c.label = @"lod pk";
		[c setBytes:frame length:frameLength atIndex:0];
		[c setBytes:params length:paramsLength atIndex:14];
		if (rebuild) {
			[c setBuffer:B(0) offset:0 atIndex:1];
			[c setBuffer:B(1) offset:0 atIndex:2];
			[c setBuffer:B(2) offset:0 atIndex:3];
			[c setBuffer:B(3) offset:0 atIndex:4];
			[c setBuffer:B(4) offset:0 atIndex:5];
			[c setBuffer:B(5) offset:0 atIndex:6];
			[c setBuffer:B(6) offset:0 atIndex:7];
			[c setBuffer:B(7) offset:0 atIndex:8];
			[c setBuffer:B(8) offset:0 atIndex:9];
			[c setBuffer:B(9) offset:0 atIndex:12];
			[c setBuffer:B(10) offset:0 atIndex:13];
			[c setBuffer:B(11) offset:0 atIndex:15];
			[c setBuffer:B(12) offset:0 atIndex:16];
			[c setComputePipelineState:reset];
			[c dispatchThreads:MTLSizeMake(128, 1, 1) threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
			[c setComputePipelineState:hzClear];
			[c dispatchThreads:MTLSizeMake(4096 * 128, 1, 1) threadsPerThreadgroup:MTLSizeMake(256, 1, 1)];
			[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
			if (realOcc) {
				[c setComputePipelineState:pkFarMin];
				[c dispatchThreads:MTLSizeMake((NSUInteger) blocks, 1, 1) threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
				[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
			}
			[c setComputePipelineState:cull];
			[c dispatchThreads:MTLSizeMake((NSUInteger) blocks, 1, 1) threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
			[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
			[c setComputePipelineState:l->meshFinishList];
			[c dispatchThreads:MTLSizeMake(1, 1, 1) threadsPerThreadgroup:MTLSizeMake(1, 1, 1)];
			[c setComputePipelineState:prefix];
			[c dispatchThreads:MTLSizeMake(4096, 1, 1) threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
			[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
			[c setComputePipelineState:emit];
			[c dispatchThreadgroupsWithIndirectBuffer:B(3) indirectBufferOffset:19 * 4 threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
			[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
			[c setComputePipelineState:l->meshFinish];
			[c dispatchThreads:MTLSizeMake(1, 1, 1) threadsPerThreadgroup:MTLSizeMake(1, 1, 1)];
			[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
			[c setComputePipelineState:quads];
			[c dispatchThreadgroupsWithIndirectBuffer:B(3) indirectBufferOffset:16 * 4 threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
			[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
		}
		if (draw == 2) {
			[c setBuffer:B(0) offset:0 atIndex:1];
			[c setBuffer:B(1) offset:0 atIndex:2];
			[c setBuffer:B(2) offset:0 atIndex:3];
			[c setBuffer:B(14) offset:0 atIndex:4];
			[c setBuffer:B(4) offset:0 atIndex:5];
			[c setBuffer:B(16) offset:0 atIndex:6];
			[c setBuffer:B(15) offset:0 atIndex:7];
			[c setBuffer:B(7) offset:0 atIndex:8];
			[c setBuffer:B(8) offset:0 atIndex:9];
			[c setBuffer:B(9) offset:0 atIndex:12];
			[c setBuffer:B(10) offset:0 atIndex:13];
			[c setBytes:comp length:compLength atIndex:22];
			[c setBuffer:B(4) offset:0 atIndex:23];
			[c setBuffer:B(1) offset:0 atIndex:24];
			[c setComputePipelineState:l->meshReset];
			[c dispatchThreads:MTLSizeMake(1, 1, 1) threadsPerThreadgroup:MTLSizeMake(1, 1, 1)];
			[c setComputePipelineState:l->hzClear];
			[c dispatchThreads:MTLSizeMake(4096 * 128, 1, 1) threadsPerThreadgroup:MTLSizeMake(256, 1, 1)];
			[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
			if (realOcc) {
				[c setComputePipelineState:liveFarMin];
				[c dispatchThreads:MTLSizeMake((NSUInteger) blocks, 1, 1) threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
				[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
			}
			[c setComputePipelineState:live];
			[c dispatchThreads:MTLSizeMake((NSUInteger) blocks, 1, 1) threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
			[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
			[c setComputePipelineState:l->meshFinishList];
			[c dispatchThreads:MTLSizeMake(1, 1, 1) threadsPerThreadgroup:MTLSizeMake(1, 1, 1)];
			[c setComputePipelineState:l->hzPrefix];
			[c dispatchThreads:MTLSizeMake(4096, 1, 1) threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
			[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
			[c setComputePipelineState:l->meshEmit];
			[c dispatchThreadgroupsWithIndirectBuffer:B(14) indirectBufferOffset:19 * 4 threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
			[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
			[c setComputePipelineState:l->meshFinish];
			[c dispatchThreads:MTLSizeMake(1, 1, 1) threadsPerThreadgroup:MTLSizeMake(1, 1, 1)];
			[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
			[c setComputePipelineState:l->meshQuads];
			[c dispatchThreadgroupsWithIndirectBuffer:B(14) indirectBufferOffset:16 * 4 threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
			[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
		}
		if (draw) {
			[c setBytes:comp length:compLength atIndex:22];
			[c setBuffer:B(0) offset:0 atIndex:1];
			[c setBuffer:B(1) offset:0 atIndex:2];
			[c setBuffer:B(14) offset:0 atIndex:4];
			[c setBuffer:B(5) offset:0 atIndex:6];
			[c setBuffer:B(6) offset:0 atIndex:7];
			[c setBuffer:B(13) offset:0 atIndex:10];
			[c setBuffer:B(15) offset:0 atIndex:11];
			[c setBuffer:B(16) offset:0 atIndex:12];
			[c setBuffer:B(11) offset:0 atIndex:15];
			[c setBuffer:B(12) offset:0 atIndex:16];
			[c setComputePipelineState:select];
			[c dispatchThreadgroups:MTLSizeMake(1, 1, 1) threadsPerThreadgroup:MTLSizeMake(128, 1, 1)];
			[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
			[c setComputePipelineState:framePass];
			[c dispatchThreadgroupsWithIndirectBuffer:B(14) indirectBufferOffset:16 * 4 threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
			[c setComputePipelineState:plants];
			[c dispatchThreadgroupsWithIndirectBuffer:B(14) indirectBufferOffset:19 * 4 threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
			[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
			[c setComputePipelineState:finish];
			[c dispatchThreads:MTLSizeMake(1, 1, 1) threadsPerThreadgroup:MTLSizeMake(1, 1, 1)];
		}
		[c endEncoding];
	}
#undef B
	return 0;
}

static struct {
	id<MTLRenderPipelineState> pso;
	id<MTLDepthStencilState> depth;
	id<MTLTexture> dist, depthT;
	int w, h;
	id<MTLBuffer> surv, out, args, cnt, near, tiles;
	int stride, tilesLen;
} pkVis;

static id<MTLTexture> pkVisTexture(Ctx *ctx, MTLPixelFormat f, int w, int h, MTLTextureUsage usage, MTLStorageMode mode) {
	MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:f width:(NSUInteger) w height:(NSUInteger) h mipmapped:NO];
	d.usage = usage;
	d.storageMode = mode;
	return [ctx->device newTextureWithDescriptor:d];
}

int mcl_pk_vis(LodCols *l, Enc *enc, const void *frame, int frameLength, const void *facet, int facetLength, const void *params, int paramsLength,
	const int64_t *bufs, int az, int bandMask, float eps0, float eps1, const void *use, int fw, int fh, int stride) {
	id<MTLComputePipelineState> gather = pkPso(l, "lod_pk_facet_gather"), args = pkPso(l, "lod_pk_facet_args"), reduce = pkPso(l, "lod_pk_facet_reduce"),
		prune = pkPso(l, "lod_pk_facet_prune"), commit = pkPso(l, "lod_pk_facet_commit");
	if (!gather || !args || !reduce || !prune || !commit || fw <= 0 || fh <= 0 || stride <= 0) return -1;
	Ctx *ctx = l->ctx;
	@autoreleasepool {
		if (!pkVis.pso) {
			MTLRenderPipelineDescriptor *pd = [[MTLRenderPipelineDescriptor new] autorelease];
			pd.label = @"lod pk facet";
			pd.vertexFunction = l->meshVs;
			pd.fragmentFunction = [[l->library newFunctionWithName:@"lod_pk_facet_fs"] autorelease];
			if (!pd.fragmentFunction) return -1;
			pd.colorAttachments[0].pixelFormat = MTLPixelFormatR32Float;
			pd.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
			NSError *e = nil;
			pkVis.pso = [ctx->device newRenderPipelineStateWithDescriptor:pd error:&e];
			if (!pkVis.pso) {
				fprintf(stderr, "mcopt-lod: lod pk facet pipeline: %s\n", e ? e.description.UTF8String : "?");
				return -1;
			}
			MTLDepthStencilDescriptor *dd = [[MTLDepthStencilDescriptor new] autorelease];
			dd.depthCompareFunction = MTLCompareFunctionGreaterEqual;
			dd.depthWriteEnabled = YES;
			pkVis.depth = [ctx->device newDepthStencilStateWithDescriptor:dd];
			pkVis.args = [ctx->device newBufferWithLength:128 options:MTLResourceStorageModePrivate];
			pkVis.cnt = [ctx->device newBufferWithLength:16 options:MTLResourceStorageModePrivate];
			pkVis.near = [ctx->device newBufferWithLength:16 options:MTLResourceStorageModePrivate];
		}
		if (fw > pkVis.w || fh > pkVis.h) {
			[pkVis.dist release];
			[pkVis.depthT release];
			pkVis.w = fw > pkVis.w ? fw : pkVis.w;
			pkVis.h = fh > pkVis.h ? fh : pkVis.h;
			pkVis.dist = pkVisTexture(ctx, MTLPixelFormatR32Float, pkVis.w, pkVis.h, MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead,
				MTLStorageModePrivate);
			pkVis.depthT = pkVisTexture(ctx, MTLPixelFormatDepth32Float, pkVis.w, pkVis.h, MTLTextureUsageRenderTarget, MTLStorageModeMemoryless);
			int tl = ((pkVis.w + 7) / 8) * ((pkVis.h + 7) / 8) * 4;
			if (tl > pkVis.tilesLen) {
				[pkVis.tiles release];
				pkVis.tiles = [ctx->device newBufferWithLength:(NSUInteger) tl options:MTLResourceStorageModePrivate];
				pkVis.tilesLen = tl;
			}
		}
		if (stride > pkVis.stride) {
			[pkVis.surv release];
			[pkVis.out release];
			pkVis.surv = [ctx->device newBufferWithLength:(NSUInteger) stride * 6 * 16 options:MTLResourceStorageModePrivate];
			pkVis.out = [ctx->device newBufferWithLength:(NSUInteger) stride * 2 * 16 options:MTLResourceStorageModePrivate];
			pkVis.stride = stride;
		}
		if (!pkVis.dist || !pkVis.depthT || !pkVis.tiles || !pkVis.surv || !pkVis.out || !pkVis.args || !pkVis.cnt || !pkVis.near) return -1;
#define B(i) ((id<MTLBuffer>) (void *) bufs[i])
		mc_pre_end_encoders(enc);
		id<MTLCommandBuffer> cb = mc_pre(enc);
		id<MTLBlitCommandEncoder> b = [cb blitCommandEncoder];
		b.label = @"lod pk facet reset";
		[b fillBuffer:pkVis.args range:NSMakeRange(0, 128) value:0];
		[b fillBuffer:pkVis.cnt range:NSMakeRange(0, 16) value:0];
		[b fillBuffer:pkVis.near range:NSMakeRange(0, 4) value:0x7f];
		[b endEncoding];
		uint32_t a = (uint32_t) az, cap = (uint32_t) stride * 6, sel[2] = {(uint32_t) az, (uint32_t) bandMask}, size[2] = {(uint32_t) fw, (uint32_t) fh};
		float eps[2] = {eps0, eps1};
		id<MTLComputeCommandEncoder> c = [cb computeCommandEncoder];
		c.label = @"lod pk facet gather";
		[c setBytes:params length:paramsLength atIndex:14];
		[c setBuffer:B(11) offset:0 atIndex:15];
		[c setBuffer:B(6) offset:0 atIndex:7];
		[c setBuffer:pkVis.surv offset:0 atIndex:11];
		[c setBuffer:pkVis.args offset:0 atIndex:4];
		[c setBytes:&a length:4 atIndex:20];
		[c setBytes:&cap length:4 atIndex:21];
		[c setBytes:use length:16 atIndex:9];
		[c setComputePipelineState:gather];
		[c dispatchThreads:MTLSizeMake((NSUInteger) cap, 1, 1) threadsPerThreadgroup:MTLSizeMake(256, 1, 1)];
		[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
		[c setComputePipelineState:args];
		[c dispatchThreads:MTLSizeMake(1, 1, 1) threadsPerThreadgroup:MTLSizeMake(1, 1, 1)];
		[c endEncoding];
		MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
		rp.colorAttachments[0].texture = pkVis.dist;
		rp.colorAttachments[0].loadAction = MTLLoadActionClear;
		rp.colorAttachments[0].clearColor = MTLClearColorMake(1e30, 0, 0, 0);
		rp.colorAttachments[0].storeAction = MTLStoreActionStore;
		rp.depthAttachment.texture = pkVis.depthT;
		rp.depthAttachment.loadAction = MTLLoadActionClear;
		rp.depthAttachment.clearDepth = 0.0;
		rp.depthAttachment.storeAction = MTLStoreActionDontCare;
		id<MTLRenderCommandEncoder> r = [cb renderCommandEncoderWithDescriptor:rp];
		r.label = @"lod pk facet";
		[r setViewport:(MTLViewport) {0, 0, (double) fw, (double) fh, 0, 1}];
		[r setScissorRect:(MTLScissorRect) {0, 0, (NSUInteger) fw, (NSUInteger) fh}];
		[r setRenderPipelineState:pkVis.pso];
		[r setDepthStencilState:pkVis.depth];
		[r setCullMode:MTLCullModeNone];
		[r setVertexBytes:facet length:facetLength atIndex:22];
		[r setVertexBuffer:B(4) offset:0 atIndex:23];
		[r setVertexBuffer:B(1) offset:0 atIndex:24];
		[r setVertexBuffer:pkVis.args offset:0 atIndex:25];
		[r setVertexBuffer:pkVis.surv offset:0 atIndex:26];
		[r setVertexBuffer:B(0) offset:0 atIndex:27];
		[r setVertexBuffer:B(9) offset:0 atIndex:28];
		[r setVertexBuffer:B(10) offset:0 atIndex:29];
		[r setVertexBytes:frame length:frameLength atIndex:30];
		[r drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexType:MTLIndexTypeUInt16 indexBuffer:l->meshIdx indexBufferOffset:0 indirectBuffer:pkVis.args
			indirectBufferOffset:0];
		[r endEncoding];
		c = [cb computeCommandEncoder];
		c.label = @"lod pk facet prune";
		[c setTexture:pkVis.dist atIndex:0];
		[c setBuffer:pkVis.tiles offset:0 atIndex:0];
		[c setBuffer:pkVis.near offset:0 atIndex:1];
		[c setBytes:size length:8 atIndex:2];
		[c setComputePipelineState:reduce];
		[c dispatchThreads:MTLSizeMake((NSUInteger) (fw + 7) / 8, (NSUInteger) (fh + 7) / 8, 1) threadsPerThreadgroup:MTLSizeMake(8, 8, 1)];
		[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
		[c setBytes:facet length:facetLength atIndex:22];
		[c setBytes:frame length:frameLength atIndex:0];
		[c setBytes:params length:paramsLength atIndex:14];
		[c setBuffer:B(11) offset:0 atIndex:15];
		[c setBuffer:B(6) offset:0 atIndex:7];
		[c setBuffer:B(12) offset:0 atIndex:16];
		[c setBuffer:B(1) offset:0 atIndex:2];
		[c setBuffer:B(0) offset:0 atIndex:1];
		[c setBuffer:pkVis.tiles offset:0 atIndex:8];
		[c setBuffer:pkVis.out offset:0 atIndex:11];
		[c setBuffer:pkVis.cnt offset:0 atIndex:12];
		[c setBytes:sel length:8 atIndex:20];
		[c setBytes:eps length:8 atIndex:21];
		[c setBuffer:pkVis.near offset:0 atIndex:9];
		[c setComputePipelineState:prune];
		[c dispatchThreads:MTLSizeMake((NSUInteger) stride * 2, 1, 1) threadsPerThreadgroup:MTLSizeMake(256, 1, 1)];
		[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
		[c setComputePipelineState:commit];
		[c dispatchThreads:MTLSizeMake((NSUInteger) stride * 2, 1, 1) threadsPerThreadgroup:MTLSizeMake(256, 1, 1)];
		[c endEncoding];
#undef B
	}
	return 0;
}
