import Foundation

/// What time of day the orchard behind the launcher and the game's menus shows: the clock's, or
/// one fixed hour. The game reads the choice from `-Dorchard.scene`, and draws the same hours
/// (`OrchardScene.java`), so both show the same picture.
public enum SceneTime: String, CaseIterable, Identifiable, Sendable {
    case timeOfDay = "time_of_day", day, sunset, night

    public var id: Self { self }

    /// The hour drawn, or nil to follow the clock.
    public var hour: Double? {
        switch self {
        case .timeOfDay: nil
        case .day: 12
        case .sunset: 18.4
        case .night: 23
        }
    }

    /// What the game is told; it follows the clock unless told otherwise.
    public var jvmArguments: [String] {
        self == .timeOfDay ? [] : ["-Dorchard.scene=\(rawValue)"]
    }
}
