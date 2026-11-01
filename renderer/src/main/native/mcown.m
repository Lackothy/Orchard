#import "mcmetal.h"
#include <string.h>
#include <os/lock.h>

#define OWN_MAX_LAYOUTS 64

typedef struct {
	MTLPixelFormat colors[MAX_COLORS];
	MTLPixelFormat depth;
	int colorCount;
	int cutout;
	id<MTLRenderPipelineState> pso;
	int shadeFs;
} OwnLayout;

typedef struct {
	Ctx *ctx;
	id<MTLLibrary> library;
	id<MTLComputePipelineState> reset, cull, finish, clearVis, hiz0, hizn, occ, finishB;
	id<MTLComputePipelineState> qReset, qSize, qA, qB, qFinish, clearWords;
	id<MTLComputePipelineState> hizTile, hizPad;
	id<MTLComputePipelineState> soClear, soRaster, soMip0, soFill;
	id<MTLComputePipelineState> qStat;
	id<MTLComputePipelineState> qrecVerify;
	id<MTLComputePipelineState> qPre;
	id<MTLComputePipelineState> cullR;
	id<MTLComputePipelineState> qBu, qSize2, qBq;
	id<MTLComputePipelineState> qBf;
	id<MTLComputePipelineState> hizTop;
	id<MTLComputePipelineState> hizTileG;
	id<MTLBuffer> qUnits;
	id<MTLBuffer> soOcc, soHead, soFaces;
	id<MTLTexture> soTex, soViews[16], soNone;
	int soW, soH, soMips;
	uint8_t soParams[256];
	int soLength, soSpan;
	id<MTLBuffer> soCols;
	int hizTiled;
	id<MTLFunction> vsQuads;
	id<MTLTexture> hiz;
	id<MTLTexture> hizViews[16];
	int hizWidth, hizHeight, hizMips;
	id<MTLFunction> vsModes[3];
	id<MTLFunction> fsAlpha;
	id<MTLFunction> vs, vsTranslucent, fsSolid, fsCutout, fsFlat, fsTranslucent, fsSolidLevel, fsCutoutLevel;
	id<MTLDepthStencilState> depth, depthEqual;
	id<MTLBuffer> quadIdx;
	int a1Mode, a1Lists, a1ListCap, a1Set;
	uint32_t a1MaskLo, a1MaskHi;
	int a1MaskSet;
	id<MTLBuffer> a1Tab[2], a1Cnt[2], a1Draws[2];
	int a1ListsCap;
	uint32_t exactPipeFallbacks;
	uint32_t exactAllocFailed;
	OwnLayout layouts[OWN_MAX_LAYOUTS];
	int layoutCount;
	int compact, fat, cpuClip;
	NSMutableDictionary *functions;
	id<MTLDepthStencilState> depthGreater;
	id<MTLComputePipelineState> fragReset, fragCull, fragFinish;
	id<MTLComputePipelineState> fragCullR, fragSig;
	id<MTLComputePipelineState> animCopy;
	id<MTLRenderPipelineState> lastPso;
	id<MTLBuffer> fragMasks, fragStats;
	uint64_t fragStatsOffset;
	int fragStatsOn;
	uint32_t fragOcc[5];
	uint32_t uoccLastPyr[5];
	uint32_t uoccLastK;
	id<MTLComputePipelineState> hizTileGS;
	id<MTLTexture> rpHiz, rpViews[16];
	int rpW, rpH, rpMips;
	id<MTLRenderPipelineState> rpPso;
	id<MTLComputePipelineState> uoccTest, uoccFinish, uoccTestF, uoccTestFB, uoccTestFG;
	id<MTLBuffer> uoccVis, uoccT, uoccBArgs, uoccListsB;
	id<MTLBuffer> tOccHead, tOccRuns, tOccVis, tOccList, tOccOut, tOccArgs;
	uint64_t tOccHeadAt, tOccArgsAt;
	id<MTLComputePipelineState> tOccTest, tOccSum, tOccCopy;
	void *tOccCmd;
	id<MTLBuffer> tieRing;
	id<MTLBuffer> tieLists;
	id<MTLComputePipelineState> tieVerifyA, tieVerifyB;
	int tieVerify;
	id<MTLBuffer> fragClear[8];
	int fragClearN;
	id<MTLComputePipelineState> fragFace;
	id<MTLBuffer> faceArena;
	int faceOn;
	id<MTLBuffer> oracleKept, oracleVis;
	id<MTLBuffer> vcount;
	uint32_t oracleFrame;
	id<MTLBuffer> oracleTest;
	id<MTLComputePipelineState> oracleCopy;
	id<MTLComputePipelineState> a1sKernel;
	id<MTLBuffer> a1sOut;
	uint32_t a1sParams[4];
	int a1sWritten;
	struct { void *args; id<MTLBuffer> gArgs; int sh, copy, lists, classes, sizes, sizeQuads, run, flags, written; } vg[4];
	int vgReqFrame;
	int vgFrameA;
	uint64_t vgStat[6];
	id<MTLBuffer> quadIdxG;
	id<MTLComputePipelineState> fragFinishG, uoccFinishG;
	void *a1sGOut;
	id<MTLBuffer> a1sGBuf;
	uint32_t a1sGClass, a1sGSh;
	void *a1sGKey[4];
	id<MTLBuffer> a1sGBufs[4];
	int shade;
	id<MTLLibrary> shadeLib[2];
	int shadeKey, shadeHdrLib;
	double shadeBuilt[2];
} Own;

static void ownErr(NSError *e, const char *what, char *err, int cap) {
	if (err && cap > 0) snprintf(err, cap, "%s: %s", what, e ? e.description.UTF8String : "unknown error");
}

Own *mco_new(Ctx *ctx, const char *source, int compact, int fat, int cpuClip, char *err, int errCap) {
	@autoreleasepool {
		MTLCompileOptions *o = [[MTLCompileOptions new] autorelease];
		o.languageVersion = MTLLanguageVersion3_0;
		o.mathMode = MTLMathModeFast;
		NSError *e = nil;
		id<MTLLibrary> lib = [ctx->device newLibraryWithSource:[NSString stringWithUTF8String:source] options:o error:&e];
		if (!lib) {
			ownErr(e, "own terrain.metal", err, errCap);
			return NULL;
		}
		Own *w = calloc(1, sizeof(Own));
		w->ctx = ctx;
		w->library = lib;
		w->compact = compact;
		w->fat = fat;
		w->cpuClip = cpuClip;
		w->functions = [NSMutableDictionary new];
		const char *kernels[] = {"own_reset", "own_cull", "own_finish", "own_clear_vis", "own_hiz0", "own_hizn", "own_occ", "own_finish_b", "own_q_reset",
			"own_q_size", "own_q_a", "own_q_b", "own_q_finish", "own_clear_words", "frag_reset", "frag_cull", "frag_finish", "own_hiz_tile", "own_hiz_pad",
			"own_so_clear", "own_so_raster", "own_so_mip0", "own_so_fill", "own_qstat", "own_q_pre", "own_cull_r", "own_q_bu", "own_q_size2", "own_q_bq", "own_q_bf", "own_hiz_top", "own_hiz_tile_g", "own_qrec_verify"};
		id<MTLComputePipelineState> *slots[] = {&w->reset, &w->cull, &w->finish, &w->clearVis, &w->hiz0, &w->hizn, &w->occ, &w->finishB, &w->qReset,
			&w->qSize, &w->qA, &w->qB, &w->qFinish, &w->clearWords, &w->fragReset, &w->fragCull, &w->fragFinish, &w->hizTile, &w->hizPad,
			&w->soClear, &w->soRaster, &w->soMip0, &w->soFill, &w->qStat, &w->qPre, &w->cullR, &w->qBu, &w->qSize2, &w->qBq, &w->qBf, &w->hizTop, &w->hizTileG, &w->qrecVerify};
		for (int i = 0; i < (int) (sizeof kernels / sizeof kernels[0]); i++) {
			id<MTLFunction> fn = [[lib newFunctionWithName:[NSString stringWithUTF8String:kernels[i]]] autorelease];
			*slots[i] = fn ? [ctx->device newComputePipelineStateWithFunction:fn error:&e] : nil;
			if (!*slots[i]) {
				ownErr(e, kernels[i], err, errCap);
				return NULL;
			}
		}
		MTLFunctionConstantValues *vcv = [[MTLFunctionConstantValues new] autorelease];
		bool cmp = compact != 0;
		[vcv setConstantValue:&cmp type:MTLDataTypeBool atIndex:1];
		bool fatv = fat != 0;
		[vcv setConstantValue:&fatv type:MTLDataTypeBool atIndex:2];
		bool clipv = cpuClip != 0;
		[vcv setConstantValue:&clipv type:MTLDataTypeBool atIndex:3];
		bool no = false, yes = true;
		int vsMode = 0;
		[vcv setConstantValue:&vsMode type:MTLDataTypeInt atIndex:7];
		[vcv setConstantValue:&no type:MTLDataTypeBool atIndex:8];
		[vcv setConstantValue:&yes type:MTLDataTypeBool atIndex:4];
		w->vsTranslucent = [lib newFunctionWithName:@"own_vs" constantValues:vcv error:&e];
		[vcv setConstantValue:&yes type:MTLDataTypeBool atIndex:8];
		[vcv setConstantValue:&no type:MTLDataTypeBool atIndex:4];
		w->vsQuads = [lib newFunctionWithName:@"own_vs" constantValues:vcv error:&e];
		[vcv setConstantValue:&no type:MTLDataTypeBool atIndex:8];
		for (vsMode = 0; vsMode < 3; vsMode++) {
			[vcv setConstantValue:&vsMode type:MTLDataTypeInt atIndex:7];
			w->vsModes[vsMode] = [lib newFunctionWithName:@"own_vs" constantValues:vcv error:&e];
		}
		w->vs = w->vsModes[0];
		for (int c = 0; c < 5; c++) {
			MTLFunctionConstantValues *cv = [[MTLFunctionConstantValues new] autorelease];
			bool cut = c == 1 || c == 2 || c == 4;
			float limit = c == 2 ? 0.1f : 0.5f;
			bool lvl = c >= 3;
			[cv setConstantValue:&cut type:MTLDataTypeBool atIndex:0];
			[cv setConstantValue:&limit type:MTLDataTypeFloat atIndex:5];
			[cv setConstantValue:&lvl type:MTLDataTypeBool atIndex:6];
			id<MTLFunction> fs = [lib newFunctionWithName:@"own_fs" constantValues:cv error:&e];
			if (!fs) {
				ownErr(e, "own_fs", err, errCap);
				return NULL;
			}
			if (c == 4) w->fsCutoutLevel = fs;
			else if (c == 3) w->fsSolidLevel = fs;
			else if (c == 2) w->fsTranslucent = fs;
			else if (cut) w->fsCutout = fs;
			else w->fsSolid = fs;
		}
		w->fsFlat = [lib newFunctionWithName:@"own_fs_flat" constantValues:[[MTLFunctionConstantValues new] autorelease] error:&e];
		if (!w->vs || !w->vsTranslucent || !w->vsQuads) {
			snprintf(err, errCap, "own terrain.metal: no own_vs");
			return NULL;
		}
		MTLDepthStencilDescriptor *dd = [[MTLDepthStencilDescriptor new] autorelease];
		dd.depthCompareFunction = MTLCompareFunctionGreaterEqual;
		dd.depthWriteEnabled = YES;
		w->depth = [ctx->device newDepthStencilStateWithDescriptor:dd];
		dd.depthCompareFunction = MTLCompareFunctionGreater;
		w->depthGreater = [ctx->device newDepthStencilStateWithDescriptor:dd];
		dd.depthCompareFunction = MTLCompareFunctionEqual;
		dd.depthWriteEnabled = NO;
		w->depthEqual = [ctx->device newDepthStencilStateWithDescriptor:dd];
		{
			MTLFunctionConstantValues *acv = [[MTLFunctionConstantValues new] autorelease];
			float half = 0.5f;
			bool f = false;
			[acv setConstantValue:&half type:MTLDataTypeFloat atIndex:5];
			[acv setConstantValue:&f type:MTLDataTypeBool atIndex:6];
			w->fsAlpha = [lib newFunctionWithName:@"own_fs_alpha" constantValues:acv error:&e];
			if (!w->fsAlpha) {
				ownErr(e, "own_fs_alpha", err, errCap);
				return NULL;
			}
		}
		uint16_t idx[64 * 6];
		for (int q = 0; q < 64; q++) {
			uint16_t b = (uint16_t) (q * 4);
			uint16_t k[6] = {b, (uint16_t) (b + 1), (uint16_t) (b + 2), (uint16_t) (b + 2), (uint16_t) (b + 3), b};
			memcpy(idx + q * 6, k, sizeof k);
		}
		w->quadIdx = [ctx->device newBufferWithBytes:idx length:sizeof idx options:MTLResourceStorageModeShared];
		uint16_t idxG[256 * 6];
		for (int q = 0; q < 256; q++) {
			uint16_t b = (uint16_t) (q * 4);
			uint16_t k[6] = {b, (uint16_t) (b + 1), (uint16_t) (b + 2), (uint16_t) (b + 2), (uint16_t) (b + 3), b};
			memcpy(idxG + q * 6, k, sizeof k);
		}
		w->quadIdxG = [ctx->device newBufferWithBytes:idxG length:sizeof idxG options:MTLResourceStorageModeShared];
		return w;
	}
}

id<MTLBuffer> mco_buffer(Ctx *ctx, uint64_t size, int shared) {
	return [ctx->device newBufferWithLength:size
		options:shared == 2 ? MTLResourceStorageModeShared : shared ? MTLResourceStorageModeShared | MTLResourceHazardTrackingModeUntracked
		: MTLResourceStorageModePrivate];
}

void *mco_contents(id<MTLBuffer> b) {
	return b.contents;
}

void mco_shade(Own *w, int on) {
	w->shade = on;
}

id<MTLLibrary> mco_library(Own *w) {
	return w->library;
}

void mco_shade_built(Own *w, double *out) {
	out[0] = w->shadeBuilt[0];
	out[1] = w->shadeBuilt[1];
}

void mco_shade_fs(Own *w, id<MTLLibrary> opaque, id<MTLLibrary> translucent, int key, int hdr) {
	w->shadeLib[0] = opaque;
	w->shadeLib[1] = translucent;
	w->shadeKey = key;
	w->shadeHdrLib = hdr;
}

void mco_release(id obj) {
	[obj release];
}

#define OP_SLOTS 10
#define OP_RING 4
static int opOn = -1;
static id<MTLCounterSampleBuffer> opBuf[OP_RING];
static int opFrame, opUsed[OP_RING];
static double opSum[OP_SLOTS];
static int opCount[OP_SLOTS], opFrames;
static MTLTimestamp opCpu0, opGpu0;
static const char *opNames[OP_SLOTS] = {"cull", "quads A (list build)", "quads B pyramid", "prefilter", "solid occ", "quads B test",
	"uocc pyramid", "uocc test", "uocc finish", "uocc split"};

void mco_set_gputime(int on) {
	opOn = on;
}

