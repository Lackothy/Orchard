// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// M3 XDR / EDR output for the Liquid Retina XDR panel (native shading, mcopt.shade.xdr; bible E23). Off unless the Java side
// calls mc_xdr_enable(1) before the layer is configured; then:
//   - mc_layer_configure (mcmetal.m) makes the CAMetalLayer RGBA16Float, extendedLinearDisplayP3, wantsExtendedDynamicRange;
//   - shade's final pass (shade_post.metal shade_final_xdr) writes the SDR image into the game's main RGBA8 target as before,
//     with alpha 0 ("the world, untouched"), and the highlights' extra brightness above SDR white (linear Rec.709, >= 0) into
//     an RG11B10Float "excess" image, which it hands over with mc_xdr_frame;
//   - the hand, screen effects and the GUI then draw over main as usual (their blending writes alpha > 0: coverage);
//   - at present (pqFill / mc_present in mcmetal.m) a compose pass writes linear extended P3 = M709toP3 (sRGB->linear(main.rgb)
//     + excess x (1 - main.a)): SDR content at SDR white, the world's highlights above it, the GUI on top untouched.
// Full-scene mode uses a complete RGBA16F world image instead of excess and a
// pre-HUD SDR reference to recover overlay coverage without changing vanilla passes.
// Screenshots read main, i.e. the SDR image. Headroom: NSScreen.maximumExtendedDynamicRangeColorComponentValue of the
// layer's screen (mc_xdr_headroom), queried by the Java side a few times a second.
#import <Metal/Metal.h>
#import <QuartzCore/CAMetalLayer.h>
#import <AppKit/AppKit.h>
#include "mcmetal.h"

static int xdrOn;
static id<MTLTexture> xdrExcess;
static int xdrValid, xdrFull;
static id<MTLTexture> xdrBase;
static float xdrUiWhite = 1.0f, xdrPeak = 1.0f;
static void *xdrCmd;
static id<MTLLibrary> xdrLib;
static id<MTLRenderPipelineState> xdrCompose, xdrCompose16, xdrPresent, xdrComposeFull, xdrComposeFull16;

