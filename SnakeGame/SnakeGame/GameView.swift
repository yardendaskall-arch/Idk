import SwiftUI

struct GameView: View {
    @ObservedObject var model: GameModel

    private let gridSize  = GameModel.gridSize
    private let bgColor   = Color(red: 0.08, green: 0.08, blue: 0.15)
    private let gridColor = Color.white.opacity(0.04)
    private let snakeHead = Color(red: 0.30, green: 0.80, blue: 0.64)
    private let foodColor = Color(red: 1.00, green: 0.38, blue: 0.38)

    var body: some View {
        Canvas { context, size in
            let cell = min(size.width, size.height) / CGFloat(gridSize)
            let bw   = cell * CGFloat(gridSize)
            let ox   = (size.width  - bw) / 2
            let oy   = (size.height - bw) / 2

            // Board
            context.fill(
                Path(CGRect(x: ox, y: oy, width: bw, height: bw)),
                with: .color(bgColor)
            )

            // Grid lines
            var grid = Path()
            for i in 0...gridSize {
                let x = ox + CGFloat(i) * cell
                let y = oy + CGFloat(i) * cell
                grid.move(to: CGPoint(x: x, y: oy));     grid.addLine(to: CGPoint(x: x, y: oy + bw))
                grid.move(to: CGPoint(x: ox, y: y));     grid.addLine(to: CGPoint(x: ox + bw, y: y))
            }
            context.stroke(grid, with: .color(gridColor), lineWidth: 0.5)

            // Food
            let fi: CGFloat = 3
            let foodRect = CGRect(
                x: ox + CGFloat(model.food.col) * cell + fi,
                y: oy + CGFloat(model.food.row) * cell + fi,
                width: cell - fi * 2, height: cell - fi * 2
            )
            context.fill(Path(ellipseIn: foodRect), with: .color(foodColor))

            // Snake body
            for (i, seg) in model.snake.enumerated() {
                let pad: CGFloat = i == 0 ? 0.5 : 1.5
                let rect = CGRect(
                    x: ox + CGFloat(seg.col) * cell + pad,
                    y: oy + CGFloat(seg.row) * cell + pad,
                    width: cell - pad * 2, height: cell - pad * 2
                )
                let fade   = 1.0 - Double(i) / Double(max(model.snake.count, 1)) * 0.45
                let color  = Color(red: 0.30 * fade, green: 0.80 * fade, blue: 0.64 * fade)
                let radius: CGFloat = i == 0 ? 5 : 3
                context.fill(Path(roundedRect: rect, cornerRadius: radius), with: .color(color))
            }

            // Eyes on head
            if let head = model.snake.first {
                let hx = ox + CGFloat(head.col) * cell
                let hy = oy + CGFloat(head.row) * cell
                let eyeR: CGFloat = cell * 0.14

                let e1: CGPoint
                let e2: CGPoint
                switch model.currentDirection {
                case .right:
                    e1 = CGPoint(x: hx + cell * 0.72, y: hy + cell * 0.28)
                    e2 = CGPoint(x: hx + cell * 0.72, y: hy + cell * 0.72)
                case .left:
                    e1 = CGPoint(x: hx + cell * 0.28, y: hy + cell * 0.28)
                    e2 = CGPoint(x: hx + cell * 0.28, y: hy + cell * 0.72)
                case .up:
                    e1 = CGPoint(x: hx + cell * 0.28, y: hy + cell * 0.28)
                    e2 = CGPoint(x: hx + cell * 0.72, y: hy + cell * 0.28)
                case .down:
                    e1 = CGPoint(x: hx + cell * 0.28, y: hy + cell * 0.72)
                    e2 = CGPoint(x: hx + cell * 0.72, y: hy + cell * 0.72)
                }

                for center in [e1, e2] {
                    let r = CGRect(x: center.x - eyeR, y: center.y - eyeR,
                                   width: eyeR * 2,    height: eyeR * 2)
                    context.fill(Path(ellipseIn: r), with: .color(.black))
                }
            }
        }
    }
}
