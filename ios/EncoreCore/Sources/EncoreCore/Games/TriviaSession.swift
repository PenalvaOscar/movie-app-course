public enum TriviaOutcome: Equatable, Sendable {
    /// `points` includes the streak bonus when it applied.
    case correct(points: Int)
    case wrong(chosen: Int)
    /// Counts as wrong: no points, streak reset (plan §3.5 assumption).
    case timedOut
}

/// One Trivia round as a pure state machine (plan §3.5). It never reads a clock: every call that
/// depends on time takes `now`, in milliseconds on any monotonic clock the caller keeps. Randomness
/// comes from the caller too, so tests replay exact rounds.
///
/// Rules: up to 5 questions drawn without repeats; 15 s per question; 100 points per correct
/// answer, plus 50 for every correct answer while the streak is 3 or more; one 50:50 per round,
/// which hides two random wrong options; a timeout counts as wrong. After each answer the feedback
/// stays 2.5 s, then the next question starts. Holding the feedback, or pausing, freezes that
/// timer; pausing also freezes the question timer and ignores answers.
public struct TriviaSession: Equatable, Sendable {
    public static let questionsPerRound = 5
    public static let questionTimeMs: Int64 = 15_000
    public static let feedbackTimeMs: Int64 = 2_500
    public static let correctPoints = 100
    public static let streakBonus = 50
    public static let streakBonusFrom = 3

    public enum Phase: Equatable, Sendable {
        case question
        case feedback(TriviaOutcome)
        case finished
    }

    public let questions: [Trivia]
    public private(set) var index = 0
    public private(set) var phase: Phase = .question
    public private(set) var score = 0
    public private(set) var streak = 0
    public private(set) var bestStreak = 0
    public private(set) var correctCount = 0
    /// Options the 50:50 hid on the current question.
    public private(set) var hiddenOptions: Set<Int> = []
    public private(set) var fiftyFiftyUsed = false
    /// The view hides the question while paused.
    public private(set) var isPaused = false
    public private(set) var isHeld = false

    /// Time left on the current phase's timer when it was last started or frozen.
    private var timerLeft: Int64 = TriviaSession.questionTimeMs
    /// When the timer was last started; nil while it is frozen.
    private var timerStart: Int64?

    /// Draws the round from `pool`. Nil when the pool is empty.
    public init?(pool: [Trivia], now: Int64, using rng: inout some RandomNumberGenerator) {
        guard !pool.isEmpty else { return nil }
        questions = Array(pool.shuffled(using: &rng).prefix(Self.questionsPerRound))
        timerStart = now
    }

    /// The question on screen (also during its feedback); nil once the round is over.
    public var question: Trivia? { phase == .finished ? nil : questions[index] }

    public var isLastQuestion: Bool { index == questions.count - 1 }

    /// Milliseconds left on the current timer: the countdown during a question, the auto-advance
    /// during feedback. 0 when the round is over.
    public func remaining(at now: Int64) -> Int64 {
        guard phase != .finished else { return 0 }
        guard let timerStart else { return timerLeft }
        return max(0, timerLeft - (now - timerStart))
    }

    /// When the running timer fires (timeout or auto-advance); nil while frozen or finished.
    public var nextDeadline: Int64? {
        guard phase != .finished, let timerStart else { return nil }
        return timerStart + timerLeft
    }

    /// Fires every deadline up to `now`, each at its own time, so a late call (the app was
    /// suspended without pausing) replays what would have happened.
    public mutating func advance(to now: Int64) {
        while let deadline = nextDeadline, deadline <= now {
            switch phase {
            case .question:
                streak = 0
                enterFeedback(.timedOut, at: deadline)
            case .feedback:
                moveOn(at: deadline)
            case .finished:
                return
            }
        }
    }

    public mutating func answer(_ option: Int, at now: Int64) {
        advance(to: now)
        guard phase == .question, !isPaused, let question,
              question.options.indices.contains(option), !hiddenOptions.contains(option)
        else { return }
        if option == question.answerIndex {
            streak += 1
            bestStreak = max(bestStreak, streak)
            correctCount += 1
            let points = Self.correctPoints + (streak >= Self.streakBonusFrom ? Self.streakBonus : 0)
            score += points
            enterFeedback(.correct(points: points), at: now)
        } else {
            streak = 0
            enterFeedback(.wrong(chosen: option), at: now)
        }
    }

    /// Hides two random wrong options on the current question. False when not allowed: already
    /// used this round, paused, or not answering a question.
    @discardableResult
    public mutating func useFiftyFifty(at now: Int64, using rng: inout some RandomNumberGenerator) -> Bool {
        advance(to: now)
        guard phase == .question, !isPaused, !fiftyFiftyUsed, let question else { return false }
        let wrong = question.options.indices.filter { $0 != question.answerIndex }
        hiddenOptions = Set(wrong.shuffled(using: &rng).prefix(2))
        fiftyFiftyUsed = true
        return true
    }

    public mutating func pause(at now: Int64) {
        advance(to: now)
        guard phase != .finished, !isPaused else { return }
        isPaused = true
        freeze(at: now)
    }

    public mutating func resume(at now: Int64) {
        guard isPaused else { return }
        isPaused = false
        if !isHeld { timerStart = now }
    }

    /// Press-and-hold on the feedback: it stays until `release`.
    public mutating func hold(at now: Int64) {
        advance(to: now)
        guard case .feedback = phase, !isHeld else { return }
        isHeld = true
        freeze(at: now)
    }

    /// The feedback timer resumes with the time it had left when held.
    public mutating func release(at now: Int64) {
        guard isHeld else { return }
        isHeld = false
        if !isPaused { timerStart = now }
    }

    /// Leaves the feedback at once (the "Next" button). Ignored while paused.
    public mutating func next(at now: Int64) {
        advance(to: now)
        guard case .feedback = phase, !isPaused else { return }
        moveOn(at: now)
    }

    private mutating func freeze(at now: Int64) {
        timerLeft = remaining(at: now)
        timerStart = nil
    }

    private mutating func enterFeedback(_ outcome: TriviaOutcome, at time: Int64) {
        phase = .feedback(outcome)
        startTimer(Self.feedbackTimeMs, at: time)
    }

    private mutating func moveOn(at time: Int64) {
        isHeld = false
        hiddenOptions = []
        if isLastQuestion {
            phase = .finished
            timerStart = nil
            timerLeft = 0
        } else {
            index += 1
            phase = .question
            startTimer(Self.questionTimeMs, at: time)
        }
    }

    private mutating func startTimer(_ duration: Int64, at time: Int64) {
        timerLeft = duration
        timerStart = isPaused || isHeld ? nil : time
    }
}
