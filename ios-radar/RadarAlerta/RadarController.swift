import AVFoundation
import CoreLocation
import Foundation
import UIKit
import UserNotifications

/// Junta GPS, base de radares, regras de aviso, som, voz e notificações.
/// Com "Iniciar", o GPS segue em segundo plano (tela bloqueada, outro app ou CarPlay na frente)
/// até o motorista tocar em "Parar".
@MainActor
final class RadarController: NSObject, ObservableObject {
    @Published var running = false
    @Published var speedKmh: Int?
    @Published var nearest: CameraAhead?
    @Published var overLimit = false
    @Published var alertShown: CameraAhead?
    @Published var status = "Toque em Iniciar antes de sair."
    @Published var cameraCount = 0
    @Published var lastUpdate: Date?
    @Published var nearby: [CameraAhead] = []
    @Published var authorization: CLAuthorizationStatus = .notDetermined

    @Published var alertDistance: Int = UserDefaults.standard.object(forKey: "alertDistance") as? Int ?? 400 {
        didSet { UserDefaults.standard.set(alertDistance, forKey: "alertDistance"); engine.alertDistanceMeters = Double(alertDistance) }
    }
    @Published var voiceOn: Bool = UserDefaults.standard.object(forKey: "voiceOn") as? Bool ?? true {
        didSet { UserDefaults.standard.set(voiceOn, forKey: "voiceOn") }
    }
    @Published var beepOn: Bool = UserDefaults.standard.object(forKey: "beepOn") as? Bool ?? true {
        didSet { UserDefaults.standard.set(beepOn, forKey: "beepOn") }
    }

    static let distanceChoices = [200, 300, 400, 600, 800]

    private let location = CLLocationManager()
    private let store = RadarStore()
    private let engine = RadarEngine()
    private let speech = AVSpeechSynthesizer()
    private var players: [AVAudioPlayer] = []
    private var lastFix: DrivingFix?
    private var downloading = false
    private var lastDownloadAttempt = Date.distantPast
    private var lastOverLimitTone = Date.distantPast
    private var hideAlertWork: DispatchWorkItem?

    private lazy var beepFar = Tone.wav(frequency: 1050, milliseconds: 150, square: true)
    private lazy var beepClose = Tone.wav(frequency: 1400, milliseconds: 110, square: true)
    private lazy var beepOver = Tone.wav(frequency: 2000, milliseconds: 90, square: true)
    private lazy var beepOk = Tone.wav(frequency: 1500, milliseconds: 120, square: false)

    override init() {
        super.init()
        location.delegate = self
        location.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        location.activityType = .automotiveNavigation
        location.distanceFilter = kCLDistanceFilterNone
        location.pausesLocationUpdatesAutomatically = false
        speech.delegate = self
        engine.alertDistanceMeters = Double(alertDistance)
        authorization = location.authorizationStatus
        refreshCounts()
        if UserDefaults.standard.bool(forKey: "wasRunning") { start() }
    }

    // MARK: - Iniciar / parar

