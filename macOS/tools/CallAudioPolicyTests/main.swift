import Foundation

// Tests for CallAudioPolicy. Each scenario drives the policy with the events IOBluetooth, the phone and the
// call window would send, and checks the commands and the reported route. Run: tools/CallAudioPolicyTests/run.sh

typealias P = CallAudioPolicy

var failures = 0
var passed = 0

func check(_ ok: Bool, _ what: String, line: Int = #line) {
    if ok { passed += 1 } else { failures += 1; print("FAIL line \(line): \(what)") }
}

/// A policy that is ready to use (Bluetooth on, phone paired).
func ready() -> P { P(bluetoothOn: true, phonePaired: true) }

@discardableResult
func send(_ p: inout P, _ e: P.Event) -> [P.Command] { p.handle(e) }

func test(_ name: String, _ body: () -> Void) {
    let before = failures
    body()
    print(failures == before ? "ok   \(name)" : "FAIL \(name)")
}

/// A ringing call with the standby link up (the dialer can offer the Mac).
func ringingWithStandby() -> P {
    var p = ready()
    send(&p, .phoneCall(.ringing))
    send(&p, .linkUp(callActive: false, callSetup: 1))
    send(&p, .graceOver)
    return p
}

/// Audio on the Mac in an active call (answered on the Mac).
func onMac() -> P {
    var p = ringingWithStandby()
    send(&p, .useMac)
    send(&p, .callIndicators(callActive: true, callSetup: 0))
    send(&p, .audioOpened)
    return p
}

// MARK: Availability

test("Bluetooth off on the Mac: unavailable with a reason, no link even during a call") {
    var p = ready()
    send(&p, .bluetoothPower(on: false))
    check(p.route == .unavailable(reason: "Turn on Bluetooth on this Mac to talk from here."), "\(p.route)")
    check(!send(&p, .phoneCall(.ringing)).contains(.connectLink), "no standby link")
    check(send(&p, .useMac).isEmpty, "useMac does nothing")
    send(&p, .bluetoothPower(on: true))
    check(p.route == .phone, "back to phone once Bluetooth is on")
}

test("Bluetooth not allowed for AirBridge: unavailable; revoking mid-call tears down") {
    var p = onMac()
    let c = send(&p, .bluetoothAccess(false))
    check(c.contains(.disconnectLink) && c.contains(.stopBridge), "\(c)")
    check(p.route == .unavailable(reason: "Allow Bluetooth for AirBridge in System Settings to talk from this Mac."), "\(p.route)")
    check(send(&p, .useMac).isEmpty, "no connect")
}

test("Phone not paired: unavailable, no link") {
    var p = P(bluetoothOn: true, phonePaired: false)
    check(p.route == .unavailable(reason: "Pair your phone with this Mac over Bluetooth to talk from here."), "\(p.route)")
    check(send(&p, .phoneCall(.ringing)).isEmpty, "no standby")
    check(send(&p, .useMac).isEmpty, "no connect")
}

test("Phone reports Bluetooth off: unavailable") {
    var p = ready()
    send(&p, .phoneInfo(bluetoothOn: false, headset: nil))
    check(p.route == .unavailable(reason: "Turn on Bluetooth on your phone to talk from this Mac."), "\(p.route)")
    send(&p, .phoneInfo(bluetoothOn: nil, headset: nil))
    check(p.route == .phone, "unknown (older phone app) counts as available")
}

test("No call: no link, whatever else is true") {
    var p = P(bluetoothOn: false, phonePaired: false)
    var all: [P.Command] = []
    all += send(&p, .bluetoothPower(on: true))
    all += send(&p, .phonePaired(true))
    all += send(&p, .phoneInfo(bluetoothOn: true, headset: nil))
    all += send(&p, .micAllowed(true))
    check(!all.contains(.connectLink), "never connects without a call: \(all)")
    check(p.route == .phone && p.link == .down, "phone, down")
}

// MARK: Standby link for the phone's dialer

test("Call rings: standby link (no audio) so the dialer lists the Mac; ringtone audio refused") {
    var p = ready()
    let c = send(&p, .phoneCall(.ringing))
    check(c.contains(.connectLink), "standby connect: \(c)")
    check(!p.switching && p.route == .phone, "not switching, route phone")
    let u = send(&p, .linkUp(callActive: false, callSetup: 1))
    check(!u.contains(.openAudio), "no audio for standby: \(u)")
    check(u.contains(.armGrace) && p.refusing, "refusing in grace: \(u)")
    send(&p, .graceOver)
    check(p.refusing, "still refusing while ringing (no in-band ringtone)")
    check(send(&p, .audioOpened) == [.closeAudio], "in-band ring audio sent back")
    check(p.route == .phone, "phone")
}