static void opInit(Own *w) {
	if (opOn != 1 || opBuf[0]) return;
	id<MTLDevice> device = w->ctx->device;
	if (![device supportsCounterSampling:MTLCounterSamplingPointAtStageBoundary]) {
		printf("mcopt-own gputime: no stage-boundary sampling on this device\n");
		opOn = 0;
		return;
	}
	for (id<MTLCounterSet> set in device.counterSets) {
		if (![set.name isEqualToString:MTLCommonCounterSetTimestamp]) continue;
		for (int i = 0; i < OP_RING; i++) {
			MTLCounterSampleBufferDescriptor *d = [[MTLCounterSampleBufferDescriptor new] autorelease];
			d.counterSet = set;
			d.storageMode = MTLStorageModeShared;
			d.sampleCount = OP_SLOTS * 2;
			opBuf[i] = [device newCounterSampleBufferWithDescriptor:d error:nil];
		}
		[device sampleTimestamps:&opCpu0 gpuTimestamp:&opGpu0];
	}
	if (!opBuf[0]) opOn = 0;
}

static void opNewFrame(Own *w) {
	if (opOn != 1) return;
	opInit(w);
	if (!opBuf[0]) return;
	opFrame++;
	int r = opFrame % OP_RING;
	if (opUsed[r]) {
		MTLTimestamp cpu1, gpu1;
		[w->ctx->device sampleTimestamps:&cpu1 gpuTimestamp:&gpu1];
		double usPerTick = (double) (cpu1 - opCpu0) / (double) (gpu1 - opGpu0) / 1000.0;
		@autoreleasepool {
			const MTLCounterResultTimestamp *ts = [opBuf[r] resolveCounterRange:NSMakeRange(0, OP_SLOTS * 2)].bytes;
			for (int k = 0; k < OP_SLOTS; k++) {
				if (!(opUsed[r] & (1 << k))) continue;
				uint64_t a = ts[2 * k].timestamp, b = ts[2 * k + 1].timestamp;
				if (a == MTLCounterErrorValue || b == MTLCounterErrorValue || a == 0 || b < a) continue;
				opSum[k] += (double) (b - a) * usPerTick;
				opCount[k]++;
			}
		}
		if (++opFrames >= 500) {
			struct timespec now;
			clock_gettime(CLOCK_REALTIME, &now);
			printf("mcopt-own gputime at %lld (us per frame, mean of the frames it ran):", (long long) now.tv_sec * 1000 + now.tv_nsec / 1000000);
			for (int k = 0; k < OP_SLOTS; k++)
				if (opCount[k]) printf(" %s %.1f (%d)", opNames[k], opSum[k] / opCount[k], opCount[k]);
			printf("\n");
			fflush(stdout);
			for (int k = 0; k < OP_SLOTS; k++) {
				opSum[k] = 0;
				opCount[k] = 0;
			}
			opFrames = 0;
		}
	}
	opUsed[r] = 0;
}

static id<MTLComputeCommandEncoder> opCompute(id<MTLCommandBuffer> cmd, int slot, MTLDispatchType type) {
	if (opOn == 1 && opBuf[0]) {
		int r = opFrame % OP_RING;
		MTLComputePassDescriptor *d = [MTLComputePassDescriptor computePassDescriptor];
		d.dispatchType = type;
		d.sampleBufferAttachments[0].sampleBuffer = opBuf[r];
		d.sampleBufferAttachments[0].startOfEncoderSampleIndex = (NSUInteger) (2 * slot);
		d.sampleBufferAttachments[0].endOfEncoderSampleIndex = (NSUInteger) (2 * slot + 1);
		opUsed[r] |= 1 << slot;
		return [cmd computeCommandEncoderWithDescriptor:d];
	}
	return type == MTLDispatchTypeConcurrent ? [cmd computeCommandEncoderWithDispatchType:type] : [cmd computeCommandEncoder];
}

