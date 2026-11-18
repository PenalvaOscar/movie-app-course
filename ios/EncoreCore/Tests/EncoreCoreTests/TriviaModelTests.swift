import Testing
@testable import EncoreCore

/// The Trivia screen model: loading, the countdown ticker and the actions it forwards to
/// `TriviaSession`. The clock is a `TestClock` and the sleeper stays closed, so time moves only
/// when a test sets it and calls `tick()`.
@MainActor
@Suite struct TriviaModelTests {
    private let catalog = FakeMovieCatalog()
    private let clock = TestClock(0)
    private let sleeper = ManualSleeper()

    init() {
        catalog.trivia = (0..<8).map {
            Trivia(id: "t\($0)", movieId: "m\($0)", kind: "year", question: "Question \($0)?",
                   options: ["A", "B", "C", "D"], answerIndex: $0 % 4, clue: nil)
        }
    }

    private func newModel() async -> TriviaModel {
        let clock = clock
        let vm = TriviaModel(catalog: catalog, clock: { clock.read() }, sleep: sleeper.sleepFunction, random: SeededRandom(seed: 5))
        #expect(await eventually { vm.state != .loading })
        return vm
    }

    private func session(_ vm: TriviaModel) -> TriviaSession? {
        if case let .ready(session) = vm.state { session } else { nil }
    }

    @Test func loadsTheQuestionsAndStartsARound() async throws {
        let vm = await newModel()

        let s = try #require(session(vm))
        #expect(s.questions.count == 5)
        #expect(s.phase == .question)
        #expect(vm.secondsLeft == 15)
    }

    @Test func noQuestionsMeansTriviaIsUnavailable() async {
        catalog.trivia = []

        #expect(await newModel().state == .unavailable)
    }

    @Test func aReadFailureShowsTheErrorStateAndRetryRecovers() async {
        catalog.failWith = TestError()
        let vm = await newModel()
        #expect(vm.state == .error)

        catalog.failWith = nil
        vm.retry()

        #expect(await eventually { session(vm) != nil })
    }

    @Test func theTickerWakesAtTheNextWholeSecond() async {
        let vm = await newModel()
        #expect(await eventually { sleeper.requested.first == .milliseconds(1_000) })

        clock.now = 1_000
        vm.tick()
        #expect(vm.secondsLeft == 14)
        clock.now = 14_200
        vm.tick()

        #expect(vm.secondsLeft == 1)
        #expect(await eventually { sleeper.requested.last == .milliseconds(800) })
    }

    @Test func runningOutOfTimeShowsTheTimeout() async {
        let vm = await newModel()

        clock.now = 15_000
        vm.tick()

        #expect(session(vm)?.phase == .feedback(.timedOut))
        #expect(vm.secondsLeft == 0)
    }

    @Test func actionsReachTheSession() async throws {
        let vm = await newModel()
        let answer = try #require(session(vm)?.question?.answerIndex)

        vm.useFiftyFifty()
        #expect(session(vm)?.hiddenOptions.count == 2)
        clock.now = 2_000
        vm.answer(answer)
        #expect(session(vm)?.score == 100)
        vm.hold()
        clock.now = 60_000
        vm.tick()
        #expect(session(vm)?.index == 0)
        vm.release()
        vm.next()
        #expect(session(vm)?.index == 1)

        clock.now = 61_000
        vm.pause()
        clock.now = 99_000
        vm.tick()
        #expect(vm.secondsLeft == 14)
        #expect(session(vm)?.isPaused == true)
        vm.resume()
        #expect(session(vm)?.isPaused == false)
    }

    @Test func pauseStopsTheTickerAndResumeRestartsIt() async {
        let vm = await newModel()
        #expect(vm.isTicking)

        vm.pause()
        #expect(!vm.isTicking)
        vm.resume()

        #expect(vm.isTicking)
    }

    @Test func secondsLeftHoldsDuringFeedbackAndResetsForTheNextQuestion() async throws {
        let vm = await newModel()
        clock.now = 4_500
        vm.answer(try #require(session(vm)?.question?.answerIndex))
        #expect(vm.secondsLeft == 11)

        clock.now = 7_000
        vm.tick()

        #expect(session(vm)?.index == 1)
        #expect(vm.secondsLeft == 15)
    }

    @Test func playAgainStartsAFreshRoundWithoutReadingAgain() async {
        let vm = await newModel()
        clock.now = 2_000
        vm.answer(session(vm)!.question!.answerIndex)

        vm.playAgain()

        #expect(session(vm)?.score == 0)
        #expect(session(vm)?.index == 0)
        #expect(vm.secondsLeft == 15)
        #expect(catalog.triviaLoads == 1)
    }
}