static const char *xdrSource =
	"#include <metal_stdlib>\n"
	"using namespace metal;\n"
	"struct V { float4 pos [[position]]; float2 uv; };\n"
	"vertex V xdr_vs(uint id [[vertex_id]]) {\n"
	"  float2 p = float2((id << 1) & 2, id & 2);\n"
	"  V v; v.pos = float4(p * 2.0 - 1.0, 0.0, 1.0); v.uv = p; return v;\n"
	"}\n"
	"struct XdrParams { int flip; int valid; int e5; int full; float uiWhite; float peak; float2 pad; };\n"
	"static float3 e5(float3 c) {\n"
	"  c = clamp(c, 0.0, 65408.0); float m = max(c.x, max(c.y, c.z));\n"
	"  int e = max(-16, int((as_type<uint>(m) >> 23) & 255u) - 127) + 1 + 15;\n"
	"  float sc = as_type<float>(uint(e - 24 + 127) << 23);\n"
	"  return min(floor(c / sc), 511.0) * sc;\n"
	"}\n"
	"static float3 srgbToLinear(float3 c) { return select(pow((c + 0.055) / 1.055, 2.4), c / 12.92, c <= 0.04045); }\n"
	"constant float3x3 M709toP3 = float3x3(float3(0.822462, 0.033194, 0.017083), float3(0.177538, 0.966806, 0.072397), float3(0.0, 0.0, 0.910520));\n"
	"template <bool FULL> static float4 xdrCompose(V v, texture2d<float> src, texture2d<half> excess, texture2d<float> base, constant XdrParams &p) {\n"
	"  uint2 px = uint2(v.pos.xy);\n"
	"  if (p.flip != 0) px.y = src.get_height() - 1 - px.y;\n"
	"  float4 m = src.read(px);\n"
	"  float3 lin = srgbToLinear(saturate(m.rgb));\n"
	"  if (p.valid != 0) {\n"
	"    constexpr sampler ls(filter::linear, address::clamp_to_edge);\n"
	"    float2 euv = (float2(px) + 0.5) / float2(src.get_width(), src.get_height());\n"
	"    half3 ex = excess.sample(ls, euv).rgb;\n"
	"    bool bad = any((as_type<ushort3>(ex) & ushort3(0x7c00)) == ushort3(0x7c00));\n"
	"    float3 hdr = bad ? float3(0.0) : float3(ex);\n"
	"    if (FULL && !bad) {\n"
	"      float a = saturate(m.a); float3 b = base.read(px).rgb;\n"
	"      if (a > 0.0) {\n"
	"        float3 ui = saturate((m.rgb - b * (1.0 - a)) / a);\n"
	"        lin = hdr * (1.0 - a) + srgbToLinear(ui) * (a * p.uiWhite);\n"
	"      } else lin = hdr + (lin - srgbToLinear(b)) * p.uiWhite;\n"
	"      lin = max(lin, 0.0);\n"
	"      lin /= max(1.0, max(max(lin.r, lin.g), lin.b) / p.peak);\n"
	"    } else lin += hdr * saturate(1.0 - m.a);\n"
	"  }\n"
	"  float3 o = M709toP3 * lin;\n"
	"  return float4(p.e5 != 0 ? e5(o) : o, 1.0);\n"
	"}\n"
	"fragment float4 xdr_compose_fs(V v [[stage_in]], texture2d<float> src [[texture(0)]], texture2d<half> excess [[texture(1)]],\n"
	"                               texture2d<float> base [[texture(2)]], constant XdrParams &p [[buffer(0)]]) {\n"
	"  return xdrCompose<false>(v, src, excess, base, p);\n"
	"}\n"
	"fragment float4 xdr_compose_full_fs(V v [[stage_in]], texture2d<float> src [[texture(0)]], texture2d<half> excess [[texture(1)]],\n"
	"                                    texture2d<float> base [[texture(2)]], constant XdrParams &p [[buffer(0)]]) {\n"
	"  return xdrCompose<true>(v, src, excess, base, p);\n"
	"}\n"
	"fragment float4 xdr_present_fs(V v [[stage_in]], texture2d<float> t [[texture(0)]]) {\n"
	"  constexpr sampler s(filter::nearest, address::clamp_to_edge);\n"
	"  return t.sample(s, v.uv);\n"
	"}\n";

void mc_xdr_enable(int on) { xdrOn = on; }
static int xdrE5 = 1;
void mc_xdr_e5(int on) { xdrE5 = on; }
int mc_xdr_on(void) { return xdrOn; }

int mc_xdr_supported(void) {
	@autoreleasepool {
		for (NSScreen *s in [NSScreen screens]) {
			if (s.maximumPotentialExtendedDynamicRangeColorComponentValue > 1.01) return 1;
		}
	}
	return 0;
}

static NSScreen *layerScreen(CAMetalLayer *layer) {
	id d = layer ? layer.delegate : nil;
	if (d && [d isKindOfClass:[NSView class]]) {
		NSWindow *w = ((NSView *) d).window;
		if (w && w.screen) return w.screen;
	}
	CALayer *l = layer;
	while (l) {
		id dd = l.delegate;
		if (dd && [dd isKindOfClass:[NSView class]] && ((NSView *) dd).window.screen) return ((NSView *) dd).window.screen;
		l = l.superlayer;
	}
	return [NSScreen mainScreen];
}

uint32_t mc_layer_display(CAMetalLayer *layer) {
	if (!layer || ![NSThread isMainThread]) return 0;
	@autoreleasepool {
		NSScreen *s = layerScreen(layer);
		NSNumber *n = s ? s.deviceDescription[@"NSScreenNumber"] : nil;
		return n ? n.unsignedIntValue : 0;
	}
}

