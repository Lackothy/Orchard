#import <Metal/Metal.h>

typedef struct {
	id<MTLDevice> device;
	id<MTLCommandQueue> queue;
	id<MTLLibrary> builtins;
	id<MTLRenderPipelineState> present;
	id<MTLSamplerState> presentSampler;
	NSMutableDictionary<NSNumber *, id<MTLRenderPipelineState>> *clearPipelines;
	id<MTLDepthStencilState> depthWrite, depthKeep;
	id<MTLBuffer> fanIndices;
} Ctx;

#define MAX_COLORS 8

typedef struct {
	Ctx *ctx;
	id<MTLCommandBuffer> cmd;
	id<MTLBlitCommandEncoder> blit;
	id<MTLRenderCommandEncoder> render;
	id<MTLTexture> colors[MAX_COLORS];
	id<MTLTexture> depth;
	int colorCount;
	int width, height;
	unsigned dead;
	id<MTLBuffer> indexBuffer;
	MTLIndexType indexType;
	int indexSize;
	MTLPrimitiveType primitive;
	id<MTLCounterSampleBuffer> samples;
	int sampleCount;
	id sampleEnc[512];
	char sampleKind[512];
	char sampleInfo[512][48];
	int renderGroup;
	id<MTLCommandBuffer> pre;
	id<MTLBlitCommandEncoder> preBlit;
	id<MTLComputeCommandEncoder> preCompute;
	unsigned renderSerial;
	MTLRenderPassDescriptor *cpuPassDesc;
	int cpuPassColors, cpuPassSampled;
	id<MTLCommandBuffer> cpuNext;
	dispatch_semaphore_t cpuNextReady;
	int cpuNextPending;
	int tlDepthCleared, tlDepthWritten;
	float tlDepthClearValue;
} Enc;

id<MTLCommandBuffer> mc_pre(Enc *enc);
id<MTLComputeCommandEncoder> mc_pre_compute(Enc *enc);
id<MTLComputeCommandEncoder> mc_profiled_compute(Enc *enc, id<MTLCommandBuffer> c, MTLDispatchType type);
id<MTLBlitCommandEncoder> mc_profiled_blit(Enc *enc, id<MTLCommandBuffer> c);
int mc_last_compute_group(void);
void mc_pre_end_encoders(Enc *enc);
id<MTLComputeCommandEncoder> mc_render_suspend(Enc *enc);
id<MTLBlitCommandEncoder> mc_blit(Enc *enc);
void mc_set_rp_log(int on);
id<MTLCommandBuffer> mc_frame_cmd(Enc *enc);
void mc_render_resume(Enc *enc, id<MTLComputeCommandEncoder> compute);
extern const char *mc_reopen_why;
void mc_render_reopen(Enc *enc);
extern void (*mc_reopen_hook)(Enc *enc);
MTLMathMode mc_math(void);
