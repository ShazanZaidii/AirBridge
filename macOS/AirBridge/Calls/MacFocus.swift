import Foundation
import Intents

/// Whether a Focus (DND included) is on on this Mac, through Apple's Focus status API. A phone call then still shows on
/// the Mac but doesn't ring here (issue #11). macOS asks the user once; without that permission calls ring as before.
@MainActor enum MacFocus {
    /// Asks once (only while calls are shown on this Mac), at launch rather than in the middle of a ringing call.
    static func prepare() {
        guard PhoneNotifSettings.showCalls, INFocusStatusCenter.default.authorizationStatus == .notDetermined else { return }
        INFocusStatusCenter.default.requestAuthorization { _ in }
    }

    /// A Focus or DND is on right now (false when unknown or not allowed).
    static var isOn: Bool {
        INFocusStatusCenter.default.authorizationStatus == .authorized && INFocusStatusCenter.default.focusStatus.isFocused == true
    }
}
