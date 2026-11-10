import Foundation

/// Um radar. `limitKmh` e `directionDegrees` ficam nil quando a fonte não informa.
struct SpeedCamera: Identifiable, Hashable {
    let id: String
    let latitude: Double
    let longitude: Double
    var limitKmh: Int?
    var directionDegrees: Int?
    var markedByDriver: Bool = false
}

/// Uma posição do GPS. Velocidade em m/s e rumo em graus, quando o GPS informa.
struct DrivingFix {
    let latitude: Double
    let longitude: Double
    var speedMetersPerSecond: Double?
    var courseDegrees: Double?

    var speedKmh: Double { (speedMetersPerSecond ?? 0) * 3.6 }
}

enum RadarAlertLevel { case approaching, close }

struct CameraAhead: Equatable {
    let camera: SpeedCamera
    let distanceMeters: Double
}

struct RadarUpdate {
    /// O radar mais próximo à frente, até `RadarEngine.lookaheadMeters`.
    let nearestAhead: CameraAhead?
    /// Um radar que acabou de entrar numa distância de aviso; nil quando nada novo deve tocar.
    let newAlert: (CameraAhead, RadarAlertLevel)?
    /// Verdadeiro enquanto o carro está acima do limite do radar que se aproxima.
    let overLimit: Bool
}

/// Decide quando o aviso toca: uma vez ao entrar em `alertDistanceMeters`, outra em
/// `closeDistanceMeters`, e de novo só depois de se afastar `resetMarginMeters` além do aviso.
/// Em movimento, só contam os radares à frente do carro.
final class RadarEngine {
    var alertDistanceMeters: Double = 400
    let closeDistanceMeters: Double = 150
    let resetMarginMeters: Double = 250
    let aheadAngleDegrees: Double = 50
    let minSpeedForDirectionKmh: Double = 10
    let overLimitToleranceKmh: Int = 3

    static let lookaheadMeters: Double = 3_000
    private static let boxDegrees = 0.02

    private var warned: [String: RadarAlertLevel] = [:]

    func reset() { warned.removeAll() }

    /// Um radar marcado agora não deve avisar na hora.
    func markWarned(_ id: String) { warned[id] = .close }

    func update(cameras: [SpeedCamera], fix: DrivingFix) -> RadarUpdate {
        let resetDistance = alertDistanceMeters + resetMarginMeters
        var nearest: CameraAhead?
        var alert: (CameraAhead, RadarAlertLevel)?
        for camera in cameras {
            if abs(camera.latitude - fix.latitude) > Self.boxDegrees || abs(camera.longitude - fix.longitude) > Self.boxDegrees {
                warned[camera.id] = nil
                continue
            }
            let distance = RadarGeo.distanceMeters(fix.latitude, fix.longitude, camera.latitude, camera.longitude)
            if distance > resetDistance { warned[camera.id] = nil }
            guard isAhead(camera, fix) else { continue }
            let ahead = CameraAhead(camera: camera, distanceMeters: distance)
            if distance <= Self.lookaheadMeters, nearest == nil || distance < nearest!.distanceMeters { nearest = ahead }
            let previous = warned[camera.id]
            let level: RadarAlertLevel?
            if distance <= closeDistanceMeters && previous != .close { level = .close }
            else if distance <= alertDistanceMeters && previous == nil { level = .approaching }
            else { level = nil }
            guard let newLevel = level else { continue }
            warned[camera.id] = newLevel
            if alert == nil || distance < alert!.0.distanceMeters { alert = (ahead, newLevel) }
        }
        var overLimit = false
        if let nearest, let limit = nearest.camera.limitKmh, nearest.distanceMeters <= alertDistanceMeters {
            overLimit = fix.speedKmh > Double(limit + overLimitToleranceKmh)
        }
        return RadarUpdate(nearestAhead: nearest, newAlert: alert, overLimit: overLimit)
    }

