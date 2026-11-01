import Foundation

public enum LauncherError: LocalizedError, Equatable {
    case missingEdition(String)
    case unknownVersion(String)
    case noJavaRuntime(String)
    case badChecksum(String)
    case download(String, status: Int)
    case modNotBuilt
    case offlineWithoutCache
    case noCompatibleVersion(String)

    public var errorDescription: String? {
        switch self {
        case .missingEdition(let key):
            "The launcher doesn’t know which \(key) to install."
        case .unknownVersion(let id):
            "Mojang doesn’t list Minecraft \(id)."
        case .noJavaRuntime(let component):
            "Mojang has no \(component) Java runtime for this Mac."
        case .badChecksum(let name):
            "\(name) didn’t download correctly."
        case .download(let name, let status):
            "\(name) couldn’t be downloaded (HTTP \(status))."
        case .modNotBuilt:
            "The Orchard mod hasn’t been built. Run ./gradlew build, then try again."
        case .offlineWithoutCache:
            "Minecraft needs to download once before it can play offline."
        case .noCompatibleVersion(let minecraft):
            "There’s no version of this for Minecraft \(minecraft) yet."
        }
    }
}