test("Headset on the phone: no standby link and no Mac audio; note names it") {
    var p = ready()
    send(&p, .phoneInfo(bluetoothOn: true, headset: "Galaxy Buds2"))
    check(!send(&p, .phoneCall(.ringing)).contains(.connectLink), "no standby link next to a headset")
    check(p.note == "Your phone is using Galaxy Buds2.", "note: \(p.note ?? "nil")")
    check(p.link == .down, "down")
    // The user can still pick this Mac on purpose.
    check(send(&p, .useMac).contains(.connectLink), "useMac next to a headset connects")
    check(p.link == .connecting, "connecting")
}

test("Headset connects mid-call: audio and standby link both go") {
    var p = onMac()
    let c = send(&p, .phoneInfo(bluetoothOn: true, headset: "Pixel Buds"))
    check(c.contains(.stopBridge) && c.contains(.disconnectLink), "\(c)")
    check(p.route == .phone && p.link == .down && p.note == "Your phone is using Pixel Buds.", "\(p.route)")
    check(send(&p, .phoneInfo(bluetoothOn: true, headset: nil)).contains(.connectLink), "standby again once the headset is gone (call still on)")
}

test("Microphone not allowed: no standby link (the Mac couldn't take the call)") {
    var p = ready()
    send(&p, .micAllowed(false))
    check(!send(&p, .phoneCall(.ringing)).contains(.connectLink), "no standby")
}

test("Answered on the phone: the phone's automatic routing to the Mac is refused (grace window)") {
    var p = ringingWithStandby()
    let c = send(&p, .callIndicators(callActive: true, callSetup: 0))
    check(c.contains(.armGrace), "grace on answer: \(c)")
    check(p.refusing && !c.contains(.refuseIncomingAudio(false)), "still refusing")
    check(send(&p, .audioOpened) == [.closeAudio], "auto-routed audio sent back")
    check(p.route == .phone && p.link == .up, "phone; standby stays for the dialer")
    let g = send(&p, .graceOver)
    check(g == [.refuseIncomingAudio(false)], "after grace the dialer may pick the Mac: \(g)")
}

test("Answer reported by AirBridge before the indicators: grace still starts on the indicators") {
    var p = ringingWithStandby()
    check(!send(&p, .phoneCall(.active)).contains(.refuseIncomingAudio(false)), "no accept yet")
    let c = send(&p, .callIndicators(callActive: true, callSetup: 0))
    check(c.contains(.armGrace), "\(c)")
}

test("User picks the Mac in the phone's dialer: audio accepted, route mac") {
    var p = ringingWithStandby()
    send(&p, .callIndicators(callActive: true, callSetup: 0))
    send(&p, .graceOver)
    let c = send(&p, .audioOpened)
    check(c.contains(.startBridge) && c.contains(.setBridgeMuted(false)), "\(c)")
    check(p.route == .mac && p.wantMac, "route mac")
}

test("User picks earpiece in the dialer: route phone, standby link stays, picking the Mac again works") {
    var p = ringingWithStandby()
    send(&p, .callIndicators(callActive: true, callSetup: 0))
    send(&p, .graceOver)
    send(&p, .audioOpened)
    let c = send(&p, .audioClosed)
    check(c.contains(.stopBridge) && !c.contains(.disconnectLink) && !c.contains(.closeAudio), "\(c)")
    check(p.route == .phone && p.link == .up && p.failure == nil, "phone, link up")
    check(!p.refusing, "no grace after the user's own choice")
    check(send(&p, .audioOpened).contains(.startBridge), "Mac picked again")
    check(p.route == .mac, "mac")
}

test("Call ends: standby link down") {
    var p = ringingWithStandby()
    send(&p, .callIndicators(callActive: true, callSetup: 0))
    let c = send(&p, .callIndicators(callActive: false, callSetup: 0))
    check(c.contains(.disconnectLink), "\(c)")
    check(p.link == .down && p.route == .phone, "down")
    check(send(&p, .phoneCall(.none)).isEmpty, "AirBridge's late 'ended' changes nothing")
}

