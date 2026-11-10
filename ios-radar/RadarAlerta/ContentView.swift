import CoreLocation
import SwiftUI

@main
struct RadarAlertaApp: App {
    @StateObject private var radar = RadarController()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(radar)
                .preferredColorScheme(.dark)
        }
    }
}

private let alertRed = Color(red: 0.9, green: 0.16, blue: 0.16)
private let cardBackground = Color(red: 0.08, green: 0.11, blue: 0.14)

struct ContentView: View {
    @EnvironmentObject var radar: RadarController

    var body: some View {
        ZStack {
            Color(red: 0.04, green: 0.06, blue: 0.08).ignoresSafeArea()
            ScrollView {
                VStack(spacing: 14) {
                    header
                    speedCard
                    controls
                    if radar.authorization == .authorizedWhenInUse && radar.running {
                        Text("Para avisar mesmo se o iOS fechar o app, escolha \"Sempre\" em Ajustes › Radar SP › Localização.")
                            .font(.footnote).foregroundColor(.orange)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    nearbyCard
                    settingsCard
                }
                .padding(16)
            }
            if let alert = radar.alertShown {
                AlertOverlay(ahead: alert) { radar.dismissAlert() }
                    .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: radar.alertShown)
    }

    private var header: some View {
        HStack {
            Circle().fill(radar.running ? Color.green : Color.gray).frame(width: 10, height: 10)
            Text("Radar SP").font(.title2.bold())
            Spacer()
            Text("\(radar.cameraCount) radares").font(.footnote).foregroundColor(.secondary)
        }
    }

    private var speedCard: some View {
        HStack(alignment: .bottom) {
            VStack(alignment: .leading, spacing: 0) {
                Text(radar.speedKmh.map { String($0) } ?? "--")
                    .font(.system(size: 72, weight: .heavy, design: .rounded))
                    .foregroundColor(radar.overLimit ? alertRed : .white)
                    .monospacedDigit()
                Text("km/h").font(.headline).foregroundColor(.secondary)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 6) {
                Text("Radar à frente").font(.footnote).foregroundColor(.secondary)
                if let n = radar.nearest {
                    LimitSign(limit: n.camera.limitKmh).frame(width: 60, height: 60)
                    Text(distanceText(n.distanceMeters)).font(.title.bold())
                } else {
                    Text("livre").font(.title2.bold()).foregroundColor(.green)
                }
            }
        }
        .padding(18)
        .background(cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: 20))
    }

    private var controls: some View {
        VStack(spacing: 10) {
            Button {
                radar.running ? radar.stop() : radar.start()
            } label: {
                Text(radar.running ? "■  Parar alertas" : "▶  Iniciar alertas")
                    .font(.headline).frame(maxWidth: .infinity).padding(.vertical, 16)
                    .background(radar.running ? alertRed : Color.blue)
                    .foregroundColor(.white)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
            }
            HStack(spacing: 10) {
                smallButton("📍 Marcar radar aqui") { radar.markHere() }
                smallButton("🔄 Atualizar base") { radar.updateNow() }
            }
            Text(radar.status).font(.footnote).foregroundColor(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func smallButton(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).font(.subheadline.bold()).frame(maxWidth: .infinity).padding(.vertical, 13)
                .background(Color(red: 0.14, green: 0.19, blue: 0.25)).foregroundColor(.white)
                .clipShape(RoundedRectangle(cornerRadius: 12))
        }
    }

    private var nearbyCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Radares por perto").font(.footnote).foregroundColor(.secondary)
            if radar.nearby.isEmpty {
                Text(radar.running ? "Nenhum radar a menos de 5 km" : "Inicie os alertas para ver")
                    .font(.subheadline).foregroundColor(.secondary)
            }
            ForEach(radar.nearby, id: \.camera.id) { item in
                HStack {
                    Text(item.camera.limitKmh.map { "\($0) km/h" } ?? "Radar").bold()
                    if item.camera.markedByDriver {
                        Text("meu").font(.caption2).padding(.horizontal, 6).padding(.vertical, 2)
                            .background(Color.gray.opacity(0.3)).clipShape(Capsule())
                    }
                    Spacer()
                    Text(distanceText(item.distanceMeters)).foregroundColor(.secondary)
                }
                .font(.subheadline)
            }
        }
        .padding(16).frame(maxWidth: .infinity, alignment: .leading)
        .background(cardBackground).clipShape(RoundedRectangle(cornerRadius: 20))
    }

    private var settingsCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Picker("Avisar a", selection: $radar.alertDistance) {
                ForEach(RadarController.distanceChoices, id: \.self) { Text("\($0) m").tag($0) }
            }
            .pickerStyle(.segmented)
            Toggle("Aviso por voz", isOn: $radar.voiceOn)
            Toggle("Bipe sonoro", isOn: $radar.beepOn)
            Text(updateText).font(.footnote).foregroundColor(.secondary)
            Text("Os radares vêm do OpenStreetMap, uma base colaborativa. A base se atualiza sozinha uma vez por dia ou quando você muda de região. Os avisos continuam com a tela bloqueada, com outro app aberto e no CarPlay.")
                .font(.footnote).foregroundColor(.secondary)
        }
        .padding(16)
        .background(cardBackground).clipShape(RoundedRectangle(cornerRadius: 20))
    }

    private var updateText: String {
        guard let date = radar.lastUpdate else { return "Base ainda não baixada: ela baixa sozinha quando o GPS achar sua posição." }
        let f = RelativeDateTimeFormatter()
        f.locale = Locale(identifier: "pt_BR")
        return "Base atualizada \(f.localizedString(for: date, relativeTo: Date()))"
    }
}

func distanceText(_ meters: Double) -> String {
    if meters >= 1000 { return String(format: "%.1f km", meters / 1000).replacingOccurrences(of: ".", with: ",") }
    return "\(max(10, Int((meters / 10).rounded()) * 10)) m"
}

struct LimitSign: View {
    let limit: Int?
    var body: some View {
        GeometryReader { geo in
            let size = min(geo.size.width, geo.size.height)
            ZStack {
                Circle().fill(Color.white)
                Circle().strokeBorder(alertRed, lineWidth: size * 0.12)
                Text(limit.map { String($0) } ?? "📷")
                    .font(.system(size: size * ((limit ?? 0) >= 100 ? 0.32 : 0.4), weight: .black))
                    .foregroundColor(.black)
            }
            .frame(width: size, height: size)
        }
    }
}

struct AlertOverlay: View {
    let ahead: CameraAhead
    let dismiss: () -> Void
    @State private var pulse = false

    var body: some View {
        ZStack {
            alertRed.opacity(pulse ? 0.97 : 0.8).ignoresSafeArea()
            VStack(spacing: 18) {
                Text("⚠️ RADAR").font(.system(size: 48, weight: .black))
                LimitSign(limit: ahead.camera.limitKmh).frame(width: 150, height: 150)
                Text(distanceText(ahead.distanceMeters)).font(.system(size: 56, weight: .heavy)).monospacedDigit()
                Text("Toque para fechar").font(.footnote).opacity(0.8)
            }
            .foregroundColor(.white)
        }
        .onTapGesture(perform: dismiss)
        .onAppear {
            withAnimation(.easeInOut(duration: 0.5).repeatForever(autoreverses: true)) { pulse = true }
        }
    }
}
