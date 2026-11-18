import Testing
@testable import EncoreCore

/// Poster Puzzle rules from plan §3.5 (Android has not built it yet): 3×3 tap-to-swap, never
/// solved at the start, progress = correct tiles / 9.
@Suite struct PuzzleBoardTests {
    @Test func aShuffledBoardIsAPermutationAndNeverSolved() {
        for seed in UInt64(0)..<2_000 {
            var rng = SeededRandom(seed: seed)
            let board = PuzzleBoard.shuffled(using: &rng)
            #expect(board.tiles.sorted() == Array(0..<9))
            #expect(!board.isSolved)
        }
    }

    @Test func aRandomSourceThatOnlyYieldsTheSolvedOrderStillGetsAnUnsolvedBoard() {
        var rng = IdentityShuffleRandom()
        #expect(!PuzzleBoard.shuffled(using: &rng).isSolved)
    }

    @Test func tapSelectsThenTheSecondTapSwaps() {
        var board = PuzzleBoard(tiles: [1, 0, 2, 3, 4, 5, 6, 7, 8])

        let firstTapSwapped = board.tap(0)
        #expect(!firstTapSwapped)
        #expect(board.selected == 0)
        let secondTapSwapped = board.tap(1)
        #expect(secondTapSwapped)

        #expect(board.tiles == Array(0..<9))
        #expect(board.selected == nil)
    }

    @Test func tappingTheSelectedTileDeselectsIt() {
        var board = PuzzleBoard(tiles: [1, 0, 2, 3, 4, 5, 6, 7, 8])

        board.tap(4)
        board.tap(4)

        #expect(board.selected == nil)
        #expect(board.tiles == [1, 0, 2, 3, 4, 5, 6, 7, 8])
    }

    @Test func progressCountsTilesInTheirPlace() {
        let board = PuzzleBoard(tiles: [1, 0, 2, 3, 4, 5, 8, 7, 6])

        #expect(board.correctCount == 5)
        #expect(board.progress == 5.0 / 9.0)
    }

    @Test func aSolvedBoardIgnoresTaps() {
        var board = PuzzleBoard(tiles: [1, 0, 2, 3, 4, 5, 6, 7, 8])
        board.tap(0)
        board.tap(1)
        #expect(board.isSolved)
        #expect(board.progress == 1)

        let swapped = board.tap(3)
        #expect(!swapped)
        #expect(board.selected == nil)
    }

    @Test func positionsMapToRowsAndColumns() {
        #expect(PuzzleBoard.row(of: 0) == 0)
        #expect(PuzzleBoard.column(of: 0) == 0)
        #expect(PuzzleBoard.row(of: 5) == 1)
        #expect(PuzzleBoard.column(of: 5) == 2)
        #expect(PuzzleBoard.row(of: 8) == 2)
    }

    @Test func invalidTilesAreRejected() {
        #expect(PuzzleBoard(validating: [0, 1, 2]) == nil)
        #expect(PuzzleBoard(validating: [0, 0, 2, 3, 4, 5, 6, 7, 8]) == nil)
        #expect(PuzzleBoard(validating: Array(0..<9)) != nil)
    }
}

/// The Poster Puzzle screen model: which movie it picks and the actions it forwards.
@MainActor
@Suite struct PuzzleModelTests {
    private func poster(_ id: String, hasPoster: Bool = true) -> MovieSummary {
        MovieSummary(id: id, title: "Film \(id)", year: 2001, director: nil, rating: nil,
                     posterUrl: hasPoster ? "https://image.example/\(id).jpg" : nil)
    }

    private func newModel(_ catalog: FakeMovieCatalog, cached: Set<String>? = nil, seed: UInt64 = 1) async -> PuzzleModel {
        let isCached: (@Sendable (String) async -> Bool)? = cached.map { set in { @Sendable url in set.contains(url) } }
        let vm = PuzzleModel(catalog: catalog, isPosterCached: isCached, random: SeededRandom(seed: seed))
        #expect(await eventually { vm.state != .loading })
        return vm
    }

