import Foundation

public enum SceneTime: String, CaseIterable, Identifiable, Sendable {
    case timeOfDay = "time_of_day", day, sunset, night

    public var id: Self { self }

    public var hour: Double? {
        switch self {
        case .timeOfDay: nil
        case .day: 12
        case .sunset: 18.4
        case .night: 23
        }
    }

    public var jvmArguments: [String] {
        self == .timeOfDay ? [] : ["-Dorchard.scene=\(rawValue)"]
    }
}
