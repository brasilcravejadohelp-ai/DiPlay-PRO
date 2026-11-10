import Foundation

/// Radares do OpenStreetMap salvos no aparelho, para os alertas funcionarem sem internet.
/// A base cobre uma área em volta do carro e se renova uma vez por dia ou quando o carro sai dela.
/// Os radares marcados pelo motorista ficam num arquivo separado que o download nunca apaga.
final class RadarStore {
    private(set) var cameras: [SpeedCamera] = []

    private let folder: URL
    private let downloadedFile: URL
    private let markedFile: URL
    private let defaults = UserDefaults.standard

    private static let halfBoxDegrees = 0.6          // cerca de 65 km para cada lado
    private static let leaveAreaMeters = 40_000.0
    private static let refreshAge: TimeInterval = 24 * 60 * 60
    private static let servers = [
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    ]

    var lastUpdate: Date? {
        let t = defaults.double(forKey: "radar.updated")
        return t > 0 ? Date(timeIntervalSince1970: t) : nil
    }

    init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        folder = base.appendingPathComponent("radar", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        downloadedFile = folder.appendingPathComponent("cameras.csv")
        markedFile = folder.appendingPathComponent("marked.csv")
        load()
    }

    func load() {
        let downloaded = (try? String(contentsOf: downloadedFile, encoding: .utf8)) ?? ""
        let marked = (try? String(contentsOf: markedFile, encoding: .utf8)) ?? ""
        cameras = RadarCSV.parse(downloaded, marked: false, prefix: "osm") +
            RadarCSV.parse(marked, marked: true, prefix: "mine")
    }

    func needsRefresh(_ fix: DrivingFix, now: Date = Date()) -> Bool {
        guard FileManager.default.fileExists(atPath: downloadedFile.path), let last = lastUpdate else { return true }
        if now.timeIntervalSince(last) > Self.refreshAge { return true }
        let lat = defaults.double(forKey: "radar.centerLat")
        let lon = defaults.double(forKey: "radar.centerLon")
        return RadarGeo.distanceMeters(fix.latitude, fix.longitude, lat, lon) > Self.leaveAreaMeters
    }

    /// Baixa os radares em volta de `fix`. Chama `completion` na fila principal.
    func download(around fix: DrivingFix, completion: @escaping (Bool) -> Void) {
        let s = fix.latitude - Self.halfBoxDegrees, n = fix.latitude + Self.halfBoxDegrees
        let w = fix.longitude - Self.halfBoxDegrees, e = fix.longitude + Self.halfBoxDegrees
        let box = String(format: "%.4f,%.4f,%.4f,%.4f", s, w, n, e)
        let query = "[out:csv(::lat,::lon,maxspeed,direction;false;\",\")][timeout:90];" +
            "(node[\"highway\"=\"speed_camera\"](\(box));" +
            "relation[\"type\"=\"enforcement\"][\"enforcement\"~\"maxspeed|average_speed\"](\(box)););" +
            "out center;"
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        let body = "data=" + (query.addingPercentEncoding(withAllowedCharacters: allowed) ?? "")
        tryServer(0, body: body, fix: fix, completion: completion)
    }

    private func tryServer(_ index: Int, body: String, fix: DrivingFix, completion: @escaping (Bool) -> Void) {
        guard index < Self.servers.count, let url = URL(string: Self.servers[index]) else {
            DispatchQueue.main.async { completion(false) }
            return
        }
        var request = URLRequest(url: url, timeoutInterval: 120)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.setValue("RadarSP-iOS", forHTTPHeaderField: "User-Agent")
        request.httpBody = body.data(using: .utf8)
        URLSession.shared.dataTask(with: request) { [weak self] data, response, _ in
            guard let self else { return }
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            guard status == 200, let data, let text = String(data: data, encoding: .utf8) else {
                self.tryServer(index + 1, body: body, fix: fix, completion: completion)
                return
            }
            let parsed = RadarCSV.parse(text, marked: false, prefix: "osm")
            // Uma resposta vazia para uma região inteira é mais provavelmente uma página de erro.
            if parsed.isEmpty && text.count < 50 {
                self.tryServer(index + 1, body: body, fix: fix, completion: completion)
                return
            }
            do {
                try text.write(to: self.downloadedFile, atomically: true, encoding: .utf8)
            } catch {
                self.tryServer(index + 1, body: body, fix: fix, completion: completion)
                return
            }
            self.defaults.set(Date().timeIntervalSince1970, forKey: "radar.updated")
            self.defaults.set(fix.latitude, forKey: "radar.centerLat")
            self.defaults.set(fix.longitude, forKey: "radar.centerLon")
            DispatchQueue.main.async {
                self.load()
                completion(true)
            }
        }.resume()
    }

    /// Salva um radar em `fix` e devolve o radar salvo.
    @discardableResult
    func markHere(_ fix: DrivingFix) -> SpeedCamera? {
        var direction = ""
        if let course = fix.courseDegrees, fix.speedKmh >= 10 { direction = String(Int(course)) }
        let line = String(format: "%.6f,%.6f,,", fix.latitude, fix.longitude) + direction + "\n"
        if let handle = try? FileHandle(forWritingTo: markedFile) {
            handle.seekToEndOfFile()
            handle.write(line.data(using: .utf8)!)
            try? handle.close()
        } else {
            try? line.write(to: markedFile, atomically: true, encoding: .utf8)
        }
        load()
        return cameras.last { $0.markedByDriver }
    }
}
