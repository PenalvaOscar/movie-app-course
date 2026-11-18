/// Owns a long-running task and cancels it when the owner is released. A screen model keeps one
/// per subscription, so its `for await` loop ends with the model instead of on the next update
/// (a `@MainActor` class's plain `deinit` cannot touch its isolated state, and this avoids
/// depending on `isolated deinit` with the iOS 17 floor).
final class TaskHandle {
    var task: Task<Void, Never>? {
        willSet { task?.cancel() }
    }

    deinit { task?.cancel() }
}
