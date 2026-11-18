import Observation

public enum TriviaState: Equatable, Sendable {
    case loading
    /// The catalog has no trivia questions.
    case unavailable
    case error
    case ready(TriviaSession)
}

/// The Trivia screen (plan §3.5): owns one `TriviaSession`, feeds it the time and forwards the
/// player's actions. A ticker wakes at the next deadline or the next whole second of the
/// countdown, whichever comes first, so `secondsLeft` changes exactly once per second.
///
/// The view pauses the round when the scene leaves `.active` (I9) and keeps this model alive
/// behind the leave-game confirmation, so a round survives both (C15).
@MainActor
@Observable
public final class TriviaModel {
    public private(set) var state: TriviaState = .loading
    /// Whole seconds left on the question countdown (rounded up). Keeps its value during feedback,
    /// except after a timeout, where it shows 0.
    public private(set) var secondsLeft = Int(TriviaSession.questionTimeMs / 1_000)

    @ObservationIgnored private let catalog: any MovieCatalog
    @ObservationIgnored private let clock: @MainActor () -> Int64
    @ObservationIgnored private let sleep: @Sendable (Duration) async throws -> Void
    @ObservationIgnored private var random: any RandomNumberGenerator
    @ObservationIgnored private let logError: @MainActor (String, any Error) -> Void
    @ObservationIgnored private var pool: [Trivia]?
    @ObservationIgnored private let tickTask = TaskHandle()
    @ObservationIgnored private let loadTask = TaskHandle()

    /// `clock` returns milliseconds on a monotonic clock (default: `ContinuousClock`).
    public init(
        catalog: any MovieCatalog,
        clock: (@MainActor () -> Int64)? = nil,
        sleep: (@Sendable (Duration) async throws -> Void)? = nil,
        random: some RandomNumberGenerator = SystemRandomNumberGenerator(),
        logError: (@MainActor (String, any Error) -> Void)? = nil
    ) {
        self.catalog = catalog
        self.clock = clock ?? monotonicMilliseconds()
        self.sleep = sleep ?? { try await Task.sleep(for: $0) }
        self.random = random
        self.logError = logError ?? Log.gamesError
        load()
    }

    public func answer(_ option: Int) { update { s, now in s.answer(option, at: now) } }

    public func useFiftyFifty() { update { s, now in s.useFiftyFifty(at: now, using: &random) } }

    public func pause() { update { s, now in s.pause(at: now) } }

    public func resume() { update { s, now in s.resume(at: now) } }

    public func hold() { update { s, now in s.hold(at: now) } }

    public func release() { update { s, now in s.release(at: now) } }

    public func next() { update { s, now in s.next(at: now) } }

    /// A new round from the questions already read.
    public func playAgain() { startRound() }

    public func retry() {
        if pool == nil { load() } else { startRound() }
    }

    /// Applies the time now; the ticker calls it, and tests call it after moving their clock.
    func tick() { update { s, now in s.advance(to: now) } }

    /// Whether a ticker is scheduled (tests: none while paused, held or finished).
    var isTicking: Bool { tickTask.task != nil }

    private func load() {
        state = .loading
        let catalog = catalog
        loadTask.task = Task { [weak self] in
            do {
                let questions = try await catalog.triviaQuestions()
                guard !Task.isCancelled, let self else { return }
                pool = questions
                startRound()
            } catch {
                guard !Task.isCancelled, let self else { return }
                logError("Couldn't read trivia", error)
                state = .error
            }
        }
    }

    private func startRound() {
        guard let pool else { return }
        let now = clock()
        guard let session = TriviaSession(pool: pool, now: now, using: &random) else {
            tickTask.task = nil
            state = .unavailable
            return
        }
        secondsLeft = Int(TriviaSession.questionTimeMs / 1_000)
        publish(session, now: now)
    }

    private func update(_ change: (inout TriviaSession, Int64) -> Void) {
        guard case var .ready(session) = state else { return }
        let now = clock()
        // The countdown as it stands now, before an answer freezes it into the feedback display.
        if session.phase == .question { secondsLeft = Self.wholeSeconds(session.remaining(at: now)) }
        change(&session, now)
        publish(session, now: now)
    }

    private func publish(_ session: TriviaSession, now: Int64) {
        state = .ready(session)
        let left = session.remaining(at: now)
        switch session.phase {
        case .question: secondsLeft = Self.wholeSeconds(left)
        case .feedback(.timedOut): secondsLeft = 0
        case .feedback, .finished: break
        }
        schedule(session, now: now)
    }

    private static func wholeSeconds(_ milliseconds: Int64) -> Int { Int((milliseconds + 999) / 1_000) }

    private func schedule(_ session: TriviaSession, now: Int64) {
        guard let deadline = session.nextDeadline else {
            tickTask.task = nil
            return
        }
        var delay = deadline - now
        if session.phase == .question, delay > 0 {
            delay -= ((delay - 1) / 1_000) * 1_000 // time until the displayed second changes
        }
        let sleep = sleep
        tickTask.task = Task { [weak self] in
            try? await sleep(.milliseconds(max(delay, 0)))
            guard !Task.isCancelled else { return }
            self?.tick()
        }
    }
}

/// Milliseconds since the first call, on `ContinuousClock`.
private func monotonicMilliseconds() -> @MainActor () -> Int64 {
    let clock = ContinuousClock()
    let start = clock.now
    return {
        let elapsed = (clock.now - start).components
        return elapsed.seconds * 1_000 + elapsed.attoseconds / 1_000_000_000_000_000
    }
}