static void ownBindSolid(Own *w, id<MTLComputeCommandEncoder> c) {
	if (!w->soNone) {
		MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatR32Float width:1 height:1 mipmapped:NO];
		d.usage = MTLTextureUsageShaderRead;
		d.storageMode = MTLStorageModePrivate;
		w->soNone = [w->ctx->device newTextureWithDescriptor:d];
	}
	if (w->soLength > 0) {
		int width = w->soW, height = w->soH;
		[c setBuffer:w->soOcc offset:0 atIndex:13];
		[c setBytes:w->soParams length:(NSUInteger) w->soLength atIndex:14];
		[c setBuffer:w->soCols offset:0 atIndex:15];
		if (!w->soHead) {
			w->soHead = [w->ctx->device newBufferWithLength:16 options:MTLResourceStorageModePrivate];
			w->soFaces = [w->ctx->device newBufferWithLength:16384 * 48 options:MTLResourceStorageModePrivate];
		}
		[c setBuffer:w->soHead offset:0 atIndex:16];
		[c setBuffer:w->soFaces offset:0 atIndex:17];
		[c setComputePipelineState:w->soClear];
		[c dispatchThreads:MTLSizeMake((NSUInteger) width * height, 1, 1) threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
		[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
		[c setComputePipelineState:w->soRaster];
		[c dispatchThreads:MTLSizeMake((NSUInteger) w->soSpan, (NSUInteger) w->soSpan, 1) threadsPerThreadgroup:MTLSizeMake(8, 8, 1)];
		[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
		[c setComputePipelineState:w->soFill];
		[c dispatchThreadgroupsWithIndirectBuffer:w->soHead indirectBufferOffset:0 threadsPerThreadgroup:MTLSizeMake(32, 1, 1)];
		[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
		[c setComputePipelineState:w->soMip0];
		[c setTexture:w->soViews[0] atIndex:1];
		[c dispatchThreads:MTLSizeMake((NSUInteger) width, (NSUInteger) height, 1) threadsPerThreadgroup:MTLSizeMake(8, 8, 1)];
		[c setComputePipelineState:w->hizn];
		for (int i = 1; i < w->soMips; i++) {
			[c memoryBarrierWithScope:MTLBarrierScopeTextures];
			[c setTexture:w->soViews[i - 1] atIndex:0];
			[c setTexture:w->soViews[i] atIndex:1];
			[c dispatchThreads:MTLSizeMake((NSUInteger) MAX(1, width >> i), (NSUInteger) MAX(1, height >> i), 1) threadsPerThreadgroup:MTLSizeMake(8, 8, 1)];
		}
		[c memoryBarrierWithScope:MTLBarrierScopeTextures | MTLBarrierScopeBuffers];
		[c setBytes:w->soParams length:(NSUInteger) w->soLength atIndex:12];
		[c setTexture:w->soTex atIndex:1];
		[c setTexture:nil atIndex:0];
	} else {
		uint8_t zero[256] = {0};
		[c setBytes:zero length:256 atIndex:12];
		[c setTexture:w->soNone atIndex:1];
	}
}

void mco_solid_occ(Own *w, Enc *enc, const void *params, int length, id<MTLBuffer> cols, int width, int height, int span) {
	if (length <= 0 || length > 256) {
		w->soLength = 0;
		return;
	}
	if (!w->soTex || w->soW != width || w->soH != height) {
		for (int i = 0; i < w->soMips; i++) [w->soViews[i] release];
		[w->soTex release];
		[w->soOcc release];
		int mips = 1;
		while ((width >> mips) > 0 || (height >> mips) > 0) mips++;
		if (mips > 16) mips = 16;
		@autoreleasepool {
			MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatR32Float width:width height:height mipmapped:YES];
			d.mipmapLevelCount = mips;
			d.usage = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite | MTLTextureUsagePixelFormatView;
			d.storageMode = MTLStorageModePrivate;
			w->soTex = [w->ctx->device newTextureWithDescriptor:d];
			for (int i = 0; i < mips; i++)
				w->soViews[i] = [w->soTex newTextureViewWithPixelFormat:MTLPixelFormatR32Float textureType:MTLTextureType2D levels:NSMakeRange(i, 1) slices:NSMakeRange(0, 1)];
		}
		w->soOcc = [w->ctx->device newBufferWithLength:(NSUInteger) width * height * 4 options:MTLResourceStorageModePrivate];
		w->soW = width;
		w->soH = height;
		w->soMips = mips;
	}
	memcpy(w->soParams, params, (size_t) length);
	uint32_t sizes[4] = {(uint32_t) width, (uint32_t) height, (uint32_t) w->soMips, 1};
	memcpy(w->soParams + 112, sizes, sizeof sizes);
	w->soLength = length;
	w->soCols = cols;
	w->soSpan = span;
}

void mco_cull(Own *w, Enc *enc, const void *frame, int frameLength, id<MTLBuffer> sections, id<MTLBuffer> recs, id<MTLBuffer> args,
	id<MTLBuffer> lists, id<MTLBuffer> mask, int sectionCount, id<MTLBuffer> prevVis, id<MTLBuffer> curVis, id<MTLBuffer> tested, int visWords,
	id<MTLBuffer> fat, int recThreads) {
	opNewFrame(w);
	mc_pre_end_encoders(enc);
	@autoreleasepool {
		id<MTLComputeCommandEncoder> c = opOn == 1 ? opCompute(mc_pre(enc), 0, MTLDispatchTypeSerial)
			: [mc_profiled_compute(enc, mc_pre(enc), MTLDispatchTypeSerial) autorelease];
		c.label = @"own cull";
		[c setBytes:frame length:frameLength atIndex:0];
		[c setBuffer:sections offset:0 atIndex:1];
		[c setBuffer:recs offset:0 atIndex:2];
		[c setBuffer:args offset:0 atIndex:3];
		[c setBuffer:lists offset:0 atIndex:4];
		[c setBuffer:mask offset:0 atIndex:5];
		[c setBuffer:curVis offset:0 atIndex:6];
		[c setBytes:&visWords length:4 atIndex:7];
		[c setBuffer:prevVis offset:0 atIndex:8];
		[c setBuffer:tested offset:0 atIndex:9];
		[c setBuffer:fat offset:0 atIndex:10];
		ownBindSolid(w, c);
		[c setComputePipelineState:w->reset];
		[c dispatchThreads:MTLSizeMake(4, 1, 1) threadsPerThreadgroup:MTLSizeMake(4, 1, 1)];
		if (visWords > 0) {
			[c setComputePipelineState:w->clearVis];
			[c dispatchThreads:MTLSizeMake((NSUInteger) visWords, 1, 1) threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
		}
		[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
		if (recThreads > 0 && visWords == 0) {
			uint32_t cap = (uint32_t) recThreads;
			[c setBytes:&cap length:4 atIndex:13];
			[c setComputePipelineState:w->cullR];
			[c dispatchThreads:MTLSizeMake((NSUInteger) recThreads, 1, 1) threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
			[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
		} else if (sectionCount > 0) {
			[c setComputePipelineState:w->cull];
			[c dispatchThreads:MTLSizeMake((NSUInteger) sectionCount, 1, 1) threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
			[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
		}
		[c setComputePipelineState:w->finish];
		[c dispatchThreads:MTLSizeMake(2, 1, 1) threadsPerThreadgroup:MTLSizeMake(2, 1, 1)];
		[c endEncoding];
	}
}

static id<MTLFunction> ownStageMake(Own *w, id<MTLLibrary> lib, NSString *name, int kind, int vertex, int shadeHdr, NSError **e);

static id<MTLFunction> ownStage(Own *w, int kind, int vertex, int shadeHdr, char *err, int errCap) {
	int frag = kind & 15, vsMode = (kind >> 4) & 3, translucent = frag == 3;
	bool half = (kind >> 18) & 1, lean = (kind >> 19) & 1, nonidx = (kind >> 20) & 1, rev = (kind >> 22) & 1, quads = (kind >> 7) & 1,
		tight = (kind >> 25) & 1, posonly = (kind >> 26) & 1, occA = (kind >> 27) & 1, occB = (kind >> 28) & 1, oracle = (kind >> 30) & 1,
		fogvert = (kind >> 14) & 1, fogvertAll = (kind >> 15) & 1, fogvertT = (kind >> 13) & 1,
		qflat = (kind >> 12) & 1,
		group = vertex == 1 && ((kind >> 11) & 1);
	NSString *name = vertex == 2 ? ((kind >> 29) & 1 ? @"own_mesh_q" : (kind >> 23) & 1 ? @"own_mesh4" : @"own_mesh") : vertex ? @"own_vs" : oracle ? @"own_fs_oracle"
		: frag == 10 ? @"own_fs_nofog"
		: frag == 9 ? @"own_fs_nofade"
		: frag == 8 ? @"own_fs_tex"
		: frag == 7 ? @"own_fs_pos"
		: frag == 6 ? @"own_fs_alpha"
		: frag == 2 ? @"own_fs_flat" : @"own_fs";
	id<MTLLibrary> lib = w->library;
	if ((shadeHdr & 2) && !vertex) {
		lib = w->shadeLib[translucent];
		name = @"shade_own_fs";
	}
	NSString *key = [NSString stringWithFormat:@"%@ %d %d %d %d %d %d %d %d %d %d %d %d %d %d %d%@", name, vertex ? vsMode | translucent << 2 | quads << 3 : frag, half, lean, nonidx,
		rev, tight, posonly, occA, occB, (kind >> 29) & 1, oracle, fogvert, fogvertAll, fogvertT, qflat, group ? @" g" : @""];
	if (shadeHdr && !vertex) key = [key stringByAppendingFormat:@" hdr%d k%d", shadeHdr, (shadeHdr & 2) ? w->shadeKey : 0];
	id<MTLFunction> fn = w->functions[key];
	if (fn) return fn;
	NSError *e = nil;
	fn = ownStageMake(w, lib, name, kind, vertex, shadeHdr, &e);
	if (!fn) {
		ownErr(e, key.UTF8String, err, errCap);
		return nil;
	}
	w->functions[key] = fn;
	[fn release];
	return fn;
}

static id<MTLFunction> ownStageMake(Own *w, id<MTLLibrary> lib, NSString *name, int kind, int vertex, int shadeHdr, NSError **e) {
	int frag = kind & 15, vsMode = (kind >> 4) & 3, translucent = frag == 3;
	bool half = (kind >> 18) & 1, lean = (kind >> 19) & 1, nonidx = (kind >> 20) & 1, rev = (kind >> 22) & 1, quads = (kind >> 7) & 1,
		tight = (kind >> 25) & 1, posonly = (kind >> 26) & 1, occA = (kind >> 27) & 1, occB = (kind >> 28) & 1, oracle = (kind >> 30) & 1,
		fogvert = (kind >> 14) & 1, fogvertAll = (kind >> 15) & 1, fogvertT = (kind >> 13) & 1, qflat = (kind >> 12) & 1,
		group = vertex == 1 && ((kind >> 11) & 1);
	MTLFunctionConstantValues *cv = [[MTLFunctionConstantValues new] autorelease];
	[cv setConstantValue:&half type:MTLDataTypeBool atIndex:9];
	[cv setConstantValue:&lean type:MTLDataTypeBool atIndex:10];
	[cv setConstantValue:&nonidx type:MTLDataTypeBool atIndex:11];
	[cv setConstantValue:&rev type:MTLDataTypeBool atIndex:12];
	[cv setConstantValue:&tight type:MTLDataTypeBool atIndex:13];
	[cv setConstantValue:&posonly type:MTLDataTypeBool atIndex:14];
	[cv setConstantValue:&occA type:MTLDataTypeBool atIndex:15];
	[cv setConstantValue:&occB type:MTLDataTypeBool atIndex:16];
	[cv setConstantValue:&oracle type:MTLDataTypeBool atIndex:17];
	[cv setConstantValue:&fogvert type:MTLDataTypeBool atIndex:18];
	[cv setConstantValue:&fogvertAll type:MTLDataTypeBool atIndex:19];
	[cv setConstantValue:&qflat type:MTLDataTypeBool atIndex:21];
	[cv setConstantValue:&fogvertT type:MTLDataTypeBool atIndex:20];
	if (group) [cv setConstantValue:&group type:MTLDataTypeBool atIndex:25];
	if (vertex) {
		bool cmp = w->compact != 0, fatv = w->fat != 0, clipv = w->cpuClip != 0, tr = translucent != 0;
		[cv setConstantValue:&cmp type:MTLDataTypeBool atIndex:1];
		[cv setConstantValue:&fatv type:MTLDataTypeBool atIndex:2];
		[cv setConstantValue:&clipv type:MTLDataTypeBool atIndex:3];
		[cv setConstantValue:&tr type:MTLDataTypeBool atIndex:4];
		[cv setConstantValue:&vsMode type:MTLDataTypeInt atIndex:7];
		[cv setConstantValue:&quads type:MTLDataTypeBool atIndex:8];
	} else {
		bool cut = frag == 1 || frag == 3 || frag == 5, lvl = frag == 4 || frag == 5;
		float limit = frag == 3 ? 0.1f : 0.5f;
		[cv setConstantValue:&cut type:MTLDataTypeBool atIndex:0];
		[cv setConstantValue:&limit type:MTLDataTypeFloat atIndex:5];
		[cv setConstantValue:&lvl type:MTLDataTypeBool atIndex:6];
		if (shadeHdr & 1) {
			bool hdr = true;
			[cv setConstantValue:&hdr type:MTLDataTypeBool atIndex:40];
		}
	}
	return [lib newFunctionWithName:name constantValues:cv error:e];
}

static NSString *ownLayerName(int kind) {
	int k = kind & 15;
	return k == 3 ? @"own translucent" : k == 1 || k == 5 || k == 6 ? @"own cutout" : @"own solid";
}

static void ownAttachments(MTLRenderPipelineDescriptor *pd, int cutout, int colorCount, const MTLPixelFormat *colors, MTLPixelFormat depth) {
	for (int c = 0; c < colorCount; c++) {
		if (colors[c] == MTLPixelFormatInvalid) continue;
		pd.colorAttachments[c].pixelFormat = colors[c];
		pd.colorAttachments[c].writeMask = c == 0 && (cutout & 15) != 6 && !(cutout & (1 << 30)) ? MTLColorWriteMaskAll : MTLColorWriteMaskNone;
		if (c == 0 && (cutout & 15) == 3) {
			pd.colorAttachments[c].blendingEnabled = YES;
			pd.colorAttachments[c].sourceRGBBlendFactor = MTLBlendFactorSourceAlpha;
			pd.colorAttachments[c].destinationRGBBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
			pd.colorAttachments[c].rgbBlendOperation = MTLBlendOperationAdd;
			pd.colorAttachments[c].sourceAlphaBlendFactor = MTLBlendFactorOne;
			pd.colorAttachments[c].destinationAlphaBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
			pd.colorAttachments[c].alphaBlendOperation = MTLBlendOperationAdd;
		}
	}
	pd.depthAttachmentPixelFormat = depth;
	pd.inputPrimitiveTopology = MTLPrimitiveTopologyClassTriangle;
}

#define OWN_SEEN_MAX 64
typedef struct { int colorCount; MTLPixelFormat colors[MAX_COLORS]; MTLPixelFormat depth; int cutout; int hdr; } OwnSeen;
static OwnSeen ownSeen[OWN_SEEN_MAX];
static int ownSeenCount;
static os_unfair_lock ownSeenLock = OS_UNFAIR_LOCK_INIT;

static void ownSeenAdd(const OwnSeen *k) {
	os_unfair_lock_lock(&ownSeenLock);
	int found = 0;
	for (int i = 0; i < ownSeenCount && !found; i++) found = memcmp(&ownSeen[i], k, sizeof *k) == 0;
	if (!found && ownSeenCount < OWN_SEEN_MAX) ownSeen[ownSeenCount++] = *k;
	os_unfair_lock_unlock(&ownSeenLock);
}

static id<MTLRenderPipelineState> ownPipeline(Own *w, Enc *enc, int cutout, char *err, int errCap) {
	int hdrTarget = enc->colorCount > 0 && enc->colors[0]
		&& (enc->colors[0].pixelFormat == MTLPixelFormatRGBA16Float || enc->colors[0].pixelFormat == MTLPixelFormatRG11B10Float);
	int shadeHdr = w->shade && hdrTarget;
	int shadeFrag = cutout & 15;
	int shadeFs = w->shade && (shadeFrag == 0 || shadeFrag == 1 || shadeFrag == 3) && w->shadeLib[shadeFrag == 3] && hdrTarget == w->shadeHdrLib
		&& !(cutout & ((1 << 26) | (1 << 30))) ? w->shadeKey : -1;
	for (int i = 0; i < w->layoutCount; i++) {
		OwnLayout *k = &w->layouts[i];
		if (k->colorCount != enc->colorCount || k->cutout != cutout || k->shadeFs != shadeFs) continue;
		if ((enc->depth ? enc->depth.pixelFormat : MTLPixelFormatInvalid) != k->depth) continue;
		int same = 1;
		for (int c = 0; c < enc->colorCount; c++)
			if ((enc->colors[c] ? enc->colors[c].pixelFormat : MTLPixelFormatInvalid) != k->colors[c]) same = 0;
		if (same) return k->pso;
	}
	double shadeT0 = shadeFs >= 0 ? CFAbsoluteTimeGetCurrent() : 0;
	@autoreleasepool {
		MTLRenderPipelineDescriptor *pd = [[MTLRenderPipelineDescriptor new] autorelease];
		pd.label = ownLayerName(cutout);
		pd.vertexFunction = (cutout & 128) ? w->vsQuads : (cutout & 15) == 3 ? w->vsTranslucent : w->vsModes[(cutout >> 4) & 3];
		int frag = cutout & 15;
		pd.fragmentFunction = frag == 6 ? w->fsAlpha : frag == 5 ? w->fsCutoutLevel : frag == 4 ? w->fsSolidLevel : frag == 3 ? w->fsTranslucent : frag == 2 ? w->fsFlat
			: frag ? w->fsCutout : w->fsSolid;
		if ((cutout & ((0x1f << 18) | (0x3f << 25) | (7 << 13) | (1 << 12) | (1 << 11))) || frag >= 8 || shadeHdr || shadeFs >= 0) {
			pd.vertexFunction = ownStage(w, cutout, 1, 0, err, errCap);
			pd.fragmentFunction = ownStage(w, cutout, 0, shadeHdr | (shadeFs >= 0 ? 2 : 0), err, errCap);
			if (!pd.vertexFunction || !pd.fragmentFunction) return nil;
		}
		MTLMeshRenderPipelineDescriptor *md = nil;
		if (cutout & (1 << 21)) {
			md = [[MTLMeshRenderPipelineDescriptor new] autorelease];
			md.label = pd.label;
			md.meshFunction = ownStage(w, cutout, 2, 0, err, errCap);
			md.fragmentFunction = ownStage(w, cutout, 0, shadeHdr | (shadeFs >= 0 ? 2 : 0), err, errCap);
			if (!md.meshFunction || !md.fragmentFunction) return nil;
			md.maxTotalThreadsPerMeshThreadgroup = (cutout & (1 << 23)) ? 128 : 32;
		}
		OwnSeen seen;
		memset(&seen, 0, sizeof seen);
		seen.colorCount = enc->colorCount;
		for (int c = 0; c < enc->colorCount; c++) seen.colors[c] = enc->colors[c] ? enc->colors[c].pixelFormat : MTLPixelFormatInvalid;
		seen.depth = enc->depth ? enc->depth.pixelFormat : MTLPixelFormatInvalid;
		seen.cutout = cutout;
		seen.hdr = hdrTarget;
		ownAttachments(pd, cutout, enc->colorCount, seen.colors, seen.depth);
		NSError *e = nil;
		id<MTLRenderPipelineState> pso;
		if (md) {
			for (int c = 0; c < enc->colorCount; c++) {
				md.colorAttachments[c].pixelFormat = pd.colorAttachments[c].pixelFormat;
				md.colorAttachments[c].writeMask = pd.colorAttachments[c].writeMask;
			}
			md.depthAttachmentPixelFormat = pd.depthAttachmentPixelFormat;
			pso = [w->ctx->device newRenderPipelineStateWithMeshDescriptor:md options:MTLPipelineOptionNone reflection:nil error:&e];
		} else {
			pso = [w->ctx->device newRenderPipelineStateWithDescriptor:pd error:&e];
		}
		if (!pso) {
			ownErr(e, "own pipeline", err, errCap);
			return nil;
		}
		if (w->layoutCount == OWN_MAX_LAYOUTS) {
			[w->layouts[0].pso release];
			memmove(&w->layouts[0], &w->layouts[1], sizeof(OwnLayout) * (OWN_MAX_LAYOUTS - 1));
			w->layoutCount--;
		}
		OwnLayout *k = &w->layouts[w->layoutCount++];
		memset(k, 0, sizeof *k);
		k->colorCount = enc->colorCount;
		for (int c = 0; c < enc->colorCount; c++) k->colors[c] = enc->colors[c] ? enc->colors[c].pixelFormat : MTLPixelFormatInvalid;
		k->depth = pd.depthAttachmentPixelFormat;
		k->cutout = cutout;
		k->pso = pso;
		k->shadeFs = shadeFs;
		if (shadeFs >= 0) {
			w->shadeBuilt[0] += 1;
			w->shadeBuilt[1] += (CFAbsoluteTimeGetCurrent() - shadeT0) * 1000.0;
			if (!md) ownSeenAdd(&seen);
		}
		return pso;
	}
}

int mco_shade_prewarm(Own *w, id<MTLLibrary> lib, int translucent, int hdrLib) {
	if (!w || !lib || !w->shade) return 0;
	OwnSeen seen[OWN_SEEN_MAX];
	os_unfair_lock_lock(&ownSeenLock);
	int n = ownSeenCount;
	memcpy(seen, ownSeen, sizeof(OwnSeen) * (size_t) n);
	os_unfair_lock_unlock(&ownSeenLock);
	int built = 0;
	for (int i = 0; i < n; i++) {
		OwnSeen *k = &seen[i];
		if (((k->cutout & 15) == 3) != (translucent != 0) || k->hdr != (hdrLib != 0)) continue;
		@autoreleasepool {
			NSError *e = nil;
			id<MTLFunction> vs = ownStageMake(w, w->library, @"own_vs", k->cutout, 1, 0, &e);
			id<MTLFunction> fs = vs ? ownStageMake(w, lib, @"shade_own_fs", k->cutout, 0, (k->hdr ? 1 : 0) | 2, &e) : nil;
			if (vs && fs) {
				MTLRenderPipelineDescriptor *pd = [[MTLRenderPipelineDescriptor new] autorelease];
				pd.label = ownLayerName(k->cutout);
				pd.vertexFunction = vs;
				pd.fragmentFunction = fs;
				ownAttachments(pd, k->cutout, k->colorCount, k->colors, k->depth);
				id<MTLRenderPipelineState> pso = [w->ctx->device newRenderPipelineStateWithDescriptor:pd error:&e];
				if (pso) built++;
				[pso release];
			}
			[vs release];
			[fs release];
		}
	}
	return built;
}

static void ownBindOcc(Own *w, id<MTLRenderCommandEncoder> r, int kind) {
	int phaseB = (kind >> 28) & 1;
	uint32_t fo[8] = {w->fragOcc[0], w->fragOcc[1], w->fragOcc[2], w->fragOcc[3], w->fragOcc[4], w->fragStatsOn && w->fragStats ? 1u : 0u,
		(uint32_t) (phaseB * 2 + (kind & 1)), 0};
	if (phaseB && !w->hiz) fo[4] = 0;
	[r setMeshBytes:fo length:sizeof fo atIndex:27];
	[r setMeshBuffer:w->fragMasks offset:0 atIndex:26];
	[r setMeshBuffer:w->fragStats offset:(NSUInteger) w->fragStatsOffset atIndex:28];
	if (phaseB) [r setMeshTexture:w->hiz atIndex:18];
}

static int ownVgFind(Own *w, id<MTLBuffer> a) {
	for (int i = 0; i < 4; i++)
		if (w->vg[i].args == (__bridge void *) a) return i;
	return -1;
}

static uint32_t ownVgClass(Own *w, int v, int id) {
	int fl = w->vg[v].flags, n = w->vg[v].lists;
	int ib = (fl & 1024) ? id % (n / 2) : id;
	int cls = (ib >> 1) % w->vg[v].classes, layer = (ib >> 1) / w->vg[v].classes;
	if ((layer == 1 && (fl & 16)) || !(fl & 2)) return (uint32_t) w->vg[v].run;
	return (uint32_t) ((MIN(cls, w->vg[v].sizes - 1) + 1) * w->vg[v].sizeQuads);
}

int mco_draw(Own *w, Enc *enc, int cutout, const void *frame, int frameLength, id<MTLBuffer> arena, id<MTLBuffer> sections, id<MTLBuffer> recs,
	id<MTLBuffer> lists, id<MTLBuffer> args, int64_t argsOffset, const int64_t *u, id<MTLTexture> atlas, id<MTLSamplerState> atlasSampler, id<MTLTexture> light,
	id<MTLSamplerState> lightSampler, id<MTLBuffer> indices, char *err, int errCap) {
	id<MTLRenderCommandEncoder> r = enc->render;
	if (!r) return 0;
	int equal = (cutout & 64) != 0;
	int greater = (cutout & (1 << 16)) != 0, bias = (cutout & (1 << 17)) != 0, nonidx = (cutout & (1 << 20)) != 0;
	int bound = (cutout & (1 << 24)) != 0;
	cutout &= ~(64 | (1 << 16) | (1 << 17) | (1 << 24));
	uint32_t vgGrp[4] = {0, 0, 0, 0};
	id<MTLBuffer> vgArgs = nil;
	int vgElig = 0;
	if (!indices && !w->fat && (cutout & 15) != 3 && !(cutout & ((1 << 7) | (1 << 12) | (1 << 20) | (1 << 21) | (1 << 22) | (1 << 23) | (1 << 25) | (1 << 26)
		| (1 << 27) | (1 << 28) | (1 << 29) | (1 << 30)))) {
		vgElig = w->vgReqFrame;
		int v = ownVgFind(w, args);
		if (w->a1sGOut && (void *) args == w->a1sGOut && (argsOffset == 0 || argsOffset == 20)) {
			vgGrp[0] = w->a1sGClass;
			vgGrp[1] = ((1u << 21) + w->a1sGClass - 1) / w->a1sGClass;
			vgGrp[2] = w->a1sGSh;
			vgArgs = w->a1sGBuf;
			cutout |= 1 << 11;
			static int a1sSaid;
			if (!a1sSaid) {
				a1sSaid = 1;
				fprintf(stderr, "mcopt-own: vGroup with tl.a1Split: the split list's halves drawn grouped (instances of %u units, class %u)\n",
					1u << vgGrp[2], vgGrp[0]);
			}
		} else if (v >= 0 && w->vg[v].copy && w->vg[v].written && w->vg[v].gArgs && argsOffset >= 0 && argsOffset % 20 == 0 && argsOffset / 20 < w->vg[v].lists) {
			vgArgs = w->vg[v].gArgs;
		} else if (v >= 0 && w->vg[v].sh > 0 && w->vg[v].written && w->vg[v].gArgs && argsOffset >= 0 && argsOffset % 20 == 0 && argsOffset / 20 < w->vg[v].lists) {
			int id = (int) (argsOffset / 20);
			uint32_t c = ownVgClass(w, v, id);
			if (c > 0 && c <= 64 && (c << w->vg[v].sh) <= 256) {
				vgGrp[0] = c;
				vgGrp[1] = ((1u << 21) + c - 1) / c;
				vgGrp[2] = (uint32_t) w->vg[v].sh;
				vgArgs = w->vg[v].gArgs;
				cutout |= 1 << 11;
			}
		}
	}
	id<MTLRenderPipelineState> pso = ownPipeline(w, enc, cutout, err, errCap);
	if (!pso && vgArgs && vgGrp[0]) {
		cutout &= ~(1 << 11);
		vgArgs = nil;
		pso = ownPipeline(w, enc, cutout, err, errCap);
	}
	if (vgElig) w->vgStat[!vgArgs ? 5 : vgArgs == w->a1sGBuf && w->a1sGOut == (void *) args ? 4 : (id) args == w->uoccBArgs ? 3 : 2]++;
	uint8_t fbuf[256];
	if (!pso && (cutout & (1 << 12))) {
		cutout &= ~(1 << 12);
		pso = ownPipeline(w, enc, cutout, err, errCap);
		if (pso && frameLength <= (int) sizeof fbuf) {
			memcpy(fbuf, frame, (size_t) frameLength);
			*(uint32_t *) fbuf = (uint32_t) ((argsOffset / 20) * w->a1ListCap);
			frame = fbuf;
			w->exactPipeFallbacks++;
		}
	}
	if (!pso) return -1;
	int mesh = (cutout & (1 << 21)) != 0;
	if (bound) {
		if (pso != w->lastPso) [r setRenderPipelineState:pso];
		w->lastPso = pso;
		[r setDepthStencilState:equal ? w->depthEqual : greater ? w->depthGreater : w->depth];
		[r setDepthBias:bias ? 1.0f : 0 slopeScale:0 clamp:0];
		if (mesh) [r setMeshBytes:frame length:frameLength atIndex:20];
		else [r setVertexBytes:frame length:frameLength atIndex:20];
		if (mesh && (cutout & (3 << 27))) ownBindOcc(w, r, cutout);
		goto drawCall;
	}
	w->lastPso = pso;
	[r setRenderPipelineState:pso];
	[r setDepthStencilState:equal ? w->depthEqual : greater ? w->depthGreater : w->depth];
	[r setCullMode:MTLCullModeBack];
	[r setFrontFacingWinding:MTLWindingClockwise];
	[r setTriangleFillMode:MTLTriangleFillModeFill];
	[r setDepthBias:bias ? 1.0f : 0 slopeScale:0 clamp:0];
	if (mesh) {
		[r setMeshBytes:frame length:frameLength atIndex:20];
		[r setMeshBuffer:arena offset:0 atIndex:16];
		[r setMeshBuffer:sections offset:0 atIndex:17];
		[r setMeshBuffer:recs offset:0 atIndex:18];
		[r setMeshBuffer:lists offset:0 atIndex:19];
		[r setMeshBuffer:(id<MTLBuffer>) (void *) u[0] offset:(NSUInteger) u[1] atIndex:21];
		[r setMeshBuffer:(id<MTLBuffer>) (void *) u[2] offset:(NSUInteger) u[3] atIndex:22];
		[r setMeshBuffer:(id<MTLBuffer>) (void *) u[4] offset:(NSUInteger) u[5] atIndex:23];
		[r setMeshTexture:light atIndex:17];
		[r setMeshSamplerState:lightSampler atIndex:15];
		if (cutout & (3 << 27)) ownBindOcc(w, r, cutout);
		if (cutout & (1 << 29)) {
			[r setMeshBuffer:args offset:0 atIndex:18];
			[r setMeshBuffer:indices offset:0 atIndex:25];
		}
	}
	[r setVertexBytes:frame length:frameLength atIndex:20];
	[r setVertexBuffer:arena offset:0 atIndex:16];
	[r setVertexBuffer:sections offset:0 atIndex:17];
	[r setVertexBuffer:recs offset:0 atIndex:18];
	[r setVertexBuffer:lists offset:0 atIndex:19];
	[r setVertexBuffer:(id<MTLBuffer>) (void *) u[0] offset:(NSUInteger) u[1] atIndex:21];
	[r setVertexBuffer:(id<MTLBuffer>) (void *) u[2] offset:(NSUInteger) u[3] atIndex:22];
	[r setVertexBuffer:(id<MTLBuffer>) (void *) u[4] offset:(NSUInteger) u[5] atIndex:23];
	int fsb = w->shade ? 28 : 22;
	[r setFragmentBuffer:(id<MTLBuffer>) (void *) u[2] offset:(NSUInteger) u[3] atIndex:fsb];
	[r setFragmentBuffer:(id<MTLBuffer>) (void *) u[4] offset:(NSUInteger) u[5] atIndex:fsb + 1];
	[r setFragmentBuffer:(id<MTLBuffer>) (void *) u[6] offset:(NSUInteger) u[7] atIndex:fsb + 2];
	if ((cutout >> 13) & 3) [r setVertexBuffer:(id<MTLBuffer>) (void *) u[6] offset:(NSUInteger) u[7] atIndex:24];
	[r setVertexTexture:light atIndex:17];
	[r setVertexSamplerState:lightSampler atIndex:15];
	[r setFragmentTexture:atlas atIndex:w->shade ? 26 : 16];
	[r setFragmentSamplerState:atlasSampler atIndex:14];
	if (w->shade) {
		[r setFragmentTexture:light atIndex:27];
		[r setFragmentSamplerState:lightSampler atIndex:15];
	}
drawCall:
	if (w->vcount && !mesh) [r setVertexBuffer:w->vcount offset:0 atIndex:28];
	if (cutout & (1 << 30)) {
		[r setVertexBuffer:w->oracleKept offset:0 atIndex:26];
		[r setVertexBytes:&w->oracleFrame length:4 atIndex:27];
		[r setFragmentBuffer:w->oracleVis offset:0 atIndex:26];
		[r setFragmentBytes:&w->oracleFrame length:4 atIndex:27];
	}
	[r pushDebugGroup:ownLayerName(cutout)];
	if (vgArgs) {
		static int vgSaid;
		if (!vgSaid) {
			vgSaid = 1;
			if (vgGrp[0]) fprintf(stderr, "mcopt-own: vGroup on: phase A class draws as instances of %u units\n", 1u << vgGrp[2]);
			else fprintf(stderr, "mcopt-own: vArgsCopy on: class draws read their arguments from the finish's private copy\n");
		}
		if (vgGrp[0]) [r setVertexBytes:vgGrp length:sizeof vgGrp atIndex:29];
		[r drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexType:MTLIndexTypeUInt16 indexBuffer:vgGrp[0] ? w->quadIdxG : w->quadIdx indexBufferOffset:0
			indirectBuffer:vgArgs indirectBufferOffset:(NSUInteger) argsOffset];
	} else if (mesh) {
		[r drawMeshThreadgroupsWithIndirectBuffer:args indirectBufferOffset:(NSUInteger) argsOffset threadsPerObjectThreadgroup:MTLSizeMake(1, 1, 1)
			threadsPerMeshThreadgroup:MTLSizeMake((cutout & (1 << 23)) ? 128 : 32, 1, 1)];
	} else if (nonidx) {
		if (indices) [r setVertexBuffer:indices offset:0 atIndex:25];
		[r drawPrimitives:MTLPrimitiveTypeTriangle indirectBuffer:args indirectBufferOffset:(NSUInteger) argsOffset];
	} else if (indices) {
		[r drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexType:MTLIndexTypeUInt32 indexBuffer:indices indexBufferOffset:0 indirectBuffer:args
			indirectBufferOffset:(NSUInteger) argsOffset];
	} else if ((cutout & (1 << 12)) && w->a1Mode == 1 && w->a1Tab[w->a1Set]) {
		int lid = (int) (argsOffset / 20), set = w->a1Set;
		[r setVertexBuffer:w->a1Tab[set] offset:0 atIndex:19];
		[r drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexType:MTLIndexTypeUInt16 indexBuffer:w->quadIdx indexBufferOffset:0 indirectBuffer:w->a1Draws[set]
			indirectBufferOffset:(NSUInteger) lid * 40];
		[r setVertexBuffer:lists offset:0 atIndex:19];
		if (frameLength <= (int) sizeof fbuf) {
			memcpy(fbuf, frame, (size_t) frameLength);
			*(uint32_t *) fbuf = (uint32_t) (lid * w->a1ListCap) | 0x80000000u;
			[r setVertexBytes:fbuf length:(NSUInteger) frameLength atIndex:20];
			[r drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexType:MTLIndexTypeUInt16 indexBuffer:w->quadIdx indexBufferOffset:0 indirectBuffer:w->a1Draws[set]
				indirectBufferOffset:(NSUInteger) lid * 40 + 20];
			[r setVertexBytes:frame length:(NSUInteger) frameLength atIndex:20];
		}
	} else if ((cutout & (1 << 12)) && frameLength <= (int) sizeof fbuf) {
		memcpy(fbuf, frame, (size_t) frameLength);
		*(uint32_t *) fbuf = (uint32_t) ((argsOffset / 20) * w->a1ListCap) | 0x80000000u;
		[r setVertexBytes:fbuf length:(NSUInteger) frameLength atIndex:20];
		[r drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexType:MTLIndexTypeUInt16 indexBuffer:w->quadIdx indexBufferOffset:0 indirectBuffer:args
			indirectBufferOffset:(NSUInteger) argsOffset];
		[r setVertexBytes:frame length:(NSUInteger) frameLength atIndex:20];
		w->exactPipeFallbacks++;
	} else {
		[r drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexType:MTLIndexTypeUInt16 indexBuffer:w->quadIdx indexBufferOffset:0 indirectBuffer:args
			indirectBufferOffset:(NSUInteger) argsOffset];
	}
	[r popDebugGroup];
	return 1;
}

int mco_occ(Own *w, Enc *enc, const void *frame, int frameLength, id<MTLBuffer> sections, id<MTLBuffer> recs, id<MTLBuffer> args,
	id<MTLBuffer> lists, id<MTLBuffer> curVis, id<MTLBuffer> tested, int *hizOut, id<MTLBuffer> fat, int stage) {
	if (!enc->render || !enc->depth) return 0;
	id<MTLTexture> depth = enc->depth;
	int dw = (int) depth.width, dh = (int) depth.height;
	int hw = (dw + 1) / 2, hh = (dh + 1) / 2;
	if (!w->hiz || w->hizWidth != hw || w->hizHeight != hh) {
		for (int i = 0; i < w->hizMips; i++) [w->hizViews[i] release];
		[w->hiz release];
		int mips = 1;
		while ((hw >> mips) > 0 || (hh >> mips) > 0) mips++;
		if (mips > 16) mips = 16;
		@autoreleasepool {
			MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatR32Float width:hw height:hh mipmapped:YES];
			d.mipmapLevelCount = mips;
			d.usage = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite | MTLTextureUsagePixelFormatView;
			d.storageMode = MTLStorageModePrivate;
			w->hiz = [w->ctx->device newTextureWithDescriptor:d];
			for (int i = 0; i < mips; i++)
				w->hizViews[i] = [w->hiz newTextureViewWithPixelFormat:MTLPixelFormatR32Float textureType:MTLTextureType2D levels:NSMakeRange(i, 1) slices:NSMakeRange(0, 1)];
		}
		w->hizWidth = hw;
		w->hizHeight = hh;
		w->hizMips = mips;
	}
	uint8_t occFrame[256];
	if (frameLength > (int) sizeof occFrame) return 0;
	memcpy(occFrame, frame, (size_t) frameLength);
	uint32_t sizes[5] = {(uint32_t) dw, (uint32_t) dh, (uint32_t) hw, (uint32_t) hh, (uint32_t) w->hizMips};
	memcpy(occFrame + 96, sizes, sizeof sizes);
	hizOut[0] = dw;
	hizOut[1] = dh;
	hizOut[2] = hw;
	hizOut[3] = hh;
	hizOut[4] = w->hizMips;
	id<MTLComputeCommandEncoder> c = mc_render_suspend(enc);
	c.label = @"own occlusion";
	{
		uint32_t two = 2;
		[c setBytes:&two length:4 atIndex:2];
	}
	[c setComputePipelineState:w->hiz0];
	[c setTexture:depth atIndex:0];
	[c setTexture:w->hizViews[0] atIndex:1];
	[c dispatchThreads:MTLSizeMake((NSUInteger) hw, (NSUInteger) hh, 1) threadsPerThreadgroup:MTLSizeMake(8, 8, 1)];
	[c setComputePipelineState:w->hizn];
	for (int i = 1; i < w->hizMips; i++) {
		[c memoryBarrierWithScope:MTLBarrierScopeTextures];
		[c setTexture:w->hizViews[i - 1] atIndex:0];
		[c setTexture:w->hizViews[i] atIndex:1];
		NSUInteger mw = MAX(1, hw >> i), mh = MAX(1, hh >> i);
		[c dispatchThreads:MTLSizeMake(mw, mh, 1) threadsPerThreadgroup:MTLSizeMake(8, 8, 1)];
	}
	if (stage == 1) {
		mc_render_resume(enc, c);
		return 1;
	}
	[c memoryBarrierWithScope:MTLBarrierScopeTextures | MTLBarrierScopeBuffers];
	[c setComputePipelineState:w->occ];
	[c setBytes:occFrame length:(NSUInteger) frameLength atIndex:0];
	[c setBuffer:sections offset:0 atIndex:1];
	[c setBuffer:recs offset:0 atIndex:2];
	[c setBuffer:args offset:0 atIndex:3];
	[c setBuffer:lists offset:0 atIndex:4];
	[c setBuffer:curVis offset:0 atIndex:6];
	[c setBuffer:tested offset:0 atIndex:9];
	[c setBuffer:fat offset:0 atIndex:10];
	[c setTexture:w->hiz atIndex:0];
	[c dispatchThreadgroupsWithIndirectBuffer:args indirectBufferOffset:21 * 4 threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
	[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
	[c setComputePipelineState:w->finishB];
	[c setBytes:occFrame length:(NSUInteger) frameLength atIndex:0];
	[c dispatchThreads:MTLSizeMake(2, 1, 1) threadsPerThreadgroup:MTLSizeMake(2, 1, 1)];
	mc_render_resume(enc, c);
	return 1;
}

int mco_split(Own *w, Enc *enc) {
	if (!enc->render) return 0;
	id<MTLComputeCommandEncoder> c = mc_render_suspend(enc);
	mc_render_resume(enc, c);
	return 1;
}

static id<MTLComputePipelineState> ownLazyKernel(Own *w, id<MTLComputePipelineState> *slot, const char *name) {
	if (!*slot) {
		@autoreleasepool {
			NSError *e = nil;
			id<MTLFunction> fn = [[w->library newFunctionWithName:[NSString stringWithUTF8String:name]] autorelease];
			*slot = fn ? [w->ctx->device newComputePipelineStateWithFunction:fn error:&e] : nil;
		}
	}
	return *slot;
}

#define A1_REGION 32768u

void mco_frag_exact_mask(Own *w, int lo, int hi) {
	w->a1MaskLo = (uint32_t) lo;
	w->a1MaskHi = (uint32_t) hi;
	w->a1MaskSet = 1;
}

void mco_frag_exact_telemetry(Own *w, uint32_t *out) {
	memset(out, 0, 32 * sizeof(uint32_t));
	id<MTLBuffer> b = w->a1Draws[w->a1Set ^ 1];
	if (b && b.storageMode == MTLStorageModeShared && b.length >= (640 + 24) * 4) memcpy(out, (const uint32_t *) b.contents + 640, 24 * sizeof(uint32_t));
	out[24] = w->exactPipeFallbacks;
	out[25] = w->exactAllocFailed;
	out[26] = w->a1Tab[0] ? (uint32_t) w->a1Tab[0].length : 0;
	out[27] = A1_REGION;
}

void mco_frag_a1_exact(Own *w, int mode, int lists, int listCap, int set) {
	w->a1Mode = mode;
	w->a1Lists = lists;
	w->a1ListCap = listCap;
	w->a1Set = set & 1;
}

int mco_a1_sh = 2;
static void ownA1Bind(Own *w, id<MTLComputeCommandEncoder> c) {
	uint32_t k[4] = {0, 0, 0, A1_REGION};
	uint32_t lo = w->a1MaskSet ? w->a1MaskLo : (w->a1Lists >= 32 ? 0xFFFFFFFFu : (1u << w->a1Lists) - 1);
	uint32_t hi = w->a1MaskSet ? w->a1MaskHi : (w->a1Lists >= 64 ? 0xFFFFFFFFu : w->a1Lists > 32 ? (1u << (w->a1Lists - 32)) - 1 : 0);
	if (w->a1Mode && (lo | hi) && w->a1ListCap > 0 && !w->exactAllocFailed) {
		if (!w->a1Cnt[0]) {
			for (int i = 0; i < 2; i++) {
				w->a1Cnt[i] = [w->ctx->device newBufferWithLength:4 * 128 options:MTLResourceStorageModePrivate];
				w->a1Draws[i] = [w->ctx->device newBufferWithLength:4 * (640 + 32) options:MTLResourceStorageModeShared];
				if (w->a1Draws[i]) memset(w->a1Draws[i].contents, 0, 4 * (640 + 32));
			}
		}
		if (w->a1Mode == 1 && !w->a1Tab[0]) {
			for (int i = 0; i < 2; i++) w->a1Tab[i] = [w->ctx->device newBufferWithLength:4 * (NSUInteger) A1_REGION * 64 options:MTLResourceStorageModePrivate];
		}
		if (!w->a1Cnt[0] || !w->a1Cnt[1] || !w->a1Draws[0] || !w->a1Draws[1] || (w->a1Mode == 1 && (!w->a1Tab[0] || !w->a1Tab[1]))) {
			w->exactAllocFailed = 1;
			for (int i = 0; i < 2; i++) {
				[w->a1Tab[i] release];
				w->a1Tab[i] = nil;
			}
			w->a1Mode = 0;
		}
	}
	if (w->a1Mode && (lo | hi) && w->a1ListCap > 0 && !w->exactAllocFailed) {
		int set = w->a1Set;
		k[0] = lo;
		k[1] = hi;
		k[2] = w->a1Mode == 1 && w->a1Tab[set] ? 1 : 2;
		[c setBuffer:w->a1Cnt[set] offset:0 atIndex:22];
		[c setBuffer:w->a1Draws[set] offset:0 atIndex:24];
		if (k[2] == 1) [c setBuffer:w->a1Tab[set] offset:0 atIndex:26];
	}
	[c setBytes:k length:sizeof k atIndex:27];
}

static int ownVgPrepare(Own *w, id<MTLBuffer> a, const void **frame, int length, int listCap, int allowed, uint8_t ffb[128], int *slot) {
	*slot = -1;
	if (length < 40 || length > 128) return 0;
	uint32_t fl;
	memcpy(&fl, (const uint8_t *) *frame + 12, 4);
	int sh = (int) ((fl >> 24) & 3), cp = (int) ((fl >> 26) & 1);
	uint32_t nl;
	memcpy(&nl, (const uint8_t *) *frame + 24, 4);
	if (sh && (!allowed || listCap <= 0 || (listCap % (1 << sh)) != 0 || nl > 64)) sh = 0;
	if (cp && (!allowed || nl > 64)) cp = 0;
	int v = ownVgFind(w, a);
	if (v < 0 && (sh || cp)) {
		for (int i = 0; i < 4 && v < 0; i++)
			if (!w->vg[i].args) v = i;
		if (v < 0) {
			if (w->vg[0].args) [(id) w->vg[0].args release];
			[w->vg[0].gArgs release];
			for (int i = 1; i < 4; i++) w->vg[i - 1] = w->vg[i];
			v = 3;
			memset(&w->vg[3], 0, sizeof w->vg[3]);
		}
	}
	if (v >= 0) {
		if (w->vg[v].args != (void *) a) {
			if (w->vg[v].args) [(id) w->vg[v].args release];
			w->vg[v].args = (void *) [a retain];
		}
		if ((sh || cp) && !w->vg[v].gArgs) w->vg[v].gArgs = [w->ctx->device newBufferWithLength:64 * 20 options:MTLResourceStorageModePrivate];
		if (!w->vg[v].gArgs) sh = cp = 0;
		const uint8_t *g = (const uint8_t *) *frame;
		int32_t f[6];
		memcpy(&f[0], g + 12, 4);
		memcpy(&f[1], g + 16, 4);
		memcpy(&f[2], g + 20, 4);
		memcpy(&f[3], g + 24, 4);
		memcpy(&f[4], g + 28, 4);
		memcpy(&f[5], g + 36, 4);
		w->vg[v].sh = sh;
		w->vg[v].copy = !sh && cp;
		w->vg[v].written = 0;
		w->vg[v].flags = f[0];
		w->vg[v].sizes = MAX(1, f[1]);
		w->vg[v].sizeQuads = f[2];
		w->vg[v].lists = f[3];
		w->vg[v].classes = MAX(1, f[4]);
		w->vg[v].run = f[5];
		*slot = v;
	}
	if ((!sh && ((fl >> 24) & 3)) || (!sh && !cp && ((fl >> 26) & 1)) || (sh && ((fl >> 26) & 1))) {
		memcpy(ffb, *frame, (size_t) length);
		fl &= ~(3u << 24);
		if (!cp || sh) fl &= ~(1u << 26);
		fl |= (uint32_t) sh << 24;
		memcpy(ffb + 12, &fl, 4);
		*frame = ffb;
	}
	return sh || cp ? (sh ? sh : -1) : 0;
}

static int vgFailA, vgFailB;
void mco_vgroup_fail_test(int a, int b) {
	vgFailA = a;
	vgFailB = b;
}

static int ownVgWriters(Own *w) {
	static int failed;
	if (failed) return 0;
	int a = !vgFailA && ownLazyKernel(w, &w->fragFinishG, "frag_finish_g");
	int b = !vgFailB && ownLazyKernel(w, &w->uoccFinishG, "frag_uocc_finish_g");
	if (a && b && w->quadIdxG) return 1;
	failed = 1;
	fprintf(stderr, "mcopt-own: vGroup drawn ungrouped: frag_finish_g %s, frag_uocc_finish_g %s%s\n", a ? "made" : "unavailable",
		b ? "made" : "unavailable", vgFailA || vgFailB ? " (forced: vGroupFailA / vGroupFailB)" : "");
	return 0;
}

void mco_frag_cull(Own *w, Enc *enc, const void *frame, int frameLength, const void *fragFrame, int fragLength, id<MTLBuffer> sections,
	id<MTLBuffer> recs, id<MTLBuffer> args, id<MTLBuffer> lists, id<MTLBuffer> mask, int sectionCount, int listCount, int lanes,
	id<MTLBuffer> vArgs, id<MTLBuffer> vLists, id<MTLBuffer> sig, int listCap) {
	if (lanes != 32 && lanes != 16 && lanes != 8 && lanes != 4) lanes = 0;
	uint8_t ffb[128];
	w->vgFrameA = 0;
	int vgReq = 0;
	if (fragLength >= 40 && fragLength <= 128) {
		uint32_t fl;
		memcpy(&fl, (const uint8_t *) fragFrame + 12, 4);
		vgReq = (fl & (7u << 24)) != 0;
	}
	int vgOk = !vArgs && (!vgReq || ownVgWriters(w));
	if (vgOk && vgReq && w->uoccBArgs) {
		uint8_t fb2[128];
		const void *ff2 = fragFrame;
		int bSlot = -1;
		vgOk = ownVgPrepare(w, w->uoccBArgs, &ff2, fragLength, listCap, 1, fb2, &bSlot) != 0 && bSlot >= 0;
	}
	int vgSlot = -1, vgSh = ownVgPrepare(w, args, &fragFrame, fragLength, listCap, vgOk, ffb, &vgSlot);
	const char *cullName = lanes == 16 ? "frag_cull_s16" : lanes == 8 ? "frag_cull_s8" : lanes == 4 ? "frag_cull_s4" : "frag_cull_s";
	if (lanes > 0 && (!ownLazyKernel(w, &w->fragCullR, cullName) || (vArgs && !ownLazyKernel(w, &w->fragSig, "frag_cull_sig")))) lanes = 0;
	opNewFrame(w);
	mc_pre_end_encoders(enc);
	@autoreleasepool {
		if (w->fragClearN > 0) {
			id<MTLBlitCommandEncoder> b = [mc_pre(enc) blitCommandEncoder];
			b.label = @"own frag clear";
			for (int i = 0; i < w->fragClearN; i++) [b fillBuffer:w->fragClear[i] range:NSMakeRange(0, w->fragClear[i].length) value:0];
			[b endEncoding];
			w->fragClearN = 0;
		}
		id<MTLComputeCommandEncoder> c = [mc_profiled_compute(enc, mc_pre(enc), MTLDispatchTypeSerial) autorelease];
		c.label = @"own frag cull";
		[c setBytes:frame length:frameLength atIndex:0];
		[c setBytes:fragFrame length:fragLength atIndex:11];
		[c setBuffer:sections offset:0 atIndex:1];
		[c setBuffer:recs offset:0 atIndex:2];
		[c setBuffer:args offset:0 atIndex:3];
		[c setBuffer:lists offset:0 atIndex:4];
		[c setBuffer:mask offset:0 atIndex:5];
		if (w->uoccVis) {
			[c setBuffer:w->uoccVis offset:0 atIndex:13];
			[c setBuffer:w->uoccT offset:0 atIndex:14];
			[c setBuffer:w->uoccBArgs offset:0 atIndex:15];
		}
		ownBindSolid(w, c);
		ownA1Bind(w, c);
		[c setBuffer:w->tieRing ? w->tieRing : sections offset:0 atIndex:30];
		[c setComputePipelineState:w->fragReset];
		NSUInteger resetThreads = (NSUInteger) MAX(listCount, 32);
		[c dispatchThreads:MTLSizeMake(resetThreads, 1, 1) threadsPerThreadgroup:MTLSizeMake(resetThreads, 1, 1)];
		[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
		if (lanes > 0 && !vArgs && sectionCount > 0) {
			[c setComputePipelineState:w->fragCullR];
			[c dispatchThreads:MTLSizeMake((NSUInteger) sectionCount * (NSUInteger) lanes, 1, 1) threadsPerThreadgroup:MTLSizeMake(256, 1, 1)];
			[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
		} else if (sectionCount > 0) {
			[c setComputePipelineState:w->fragCull];
			[c dispatchThreads:MTLSizeMake((NSUInteger) sectionCount, 1, 1) threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
			[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
		}
		int vgEnc = vgSh != 0 && vgSlot >= 0 && w->fragFinishG && w->vg[vgSlot].gArgs;
		if (vgEnc) {
			[c setComputePipelineState:w->fragFinishG];
			[c setBuffer:w->vg[vgSlot].gArgs offset:0 atIndex:28];
		} else {
			[c setComputePipelineState:w->fragFinish];
		}
		[c dispatchThreads:MTLSizeMake((NSUInteger) listCount, 1, 1) threadsPerThreadgroup:MTLSizeMake((NSUInteger) listCount, 1, 1)];
		if (vgEnc) {
			w->vg[vgSlot].written = 1;
			w->vgFrameA = 1;
		}
		if (vgReq) w->vgStat[vgEnc ? 0 : 1]++;
		w->vgReqFrame = vgReq;
		w->a1sGOut = NULL;
		if (w->a1sOut && w->a1sKernel) {
			[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
			[c setComputePipelineState:w->a1sKernel];
			[c setBuffer:w->a1sOut offset:0 atIndex:29];
			[c setBytes:w->a1sParams length:16 atIndex:28];
			[c dispatchThreads:MTLSizeMake(1, 1, 1) threadsPerThreadgroup:MTLSizeMake(1, 1, 1)];
			int vgSplit = vgSh > 0 && vgSlot >= 0 && w->vg[vgSlot].written && (int) w->a1sParams[0] < w->vg[vgSlot].lists;
			uint32_t gc = vgSplit ? ownVgClass(w, vgSlot, (int) w->a1sParams[0]) : 0;
			if (vgSplit && (gc == 0 || gc > 64 || (gc << vgSh) > 256)) vgSplit = 0;
			id<MTLBuffer> gb = nil;
			if (vgSplit) {
				int k = -1;
				for (int i = 0; i < 4 && k < 0; i++)
					if (w->a1sGKey[i] == (void *) w->a1sOut) k = i;
				for (int i = 0; i < 4 && k < 0; i++)
					if (!w->a1sGKey[i]) k = i;
				if (k < 0) {
					k = 3;
					[w->a1sGBufs[3] release];
					w->a1sGBufs[3] = nil;
				}
				w->a1sGKey[k] = (void *) w->a1sOut;
				if (!w->a1sGBufs[k]) w->a1sGBufs[k] = [w->ctx->device newBufferWithLength:64 options:MTLResourceStorageModePrivate];
				gb = w->a1sGBufs[k];
			}
			if (gb) {
				[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
				[c setBuffer:w->vg[vgSlot].gArgs offset:0 atIndex:3];
				[c setBuffer:gb offset:0 atIndex:29];
				[c dispatchThreads:MTLSizeMake(1, 1, 1) threadsPerThreadgroup:MTLSizeMake(1, 1, 1)];
				[c setBuffer:args offset:0 atIndex:3];
				w->a1sGOut = (void *) w->a1sOut;
				w->a1sGBuf = gb;
				w->a1sGClass = gc;
				w->a1sGSh = (uint32_t) vgSh;
			}
			w->a1sWritten = 1;
		}
		w->a1sOut = nil;
		if (lanes > 0 && vArgs && sectionCount > 0) {
			[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
			[c setBuffer:vArgs offset:0 atIndex:3];
			[c setBuffer:vLists offset:0 atIndex:4];
			[c setComputePipelineState:w->fragReset];
			[c dispatchThreads:MTLSizeMake(resetThreads, 1, 1) threadsPerThreadgroup:MTLSizeMake(resetThreads, 1, 1)];
			[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
			[c setComputePipelineState:w->fragCullR];
			[c dispatchThreads:MTLSizeMake((NSUInteger) sectionCount * (NSUInteger) lanes, 1, 1) threadsPerThreadgroup:MTLSizeMake(256, 1, 1)];
			[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
			[c setComputePipelineState:w->fragFinish];
			[c dispatchThreads:MTLSizeMake((NSUInteger) listCount, 1, 1) threadsPerThreadgroup:MTLSizeMake((NSUInteger) listCount, 1, 1)];
			[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
			[c setComputePipelineState:w->fragSig];
			[c setBuffer:sig offset:0 atIndex:16];
			NSUInteger total = (NSUInteger) listCount * (NSUInteger) listCap;
			for (uint32_t set = 0; set < 2; set++) {
				[c setBuffer:set == 0 ? args : vArgs offset:0 atIndex:3];
				[c setBuffer:set == 0 ? lists : vLists offset:0 atIndex:4];
				[c setBytes:&set length:4 atIndex:17];
				[c dispatchThreads:MTLSizeMake(total, 1, 1) threadsPerThreadgroup:MTLSizeMake(256, 1, 1)];
			}
			[c setBuffer:args offset:0 atIndex:3];
			[c setBuffer:lists offset:0 atIndex:4];
		}
		if (w->faceOn && w->faceArena) {
			if (!w->fragFace) {
				NSError *e = nil;
				id<MTLFunction> fn = [[w->library newFunctionWithName:@"frag_face_stats"] autorelease];
				w->fragFace = fn ? [w->ctx->device newComputePipelineStateWithFunction:fn error:&e] : nil;
				if (!w->fragFace) w->faceOn = 0;
			}
			if (w->fragFace) {
				[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
				[c setComputePipelineState:w->fragFace];
				uint32_t compact = w->compact ? 1 : 0;
				[c setBytes:&compact length:4 atIndex:8];
				[c setBuffer:w->faceArena offset:0 atIndex:15];
				for (int id = 0; id < listCount; id++) {
					uint32_t lid = (uint32_t) id;
					[c setBytes:&lid length:4 atIndex:7];
					[c dispatchThreadgroupsWithIndirectBuffer:args indirectBufferOffset:(NSUInteger) (listCount * 5 + id * 3) * 4
						threadsPerThreadgroup:MTLSizeMake(32, 1, 1)];
				}
			}
		}
		[c endEncoding];
	}
}

void mco_frag_face(Own *w, id<MTLBuffer> arena, int on) {
	w->faceArena = arena;
	w->faceOn = on;
}

int mco_hiz_tiled = 0;
static int mco_hiz_top = 0;
static int mco_hiz_gather = 0;
static int mco_uocc_debug = 0;
static int mco_uocc_fast = 0;
static int mco_uocc_box = 0;
static int mco_uocc_gather = 0;
static int mco_uocc_enc = 0;
static int mco_hiz_skip = 0;

void mco_set_hiz_tiled(int on) {
	mco_hiz_tiled = on & 1;
	mco_hiz_top = (on >> 1) & 1;
	mco_hiz_gather = (on >> 2) & 1;
	mco_uocc_debug = (on >> 8) & 255;
	mco_uocc_enc = (on >> 16) & 1;
	mco_uocc_fast = (on >> 17) & 1;
	mco_uocc_box = (on >> 18) & 1;
	mco_uocc_gather = (on >> 19) & 1;
}

static id<MTLComputeCommandEncoder> ownNextCompute(Enc *enc, id<MTLComputeCommandEncoder> c, NSString *label, int slot) {
	[c endEncoding];
	[c release];
	c = opOn == 1 && opBuf[0] ? [opCompute(mc_frame_cmd(enc), slot, MTLDispatchTypeConcurrent) retain]
		: mc_profiled_compute(enc, mc_frame_cmd(enc), MTLDispatchTypeConcurrent);
	c.label = label;
	return c;
}

static void ownShadeSubRect(Own *w, Enc *enc, uint32_t *sizes) {
	if (!w->shade || !enc || enc->width <= 0 || enc->height <= 0) return;
	sizes[0] = MIN(sizes[0], (uint32_t) enc->width);
	sizes[1] = MIN(sizes[1], (uint32_t) enc->height);
}

static void ownPyramid(Own *w, Enc *enc, id<MTLComputeCommandEncoder> c, id<MTLTexture> depth, uint8_t *frame) {
	int dw = (int) depth.width, dh = (int) depth.height;
	uint32_t scale = 2;
	while (dw / (int) (scale * 2) >= 960) scale *= 2;
	if (mco_hiz_tiled) scale = 2;
	int hw = (dw + (int) scale - 1) / (int) scale, hh = (dh + (int) scale - 1) / (int) scale;
	int skip = mco_hiz_tiled && mco_hiz_skip > 0 && ownLazyKernel(w, &w->hizTileGS, "own_hiz_tile_gs") ? mco_hiz_skip : 0;
	if (mco_hiz_tiled) {
		int pad = 32 << skip;
		hw = (hw + pad - 1) / pad * pad;
		hh = (hh + pad - 1) / pad * pad;
	}
	if (!w->hiz || w->hizWidth != hw || w->hizHeight != hh) {
		for (int i = 0; i < w->hizMips; i++) [w->hizViews[i] release];
		[w->hiz release];
		int mips = 1;
		while ((hw >> mips) > 0 || (hh >> mips) > 0) mips++;
		if (mips > 16) mips = 16;
		@autoreleasepool {
			MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatR32Float width:hw height:hh mipmapped:YES];
			d.mipmapLevelCount = mips;
			d.usage = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite | MTLTextureUsagePixelFormatView;
			d.storageMode = MTLStorageModePrivate;
			w->hiz = [w->ctx->device newTextureWithDescriptor:d];
			for (int i = 0; i < mips; i++)
				w->hizViews[i] = [w->hiz newTextureViewWithPixelFormat:MTLPixelFormatR32Float textureType:MTLTextureType2D levels:NSMakeRange(i, 1) slices:NSMakeRange(0, 1)];
		}
		w->hizWidth = hw;
		w->hizHeight = hh;
		w->hizMips = mips;
	}
	uint32_t sizes[5] = {(uint32_t) dw, (uint32_t) dh, (uint32_t) hw, (uint32_t) hh, (uint32_t) w->hizMips};
	ownShadeSubRect(w, enc, sizes);
	memcpy(frame + 96, sizes, sizeof sizes);
	memcpy(frame + 136, &scale, 4);
	if (skip > 0 && w->hizMips < skip + 5) skip = 0;
	if (mco_hiz_tiled && skip > 0) {
		uint32_t k = (uint32_t) skip;
		[c setComputePipelineState:w->hizTileGS];
		[c setTexture:depth atIndex:0];
		for (int i = 0; i < 5; i++) [c setTexture:w->hizViews[skip + i] atIndex:(NSUInteger) (1 + i)];
		[c setBytes:&k length:4 atIndex:0];
		[c dispatchThreadgroups:MTLSizeMake((NSUInteger) (hw >> skip) / 16, (NSUInteger) (hh >> skip) / 16, 1) threadsPerThreadgroup:MTLSizeMake(16, 16, 1)];
		[c setComputePipelineState:w->hizPad];
		int i = skip + 5;
		for (; i < w->hizMips; i++) {
			NSUInteger mw = MAX(1, hw >> i), mh = MAX(1, hh >> i);
			if (mco_hiz_top && w->hizTop && mw <= 32 && mh <= 32) break;
			[c memoryBarrierWithScope:MTLBarrierScopeTextures];
			[c setTexture:w->hizViews[i - 1] atIndex:0];
			[c setTexture:w->hizViews[i] atIndex:1];
			[c dispatchThreads:MTLSizeMake(mw, mh, 1) threadsPerThreadgroup:MTLSizeMake(8, 8, 1)];
		}
		if (i < w->hizMips) {
			uint32_t levels = (uint32_t) MIN(11, w->hizMips - i);
			[c memoryBarrierWithScope:MTLBarrierScopeTextures];
			[c setComputePipelineState:w->hizTop];
			[c setTexture:w->hizViews[i - 1] atIndex:0];
			for (uint32_t q = 0; q < levels; q++) [c setTexture:w->hizViews[i + (int) q] atIndex:1 + q];
			[c setBytes:&levels length:4 atIndex:0];
			[c dispatchThreadgroups:MTLSizeMake(1, 1, 1) threadsPerThreadgroup:MTLSizeMake(32, 32, 1)];
		}
		mco_hiz_skip = -skip;
		return;
	}
	if (mco_hiz_tiled && w->hizMips >= 5) {
		[c setComputePipelineState:mco_hiz_gather && w->hizTileG ? w->hizTileG : w->hizTile];
		[c setTexture:depth atIndex:0];
		for (int i = 0; i < 5; i++) [c setTexture:w->hizViews[i] atIndex:(NSUInteger) (1 + i)];
		[c dispatchThreadgroups:MTLSizeMake((NSUInteger) hw / 16, (NSUInteger) hh / 16, 1) threadsPerThreadgroup:MTLSizeMake(16, 16, 1)];
		[c setComputePipelineState:w->hizPad];
		int i = 5;
		for (; i < w->hizMips; i++) {
			NSUInteger mw = MAX(1, hw >> i), mh = MAX(1, hh >> i);
			if (mco_hiz_top && w->hizTop && mw <= 32 && mh <= 32) break;
			[c memoryBarrierWithScope:MTLBarrierScopeTextures];
			[c setTexture:w->hizViews[i - 1] atIndex:0];
			[c setTexture:w->hizViews[i] atIndex:1];
			[c dispatchThreads:MTLSizeMake(mw, mh, 1) threadsPerThreadgroup:MTLSizeMake(8, 8, 1)];
		}
		if (i < w->hizMips) {
			uint32_t levels = (uint32_t) MIN(11, w->hizMips - i);
			[c memoryBarrierWithScope:MTLBarrierScopeTextures];
			[c setComputePipelineState:w->hizTop];
			[c setTexture:w->hizViews[i - 1] atIndex:0];
			for (uint32_t k = 0; k < levels; k++) [c setTexture:w->hizViews[i + (int) k] atIndex:1 + k];
			[c setBytes:&levels length:4 atIndex:0];
			[c dispatchThreadgroups:MTLSizeMake(1, 1, 1) threadsPerThreadgroup:MTLSizeMake(32, 32, 1)];
		}
		return;
	}
	[c setComputePipelineState:w->hiz0];
	[c setBytes:&scale length:4 atIndex:2];
	[c setTexture:depth atIndex:0];
	[c setTexture:w->hizViews[0] atIndex:1];
	[c dispatchThreads:MTLSizeMake((NSUInteger) hw, (NSUInteger) hh, 1) threadsPerThreadgroup:MTLSizeMake(8, 8, 1)];
	[c setComputePipelineState:w->hizn];
	for (int i = 1; i < w->hizMips; i++) {
		[c memoryBarrierWithScope:MTLBarrierScopeTextures];
		[c setTexture:w->hizViews[i - 1] atIndex:0];
		[c setTexture:w->hizViews[i] atIndex:1];
		NSUInteger mw = MAX(1, hw >> i), mh = MAX(1, hh >> i);
		[c dispatchThreads:MTLSizeMake(mw, mh, 1) threadsPerThreadgroup:MTLSizeMake(8, 8, 1)];
	}
}

int mco_frag_occ(Own *w, Enc *enc) {
	w->fragOcc[4] = 0;
	if (!enc->render || !enc->depth) return 0;
	uint8_t qf[128];
	memset(qf, 0, sizeof qf);
	id<MTLTexture> depth = enc->depth;
	id<MTLComputeCommandEncoder> c = mc_render_suspend(enc);
	c.label = @"own frag occlusion";
	ownPyramid(w, enc, c, depth, qf);
	memcpy(w->fragOcc, qf + 96, sizeof w->fragOcc);
	mc_render_resume(enc, c);
	return 1;
}

static int ownPyramidRP(Own *w, id<MTLCommandBuffer> cb, id<MTLTexture> depth, uint32_t *sizes, char *err, int errCap) {
	int dw = (int) depth.width, dh = (int) depth.height;
	int hw = ((dw + 1) / 2 + 31) / 32 * 32, hh = ((dh + 1) / 2 + 31) / 32 * 32;
	@autoreleasepool {
		if (!w->rpPso) {
			NSError *e = nil;
			MTLRenderPipelineDescriptor *pd = [[MTLRenderPipelineDescriptor new] autorelease];
			pd.label = @"own frag pyramid base";
			pd.vertexFunction = [[w->library newFunctionWithName:@"own_pyr_vs"] autorelease];
			pd.fragmentFunction = [[w->library newFunctionWithName:@"own_pyr_fs"] autorelease];
			pd.colorAttachments[0].pixelFormat = MTLPixelFormatR32Float;
			w->rpPso = pd.vertexFunction && pd.fragmentFunction ? [w->ctx->device newRenderPipelineStateWithDescriptor:pd error:&e] : nil;
			if (!w->rpPso) {
				ownErr(e, "own_pyr", err, errCap);
				return 0;
			}
		}
		if (!w->rpHiz || w->rpW != hw || w->rpH != hh) {
			for (int i = 0; i < w->rpMips; i++) [w->rpViews[i] release];
			[w->rpHiz release];
			int mips = 1;
			while ((hw >> mips) > 0 || (hh >> mips) > 0) mips++;
			if (mips > 16) mips = 16;
			MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatR32Float width:hw height:hh mipmapped:YES];
			d.mipmapLevelCount = mips;
			d.usage = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite | MTLTextureUsageRenderTarget | MTLTextureUsagePixelFormatView;
			d.storageMode = MTLStorageModePrivate;
			w->rpHiz = [w->ctx->device newTextureWithDescriptor:d];
			for (int i = 0; i < mips; i++)
				w->rpViews[i] = [w->rpHiz newTextureViewWithPixelFormat:MTLPixelFormatR32Float textureType:MTLTextureType2D levels:NSMakeRange(i, 1) slices:NSMakeRange(0, 1)];
			w->rpW = hw;
			w->rpH = hh;
			w->rpMips = mips;
		}
		MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
		rp.colorAttachments[0].texture = w->rpViews[0];
		rp.colorAttachments[0].loadAction = MTLLoadActionDontCare;
		rp.colorAttachments[0].storeAction = MTLStoreActionStore;
		id<MTLRenderCommandEncoder> r = [cb renderCommandEncoderWithDescriptor:rp];
		r.label = @"own frag pyramid base";
		[r setRenderPipelineState:w->rpPso];
		[r setFragmentTexture:depth atIndex:0];
		[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
		[r endEncoding];
	}
	sizes[0] = (uint32_t) dw;
	sizes[1] = (uint32_t) dh;
	sizes[2] = (uint32_t) hw;
	sizes[3] = (uint32_t) hh;
	sizes[4] = (uint32_t) w->rpMips;
	return 1;
}

void mco_frag_uocc_buffers(Own *w, id<MTLBuffer> occVis, id<MTLBuffer> tList, id<MTLBuffer> bArgs, id<MTLBuffer> listsB) {
	w->uoccVis = occVis;
	w->uoccT = tList;
	w->uoccBArgs = bArgs;
	w->uoccListsB = listsB;
}

void mco_frag_t_occ(Own *w, id<MTLBuffer> head, int64_t headAt, id<MTLBuffer> runs, id<MTLBuffer> vis, id<MTLBuffer> list, id<MTLBuffer> out, id<MTLBuffer> args,
	int64_t argsAt) {
	w->tOccHead = head;
	w->tOccHeadAt = (uint64_t) headAt;
	w->tOccRuns = runs;
	w->tOccVis = vis;
	w->tOccList = list;
	w->tOccOut = out;
	w->tOccArgs = args;
	w->tOccArgsAt = (uint64_t) argsAt;
	w->tOccCmd = NULL;
}

int mco_frag_t_ready(Own *w, Enc *enc) {
	int ready = w->tOccCmd && w->tOccCmd == (void *) enc->cmd;
	w->tOccCmd = NULL;
	return ready;
}

void mco_frag_tie_ring(Own *w, id<MTLBuffer> ring) {
	w->tieRing = ring;
}

void mco_frag_clear(Own *w, id<MTLBuffer> buf) {
	if (buf && w->fragClearN < 8) w->fragClear[w->fragClearN++] = buf;
}

void mco_frag_tie_verify(Own *w, id<MTLBuffer> lists, int on) {
	w->tieLists = lists;
	w->tieVerify = on;
}

int mco_frag_uocc(Own *w, Enc *enc, const void *cullFrame, int cullLength, const void *fragFrame, int fragLength, const float *clip,
	id<MTLBuffer> sections, id<MTLBuffer> recs, id<MTLBuffer> args, int lists, int fine, char *err, int errCap) {
	if (!enc->render) return 0;
	uint8_t ffb[128];
	int vgSlot = -1, vgSh = 0;
	if (w->uoccBArgs && cullLength >= 124) {
		uint32_t cap;
		memcpy(&cap, (const uint8_t *) cullFrame + 120, 4);
		vgSh = ownVgPrepare(w, w->uoccBArgs, &fragFrame, fragLength, (int) cap, w->vgFrameA && !vgFailB && w->uoccFinishG, ffb, &vgSlot);
	}
	if (!w->uoccTest) {
		@autoreleasepool {
			NSError *e = nil;
			id<MTLFunction> t = [[w->library newFunctionWithName:@"frag_uocc_test"] autorelease];
			id<MTLFunction> f = [[w->library newFunctionWithName:@"frag_uocc_finish"] autorelease];
			w->uoccTest = t ? [w->ctx->device newComputePipelineStateWithFunction:t error:&e] : nil;
			w->uoccFinish = f ? [w->ctx->device newComputePipelineStateWithFunction:f error:&e] : nil;
			if (!w->uoccTest || !w->uoccFinish) {
				ownErr(e, "frag_uocc", err, errCap);
				return -1;
			}
		}
	}
	uint8_t qf[256];
	memset(qf, 0, sizeof qf);
	uint32_t ft[28];
	memset(ft, 0, sizeof ft);
	int pyrK = (fine >> 13) & 3;
	if ((fine >> 15) & 1) {
		int target = ((fine >> 16) & 255) * 64;
		pyrK = 0;
		if (enc->depth && target > 0) while (pyrK < 2 && ((int) enc->depth.width >> (pyrK + 2)) >= target) pyrK++;
	}
	memcpy(ft, clip, 64);
	id<MTLTexture> depth = enc->depth;
	id<MTLComputeCommandEncoder> c = mc_render_suspend(enc);
	c.label = @"own frag unit occlusion";
	if (opOn == 1 && opBuf[0] && !((fine >> 12) & 1)) c = ownNextCompute(enc, c, mco_uocc_enc ? @"own frag uocc pyramid" : @"own frag unit occlusion", mco_uocc_enc ? 6 : 9);
	int noPyr = (fine >> 9) & 1;
	int rPyr = (fine >> 10) & 1;
	int pyrOnly = (fine >> 11) & 1, testOnly = (fine >> 12) & 1;
	if (pyrOnly) c.label = @"own frag pyramid";
	id<MTLTexture> hizTex = w->hiz;
	if (depth && testOnly) {
		if (!w->hiz) memset(ft + 16, 0, 20);
		else memcpy(ft + 16, w->uoccLastPyr, 20);
		ft[24] = w->hiz ? w->uoccLastK : 0;
		ft[21] = (uint32_t) MAX(0, fine & 255);
		ft[22] = (uint32_t) ((fine >> 8) & 1);
	} else if (depth && rPyr) {
		[c endEncoding];
		[c release];
		id<MTLCommandBuffer> cb = mc_frame_cmd(enc);
		uint32_t sizes[5];
		if (!ownPyramidRP(w, cb, depth, sizes, err, errCap)) {
			mc_render_resume(enc, mc_profiled_compute(enc, cb, MTLDispatchTypeConcurrent));
			return -1;
		}
		ownShadeSubRect(w, enc, sizes);
		c = mc_profiled_compute(enc, cb, MTLDispatchTypeConcurrent);
		c.label = @"own frag unit occlusion";
		[c setComputePipelineState:w->hizPad];
		for (int i = 1; i < w->rpMips; i++) {
			if (i > 1) [c memoryBarrierWithScope:MTLBarrierScopeTextures];
			[c setTexture:w->rpViews[i - 1] atIndex:0];
			[c setTexture:w->rpViews[i] atIndex:1];
			NSUInteger mw = MAX(1, w->rpW >> i), mh = MAX(1, w->rpH >> i);
			[c dispatchThreads:MTLSizeMake(mw, mh, 1) threadsPerThreadgroup:MTLSizeMake(8, 8, 1)];
		}
		memcpy(ft + 16, sizes, 20);
		ft[21] = (uint32_t) MAX(0, fine & 255);
		ft[22] = (uint32_t) ((fine >> 8) & 1);
		hizTex = w->rpHiz;
		[c memoryBarrierWithScope:MTLBarrierScopeTextures | MTLBarrierScopeBuffers];
	} else if (depth && noPyr && w->hiz) {
		memcpy(ft + 16, w->uoccLastPyr, 20);
		ft[24] = w->uoccLastK;
		ft[21] = (uint32_t) MAX(0, fine & 255);
		ft[22] = (uint32_t) ((fine >> 8) & 1);
	} else if (depth) {
		int tiled = mco_hiz_tiled;
		mco_hiz_tiled = 1;
		mco_hiz_skip = pyrK;
		ownPyramid(w, enc, c, depth, qf);
		hizTex = w->hiz;
		uint32_t k = mco_hiz_skip < 0 ? (uint32_t) -mco_hiz_skip : 0;
		mco_hiz_skip = 0;
		mco_hiz_tiled = tiled;
		memcpy(ft + 16, qf + 96, 20);
		memcpy(w->uoccLastPyr, qf + 96, 20);
		ft[24] = k;
		w->uoccLastK = k;
		ft[21] = (uint32_t) MAX(0, fine & 255);
		ft[22] = (uint32_t) ((fine >> 8) & 1);
		[c memoryBarrierWithScope:MTLBarrierScopeTextures | MTLBarrierScopeBuffers];
	}
	if (pyrOnly) {
		mc_render_resume(enc, c);
		return 1;
	}
	ft[23] = (uint32_t) mco_uocc_debug;
	if (mco_uocc_enc && !testOnly) c = ownNextCompute(enc, c, @"own frag uocc test", 7);
	id<MTLComputePipelineState> test = w->uoccTest;
	if (mco_uocc_fast && !ft[22]) {
		int gather = mco_uocc_gather && depth && hizTex == w->hiz && w->hiz && w->hizMips <= 12;
		test = gather ? ownLazyKernel(w, &w->uoccTestFG, "frag_uocc_test_fg") : mco_uocc_box ? ownLazyKernel(w, &w->uoccTestFB, "frag_uocc_test_fb")
			: ownLazyKernel(w, &w->uoccTestF, "frag_uocc_test_f");
		if (gather && test) for (int k = 0; k < w->hizMips; k++) [c setTexture:w->hizViews[k] atIndex:(NSUInteger) (1 + k)];
		if (!test) {
			snprintf(err, (size_t) errCap, "frag_uocc_test_f: no pipeline");
			mc_render_resume(enc, c);
			return -1;
		}
	}
	[c setComputePipelineState:test];
	[c setBytes:cullFrame length:(NSUInteger) cullLength atIndex:0];
	[c setBytes:fragFrame length:(NSUInteger) fragLength atIndex:11];
	[c setBytes:ft length:sizeof ft atIndex:17];
	[c setBuffer:sections offset:0 atIndex:1];
	[c setBuffer:recs offset:0 atIndex:2];
	[c setBuffer:args offset:0 atIndex:3];
	[c setBuffer:w->uoccVis offset:0 atIndex:13];
	[c setBuffer:w->uoccT offset:0 atIndex:14];
	[c setBuffer:w->uoccBArgs offset:0 atIndex:15];
	[c setBuffer:w->uoccListsB offset:0 atIndex:16];
	[c setTexture:depth ? hizTex : nil atIndex:0];
	[c dispatchThreadgroupsWithIndirectBuffer:args indirectBufferOffset:(NSUInteger) (lists * 8 + 19) * 4 threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
	if (mco_uocc_enc) c = ownNextCompute(enc, c, @"own frag uocc finish", 8);
	else [c memoryBarrierWithScope:MTLBarrierScopeBuffers];
	int vgEnc = vgSh != 0 && vgSlot >= 0 && w->uoccFinishG && w->vg[vgSlot].gArgs;
	if (vgEnc) {
		[c setComputePipelineState:w->uoccFinishG];
		if (mco_uocc_enc) {
			[c setBytes:cullFrame length:(NSUInteger) cullLength atIndex:0];
			[c setBytes:fragFrame length:(NSUInteger) fragLength atIndex:11];
			[c setBuffer:w->uoccBArgs offset:0 atIndex:15];
		}
		[c setBuffer:w->uoccListsB offset:0 atIndex:16];
		[c setBuffer:w->vg[vgSlot].gArgs offset:0 atIndex:28];
	} else {
		[c setComputePipelineState:w->uoccFinish];
	}
	[c dispatchThreads:MTLSizeMake((NSUInteger) lists, 1, 1) threadsPerThreadgroup:MTLSizeMake((NSUInteger) lists, 1, 1)];
	if (vgEnc) w->vg[vgSlot].written = 1;
	if (w->tieVerify && w->tieLists && cullLength >= 124) {
		id<MTLComputePipelineState> va = ownLazyKernel(w, &w->tieVerifyA, "frag_tie_verify_a"), vb = ownLazyKernel(w, &w->tieVerifyB, "frag_tie_verify_b");
		uint32_t listCap;
		memcpy(&listCap, (const uint8_t *) cullFrame + 120, 4);
		if (va && vb && listCap > 0) {
			NSUInteger total = (NSUInteger) lists * listCap;
			[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
			[c setBuffer:w->tieLists offset:0 atIndex:4];
			[c setComputePipelineState:va];
			[c dispatchThreads:MTLSizeMake(total, 1, 1) threadsPerThreadgroup:MTLSizeMake(256, 1, 1)];
			[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
			[c setComputePipelineState:vb];
			[c dispatchThreads:MTLSizeMake(total, 1, 1) threadsPerThreadgroup:MTLSizeMake(256, 1, 1)];
		}
	}
	if (w->oracleTest && w->uoccVis) {
		if (!w->oracleCopy) {
			id<MTLFunction> fn = [[w->library newFunctionWithName:@"own_oracle_copy"] autorelease];
			NSError *e = nil;
			w->oracleCopy = fn ? [w->ctx->device newComputePipelineStateWithFunction:fn error:&e] : nil;
		}
		if (w->oracleCopy) {
			uint32_t n = (uint32_t) (MIN(w->uoccVis.length, w->oracleTest.length) / 4);
			[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
			[c setComputePipelineState:w->oracleCopy];
			[c setBuffer:w->uoccVis offset:0 atIndex:0];
			[c setBuffer:w->oracleTest offset:0 atIndex:1];
			[c setBytes:&n length:4 atIndex:2];
			[c dispatchThreads:MTLSizeMake(n, 1, 1) threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
		}
		w->oracleTest = nil;
	}
	if (w->tOccHead) {
		id<MTLComputePipelineState> t = ownLazyKernel(w, &w->tOccTest, "frag_t_test"), s = ownLazyKernel(w, &w->tOccSum, "frag_t_sum"),
			o = ownLazyKernel(w, &w->tOccCopy, "frag_t_copy");
		if (t && s && o) {
			[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
			[c setBytes:ft length:sizeof ft atIndex:17];
			[c setBuffer:w->tOccHead offset:w->tOccHeadAt atIndex:0];
			[c setBuffer:w->tOccRuns offset:0 atIndex:1];
			[c setBuffer:w->tOccVis offset:0 atIndex:2];
			[c setBuffer:w->tOccArgs offset:w->tOccArgsAt atIndex:3];
			[c setBuffer:w->tOccList offset:0 atIndex:4];
			[c setBuffer:w->tOccOut offset:0 atIndex:5];
			[c setTexture:depth ? hizTex : nil atIndex:0];
			[c setComputePipelineState:t];
			[c dispatchThreadgroupsWithIndirectBuffer:w->tOccHead indirectBufferOffset:w->tOccHeadAt threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
			[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
			[c setComputePipelineState:s];
			[c dispatchThreads:MTLSizeMake(1, 1, 1) threadsPerThreadgroup:MTLSizeMake(1, 1, 1)];
			[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
			[c setComputePipelineState:o];
			[c dispatchThreadgroupsWithIndirectBuffer:w->tOccHead indirectBufferOffset:w->tOccHeadAt threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
			w->tOccCmd = (void *) enc->cmd;
		}
		w->tOccHead = nil;
	}
	mc_render_resume(enc, c);
	return 1;
}

void mco_frag_masks(Own *w, id<MTLBuffer> masks, id<MTLBuffer> stats, int64_t statsOffset, int on) {
	w->fragMasks = masks;
	w->fragStats = stats;
	w->fragStatsOffset = (uint64_t) statsOffset;
	w->fragStatsOn = on;
}

void mco_quads_a(Own *w, Enc *enc, const void *frame, int frameLength, id<MTLBuffer> recs, id<MTLBuffer> args, id<MTLBuffer> tested, id<MTLBuffer> prevVis,
	id<MTLBuffer> curVis, int visWords, id<MTLBuffer> idx, id<MTLBuffer> qargs, id<MTLBuffer> tfat) {
	mc_pre_end_encoders(enc);
	@autoreleasepool {
		id<MTLComputeCommandEncoder> c = opCompute(mc_pre(enc), 1, MTLDispatchTypeSerial);
		c.label = @"own quads A";
		[c setBuffer:tfat offset:0 atIndex:10];
		[c setBytes:frame length:frameLength atIndex:0];
		[c setBuffer:recs offset:0 atIndex:2];
		[c setBuffer:args offset:0 atIndex:3];
		[c setBuffer:tested offset:0 atIndex:9];
		[c setBuffer:qargs offset:0 atIndex:11];
		[c setBuffer:prevVis offset:0 atIndex:12];
		[c setBuffer:idx offset:0 atIndex:13];
		[c setBuffer:curVis offset:0 atIndex:14];
		[c setBytes:&visWords length:4 atIndex:7];
		[c setComputePipelineState:w->clearWords];
		[c dispatchThreads:MTLSizeMake((NSUInteger) MAX(1, visWords), 1, 1) threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
		[c setComputePipelineState:w->qReset];
		[c dispatchThreads:MTLSizeMake(4, 1, 1) threadsPerThreadgroup:MTLSizeMake(4, 1, 1)];
		[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
		[c setComputePipelineState:w->qSize];
		[c dispatchThreads:MTLSizeMake(1, 1, 1) threadsPerThreadgroup:MTLSizeMake(1, 1, 1)];
		[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
		[c setComputePipelineState:w->qA];
		[c dispatchThreadgroupsWithIndirectBuffer:qargs indirectBufferOffset:24 * 4 threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
		[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
		[c setComputePipelineState:w->qFinish];
		[c dispatchThreads:MTLSizeMake(4, 1, 1) threadsPerThreadgroup:MTLSizeMake(4, 1, 1)];
		[c endEncoding];
	}
}

int mco_quads_b(Own *w, Enc *enc, const void *frame, int frameLength, id<MTLBuffer> sections, id<MTLBuffer> recs, id<MTLBuffer> args, id<MTLBuffer> tested,
	id<MTLBuffer> prevVis, id<MTLBuffer> curVis, id<MTLBuffer> idx, id<MTLBuffer> qargs, id<MTLBuffer> arena, id<MTLBuffer> boxes, id<MTLBuffer> tfat) {
	if (!enc->render || !enc->depth) return 0;
	uint8_t qf[256];
	if (frameLength > (int) sizeof qf) return 0;
	memcpy(qf, frame, (size_t) frameLength);
	id<MTLTexture> depth = enc->depth;
	id<MTLComputeCommandEncoder> c = mc_render_suspend(enc);
	if (opOn == 1) {
		[c endEncoding];
		[c release];
		c = [opCompute(mc_frame_cmd(enc), 2, MTLDispatchTypeConcurrent) retain];
	}
	c.label = @"own quads B";
	ownPyramid(w, enc, c, depth, qf);
	if (opOn == 1) {
		[c endEncoding];
		[c release];
		c = [opCompute(mc_frame_cmd(enc), 5, MTLDispatchTypeConcurrent) retain];
		c.label = @"own quads B test";
	}
	[c memoryBarrierWithScope:MTLBarrierScopeTextures | MTLBarrierScopeBuffers];
	uint32_t units = 0, listCap = 0;
	if (frameLength >= 152) memcpy(&units, qf + 148, 4);
	memcpy(&listCap, qf + 128, 4);
	if (units && (!w->qUnits || w->qUnits.length < (NSUInteger) listCap * 4)) {
		w->qUnits = [w->ctx->device newBufferWithLength:MAX((NSUInteger) listCap * 8, 4) options:MTLResourceStorageModePrivate];
	}
	uint32_t flat = 0;
	if (frameLength >= 168) memcpy(&flat, qf + 164, 4);
	[c setComputePipelineState:units ? w->qBu : flat && boxes ? w->qBf : w->qB];
	[c setBytes:qf length:(NSUInteger) frameLength atIndex:0];
	[c setBuffer:sections offset:0 atIndex:1];
	[c setBuffer:recs offset:0 atIndex:2];
	[c setBuffer:args offset:0 atIndex:3];
	[c setBuffer:tested offset:0 atIndex:9];
	[c setBuffer:qargs offset:0 atIndex:11];
	[c setBuffer:prevVis offset:0 atIndex:12];
	[c setBuffer:idx offset:0 atIndex:13];
	[c setBuffer:curVis offset:0 atIndex:14];
	[c setBuffer:arena offset:0 atIndex:15];
	[c setBuffer:boxes ? boxes : arena offset:0 atIndex:17];
	[c setBuffer:tfat offset:0 atIndex:10];
	[c setTexture:w->hiz atIndex:0];
	if (units) {
		[c setBuffer:w->qUnits offset:0 atIndex:16];
		[c dispatchThreadgroupsWithIndirectBuffer:qargs indirectBufferOffset:24 * 4 threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
		[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
		[c setComputePipelineState:w->qSize2];
		[c dispatchThreads:MTLSizeMake(1, 1, 1) threadsPerThreadgroup:MTLSizeMake(1, 1, 1)];
		[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
		[c setComputePipelineState:w->qBq];
		[c dispatchThreadgroupsWithIndirectBuffer:qargs indirectBufferOffset:29 * 4 threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
	} else {
		[c dispatchThreadgroupsWithIndirectBuffer:qargs indirectBufferOffset:20 * 4 threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
	}
	[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
	[c setComputePipelineState:w->qFinish];
	[c dispatchThreads:MTLSizeMake(4, 1, 1) threadsPerThreadgroup:MTLSizeMake(4, 1, 1)];
	mc_render_resume(enc, c);
	return 1;
}

int mco_qstats(Own *w, Enc *enc, const void *frame, int frameLength, id<MTLBuffer> sections, id<MTLBuffer> recs, id<MTLBuffer> args, id<MTLBuffer> lists,
	id<MTLBuffer> arena, id<MTLBuffer> stats, int threads) {
	if (!enc->render || !enc->depth) return 0;
	uint8_t qf[256];
	if (frameLength > (int) sizeof qf) return 0;
	memcpy(qf, frame, (size_t) frameLength);
	id<MTLTexture> depth = enc->depth;
	id<MTLComputeCommandEncoder> c = mc_render_suspend(enc);
	c.label = @"own quad tally";
	ownPyramid(w, enc, c, depth, qf);
	[c memoryBarrierWithScope:MTLBarrierScopeTextures | MTLBarrierScopeBuffers];
	[c setComputePipelineState:w->qStat];
	[c setBytes:qf length:(NSUInteger) frameLength atIndex:0];
	[c setBuffer:sections offset:0 atIndex:1];
	[c setBuffer:recs offset:0 atIndex:2];
	[c setBuffer:args offset:0 atIndex:3];
	[c setBuffer:lists offset:0 atIndex:4];
	[c setBuffer:arena offset:0 atIndex:15];
	[c setBuffer:stats offset:0 atIndex:18];
	[c setTexture:w->hiz atIndex:0];
	[c dispatchThreads:MTLSizeMake((NSUInteger) threads, 1, 1) threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
	mc_render_resume(enc, c);
	return 1;
}

void mco_quads_pre(Own *w, Enc *enc, const void *frame, int frameLength, id<MTLBuffer> sections, id<MTLBuffer> recs, id<MTLBuffer> args,
	id<MTLBuffer> tested, id<MTLBuffer> idx, id<MTLBuffer> qargs, id<MTLBuffer> arena) {
	mc_pre_end_encoders(enc);
	@autoreleasepool {
		id<MTLComputeCommandEncoder> c = opCompute(mc_pre(enc), 3, MTLDispatchTypeSerial);
		c.label = @"own quad prefilter";
		[c setBytes:frame length:frameLength atIndex:0];
		[c setBuffer:sections offset:0 atIndex:1];
		[c setBuffer:recs offset:0 atIndex:2];
		[c setBuffer:args offset:0 atIndex:3];
		[c setBuffer:tested offset:0 atIndex:9];
		[c setBuffer:qargs offset:0 atIndex:11];
		[c setBuffer:idx offset:0 atIndex:13];
		[c setBuffer:arena offset:0 atIndex:15];
		[c setComputePipelineState:w->qReset];
		[c dispatchThreads:MTLSizeMake(4, 1, 1) threadsPerThreadgroup:MTLSizeMake(4, 1, 1)];
		[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
		[c setComputePipelineState:w->qSize];
		[c dispatchThreads:MTLSizeMake(1, 1, 1) threadsPerThreadgroup:MTLSizeMake(1, 1, 1)];
		[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
		[c setComputePipelineState:w->qPre];
		[c dispatchThreadgroupsWithIndirectBuffer:qargs indirectBufferOffset:20 * 4 threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
		[c memoryBarrierWithScope:MTLBarrierScopeBuffers];
		[c setComputePipelineState:w->qFinish];
		[c dispatchThreads:MTLSizeMake(4, 1, 1) threadsPerThreadgroup:MTLSizeMake(4, 1, 1)];
		[c endEncoding];
	}
}

void mco_qrec_verify(Own *w, Enc *enc, id<MTLBuffer> sections, id<MTLBuffer> recs, id<MTLBuffer> arena, id<MTLBuffer> stats, int slots) {
	if (slots <= 0) return;
	mc_pre_end_encoders(enc);
	@autoreleasepool {
		id<MTLComputeCommandEncoder> c = [mc_pre(enc) computeCommandEncoder];
		c.label = @"own qrec verify";
		uint32_t n = (uint32_t) slots;
		[c setComputePipelineState:w->qrecVerify];
		[c setBuffer:sections offset:0 atIndex:1];
		[c setBuffer:recs offset:0 atIndex:2];
		[c setBuffer:arena offset:0 atIndex:15];
		[c setBuffer:stats offset:0 atIndex:18];
		[c setBytes:&n length:4 atIndex:7];
		[c dispatchThreads:MTLSizeMake(n, 1, 1) threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
		[c endEncoding];
	}
}

void mco_set_vcount(Own *w, id<MTLBuffer> counters) {
	w->vcount = counters;
}

void mco_oracle(Own *w, id<MTLBuffer> kept, id<MTLBuffer> vis, uint32_t frame) {
	w->oracleKept = kept;
	w->oracleVis = vis;
	w->oracleFrame = frame;
}

void mco_oracle_test(Own *w, id<MTLBuffer> test) {
	w->oracleTest = test;
}

void mco_pre_commit(Enc *enc) {
	if (!enc->pre) return;
	mc_pre_end_encoders(enc);
	[enc->pre commit];
	[enc->pre release];
	enc->pre = nil;
}

int mco_anim_copy(Own *w, Enc *enc, id<MTLTexture> src, id<MTLTexture> dst, int mip, const uint32_t *rects, int count, int maxW, int maxH) {
	if (count <= 0) return 1;
	if (!ownLazyKernel(w, &w->animCopy, "anim_copy")) return 0;
	@autoreleasepool {
		id<MTLTexture> s = [[src newTextureViewWithPixelFormat:src.pixelFormat textureType:MTLTextureType2D levels:NSMakeRange(mip, 1) slices:NSMakeRange(0, 1)] autorelease];
		id<MTLTexture> d = [[dst newTextureViewWithPixelFormat:dst.pixelFormat textureType:MTLTextureType2D levels:NSMakeRange(mip, 1) slices:NSMakeRange(0, 1)] autorelease];
		id<MTLComputeCommandEncoder> c = [mc_profiled_compute(enc, mc_frame_cmd(enc), MTLDispatchTypeSerial) autorelease];
		c.label = @"mcopt animation copy";
		[c setComputePipelineState:w->animCopy];
		[c setTexture:s atIndex:0];
		[c setTexture:d atIndex:1];
		[c setBytes:rects length:(NSUInteger) count * 24 atIndex:0];
		[c dispatchThreads:MTLSizeMake((NSUInteger) maxW, (NSUInteger) maxH, (NSUInteger) count) threadsPerThreadgroup:MTLSizeMake(8, 8, 1)];
		[c endEncoding];
	}
	return 1;
}

int mco_anim_blit_mips(Own *w, Enc *enc, id<MTLTexture> src, id<MTLTexture> dst, const uint32_t *rects, int count) {
	(void) w;
	if (count <= 0) return 1;
	@autoreleasepool {
		id<MTLBlitCommandEncoder> b = [mc_profiled_blit(enc, mc_frame_cmd(enc)) autorelease];
		b.label = @"mcopt animation copy (one blit encoder)";
		for (int i = 0; i < count; i++) {
			const uint32_t *r = rects + i * 7;
			[b copyFromTexture:src sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(r[0], r[1], 0) sourceSize:MTLSizeMake(r[4], r[5], 1)
				toTexture:dst destinationSlice:0 destinationLevel:r[6] destinationOrigin:MTLOriginMake(r[2], r[3], 0)];
		}
		[b endEncoding];
	}
	return 1;
}

int mco_anim_kernel_ready(Own *w) {
	return ownLazyKernel(w, &w->animCopy, "anim_copy") ? 1 : 0;
}

int mco_anim_copy_mips(Own *w, Enc *enc, id<MTLTexture> src, id<MTLTexture> dst, const uint32_t *rects, int count) {
	if (count <= 0) return 1;
	if (!ownLazyKernel(w, &w->animCopy, "anim_copy")) return 0;
	@autoreleasepool {
		id<MTLComputeCommandEncoder> c = [mc_profiled_compute(enc, mc_frame_cmd(enc), MTLDispatchTypeSerial) autorelease];
		c.label = @"mcopt animation copy (one encoder)";
		[c setComputePipelineState:w->animCopy];
		[c setTexture:src atIndex:0];
		uint32_t group[6 * 512];
		int i = 0;
		while (i < count) {
			int mip = (int) rects[i * 7 + 6], n = 0, maxW = 0, maxH = 0;
			while (i < count && (int) rects[i * 7 + 6] == mip && n < 512) {
				for (int k = 0; k < 6; k++) group[n * 6 + k] = rects[i * 7 + k];
				if ((int) rects[i * 7 + 4] > maxW) maxW = (int) rects[i * 7 + 4];
				if ((int) rects[i * 7 + 5] > maxH) maxH = (int) rects[i * 7 + 5];
				n++;
				i++;
			}
			id<MTLTexture> d = [[dst newTextureViewWithPixelFormat:dst.pixelFormat textureType:MTLTextureType2D levels:NSMakeRange(mip, 1) slices:NSMakeRange(0, 1)] autorelease];
			[c setTexture:d atIndex:1];
			[c setBytes:group length:(NSUInteger) n * 24 atIndex:0];
			[c dispatchThreads:MTLSizeMake((NSUInteger) maxW, (NSUInteger) maxH, (NSUInteger) n) threadsPerThreadgroup:MTLSizeMake(8, 8, 1)];
		}
		[c endEncoding];
	}
	return 1;
}

static int a1sFailTest;
void mco_tl_a1split_test(int fail) { a1sFailTest = fail; }
int mco_tl_a1split_written(Own *w) { return w->a1sWritten; }
int mco_tl_a1split_req(Own *w, id<MTLBuffer> out, int list, int num, int den) {
	w->a1sWritten = 0;
	w->a1sOut = nil;
	if (!out || num <= 0 || num >= den || a1sFailTest || !ownLazyKernel(w, &w->a1sKernel, "own_tl_a1split")) return 0;
	w->a1sOut = out;
	w->a1sParams[0] = (uint32_t) list;
	w->a1sParams[1] = (uint32_t) num;
	w->a1sParams[2] = (uint32_t) den;
	w->a1sParams[3] = 0;
	return 1;
}

void mco_vgroup_stats(Own *w, uint64_t *out) {
	for (int i = 0; i < 6; i++) out[i] = w->vgStat[i];
}
