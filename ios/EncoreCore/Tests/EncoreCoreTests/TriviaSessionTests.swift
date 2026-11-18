import Testing
@testable import EncoreCore

/// Trivia rules from plan §3.5 (Android has not built Trivia yet). Times are milliseconds on the
/// session's own clock; the session is pure, so every test drives time explicitly.
@Suite struct TriviaSessionTests {
    /// Fictional questions; the right answer sits at `answer`.
    private func q(_ id: String, answer: Int = 0) -> Trivia {
        Trivia(id: id, movieId: "m\(id)", kind: "year", question: "Question \(id)?",
               options: ["A", "B", "C", "D"], answerIndex: answer, clue: nil)
    }

    private func pool(_ count: Int) -> [Trivia] { (0..<count).map { q("\($0)", answer: $0 % 4) } }

    private func session(_ count: Int = 5, seed: UInt64 = 1, now: Int64 = 0) throws -> TriviaSession {
        var rng = SeededRandom(seed: seed)
        return try #require(TriviaSession(pool: pool(count), now: now, using: &rng))
    }

    private func right(_ s: TriviaSession) -> Int { s.question!.answerIndex }
    private func wrong(_ s: TriviaSession) -> Int { (s.question!.answerIndex + 1) % 4 }

    /// Answers the current question at `now` and skips the feedback.
    private func play(_ s: inout TriviaSession, correct: Bool, at now: Int64 = 0) {
        s.answer(correct ? right(s) : wrong(s), at: now)
        s.next(at: now)
    }

    // MARK: Round setup

    @Test func aRoundHasFiveDistinctQuestionsFromThePool() throws {
        let s = try session(12)

        #expect(s.questions.count == 5)
        #expect(Set(s.questions.map(\.id)).count == 5)
        #expect(s.questions.allSatisfy { Set(pool(12).map(\.id)).contains($0.id) })
        #expect(s.index == 0)
        #expect(s.phase == .question)
        #expect(s.remaining(at: 0) == 15_000)
    }

    @Test func aSmallPoolGivesAShorterRoundAndAnEmptyPoolNone() throws {
        #expect(try session(3).questions.count == 3)
        var rng = SeededRandom(seed: 1)
        #expect(TriviaSession(pool: [], now: 0, using: &rng) == nil)
    }

    @Test func theSameSeedDrawsTheSameRound() throws {
        #expect(try session(20, seed: 7).questions == session(20, seed: 7).questions)
    }

    // MARK: Scoring

    @Test func aCorrectAnswerScores100AndShowsFeedback() throws {
        var s = try session()

        s.answer(right(s), at: 4_000)

        #expect(s.phase == .feedback(.correct(points: 100)))
        #expect(s.score == 100)
        #expect(s.streak == 1)
        #expect(s.correctCount == 1)
    }

    @Test func aWrongAnswerScoresNothingAndResetsTheStreak() throws {
        var s = try session()
        play(&s, correct: true)

        let chosen = wrong(s)
        s.answer(chosen, at: 0)

        #expect(s.phase == .feedback(.wrong(chosen: chosen)))
        #expect(s.score == 100)
        #expect(s.streak == 0)
    }

    @Test func theStreakBonusStartsAtTheThirdConsecutiveCorrectAnswer() throws {
        var s = try session()
        var points: [Int] = []
        for _ in 0..<4 {
            s.answer(right(s), at: 0)
            if case let .feedback(.correct(p)) = s.phase { points.append(p) }
            s.next(at: 0)
        }

        #expect(points == [100, 100, 150, 150])
        #expect(s.score == 500)
        #expect(s.bestStreak == 4)
    }

    @Test func aBrokenStreakMustBeRebuiltBeforeTheBonusReturns() throws {
        var s = try session()
        for correct in [true, true, false, true, true] { play(&s, correct: correct) }

        #expect(s.score == 400)
        #expect(s.bestStreak == 2)
        #expect(s.phase == .finished)
    }

    // MARK: Timer

    @Test func aTimeoutCountsAsWrongAndResetsTheStreak() throws {
        var s = try session()
        play(&s, correct: true)

        s.advance(to: 14_999)
        #expect(s.phase == .question)
        s.advance(to: 15_000)

        #expect(s.phase == .feedback(.timedOut))
        #expect(s.streak == 0)
        #expect(s.score == 100)
    }

    @Test func anAnswerAfterTheDeadlineIsATimeout() throws {
        var s = try session()

        s.answer(right(s), at: 15_001)

        #expect(s.phase == .feedback(.timedOut))
        #expect(s.score == 0)
    }

    @Test func feedbackAdvancesAfterTwoAndAHalfSecondsWithAFullTimer() throws {
        var s = try session()
        s.answer(right(s), at: 1_000)

        s.advance(to: 3_499)
        #expect(s.index == 0)
        s.advance(to: 3_500)

        #expect(s.index == 1)
        #expect(s.phase == .question)
        #expect(s.remaining(at: 3_500) == 15_000)
    }