void mc_system_info(CAMetalLayer *layer, char *out, int cap) {
	@autoreleasepool {
		NSScreen *mine = [NSThread isMainThread] ? layerScreen(layer) : nil;
		NSProcessInfo *p = [NSProcessInfo processInfo];
		NSMutableString *s = [NSMutableString stringWithFormat:@"macOS %@%@", p.operatingSystemVersionString, p.lowPowerModeEnabled ? @", Low Power Mode" : @""];
		for (NSScreen *sc in [NSScreen screens]) {
			NSSize px = [sc convertRectToBacking:sc.frame].size;
			[s appendFormat:@"; %@%@ (display %@) %.0fx%.0f %ld Hz EDR %.1f", sc == mine ? @"*" : @"", sc.localizedName,
				sc.deviceDescription[@"NSScreenNumber"], px.width, px.height, (long) sc.maximumFramesPerSecond,
				sc.maximumPotentialExtendedDynamicRangeColorComponentValue];
		}
		snprintf(out, cap, "%s", s.UTF8String);
	}
}

int mc_thermal_state(void) {
	@autoreleasepool {
		return (int) [NSProcessInfo processInfo].thermalState;
	}
}

float mc_xdr_headroom(CAMetalLayer *layer, int potential) {
	@autoreleasepool {
		NSScreen *s = layerScreen(layer);
		if (!s) return 1.0f;
		return (float) (potential ? s.maximumPotentialExtendedDynamicRangeColorComponentValue : s.maximumExtendedDynamicRangeColorComponentValue);
	}
}

void mc_xdr_frame(Enc *enc, id<MTLTexture> excess) {
	xdrFull = 0;
	xdrCmd = (void *) enc->cmd;
	if (excess != xdrExcess) {
		[excess retain];
		[xdrExcess release];
		xdrExcess = excess;
	}
	xdrValid = excess != nil;
}

void mc_xdr_full_frame(Enc *enc, id<MTLTexture> hdr, id<MTLTexture> main, float uiWhite, float peak) {
	mc_xdr_frame(enc, nil);
	if (!hdr || !main) return;
	if (!xdrBase || xdrBase.width != main.width || xdrBase.height != main.height || xdrBase.pixelFormat != main.pixelFormat) {
		[xdrBase release];
		MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:main.pixelFormat width:main.width height:main.height mipmapped:NO];
		d.storageMode = MTLStorageModePrivate;
		d.usage = MTLTextureUsageShaderRead;
		xdrBase = [main.device newTextureWithDescriptor:d];
		xdrBase.label = @"mcopt shade: SDR world reference";
	}
	if (!xdrBase) return;
	id<MTLCommandBuffer> cb = mc_frame_cmd(enc);
	id<MTLBlitCommandEncoder> b = [cb blitCommandEncoder];
	[b copyFromTexture:main toTexture:xdrBase];
	[b endEncoding];
	mc_xdr_frame(enc, hdr);
	xdrFull = 1;
	xdrUiWhite = fmaxf(0.25f, fminf(uiWhite, 2.0f));
	xdrPeak = fmaxf(1.0f, fminf(peak, 16.0f));
}