test("Call ends per AirBridge while the link is up: link down") {
    var p = onMac()
    let c = send(&p, .phoneCall(.none))
    check(c.contains(.stopBridge) && c.contains(.disconnectLink), "\(c)")
    check(p.link == .down && !p.callInProgress, "down")
}

test("Missed call (ringing then nothing): standby link down") {
    var p = ringingWithStandby()
    let c = send(&p, .callIndicators(callActive: false, callSetup: 0))
    check(c.contains(.disconnectLink), "\(c)")
}

test("Standby link can't be set up: no error shown, no retry loop during this call; useMac still retries") {
    var p = ready()
    send(&p, .phoneCall(.ringing))
    let c = send(&p, .linkFailed)
    check(!c.contains(.connectLink), "no retry: \(c)")
    check(p.route == .phone, "no error for a link nobody asked for: \(p.route)")
    check(!send(&p, .phoneCall(.active)).contains(.connectLink), "still no retry")
    check(send(&p, .useMac).contains(.connectLink), "explicit useMac retries")
    send(&p, .callEnded)
    check(send(&p, .phoneCall(.ringing)).contains(.connectLink), "next call tries again")
}

test("Phone drops the standby link (user disconnected the Mac there): no reconnect during this call") {
    var p = ringingWithStandby()
    let c = send(&p, .linkDown)
    check(!c.contains(.connectLink), "\(c)")
    check(p.route == .phone && p.failure == nil, "phone")
}

// MARK: Answered on the Mac / picked on the Mac

test("Answered on the Mac while ringing with standby: audio opens on answer") {
    var p = ringingWithStandby()
    let c1 = send(&p, .useMac)
    check(!c1.contains(.openAudio) && p.switching, "waits for the answer: \(c1)")
    let c2 = send(&p, .callIndicators(callActive: true, callSetup: 0))
    check(c2.contains(.openAudio) && c2.contains(.refuseIncomingAudio(false)), "\(c2)")
    check(!c2.contains(.armGrace), "no grace when the Mac is wanted")
    send(&p, .audioOpened)
    check(p.route == .mac && !p.switching, "mac")
}

test("useMac with no link yet: link, then audio, then bridge") {
    var p = ready()
    send(&p, .phoneCall(.active))
    var p2 = p
    // (standby connect is already under way; useMac rides on it)
    check(p2.link == .connecting, "standby connecting")
    check(send(&p2, .useMac).isEmpty, "nothing extra while connecting")
    let c = send(&p2, .linkUp(callActive: true, callSetup: 0))
    check(c.contains(.openAudio), "\(c)")
    check(send(&p2, .audioOpened).contains(.startBridge), "bridge")
    check(p2.route == .mac, "mac")
    p = p2
}

test("Outgoing call picked for the Mac: audio opens during dialing") {
    var p = ready()
    send(&p, .phoneCall(.active))
    send(&p, .useMac)
    let c = send(&p, .linkUp(callActive: false, callSetup: 2))
    check(c.contains(.openAudio), "\(c)")
}

test("Phone opens audio itself just as we answer on the Mac: accepted as ours") {
    var p = ringingWithStandby()
    send(&p, .useMac)
    send(&p, .callIndicators(callActive: true, callSetup: 0))
    check(send(&p, .audioOpened).contains(.startBridge), "accepted")
    check(send(&p, .audioOpened).isEmpty, "duplicate harmless")
}

// MARK: Switching mid-call

test("Mac -> phone from the Mac: audio closes, standby stays, grace against the phone handing it back") {
    var p = onMac()
    let c = send(&p, .usePhone)
    check(c.contains(.stopBridge) && c.contains(.closeAudio) && c.contains(.armGrace), "\(c)")
    check(!c.contains(.disconnectLink), "standby link kept for the dialer")
    check(p.route == .phone && p.refusing, "phone, refusing")
    check(send(&p, .audioClosed).isEmpty, "late audioClosed ignored")
    check(send(&p, .audioOpened) == [.closeAudio], "an automatic hand-back is refused")
}

test("Phone -> Mac again") {
    var p = onMac()
    send(&p, .usePhone)
    let c = send(&p, .useMac)
    check(c.contains(.openAudio), "opens over the standby link: \(c)")
    send(&p, .audioOpened)
    check(p.route == .mac, "mac")
}

test("User switches back to the phone while the link is still connecting (standby keeps it)") {
    var p = ready()
    send(&p, .phoneCall(.active))
    send(&p, .useMac)
    send(&p, .usePhone)
    let c = send(&p, .linkUp(callActive: true, callSetup: 0))
    check(!c.contains(.openAudio) && c.contains(.armGrace), "standby only: \(c)")
    check(p.route == .phone, "phone")
}

