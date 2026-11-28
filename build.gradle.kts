plugins {
    id("net.fabricmc.fabric-loom") version "1.19.3"
    checkstyle
}

group = "com.lackothy"
version = "0.4.0"

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

val mcoptFlags by tasks.registering {
    val root = file("renderer/src/main/java")
    val sources = fileTree(root) { include("**/*.java") }
    val output = layout.buildDirectory.file("mcopt-flags/flags.tsv")
    inputs.files(sources)
    outputs.file(output)
    doLast {
        output.get().asFile.writeText(scanFlags(root, sources.files.sortedBy { it.path }))
    }
}

val flagReads: List<Triple<Regex, String, Any>> = listOf(
    Triple("""Boolean\.getBoolean\(\s*"K"\s*\)""", "bool", "false"),
    Triple("""Boolean\.parseBoolean\(\s*System\.getProperty\(\s*"K"\s*,\s*"(true|false)"\s*\)""", "bool", 1),
    Triple(""""true"\.equals(?:IgnoreCase)?\(\s*System\.getProperty\(\s*"K"\s*,\s*"(true|false)"""", "bool", 1),
    Triple("""!\s*"false"\.equals(?:IgnoreCase)?\(\s*System\.getProperty\(\s*"K"""", "bool", "true"),
    Triple(""""false"\.equals(?:IgnoreCase)?\(\s*System\.getProperty\(\s*"K"""", "bool", "true"),
    Triple(""""true"\.equals(?:IgnoreCase)?\(\s*System\.getProperty\(\s*"K"\s*\)""", "bool", "false"),
    Triple("""\b(?:flag|b|bool)\(\s*"K"\s*,\s*(true|false)\s*\)""", "bool", 1),
    Triple("""(?:Integer\.getInteger|Long\.getLong)\(\s*"K"\s*,\s*(-?\d+)L?\s*\)""", "int", 1),
    Triple("""(?:Integer\.getInteger|Long\.getLong)\(\s*"K"""", "int", ""),
    Triple("""(?:Double\.parseDouble|Float\.parseFloat)\(\s*System\.getProperty\(\s*"K"\s*,\s*"(-?[\d.]+)[fFdD]?"""", "number", 1),
    Triple("""(?:Integer\.parseInt|Long\.parseLong)\(\s*System\.getProperty\(\s*"K"\s*,\s*"(-?\d+)"""", "int", 1),
    Triple("""\b(?:floatProperty|floatFlag|f|live|intProperty)\(\s*"K"\s*,\s*(-?[\d.]+)[fFdD]?""", "number", 1),
    Triple("""Boolean\.parseBoolean\(\s*System\.getProperty\(\s*"K"""", "bool", ""),
    Triple("""(?:Double\.parseDouble|Float\.parseFloat)\(\s*System\.getProperty\(\s*"K"""", "number", ""),
    Triple("""(?:Integer\.parseInt|Long\.parseLong)\(\s*System\.getProperty\(\s*"K"""", "int", ""),
    Triple("""System\.getProperty\(\s*"K"\s*,\s*"([^"]*)"\s*\)""", "text", 1),
    Triple("""System\.getProperty\(\s*"K"\s*\)""", "text", ""),
).map { (pattern, kind, default) -> Triple(Regex(pattern), kind, default) }

fun scanFlags(root: File, files: List<File>): String {
    val key = Regex(""""(mcopt\.[A-Za-z0-9_.]+)"""")
    val write = Regex("""System\.(?:setProperty|clearProperty)\(\s*"K"""")
    val shaderScreen = key.findAll(root.resolve("mcopt/metal/shade/ShadeOptions.java").readText()).map { it.groupValues[1] }.toSet()
    val found = sortedMapOf<String, Array<String>>()
    for (file in files) {
        val path = file.relativeTo(root).invariantSeparatorsPath
        val lines = file.readLines()
        for ((i, line) in lines.withIndex()) {
            for (match in key.findAll(line)) {
                val name = match.groupValues[1]
                if (name.endsWith(".") || name == "mcopt.properties" || name.split('.').drop(1).any { it.first().isUpperCase() }
                    || name in shaderScreen) {
                    continue
                }
                val code = (line + " " + lines.getOrElse(i + 1) { "" }).replace("\"$name\"", "\"K\"")
                val read = flagReads.firstNotNullOfOrNull { (regex, kind, default) ->
                    regex.find(code)?.let { arrayOf(kind, if (default is Int) it.groupValues[default] else default as String) }
                }
                if (read == null && write.containsMatchIn(code)) {
                    continue
                }
                val now = found[name]
                val description = commentAbove(lines, i)
                if (now == null || read != null && (now[0].isEmpty() || now[3].isEmpty() && description.isNotEmpty())) {
                    found[name] = arrayOf(read?.get(0) ?: "", read?.get(1) ?: "", path, description)
                }
            }
        }
    }
    for ((name, flag) in found) {
        if (flag[3].isEmpty()) {
            root.resolve(flag[2]).readLines().map { it.trim() }
                .firstOrNull { (it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")) && name in it }
                ?.let { flag[3] = clean(it.replace(Regex("""^[/*\s]+"""), "").trimEnd('*', '/', ' ')) }
        }
    }
    return found.entries.joinToString("") { (name, flag) ->
        listOf(name, flag[0].ifEmpty { "text" }, flag[1], flag[2], flag[3]).joinToString("\t") { it.replace(Regex("""\s+"""), " ") } + "\n"
    }
}

fun commentAbove(lines: List<String>, i: Int): String {
    val out = ArrayDeque<String>()
    var j = i - 1
    while (j >= 0) {
        val s = lines[j].trim()
        if (s.startsWith("@")) {
            j--
        } else if (s.startsWith("//")) {
            out.addFirst(s.removePrefix("//").trim())
            j--
        } else {
            if (s.endsWith("*/")) {
                var k = j
                while (k > 0 && "/*" !in lines[k]) {
                    k--
                }
                out.addFirst(lines.subList(k, j + 1).joinToString(" ") { it.trim().trimStart('/', '*').trimEnd('/', '*').trim() })
            }
            break
        }
    }
    return clean(out.joinToString(" ").ifEmpty { Regex("""//\s*(.*)$""").find(lines[i])?.groupValues?.get(1) ?: "" })
}

fun clean(text: String): String = text
    .replace(Regex("""\{@(?:code|link|linkplain)\s+([^}]*)}"""), "$1")
    .replace(Regex("""<[^>]+>"""), "")
    .replace(Regex("""\s+"""), " ")
    .trim()

sourceSets.main {
    java.srcDir("rain/src/main/java")
    resources.srcDir("rain/src/main/resources")
}

val sodiumApi: SourceSet = sourceSets.create("sodiumApi") {
    java.srcDir("sodium-config-api/src/main/java")
    compileClasspath += sourceSets.main.get().compileClasspath
}

sourceSets.main {
    compileClasspath += sodiumApi.output
}

val sodiumApiJar by tasks.registering(Jar::class) {
    archiveFileName.set("sodium-config-api.jar")
    destinationDirectory.set(layout.buildDirectory.dir("sodium-config-api"))
    from(sodiumApi.output)
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
    from(sodiumApiJar) {
        into("META-INF/russet")
    }
    from(mcoptFlags) {
        into("mcopt")
    }
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
    from(files("rain/LICENSE", "rain/NOTICE")) {
        into("licenses/particle-rain")
    }
}

tasks.clean {
    delete(layout.projectDirectory.dir("jars"))
}