    @Test func holdingKeepsTheFeedbackAndReleaseResumesTheRest() throws {
        var s = try session()
        s.answer(right(s), at: 1_000)

        s.hold(at: 2_000)
        s.advance(to: 100_000)
        #expect(s.index == 0)
        s.release(at: 100_000)
        s.advance(to: 101_499)
        #expect(s.index == 0)
        s.advance(to: 101_500)

        #expect(s.index == 1)
    }

    @Test func nextSkipsTheFeedback() throws {
        var s = try session()
        s.answer(right(s), at: 1_000)

        s.next(at: 1_200)

        #expect(s.index == 1)
        #expect(s.remaining(at: 1_200) == 15_000)
    }

    @Test func pauseFreezesTheTimer() throws {
        var s = try session()

        s.pause(at: 5_000)
        s.advance(to: 60_000)
        #expect(s.phase == .question)
        #expect(s.remaining(at: 60_000) == 10_000)
        #expect(s.nextDeadline == nil)
        s.resume(at: 60_000)
        s.advance(to: 69_999)
        #expect(s.phase == .question)
        s.advance(to: 70_000)

        #expect(s.phase == .feedback(.timedOut))
    }

    @Test func whilePausedAnswersAndFiftyFiftyAreIgnored() throws {
        var s = try session()
        var rng = SeededRandom(seed: 3)
        s.pause(at: 1_000)

        s.answer(right(s), at: 2_000)
        #expect(!s.useFiftyFifty(at: 2_000, using: &rng))

        #expect(s.isPaused)
        #expect(s.phase == .question)
        #expect(!s.fiftyFiftyUsed)
    }

    @Test func pauseDuringFeedbackFreezesTheAutoAdvance() throws {
        var s = try session()
        s.answer(right(s), at: 0)

        s.pause(at: 1_000)
        s.next(at: 1_000) // ignored while paused
        s.advance(to: 50_000)
        #expect(s.index == 0)
        s.resume(at: 50_000)
        s.advance(to: 51_500)

        #expect(s.index == 1)
    }

    @Test func pausedAndHeldTogetherNeedBothUndoneInEitherOrder() throws {
        for resumeFirst in [true, false] {
            var s = try session()
            s.answer(right(s), at: 0) // feedback until 2_500
            s.hold(at: 500)
            s.pause(at: 1_000)
            if resumeFirst { s.resume(at: 10_000) } else { s.release(at: 10_000) }
            s.advance(to: 50_000)
            #expect(s.index == 0)
            #expect(s.nextDeadline == nil)

            if resumeFirst { s.release(at: 50_000) } else { s.resume(at: 50_000) }

            #expect(s.nextDeadline == Int64(52_000)) // 2_000 were left when held
        }
    }

    @Test func severalMissedDeadlinesAreProcessedInOrder() throws {
        var s = try session()

        s.advance(to: 15_000 + 2_500 + 15_000)

        #expect(s.index == 1)
        #expect(s.phase == .feedback(.timedOut))
        #expect(s.nextDeadline == Int64(15_000 + 2_500 + 15_000 + 2_500))
    }

    // MARK: 50:50

    @Test func fiftyFiftyRemovesTwoWrongOptionsOncePerRound() throws {
        var s = try session()
        var rng = SeededRandom(seed: 9)

        #expect(s.useFiftyFifty(at: 0, using: &rng))
        #expect(s.hiddenOptions.count == 2)
        #expect(!s.hiddenOptions.contains(right(s)))
        #expect(!s.useFiftyFifty(at: 0, using: &rng))

        play(&s, correct: true)
        #expect(s.hiddenOptions.isEmpty)
        #expect(s.fiftyFiftyUsed)
        #expect(!s.useFiftyFifty(at: 0, using: &rng))
    }

    @Test func aHiddenOptionCannotBeAnswered() throws {
        var s = try session()
        var rng = SeededRandom(seed: 9)
        _ = s.useFiftyFifty(at: 0, using: &rng)
        let hidden = try #require(s.hiddenOptions.first)

        s.answer(hidden, at: 0)
        s.answer(7, at: 0) // out of range

        #expect(s.phase == .question)
    }

    // MARK: Round end

    @Test func theRoundEndsAfterTheLastFeedbackAndThenIgnoresInput() throws {
        var s = try session()
        for _ in 0..<4 { play(&s, correct: true) }
        s.answer(right(s), at: 0)
        #expect(s.isLastQuestion)
        s.advance(to: 2_500)

        #expect(s.phase == .finished)
        #expect(s.question == nil)
        #expect(s.nextDeadline == nil)
        #expect(s.correctCount == 5)
        let finished = s
        s.answer(0, at: 3_000)
        s.pause(at: 3_000)
        s.advance(to: 90_000)
        #expect(s == finished)
    }
}
