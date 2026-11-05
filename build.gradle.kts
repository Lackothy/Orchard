plugins {
    // The non-remapping Loom: Minecraft ships unobfuscated from 26.1 onwards, so
    // there are no mappings to declare and no remapJar step. The plain `jar`
    // task output is the mod.
    id("net.fabricmc.fabric-loom") version "1.19.3"
    checkstyle
}

group = "com.lackothy"
version = "0.2.0"

val minecraftVersion: String by project
val loaderVersion: String by project
val fabricApiVersion: String by project

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ── The Metal renderer ──
// A modified copy of mcopt (renderer/, see its NOTICE), built into the jar. Beside Sodium only its
// backend runs, and beside an Iris that isn't set to Vulkan none of it (mcopt.metal.Switch).
sourceSets.main {
    java.srcDir("renderer/src/main/java")
    resources.srcDir("renderer/src/main/resources")
}

val nativeOut = layout.buildDirectory.dir("natives/natives")

val compileNative by tasks.registering(Exec::class) {
    val sources = listOf("mcmetal.m", "mclod.m", "lodseam.m", "lodmesh.c", "lodnoise.c", "rec.m", "mcown.m", "mccap.m", "upscale.m",
        "mcshade.m", "mcshadow.m", "mcwater.m", "mcvl.m", "mctaa.m", "mcdynlight.m", "mcxdr.m", "mcclouds.m", "mccompile.m")
        .map { file("renderer/src/main/native/$it") }
    val output = nativeOut.get().file("libmcmetal.dylib").asFile
    inputs.files(sources, file("renderer/src/main/native/mcmetal.h"), file("renderer/src/main/native/lodmesh.h"))
    outputs.file(output)
    doFirst { output.parentFile.mkdirs() }
    commandLine(
        listOf(
            "clang", "-fno-objc-arc", "-O2", "-Wall", "-arch", "arm64", "-mmacosx-version-min=15.0",
            "-dynamiclib", "-install_name", "@rpath/libmcmetal.dylib",
            "-framework", "AppKit", "-framework", "CoreGraphics", "-framework", "CoreVideo", "-framework", "Foundation",
            "-framework", "Metal", "-framework", "MetalFX", "-framework", "QuartzCore", "-o", output.path,
        ) + sources.map { it.path },
    )
}

// ── Client game tests ──
// `./gradlew runClientGameTest` boots a real client and screenshots the loading
// screen and the video settings (src/gametest). Not part of `build`: it opens a
// game window and takes a minute.
fabricApi {
    configureTests {
        createSourceSet = true
        modId = "russet-gametest"
        enableGameTests = false
        enableClientGameTests = true
        eula = true
    }
}

loom {
    runs {
        configureEach {
            vmArg("--enable-native-access=ALL-UNNAMED")
        }
        // `./gradlew runClient` (game dir run/): -PextraMods=a.jar:b.jar adds other mods beside it
        // (Sodium and Iris, as Lunar bundles them), and -Pjvm="-Da=b -Dc=d" adds properties.
        named("client") {
            findProperty("jvm")?.toString()?.split(" ")?.filter { it.isNotBlank() }?.forEach { vmArg(it) }
            findProperty("extraMods")?.let { vmArg("-Dfabric.addMods=$it") }
        }
        named("clientGameTest") {
            vmArg("-Drusset.screenshots=${layout.buildDirectory.dir("russet-screenshots").get().asFile}")
            // -PguiScale=3 to review the UI at another scale; 0 is Minecraft's "Auto".
            vmArg("-Drusset.guiScale=${findProperty("guiScale") ?: 0}")
            // -Pmetal to run on the Metal renderer. Without it, this is Mojang's.
            if (!hasProperty("metal")) {
                vmArg("-Drusset.renderer=mojang")
            }
            // -Pframes runs only ScreenTiming (-PframeCases=on,off60,off picks the cases); -Pjvm="-Da=b -Dc=d" adds properties.
            if (hasProperty("frames")) {
                vmArg("-Drusset.tour.frames=true")
                findProperty("frameCases")?.let { vmArg("-Drusset.tour.frames.cases=$it") }
            }
            findProperty("jvm")?.toString()?.split(" ")?.filter { it.isNotBlank() }?.forEach { vmArg(it) }
            // -PextraMods=a.jar:b.jar adds other mods, to check them beside Russet's.
            findProperty("extraMods")?.let { vmArg("-Dfabric.addMods=$it") }
        }
    }
}