    func isAhead(_ camera: SpeedCamera, _ fix: DrivingFix) -> Bool {
        guard let course = fix.courseDegrees, fix.speedKmh >= minSpeedForDirectionKmh else { return true }
        let toCamera = RadarGeo.bearingDegrees(fix.latitude, fix.longitude, camera.latitude, camera.longitude)
        if RadarGeo.angleBetween(toCamera, course) > aheadAngleDegrees { return false }
        guard let direction = camera.directionDegrees else { return true }
        // Radar de um sentido: aceita esse sentido ou o oposto, porque o mapeamento varia.
        let d = Double(direction)
        return RadarGeo.angleBetween(d, course) <= 60 ||
            RadarGeo.angleBetween((d + 180).truncatingRemainder(dividingBy: 360), course) <= 60
    }
}

enum RadarGeo {
    private static let earthRadius = 6_371_000.0
    private static func rad(_ d: Double) -> Double { d * .pi / 180 }

    static func distanceMeters(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
        let x = rad(lon2 - lon1) * cos(rad((lat1 + lat2) / 2))
        let y = rad(lat2 - lat1)
        return (x * x + y * y).squareRoot() * earthRadius
    }

    static func bearingDegrees(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
        let y = sin(rad(lon2 - lon1)) * cos(rad(lat2))
        let x = cos(rad(lat1)) * sin(rad(lat2)) - sin(rad(lat1)) * cos(rad(lat2)) * cos(rad(lon2 - lon1))
        let degrees = atan2(y, x) * 180 / .pi
        return (degrees + 360).truncatingRemainder(dividingBy: 360)
    }

    static func angleBetween(_ a: Double, _ b: Double) -> Double {
        let d = abs((a - b).truncatingRemainder(dividingBy: 360))
        return d > 180 ? 360 - d : d
    }
}

/// Lê linhas `lat,lon,maxspeed,direction`: a resposta CSV do Overpass e o arquivo de radares marcados.
enum RadarCSV {
    private static let compass: [String: Int] = [
        "N": 0, "NNE": 22, "NE": 45, "ENE": 67, "E": 90, "ESE": 112, "SE": 135, "SSE": 157,
        "S": 180, "SSW": 202, "SW": 225, "WSW": 247, "W": 270, "WNW": 292, "NW": 315, "NNW": 337,
    ]

    static func parse(_ text: String, marked: Bool, prefix: String) -> [SpeedCamera] {
        var result: [SpeedCamera] = []
        for (index, raw) in text.split(separator: "\n", omittingEmptySubsequences: false).enumerated() {
            let line = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            if line.isEmpty { continue }
            let fields = line.split(separator: ",", omittingEmptySubsequences: false).map { String($0) }
            guard fields.count >= 2,
                  let lat = Double(fields[0].trimmingCharacters(in: .whitespaces)),
                  let lon = Double(fields[1].trimmingCharacters(in: .whitespaces)),
                  (-90...90).contains(lat), (-180...180).contains(lon), !(lat == 0 && lon == 0) else { continue }
            result.append(SpeedCamera(
                id: "\(prefix)-\(index)-\(String(format: "%.5f,%.5f", lat, lon))",
                latitude: lat,
                longitude: lon,
                limitKmh: parseLimit(fields.count > 2 ? fields[2] : nil),
                directionDegrees: parseDirection(fields.count > 3 ? fields[3] : nil),
                markedByDriver: marked
            ))
        }
        return result
    }

    static func parseLimit(_ raw: String?) -> Int? {
        guard let value = raw?.trimmingCharacters(in: CharacterSet(charactersIn: "\" ")), !value.isEmpty else { return nil }
        let digits = value.prefix { $0.isNumber }
        guard let number = Int(digits) else { return nil }
        let kmh = value.contains("mph") ? Int(Double(number) * 1.609) : number
        return (5...200).contains(kmh) ? kmh : nil
    }

    static func parseDirection(_ raw: String?) -> Int? {
        guard let value = raw?.trimmingCharacters(in: CharacterSet(charactersIn: "\" ")).uppercased(), !value.isEmpty else { return nil }
        if let c = compass[value] { return c }
        guard let first = value.split(separator: ";").first, let number = Double(first) else { return nil }
        let normalized = (number.truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360)
        return Int(normalized)
    }
}
