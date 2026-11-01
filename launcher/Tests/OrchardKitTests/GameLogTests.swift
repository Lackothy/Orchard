import Foundation
import Testing
@testable import OrchardKit

@Suite struct GameLogTests {

    @Test func readsTheGamesLines() throws {
        var log = GameLog()
        log.append("[15:56:19] [main/INFO]: Loading Minecraft 26.1.2 with Fabric Loader 0.20.1")
        log.append("[15:56:23] [Render thread/WARN]: Failed to get system info for Render Extensions")
        let first = try #require(log.entries.first)
        #expect(first.time == "15:56:19")
        #expect(first.thread == "main")
        #expect(first.level == .info)
        #expect(first.message == "Loading Minecraft 26.1.2 with Fabric Loader 0.20.1")
        #expect(log.entries[1].thread == "Render thread")
        #expect(log.entries[1].level == .warning)
        #expect(log.warnings == 1 && log.errors == 0)
    }

    @Test func foldsAStackTraceIntoItsEntry() throws {
        var log = GameLog()
        log.append("[15:56:22] [Download-2/ERROR]: Failed to fetch user properties")
        log.append("com.mojang.authlib.exceptions.InvalidCredentialsException: Status: 401")
        log.append("\tat knot//com.mojang.authlib.yggdrasil.YggdrasilUserApiService.fetchProperties(YggdrasilUserApiService.java:150)")
        log.append("Caused by: com.mojang.authlib.exceptions.MinecraftClientHttpException: Status: 401")
        log.append("\t... 5 more")
        log.append("[15:56:23] [Render thread/INFO]: Backend library: LWJGL version 3.3.3")
        #expect(log.entries.count == 2)
        let error = try #require(log.entries.first)
        #expect(error.level == .error)
        #expect(error.detail.count == 4)
        #expect(error.text.hasPrefix("[15:56:22] [Download-2/ERROR]: Failed to fetch user properties\ncom.mojang"))
        #expect(log.errors == 1)
        #expect(log.lines == 6)
    }

    @Test func readsALoggerName() throws {
        var log = GameLog()
        log.append("[11:51:34] [Download-2/ERROR] (Minecraft) Failed to fetch Realms feature flags")
        let entry = try #require(log.entries.first)
        #expect(entry.level == .error)
        #expect(entry.thread == "Download-2")
        #expect(entry.message == "Failed to fetch Realms feature flags")
    }

    @Test func keepsTheModListWithItsHeading() {
        var log = GameLog()
        log.append("[15:56:19] [main/INFO]: Loading 49 mods:")
        log.append("\t- fabric-api 0.155.3+26.1.2")
        log.append("\t   |-- fabric-api-base 2.0.3+ece063234c")
        #expect(log.entries.count == 1)
        #expect(log.entries[0].detail.count == 2)
    }

    @Test func plainLinesAndColoursStandAlone() {
        var log = GameLog()
        log.note("Starting fabric-loader-0.20.1-26.1.2 in Orchard as Lackothy")
        log.append("\tthis indented line has no game entry to join")
        log.append("WARNING: A restricted method in java.lang.System has been called")
        log.append("\u{1B}[32m[10:00:00] [main/INFO]: Coloured\u{1B}[0m")
        #expect(log.entries.map(\.level) == [.launcher, .info, .warning, .info])
        #expect(log.entries[3].message == "Coloured")
        #expect(log.entries[0].time?.count == 8)
    }

    @Test func dropsTheOldestAndRecounts() {
        var log = GameLog(limit: 10)
        for index in 0..<12 {
            log.append("[10:00:00] [main/WARN]: warning \(index)")
        }
        #expect(log.entries.count == 10)
        #expect(log.entries.first?.message == "warning 2")
        #expect(log.warnings == 10)
    }
}

@Suite struct CrashReportTests {

    @Test func readsWhatWentWrong() throws {
        let text = """
            ---- Minecraft Crash Report ----
            // Who set us up the TNT?

            Time: 2026-10-30 15:56:23
            Description: Initializing game

            java.lang.RuntimeException: Could not execute entrypoint stage 'client' due to errors, provided by 'orchard' at 'com.lackothy.Orchard'!
            \tat net.fabricmc.loader.impl.FabricLoaderImpl.lambda$invokeEntrypoints$0(FabricLoaderImpl.java:418)
            Caused by: java.lang.NullPointerException: Cannot read field "keySprint" because "mc.options" is null
            \tat knot//com.lackothy.orchard.module.mechanic.ToggleSprintModule.key(ToggleSprintModule.java:21)
            \t... 6 more


            A detailed walkthrough of the error, its code path and all known details is as follows:
            Caused by: java.lang.IllegalStateException: not this one
            """
        let report = try #require(CrashReport(text: text, file: URL(filePath: "/tmp/crash.txt")))
        #expect(report.description == "Initializing game")
        #expect(report.cause == #"NullPointerException: Cannot read field "keySprint" because "mc.options" is null"#)
        #expect(report.mod == "orchard")
    }

    @Test func aPlainCrashNamesNoMod() throws {
        let text = "Description: Rendering screen\n\njava.lang.IllegalStateException: Broken\n\tat a.b.C(C.java:1)\n"
        let report = try #require(CrashReport(text: text, file: URL(filePath: "/tmp/crash.txt")))
        #expect(report.cause == "IllegalStateException: Broken")
        #expect(report.mod == nil)
    }
}
