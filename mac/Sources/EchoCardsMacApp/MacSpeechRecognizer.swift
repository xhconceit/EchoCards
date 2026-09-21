@preconcurrency import Speech
@preconcurrency import AVFoundation
import Foundation

@MainActor final class MacSpeechRecognizer: NSObject, SFSpeechRecognizerDelegate {
    private let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "zh-CN"))
    private let audioEngine = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private(set) var operationID: UUID?

    func authorize() async -> Bool {
        let speechAuthorized: Bool
        switch SFSpeechRecognizer.authorizationStatus() {
        case .authorized: speechAuthorized = true
        case .denied, .restricted: speechAuthorized = false
        case .notDetermined:
            speechAuthorized = await withTaskGroup(of: Bool?.self) { group in
                group.addTask { await withCheckedContinuation { continuation in SFSpeechRecognizer.requestAuthorization { continuation.resume(returning: $0 == .authorized) } } }
                group.addTask { try? await Task.sleep(for: .seconds(3)); return nil }
                return await group.next()! ?? false
            }
        @unknown default: speechAuthorized = false
        }
        guard speechAuthorized else { return false }
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .authorized: return true
        case .denied, .restricted: return false
        case .notDetermined:
            return await withTaskGroup(of: Bool?.self) { group in
                group.addTask { await withCheckedContinuation { continuation in AVCaptureDevice.requestAccess(for: .audio) { continuation.resume(returning: $0) } } }
                group.addTask { try? await Task.sleep(for: .seconds(3)); return nil }
                return await group.next()! ?? false
            }
        @unknown default: return false
        }
    }

    func start(operationID: UUID = UUID(), onResult: @escaping (UUID, String, Bool) -> Void) throws {
        stop()
        guard let recognizer, recognizer.isAvailable else { throw SpeechAdapterError.unavailable }
        let request = SFSpeechAudioBufferRecognitionRequest()
        self.request = request
        self.operationID = operationID
        let input = audioEngine.inputNode
        let format = input.outputFormat(forBus: 0)
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buffer, _ in
            self?.request?.append(buffer)
        }
        audioEngine.prepare()
        try audioEngine.start()
        task = recognizer.recognitionTask(with: request) { [weak self] result, error in
            guard let self, let result, let id = self.operationID else { return }
            let text = result.bestTranscription.formattedString
            Task { @MainActor in onResult(id, text, result.isFinal); if error != nil { self.stop() } }
        }
    }

    func stop() {
        audioEngine.stop(); audioEngine.inputNode.removeTap(onBus: 0); request?.endAudio(); task?.cancel()
        request = nil; task = nil; operationID = nil
    }
}

enum SpeechAdapterError: LocalizedError { case unavailable; var errorDescription: String? { "macOS 语音识别服务不可用" } }
