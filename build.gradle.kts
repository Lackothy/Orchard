plugins {
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
        named("client") {
            findProperty("jvm")?.toString()?.split(" ")?.filter { it.isNotBlank() }?.forEach { vmArg(it) }
            findProperty("extraMods")?.let { vmArg("-Dfabric.addMods=$it") }
        }
        named("clientGameTest") {
            vmArg("-Drusset.screenshots=${layout.buildDirectory.dir("russet-screenshots").get().asFile}")
            vmArg("-Drusset.guiScale=${findProperty("guiScale") ?: 0}")
            if (!hasProperty("metal")) {
                vmArg("-Drusset.renderer=mojang")
            }
            if (hasProperty("frames")) {
                vmArg("-Drusset.tour.frames=true")
                findProperty("frameCases")?.let { vmArg("-Drusset.tour.frames.cases=$it") }
            }
            findProperty("jvm")?.toString()?.split(" ")?.filter { it.isNotBlank() }?.forEach { vmArg(it) }
            findProperty("extraMods")?.let { vmArg("-Dfabric.addMods=$it") }
        }
    }
}

tasks.test {
    useJUnitPlatform()
    val testRun = layout.buildDirectory.dir("test-run")
    workingDir(testRun)
    doFirst { testRun.get().asFile.mkdirs() }
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.compileJava {
    options.compilerArgs.addAll(listOf("--add-modules", "jdk.incubator.vector"))
}

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

tasks.checkstyleMain {
    exclude("mcopt/**")
}

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

val gitCommitId: String by lazy { git("rev-parse", "--short=8", "HEAD") ?: "unknown" }

val gitBranch: String by lazy { git("symbolic-ref", "--short", "HEAD")?.ifEmpty { null } ?: "detached" }

val gitStatus: String by lazy {
    val status = git("status", "--porcelain") ?: return@lazy "unknown"
    if (status.isBlank()) "clean" else "dirty"
}

fun ProcessResources.stampModJson() {
    filteringCharset = "UTF-8"
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
    from(files("renderer/LICENSE", "renderer/NOTICE", "renderer/LICENSES/MIT-Photon.txt")) {
        into("licenses/mcopt")
    }
}

tasks.clean {
    delete(layout.projectDirectory.dir("jars"))
}