    func start() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
        switch location.authorizationStatus {
        case .notDetermined:
            location.requestWhenInUseAuthorization()
            UserDefaults.standard.set(true, forKey: "wasRunning")
            return
        case .denied, .restricted:
            status = "Localização negada. Ative em Ajustes › Radar SP › Localização › Sempre."
            return
        default:
            break
        }
        if location.authorizationStatus == .authorizedWhenInUse {
            // "Sempre" mantém os alertas mesmo se o iOS fechar o app em segundo plano.
            location.requestAlwaysAuthorization()
        }
        location.allowsBackgroundLocationUpdates = true
        location.showsBackgroundLocationIndicator = true
        location.startUpdatingLocation()
        location.startMonitoringSignificantLocationChanges()
        running = true
        UserDefaults.standard.set(true, forKey: "wasRunning")
        UIApplication.shared.isIdleTimerDisabled = true
        status = "Procurando GPS…"
        speak("Alertas de radar ativados")
    }

    func stop() {
        location.stopUpdatingLocation()
        location.stopMonitoringSignificantLocationChanges()
        location.allowsBackgroundLocationUpdates = false
        running = false
        UserDefaults.standard.set(false, forKey: "wasRunning")
        UIApplication.shared.isIdleTimerDisabled = false
        status = "Alertas pausados."
        speedKmh = nil
    }

    func updateNow() {
        guard let fix = lastFix else {
            status = "Inicie os alertas para saber onde baixar os radares."
            return
        }
        download(fix, forced: true)
    }

    func markHere() {
        guard let fix = lastFix else {
            status = "Ainda sem posição do GPS para marcar."
            return
        }
        if let camera = store.markHere(fix) { engine.markWarned(camera.id) }
        play(beepOk)
        status = "Radar salvo aqui ✔"
        refreshCounts()
    }

    func dismissAlert() { alertShown = nil }

    // MARK: - GPS

    private func handle(_ loc: CLLocation) {
        guard loc.horizontalAccuracy >= 0, loc.horizontalAccuracy < 80, -loc.timestamp.timeIntervalSinceNow < 10 else { return }
        let fix = DrivingFix(
            latitude: loc.coordinate.latitude,
            longitude: loc.coordinate.longitude,
            speedMetersPerSecond: loc.speed >= 0 ? loc.speed : nil,
            courseDegrees: loc.course >= 0 ? loc.course : nil
        )
        lastFix = fix
        speedKmh = fix.speedMetersPerSecond.map { Int(($0 * 3.6).rounded()) }
        if status == "Procurando GPS…" { status = "GPS ok · \(store.cameras.count) radares na base" }

        if store.needsRefresh(fix) { download(fix, forced: false) }

        let update = engine.update(cameras: store.cameras, fix: fix)
        nearest = update.nearestAhead
        overLimit = update.overLimit
        if let alert = update.newAlert { warn(alert.0, alert.1) }
        if let shown = alertShown, let n = update.nearestAhead, shown.camera.id == n.camera.id { alertShown = n }
        if update.overLimit, Date().timeIntervalSince(lastOverLimitTone) > 4 {
            lastOverLimitTone = Date()
            playSequence(beepOver, count: 2, gap: 0.15)
        }
        if UIApplication.shared.applicationState == .active {
            nearby = store.cameras
                .map { CameraAhead(camera: $0, distanceMeters: RadarGeo.distanceMeters(fix.latitude, fix.longitude, $0.latitude, $0.longitude)) }
                .filter { $0.distanceMeters < 5_000 }
                .sorted { $0.distanceMeters < $1.distanceMeters }
                .prefix(6).map { $0 }
        }
    }

    private func download(_ fix: DrivingFix, forced: Bool) {
        guard !downloading else { return }
        if !forced && Date().timeIntervalSince(lastDownloadAttempt) < 600 { return }
        downloading = true
        lastDownloadAttempt = Date()
        if forced { status = "Atualizando a base de radares…" }
        store.download(around: fix) { [weak self] ok in
            guard let self else { return }
            self.downloading = false
            self.refreshCounts()
            if ok {
                self.status = "Base atualizada · \(self.store.cameras.count) radares"
            } else if forced {
                self.status = "Sem conexão com o servidor. Usando a base salva."
            }
        }
    }

    private func refreshCounts() {
        cameraCount = store.cameras.count
        lastUpdate = store.lastUpdate
    }

    // MARK: - Avisos

    private func warn(_ ahead: CameraAhead, _ level: RadarAlertLevel) {
        let meters = max(10, Int((ahead.distanceMeters / 10).rounded()) * 10)
        switch level {
        case .approaching: playSequence(beepFar, count: 3, gap: 0.25)
        case .close: playSequence(beepClose, count: 4, gap: 0.17)
        }
        let sentence: String
        if let limit = ahead.camera.limitKmh { sentence = "Radar a \(meters) metros, limite \(limit)" }
        else { sentence = "Radar a \(meters) metros" }
        if level == .approaching && voiceOn {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.9) { [weak self] in self?.speak(sentence) }
        }
        if UIApplication.shared.applicationState == .active {
            alertShown = ahead
            hideAlertWork?.cancel()
            let work = DispatchWorkItem { [weak self] in self?.alertShown = nil }
            hideAlertWork = work
            DispatchQueue.main.asyncAfter(deadline: .now() + 6, execute: work)
        } else {
            notify(sentence)
        }
    }

    private func notify(_ text: String) {
        let content = UNMutableNotificationContent()
        content.title = "⚠️ RADAR"
        content.body = text
        content.sound = nil   // o bipe já tocou
        if #available(iOS 15.0, *) { content.interruptionLevel = .active }
        let request = UNNotificationRequest(identifier: "radar-alert", content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
        DispatchQueue.main.asyncAfter(deadline: .now() + 20) {
            UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ["radar-alert"])
        }
    }

    // MARK: - Som

    /// Abaixa a música (ou o CarPlay) enquanto o aviso toca, e devolve o volume depois.
    private func activateAudio() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .voicePrompt, options: [.duckOthers, .interruptSpokenAudioAndMixWithOthers])
        try? session.setActive(true)
    }

    private func deactivateAudioLater() {
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { [weak self] in
            guard let self, !self.speech.isSpeaking, !self.players.contains(where: { $0.isPlaying }) else { return }
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }
    }

    private func play(_ data: Data) {
        guard beepOn else { return }
        activateAudio()
        guard let player = try? AVAudioPlayer(data: data) else { return }
        player.volume = 1
        players.removeAll { !$0.isPlaying }
        players.append(player)
        player.play()
        deactivateAudioLater()
    }

    private func playSequence(_ data: Data, count: Int, gap: Double) {
        for i in 0..<count {
            DispatchQueue.main.asyncAfter(deadline: .now() + Double(i) * gap) { [weak self] in self?.play(data) }
        }
    }

    private func speak(_ text: String) {
        guard voiceOn else { return }
        activateAudio()
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = AVSpeechSynthesisVoice(language: "pt-BR")
        utterance.rate = 0.52
        speech.stopSpeaking(at: .immediate)
        speech.speak(utterance)
    }
}