static int ensurePipelines(id<MTLDevice> device) {
	if (xdrCompose && xdrCompose16 && xdrPresent) return 1;
	@autoreleasepool {
		NSError *e = nil;
		if (!xdrLib) {
			MTLCompileOptions *o = [[MTLCompileOptions new] autorelease];
			o.languageVersion = MTLLanguageVersion3_0;
			xdrLib = [device newLibraryWithSource:[NSString stringWithUTF8String:xdrSource] options:o error:&e];
		}
		if (!xdrLib) {
			NSLog(@"mcopt shade xdr: %@", e);
			return 0;
		}
		MTLRenderPipelineDescriptor *pd = [[MTLRenderPipelineDescriptor new] autorelease];
		pd.vertexFunction = [[xdrLib newFunctionWithName:@"xdr_vs"] autorelease];
		pd.fragmentFunction = [[xdrLib newFunctionWithName:@"xdr_compose_fs"] autorelease];
		pd.colorAttachments[0].pixelFormat = MTLPixelFormatRGB9E5Float;
		xdrCompose = [device newRenderPipelineStateWithDescriptor:pd error:&e];
		pd.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA16Float;
		xdrCompose16 = [device newRenderPipelineStateWithDescriptor:pd error:&e];
		pd.fragmentFunction = [[xdrLib newFunctionWithName:@"xdr_compose_full_fs"] autorelease];
		xdrComposeFull16 = [device newRenderPipelineStateWithDescriptor:pd error:&e];
		pd.colorAttachments[0].pixelFormat = MTLPixelFormatRGB9E5Float;
		xdrComposeFull = [device newRenderPipelineStateWithDescriptor:pd error:&e];
		pd.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA16Float;
		pd.fragmentFunction = [[xdrLib newFunctionWithName:@"xdr_present_fs"] autorelease];
		xdrPresent = [device newRenderPipelineStateWithDescriptor:pd error:&e];
		if (!xdrCompose || !xdrCompose16 || !xdrPresent) NSLog(@"mcopt shade xdr: %@", e);
	}
	return xdrCompose && xdrCompose16 && xdrPresent;
}

int mc_xdr_ready(id<MTLDevice> device) {
	if (!xdrOn) return 0;
	if (!ensurePipelines(device)) xdrOn = 0;
	return xdrOn;
}

id<MTLRenderPipelineState> mc_xdr_present_pso(id<MTLDevice> device) {
	return ensurePipelines(device) ? xdrPresent : nil;
}

static void dumpMaybe(id<MTLCommandBuffer> cb, id<MTLTexture> dst, int valid) {
	static const char *prefix;
	static int checked, n, max;
	static double last;
	if (!checked) {
		checked = 1;
		prefix = getenv("MCOPT_XDR_DUMP");
		const char *m = getenv("MCOPT_XDR_DUMP_MAX");
		max = m ? atoi(m) : 24;
	}
	if (!prefix || !valid || n >= max) return;
	double now = CACurrentMediaTime();
	if (now - last < 8.0) return;
	last = now;
	NSUInteger bpp = dst.pixelFormat == MTLPixelFormatRGB9E5Float ? 4 : 8;
	NSUInteger w = dst.width, h = dst.height, bytes = w * h * bpp;
	id<MTLBuffer> buf = [cb.device newBufferWithLength:bytes options:MTLResourceStorageModeShared];
	id<MTLBlitCommandEncoder> b = [cb blitCommandEncoder];
	[b copyFromTexture:dst sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0, 0, 0) sourceSize:MTLSizeMake(w, h, 1) toBuffer:buf
		destinationOffset:0 destinationBytesPerRow:w * bpp destinationBytesPerImage:bytes];
	[b endEncoding];
	int k = n++;
	char path[1024];
	snprintf(path, sizeof path, "%s-%d-%lux%lu.%s", prefix, k, (unsigned long) w, (unsigned long) h, bpp == 4 ? "rgb9e5" : "rgba16f");
	char *pathCopy = strdup(path);
	[cb addCompletedHandler:^(id<MTLCommandBuffer> done) {
		FILE *fp = fopen(pathCopy, "wb");
		if (fp) {
			fwrite(buf.contents, 1, bytes, fp);
			fclose(fp);
		}
		free(pathCopy);
		[buf release];
	}];
}

