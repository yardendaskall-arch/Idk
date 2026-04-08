import Foundation
import UIKit

enum Direction {
    case up, down, left, right

    var opposite: Direction {
        switch self {
        case .up:    return .down
        case .down:  return .up
        case .left:  return .right
        case .right: return .left
        }
    }
}

enum GameState {
    case idle, playing, gameOver
}

struct GridPoint: Equatable, Hashable {
    var col: Int
    var row: Int
}

class GameModel: ObservableObject {
    static let gridSize = 20

    @Published var snake: [GridPoint] = []
    @Published var food: GridPoint = GridPoint(col: 10, row: 10)
    @Published var score: Int = 0
    @Published var highScore: Int = UserDefaults.standard.integer(forKey: "snakeHS")
    @Published var gameState: GameState = .idle
    @Published var currentDirection: Direction = .right

    private var nextDirection: Direction = .right
    private var timer: Timer?

    func startGame() {
        let mid = GameModel.gridSize / 2
        snake = [
            GridPoint(col: mid,     row: mid),
            GridPoint(col: mid - 1, row: mid),
            GridPoint(col: mid - 2, row: mid),
        ]
        currentDirection = .right
        nextDirection    = .right
        score = 0
        gameState = .playing
        placeFood()
        scheduleTimer()
    }

    func setDirection(_ dir: Direction) {
        guard dir != currentDirection.opposite else { return }
        nextDirection = dir
    }

    private func scheduleTimer() {
        timer?.invalidate()
        // scheduledTimer on the main run loop fires on the main thread
        timer = Timer.scheduledTimer(withTimeInterval: 0.15, repeats: true) { [weak self] _ in
            self?.step()
        }
    }

    private func step() {
        currentDirection = nextDirection
        guard let head = snake.first else { return }

        var next = head
        switch currentDirection {
        case .up:    next.row -= 1
        case .down:  next.row += 1
        case .left:  next.col -= 1
        case .right: next.col += 1
        }

        let size = GameModel.gridSize
        guard next.col >= 0 && next.col < size &&
              next.row >= 0 && next.row < size else {
            endGame(); return
        }

        // Self-collision: skip the last tail segment that's about to vacate
        if snake.dropLast().contains(next) {
            endGame(); return
        }

        snake.insert(next, at: 0)

        if next == food {
            score += 10
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            placeFood()
        } else {
            snake.removeLast()
        }
    }

    private func placeFood() {
        let occupied = Set(snake)
        var candidate: GridPoint
        repeat {
            candidate = GridPoint(
                col: Int.random(in: 0..<GameModel.gridSize),
                row: Int.random(in: 0..<GameModel.gridSize)
            )
        } while occupied.contains(candidate)
        food = candidate
    }

    private func endGame() {
        timer?.invalidate()
        timer = nil
        if score > highScore {
            highScore = score
            UserDefaults.standard.set(highScore, forKey: "snakeHS")
        }
        UINotificationFeedbackGenerator().notificationOccurred(.error)
        gameState = .gameOver
    }
}
