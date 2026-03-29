import Foundation

/// アプリの外観切り替えモード
enum SwitchMode: String, CaseIterable, Sendable {
    /// 切り替え無効
    case off
    /// 環境光センサーによる自動切り替え
    case auto
    /// 画面輝度による手動切り替え（MAX=ライト、それ以外=ダーク）
    case manual

    var displayName: String {
        switch self {
        case .off:
            return "Off"
        case .auto:
            return "Auto"
        case .manual:
            return "Manual"
        }
    }

    var menuDescription: String {
        switch self {
        case .off:
            return "Switching disabled."
        case .auto:
            return "Automatic switching by ambient light."
        case .manual:
            return "Manual switching by display brightness."
        }
    }
}
