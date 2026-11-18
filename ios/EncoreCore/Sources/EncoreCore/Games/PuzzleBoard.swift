/// A 3×3 tap-to-swap Poster Puzzle board (plan §3.5). `tiles[position]` is the poster piece shown
/// at that position; the board is solved when every piece sits at its own number. Positions and
/// pieces are numbered row by row from the top left.
public struct PuzzleBoard: Equatable, Sendable {
    public static let size = 3
    public static let tileCount = size * size

    public private(set) var tiles: [Int]
    /// The position tapped first, waiting for the tile to swap with.
    public private(set) var selected: Int?

    /// `tiles` must be a permutation of `0..<9`.
    public init(tiles: [Int]) {
        precondition(Self.isValid(tiles), "tiles must be a permutation of 0..<\(Self.tileCount)")
        self.tiles = tiles
    }

    public init?(validating tiles: [Int]) {
        guard Self.isValid(tiles) else { return nil }
        self.tiles = tiles
    }

    /// A random board that is never already solved.
    public static func shuffled(using rng: inout some RandomNumberGenerator) -> PuzzleBoard {
        var tiles = Array(0..<tileCount).shuffled(using: &rng)
        if tiles == Array(0..<tileCount) { tiles.swapAt(0, 1) } // 1 in 9! draws
        return PuzzleBoard(tiles: tiles)
    }

    public var correctCount: Int { tiles.indices.filter { tiles[$0] == $0 }.count }

    /// Correct tiles / 9.
    public var progress: Double { Double(correctCount) / Double(Self.tileCount) }

    public var isSolved: Bool { correctCount == Self.tileCount }

    /// First tap selects, a tap on the selected tile deselects, a tap on another tile swaps the
    /// two. Returns whether tiles were swapped. Ignored once solved or out of range. The VoiceOver
    /// actions "Select" and "Swap with selected" both call this.
    @discardableResult
    public mutating func tap(_ position: Int) -> Bool {
        guard !isSolved, tiles.indices.contains(position) else { return false }
        guard let first = selected else {
            selected = position
            return false
        }
        selected = nil
        guard first != position else { return false }
        tiles.swapAt(first, position)
        return true
    }

    public static func row(of position: Int) -> Int { position / size }

    public static func column(of position: Int) -> Int { position % size }

    private static func isValid(_ tiles: [Int]) -> Bool { tiles.sorted() == Array(0..<tileCount) }
}