extension RadarController: CLLocationManagerDelegate {
    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let last = locations.last else { return }
        Task { @MainActor in self.handle(last) }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let current = manager.authorizationStatus
        Task { @MainActor in self.authorizationChanged(current) }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        Task { @MainActor in
            if self.running { self.status = "GPS sem sinal… tentando de novo." }
        }
    }
}

extension RadarController {
    fileprivate func authorizationChanged(_ current: CLAuthorizationStatus) {
        authorization = current
        let wanted = UserDefaults.standard.bool(forKey: "wasRunning")
        switch current {
        case .authorizedAlways, .authorizedWhenInUse:
            if wanted && !running { start() }
        case .denied, .restricted:
            if running { stop() }
            status = "Localização negada. Ative em Ajustes › Radar SP › Localização › Sempre."
        default:
            break
        }
    }
}

extension RadarController: AVSpeechSynthesizerDelegate {
    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        Task { @MainActor in self.deactivateAudioLater() }
    }
}

/// Bipes gerados em memória (WAV), sem arquivos de áudio no app.
enum Tone {
    static func wav(frequency: Double, milliseconds: Int, square: Bool) -> Data {
        let rate = 44_100
        let count = rate * milliseconds / 1000
        var samples = [Int16](repeating: 0, count: count)
        let fade = max(1, rate / 200)
        for i in 0..<count {
            let phase = sin(2 * .pi * frequency * Double(i) / Double(rate))
            var v = square ? (phase >= 0 ? 1.0 : -1.0) : phase
            let edge = min(i, count - 1 - i)
            if edge < fade { v *= Double(edge) / Double(fade) }
            samples[i] = Int16(v * 0.6 * Double(Int16.max))
        }
        var data = Data()
        func append<T: FixedWidthInteger>(_ value: T) { withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) } }
        let dataSize = UInt32(count * 2)
        data.append(contentsOf: Array("RIFF".utf8)); append(UInt32(36) + dataSize)
        data.append(contentsOf: Array("WAVE".utf8))
        data.append(contentsOf: Array("fmt ".utf8)); append(UInt32(16)); append(UInt16(1)); append(UInt16(1))
        append(UInt32(rate)); append(UInt32(rate * 2)); append(UInt16(2)); append(UInt16(16))
        data.append(contentsOf: Array("data".utf8)); append(dataSize)
        samples.forEach { append($0) }
        return data
    }
}