// ── Tests ──
tasks.test {
    useJUnitPlatform()
    // Minecraft's log4j config is on the test classpath and writes logs/ into the
    // working directory; run from build/ so that lands there, not in the repo root.
    val testRun = layout.buildDirectory.dir("test-run")
    workingDir(testRun)
    doFirst { testRun.get().asFile.mkdirs() }
    // As in the game's runs, where the renderer reaches its native library through FFM.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25)
}

// The far terrain's Vector API noise (LodNoiseVector) loads only when the game is started with that module.
tasks.compileJava {
    options.compilerArgs.addAll(listOf("--add-modules", "jdk.incubator.vector"))
}

// ── Lint (checkstyle) ──
// javac no longer reports unused imports on modern JDKs, so Checkstyle carries
// that check. Unused/redundant/duplicate imports fail ./gradlew build.
checkstyle {
    toolVersion = "10.20.0"
    maxWarnings = 0
}

tasks.withType<Checkstyle> {
    reports {
        xml.required = false
        html.required = true
    }
}

// mcopt's code (renderer/) stays as it was taken, unused imports and all.
tasks.checkstyleMain {
    exclude("mcopt/**")
}

// ── Git info for fabric.mod.json ──

fun git(vararg args: String): String? = try {
    val proc = ProcessBuilder("git", *args)
        .directory(projectDir)
        .redirectErrorStream(false)
        .start()
    val out = proc.inputStream.bufferedReader().readText().trim()
    if (proc.waitFor() == 0) out else null
} catch (_: Exception) {
    null
}

// A repo with no commits yet answers rev-parse with an error, not a hash.
val gitCommitId: String by lazy { git("rev-parse", "--short=8", "HEAD") ?: "unknown" }

// symbolic-ref rather than rev-parse --abbrev-ref: it names the branch even
// before the first commit, and fails only when HEAD really is detached.
val gitBranch: String by lazy { git("symbolic-ref", "--short", "HEAD")?.ifEmpty { null } ?: "detached" }

// "unknown" when git cannot answer at all (no git, or Xcode's git shim waiting
// on its licence), so a jar never claims to be clean when nobody checked.
val gitStatus: String by lazy {
    val status = git("status", "--porcelain") ?: return@lazy "unknown"
    if (status.isBlank()) "clean" else "dirty"
}

fun ProcessResources.stampModJson() {
    filteringCharset = "UTF-8"
    // Declared as inputs, or Gradle reuses the last fabric.mod.json after a
    // commit and the jar reports the previous version.
    inputs.property("version", project.version)
    inputs.property("minecraftVersion", minecraftVersion)
    inputs.property("loaderVersion", loaderVersion)
    inputs.property("gitCommitId", gitCommitId)
    inputs.property("gitStatus", gitStatus)
    filesMatching("fabric.mod.json") {
        filter { line ->
            line
                .replace("\${version}", "${project.version}+$gitCommitId.$gitStatus")
                .replace("\${minecraft_version}", minecraftVersion)
                .replace("\${loader_version}", loaderVersion)
        }
    }
}

tasks.processResources {
    dependsOn(compileNative)
    from(layout.buildDirectory.dir("natives"))
    stampModJson()
}

tasks.jar {
    archiveFileName.set("Russet-${gitBranch}-${gitCommitId}-${gitStatus}.jar")
    destinationDirectory.set(layout.projectDirectory.dir("jars"))
    from(files("LICENSE", "NOTICE")) {
        into("licenses/russet")
    }
    // Apache-2.0 asks for mcopt's LICENSE and NOTICE in every copy.
    from(files("renderer/LICENSE", "renderer/NOTICE", "renderer/LICENSES/MIT-Photon.txt")) {
        into("licenses/mcopt")
    }
}

tasks.clean {
    delete(layout.projectDirectory.dir("jars"))
}