test("Without a call, a late linkUp is dropped") {
    var p = ready()
    check(send(&p, .linkUp(callActive: false, callSetup: 0)) == [.disconnectLink], "dropped")
}

test("Mic mute applies to the bridge, resets when the call ends") {
    var p = ready()
    check(send(&p, .setMicMuted(true)).isEmpty, "remembered")
    send(&p, .phoneCall(.active)); send(&p, .useMac); send(&p, .linkUp(callActive: true, callSetup: 0))
    check(send(&p, .audioOpened).contains(.setBridgeMuted(true)), "applied")
    check(send(&p, .setMicMuted(false)) == [.setBridgeMuted(false)], "unmute")
    send(&p, .setMicMuted(true))
    send(&p, .callEnded)
    check(!p.micMuted, "reset")
}

test("useMac with no call at all: idle timer, then let go") {
    var p = ready()
    send(&p, .useMac)
    let c = send(&p, .linkUp(callActive: false, callSetup: 0))
    check(c.contains(.armIdleTimer) && !c.contains(.openAudio), "\(c)")
    check(send(&p, .idleTimeout).contains(.disconnectLink), "let go")
    check(p.link == .down, "down")
}

test("Idle timeout during ringing does nothing") {
    var p = ringingWithStandby()
    send(&p, .useMac)
    check(send(&p, .idleTimeout).isEmpty, "keeps waiting")
}

// MARK: Failures

test("useMac can't reach the phone: unavailable with a reason; retry clears it") {
    var p = ready()
    send(&p, .phoneCall(.active))
    send(&p, .useMac)
    send(&p, .linkFailed)
    check(p.route == .unavailable(reason: P.sentence(.unreachable)), "\(p.route)")
    check(send(&p, .useMac).contains(.connectLink), "retry")
    check(p.failure == nil, "cleared")
}

test("Link lost while audio is on the Mac: unavailable(linkLost), nothing to disconnect, no reconnect") {
    var p = onMac()
    let c = send(&p, .linkDown)
    check(c.contains(.stopBridge) && !c.contains(.disconnectLink) && !c.contains(.connectLink), "\(c)")
    check(p.route == .unavailable(reason: P.sentence(.linkLost)), "\(p.route)")
    send(&p, .callEnded)
    check(p.route == .phone, "clears when the call ends")
}

test("Audio connection fails: unavailable(audioFailed), standby stays") {
    var p = ringingWithStandby()
    send(&p, .useMac)
    send(&p, .callIndicators(callActive: true, callSetup: 0))
    let c = send(&p, .audioFailed)
    check(!c.contains(.disconnectLink), "\(c)")
    check(p.route == .unavailable(reason: P.sentence(.audioFailed)), "\(p.route)")
}

test("Bridge fails: audio back to the phone with a reason") {
    var p = onMac()
    let c = send(&p, .bridgeFailed)
    check(c.contains(.closeAudio) && c.contains(.stopBridge), "\(c)")
    check(p.route == .unavailable(reason: P.sentence(.bridgeFailed)), "\(p.route)")
}

test("Microphone denied: unavailable with a reason, standby dropped") {
    var p = ringingWithStandby()
    let c = send(&p, .micDenied)
    check(c.contains(.disconnectLink), "\(c)")
    check(p.route == .unavailable(reason: P.sentence(.micDenied)), "\(p.route)")
}

test("Bluetooth turned off mid-call: torn down") {
    var p = onMac()
    let c = send(&p, .bluetoothPower(on: false))
    check(c.contains(.stopBridge) && c.contains(.disconnectLink), "\(c)")
    check(p.link == .down, "down")
}

test("Pairing removed mid-call: torn down") {
    var p = onMac()
    check(send(&p, .phonePaired(false)).contains(.disconnectLink), "down")
}

test("A blank headset name counts as none") {
    var p = ready()
    send(&p, .phoneInfo(bluetoothOn: true, headset: "  "))
    check(p.note == nil && send(&p, .phoneCall(.ringing)).contains(.connectLink), "standby allowed")
}

test("Audio with no link at all is closed") {
    var p = ready()
    check(send(&p, .audioOpened) == [.closeAudio], "closed")
}

print("\n\(passed) checks passed, \(failures) failed")
exit(failures == 0 ? 0 : 1)