int mc_xdr_compose(id<MTLCommandBuffer> cb, id<MTLTexture> src, id<MTLTexture> dst, int flip) {
	if (!ensurePipelines(cb.device)) return 0;
	@autoreleasepool {
		MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
		rp.colorAttachments[0].texture = dst;
		rp.colorAttachments[0].loadAction = MTLLoadActionDontCare;
		rp.colorAttachments[0].storeAction = MTLStoreActionStore;
		id<MTLRenderCommandEncoder> r = [cb renderCommandEncoderWithDescriptor:rp];
		r.label = @"mcopt shade: XDR compose";
		int valid = xdrValid && (void *) cb == xdrCmd && xdrExcess && xdrExcess.width <= src.width && xdrExcess.height <= src.height;
		int e5 = flip && dst.pixelFormat == MTLPixelFormatRGBA16Float && xdrE5;
		int full = valid && xdrFull && xdrBase && xdrBase.width == src.width && xdrBase.height == src.height;
		if (full && !(dst.pixelFormat == MTLPixelFormatRGB9E5Float ? xdrComposeFull : xdrComposeFull16)) full = 0;
		if (xdrFull && !full) valid = 0;
		BOOL e9 = dst.pixelFormat == MTLPixelFormatRGB9E5Float;
		[r setRenderPipelineState:full ? (e9 ? xdrComposeFull : xdrComposeFull16) : (e9 ? xdrCompose : xdrCompose16)];
		struct { int flip, valid, e5, full; float uiWhite, peak, pad[2]; } p = {flip, valid, e5, full, xdrUiWhite, xdrPeak, {0, 0}};
		[r setFragmentTexture:src atIndex:0];
		[r setFragmentTexture:valid ? xdrExcess : src atIndex:1];
		[r setFragmentTexture:full ? xdrBase : src atIndex:2];
		[r setFragmentBytes:&p length:sizeof p atIndex:0];
		[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
		[r endEncoding];
		if (!flip) dumpMaybe(cb, dst, valid);
	}
	xdrValid = 0;
	return 1;
}

int mc_shade_bind_render(id<MTLRenderCommandEncoder> r);

id<MTLRenderPipelineState> mc_xdr_pso2(Ctx *ctx, id<MTLLibrary> lib, const char *vs, const char *fs, int format0, int format1, char *err, int errCap) {
	@autoreleasepool {
		MTLRenderPipelineDescriptor *pd = [[MTLRenderPipelineDescriptor new] autorelease];
		pd.vertexFunction = [[lib newFunctionWithName:[NSString stringWithUTF8String:vs]] autorelease];
		pd.fragmentFunction = [[lib newFunctionWithName:[NSString stringWithUTF8String:fs]] autorelease];
		pd.colorAttachments[0].pixelFormat = (MTLPixelFormat) format0;
		pd.colorAttachments[1].pixelFormat = (MTLPixelFormat) format1;
		NSError *e = nil;
		id<MTLRenderPipelineState> pso = pd.vertexFunction && pd.fragmentFunction ? [ctx->device newRenderPipelineStateWithDescriptor:pd error:&e] : nil;
		if (!pso && err) strlcpy(err, e ? e.localizedDescription.UTF8String : "missing function", errCap);
		return pso;
	}
}

void mc_xdr_pass2(Enc *enc, id<MTLRenderPipelineState> pso, id<MTLTexture> t0, id<MTLTexture> t1, id<MTLTexture> const *textures, int count,
                  const void *bytes, int length, const char *label) {
	if (!pso || !t0 || !t1) return;
	@autoreleasepool {
		id<MTLCommandBuffer> cb = mc_frame_cmd(enc);
		MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
		rp.colorAttachments[0].texture = t0;
		rp.colorAttachments[0].loadAction = MTLLoadActionDontCare;
		rp.colorAttachments[0].storeAction = MTLStoreActionStore;
		rp.colorAttachments[1].texture = t1;
		rp.colorAttachments[1].loadAction = MTLLoadActionDontCare;
		rp.colorAttachments[1].storeAction = MTLStoreActionStore;
		id<MTLRenderCommandEncoder> r = [cb renderCommandEncoderWithDescriptor:rp];
		if (label) r.label = [NSString stringWithUTF8String:label];
		[r setRenderPipelineState:pso];
		mc_shade_bind_render(r);
		for (int i = 0; i < count; i++) if (textures[i]) [r setFragmentTexture:textures[i] atIndex:i];
		if (bytes && length > 0) [r setFragmentBytes:bytes length:length atIndex:0];
		[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
		[r endEncoding];
	}
}
