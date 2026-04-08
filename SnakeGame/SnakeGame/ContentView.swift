import SwiftUI

struct ContentView: View {
    @StateObject private var model = GameModel()

    private let teal = Color(red: 0.30, green: 0.80, blue: 0.64)
    private let bg   = Color(red: 0.10, green: 0.10, blue: 0.18)

    var body: some View {
        ZStack {
            bg.ignoresSafeArea()

            VStack(spacing: 0) {
                header
                GameView(model: model)
                    .aspectRatio(1, contentMode: .fit)
                    .padding(.horizontal, 4)
                    .gesture(swipeGesture)
                dpad.padding(.vertical, 14)
            }

            if model.gameState != .playing {
                overlay
            }
        }
        .preferredColorScheme(.dark)
    }

    // MARK: - Header

    private var header: some View {
        HStack {
            scoreBox(label: "SCORE", value: model.score,     color: teal)
            Spacer()
            Text("SNAKE")
                .font(.title.bold())
                .foregroundColor(teal)
                .shadow(color: teal.opacity(0.6), radius: 10)
            Spacer()
            scoreBox(label: "BEST",  value: model.highScore, color: .yellow)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
    }

    private func scoreBox(label: String, value: Int, color: Color) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(.caption2.weight(.semibold))
                .foregroundColor(.gray)
                .tracking(1.5)
            Text("\(value)")
                .font(.title2.bold().monospaced())
                .foregroundColor(color)
                .contentTransition(.numericText())
                .animation(.spring(duration: 0.3), value: value)
        }
    }

    // MARK: - D-Pad

    private var dpad: some View {
        VStack(spacing: 6) {
            arrowBtn(.up,    "chevron.up")
            HStack(spacing: 6) {
                arrowBtn(.left,  "chevron.left")
                arrowBtn(.down,  "chevron.down")
                arrowBtn(.right, "chevron.right")
            }
        }
    }

    private func arrowBtn(_ dir: Direction, _ icon: String) -> some View {
        Button { model.setDirection(dir) } label: {
            Image(systemName: icon)
                .font(.title2.bold())
                .foregroundColor(teal)
                .frame(width: 62, height: 62)
                .background(Color.white.opacity(0.07))
                .clipShape(RoundedRectangle(cornerRadius: 14))
                .overlay(RoundedRectangle(cornerRadius: 14)
                    .stroke(Color.white.opacity(0.1), lineWidth: 1))
        }
    }

    // MARK: - Swipe gesture

    private var swipeGesture: some Gesture {
        DragGesture(minimumDistance: 20)
            .onEnded { v in
                let dx = v.translation.width, dy = v.translation.height
                if abs(dx) > abs(dy) {
                    model.setDirection(dx > 0 ? .right : .left)
                } else {
                    model.setDirection(dy > 0 ? .down : .up)
                }
            }
    }

    // MARK: - Overlay (idle / game-over)

    private var overlay: some View {
        ZStack {
            Color.black.opacity(0.78).ignoresSafeArea()

            VStack(spacing: 22) {
                if model.gameState == .gameOver {
                    Text("GAME OVER")
                        .font(.system(size: 36, weight: .black))
                        .foregroundColor(Color(red: 1, green: 0.38, blue: 0.38))

                    VStack(spacing: 6) {
                        Text("Score: \(model.score)")
                            .font(.title2.monospaced())
                            .foregroundColor(.white)
                        if model.score > 0 && model.score == model.highScore {
                            Label("New High Score!", systemImage: "star.fill")
                                .font(.subheadline.bold())
                                .foregroundColor(.yellow)
                        }
                    }
                } else {
                    VStack(spacing: 10) {
                        Text("SNAKE")
                            .font(.system(size: 52, weight: .black))
                            .foregroundColor(teal)
                            .shadow(color: teal.opacity(0.7), radius: 16)
                        Text("Swipe or use the D-pad to play")
                            .font(.subheadline)
                            .foregroundColor(.gray)
                    }
                }

                Button { model.startGame() } label: {
                    Text(model.gameState == .idle ? "START GAME" : "PLAY AGAIN")
                        .font(.headline.bold())
                        .foregroundColor(bg)
                        .padding(.horizontal, 44)
                        .padding(.vertical, 16)
                        .background(teal)
                        .clipShape(Capsule())
                        .shadow(color: teal.opacity(0.5), radius: 12)
                }
            }
            .padding(32)
        }
    }
}

#Preview {
    ContentView()
}
