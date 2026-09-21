@preconcurrency import AVFoundation

@MainActor final class MacSpeechPlayer: NSObject, @preconcurrency AVSpeechSynthesizerDelegate {
    private let synthesizer = AVSpeechSynthesizer()
    private(set) var operationID: UUID?
    private var completion: CheckedContinuation<Void, Never>?

    override init() {
        super.init()
        synthesizer.delegate = self
    }

    func speak(_ text: String, rate: Float = AVSpeechUtteranceDefaultSpeechRate) -> UUID {
        stop()
        let id = UUID()
        operationID = id
        let utterance = AVSpeechUtterance(string: text)
        utterance.rate = rate
        utterance.voice = AVSpeechSynthesisVoice(language: "zh-CN")
        synthesizer.speak(utterance)
        return id
    }

    func speakAndWait(_ text: String, rate: Float = AVSpeechUtteranceDefaultSpeechRate) async {
        _ = speak(text, rate: rate)
        await withCheckedContinuation { continuation in completion = continuation }
    }

    func pause() { synthesizer.pauseSpeaking(at: .immediate) }
    func resume() { synthesizer.continueSpeaking() }
    func stop() { synthesizer.stopSpeaking(at: .immediate); operationID = nil; completion?.resume(); completion = nil }
    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) { operationID = nil; completion?.resume(); completion = nil }
}
