// Orchard's own file (see NOTICE): not part of mcopt.
// Render scale (RenderScale.java): MetalFX's spatial scaler takes the world, drawn smaller, up to the window's size in
// the frame's command buffer, after the world and before the menus, so the menus stay sharp.
#import <Metal/Metal.h>
#import <MetalFX/MetalFX.h>
#include "mcmetal.h"

id<MTLCommandBuffer> mc_frame_cmd(Enc *enc);
id<MTLBlitCommandEncoder> mc_blit(Enc *enc);

typedef struct {
	id<MTLFXSpatialScaler> scaler;
	// Where MetalFX writes when the game's target lacks the usage it writes with, then copied into the target; nil when
	// it writes straight in.
	id<MTLTexture> between;
} Upscaler;

// A scaler from textures of in's size and format to out's, or NULL when MetalFX can't do it here (the reason is printed).
Upscaler *mc_upscaler_new(id<MTLTexture> in, id<MTLTexture> out) {
	@autoreleasepool {
		id<MTLDevice> device = in.device;
		if (![MTLFXSpatialScalerDescriptor supportsDevice:device]) {
			printf("orchard-render-scale: MetalFX's spatial scaler isn't supported on %s\n", device.name.UTF8String);
			return NULL;
		}
		MTLFXSpatialScalerDescriptor *d = [[MTLFXSpatialScalerDescriptor new] autorelease];
		d.inputWidth = in.width;
		d.inputHeight = in.height;
		d.outputWidth = out.width;
		d.outputHeight = out.height;
		d.colorTextureFormat = in.pixelFormat;
		d.outputTextureFormat = out.pixelFormat;
		d.colorProcessingMode = MTLFXSpatialScalerColorProcessingModePerceptual;  // the game's 8-bit colour, as shown
		id<MTLFXSpatialScaler> scaler = [d newSpatialScalerWithDevice:device];
		if (!scaler) {
			printf("orchard-render-scale: MetalFX made no scaler for %lux%lu to %lux%lu, format %lu\n", (unsigned long) in.width,
				(unsigned long) in.height, (unsigned long) out.width, (unsigned long) out.height, (unsigned long) in.pixelFormat);
			return NULL;
		}
		if ((in.usage & scaler.colorTextureUsage) != scaler.colorTextureUsage) {
			printf("orchard-render-scale: the world's target can't be read by MetalFX (usage %lu, needs %lu)\n",
				(unsigned long) in.usage, (unsigned long) scaler.colorTextureUsage);
			[scaler release];
			return NULL;
		}
		Upscaler *u = calloc(1, sizeof(Upscaler));
		u->scaler = scaler;
		if ((out.usage & scaler.outputTextureUsage) != scaler.outputTextureUsage) {
			MTLTextureDescriptor *t = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:out.pixelFormat width:out.width
				height:out.height mipmapped:NO];
			t.usage = scaler.outputTextureUsage;
			t.storageMode = MTLStorageModePrivate;
			u->between = [device newTextureWithDescriptor:t];
			u->between.label = @"Orchard render scale";
		}
		return u;
	}
}

// Encodes the upscale of in into out at this point of the frame. Whatever encoder mcopt has open is ended first.
void mc_upscale(Enc *enc, Upscaler *u, id<MTLTexture> in, id<MTLTexture> out) {
	id<MTLCommandBuffer> cmd = mc_frame_cmd(enc);
	u->scaler.colorTexture = in;
	u->scaler.inputContentWidth = in.width;
	u->scaler.inputContentHeight = in.height;
	u->scaler.outputTexture = u->between ? u->between : out;
	[u->scaler encodeToCommandBuffer:cmd];
	if (u->between) [mc_blit(enc) copyFromTexture:u->between toTexture:out];
}

// Only once the GPU is done with the frames that used it (RenderScale frees it through afterGpuFinishes).
void mc_upscaler_free(Upscaler *u) {
	if (!u) return;
	[u->scaler release];
	[u->between release];
	free(u);
}
