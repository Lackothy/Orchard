// Copyright 2026 mcopt-shade contributors
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
// License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable
// law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
// OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and
// limitations under the License.
//
// Native shading's parallel shader compilation (-Dmcopt.shade=true, mcopt.shade.compile.parallel; Java side
// mcopt.metal.shade.ShadeCompile, which owns the threads). Metal compiles in a separate process (MTLCompilerService), several
// requests at once, each at the QoS of the thread that sent it, and both steps are cached on disk per app: MSL -> AIR for
// newLibraryWithSource, AIR -> GPU code for every pipeline state. With the cache cold (first launch, a mod update that
// changes the MSL, the first launch after a macOS update) shade's ~90 compiles took seconds one after another; on a pool of
// threads they overlap. Nothing here changes what is compiled: the same sources, options and descriptors as before.
// Manual retain/release (the dylib builds with -fno-objc-arc), like the other files.
#import "mcmetal.h"
#include <pthread/qos.h>

int mc_compile_setup(Ctx *ctx, int maximize) {
	if (maximize) ctx->device.shouldMaximizeConcurrentCompilation = YES;
	return (int) ctx->device.maximumConcurrentCompilationTaskCount;
}

int mc_compile_thread_qos(int qos) {
	return pthread_set_qos_class_self_np((qos_class_t) qos, 0);
}