    private func movieId(_ vm: PuzzleModel) -> String? {
        if case let .ready(movie, _) = vm.state { movie.id } else { nil }
    }

    private func board(_ vm: PuzzleModel) -> PuzzleBoard? {
        if case let .ready(_, board) = vm.state { board } else { nil }
    }

    @Test func picksAMovieWithAPosterAndAnUnsolvedBoard() async {
        let catalog = FakeMovieCatalog(featured: [poster("a"), poster("b", hasPoster: false), poster("c")])

        let vm = await newModel(catalog)

        #expect(["a", "c"].contains(movieId(vm)))
        #expect(board(vm)?.isSolved == false)
    }

    @Test func prefersPostersThatAreAlreadyCached() async {
        let catalog = FakeMovieCatalog(featured: ["a", "b", "c", "d"].map { poster($0) })

        for seed in UInt64(0)..<10 {
            let vm = await newModel(catalog, cached: ["https://image.example/c.jpg"], seed: seed)
            #expect(movieId(vm) == "c")
        }
    }

    @Test func withNothingCachedAnyCandidateCanBePicked() async {
        let catalog = FakeMovieCatalog(featured: ["a", "b"].map { poster($0) })
        var picked = Set<String>()

        for seed in UInt64(0)..<20 {
            if let id = movieId(await newModel(catalog, cached: [], seed: seed)) { picked.insert(id) }
        }

        #expect(picked == ["a", "b"])
    }

    @Test func aNewPuzzleUsesADifferentMovieWhenThereIsOne() async {
        let catalog = FakeMovieCatalog(featured: ["a", "b"].map { poster($0) })
        let vm = await newModel(catalog)
        let first = movieId(vm)

        vm.newPuzzle()

        #expect(await eventually { movieId(vm) != nil && movieId(vm) != first })
        #expect(board(vm)?.isSolved == false)
    }

    @Test func aLoneCachedPosterIsNotRepeatedWhenAnotherCandidateExists() async {
        let catalog = FakeMovieCatalog(featured: ["a", "b"].map { poster($0) })
        let vm = await newModel(catalog, cached: ["https://image.example/a.jpg"])
        #expect(movieId(vm) == "a")

        vm.newPuzzle()

        #expect(await eventually { movieId(vm) == "b" })
    }

    @Test func noCandidatesMeansThePuzzleIsUnavailable() async {
        #expect(await newModel(FakeMovieCatalog(featured: [poster("a", hasPoster: false)])).state == .unavailable)
    }

    @Test func aReadFailureShowsTheErrorStateAndRetryRecovers() async {
        let catalog = FakeMovieCatalog(featured: [poster("a")])
        catalog.failWith = TestError()
        let vm = await newModel(catalog)
        #expect(vm.state == .error)

        catalog.failWith = nil
        vm.retry()

        #expect(await eventually { movieId(vm) == "a" })
    }

    @Test func tapsReachTheBoard() async throws {
        let vm = await newModel(FakeMovieCatalog(featured: [poster("a")]))
        let start = try #require(board(vm))
        let misplaced = try #require(start.tiles.indices.first { start.tiles[$0] != $0 })
        let home = try #require(start.tiles.firstIndex(of: misplaced))

        vm.tap(misplaced)
        #expect(board(vm)?.selected == misplaced)
        vm.tap(home)

        #expect(board(vm)?.tiles[misplaced] == misplaced)
        #expect(board(vm)!.correctCount > start.correctCount)
    }
}

/// Makes `shuffled(using:)` keep every element in place. It returns 1, not 0: the standard
/// library's unbiased `next(upperBound:)` rejects 0 for every bound and would draw forever, while
/// 1 × bound has a zero high word (index 0) and a low word no smaller than the rejection threshold.
struct IdentityShuffleRandom: RandomNumberGenerator {
    mutating func next() -> UInt64 { 1 }
}
