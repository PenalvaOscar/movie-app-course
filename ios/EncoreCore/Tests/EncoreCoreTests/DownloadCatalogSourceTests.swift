import Foundation
import Testing
@testable import EncoreCore

/// `DownloadCatalogSource` against a stubbed `URLProtocol`: no network is touched.
/// Serialized because the stub's canned reply is shared state.
@MainActor
@Suite(.serialized) struct DownloadCatalogSourceTests {
    final class StubProtocol: URLProtocol {
        struct Reply: Sendable {
            var status: Int
            var body: Data
            var declaredLength: Bool = true
        }

        nonisolated(unsafe) static var reply = Reply(status: 200, body: Data())
        nonisolated(unsafe) static var lastRequest: URLRequest?

        override class func canInit(with request: URLRequest) -> Bool { true }
        override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

        override func startLoading() {
            Self.lastRequest = request
            let reply = Self.reply
            var headers = ["Content-Type": "application/json"]
            if reply.declaredLength { headers["Content-Length"] = String(reply.body.count) }
            let response = HTTPURLResponse(url: request.url!, statusCode: reply.status, httpVersion: "HTTP/1.1", headerFields: headers)!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            // Deliver in chunks so progress has something to report.
            var offset = 0
            while offset < reply.body.count {
                let end = min(offset + 100_000, reply.body.count)
                client?.urlProtocol(self, didLoad: reply.body.subdata(in: offset..<end))
                offset = end
            }
            client?.urlProtocolDidFinishLoading(self)
        }

        override func stopLoading() {}
    }

    private func source(maxBytes: Int = DownloadCatalogSource.defaultMaxBytes) -> DownloadCatalogSource {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        return DownloadCatalogSource(url: URL(string: "https://catalog.test/catalog")!, session: URLSession(configuration: config), maxBytes: maxBytes)
    }

    @Test func returnsTheBodyAndReportsProgressUpToComplete() async throws {
        let body = Data(repeating: 0x20, count: 1_000_000)
        StubProtocol.reply = .init(status: 200, body: body)
        var progress: [Double] = []

        let data = try await source().fetch { progress.append($0) }

        #expect(data == body)
        #expect(progress.first == 0)
        #expect(progress.last == 1)
        #expect(zip(progress, progress.dropFirst()).allSatisfy { $0 <= $1 })
        #expect(StubProtocol.lastRequest?.value(forHTTPHeaderField: "Accept") == "application/json")
    }

    @Test func anUnknownLengthReportsNoProgress() async throws {
        StubProtocol.reply = .init(status: 200, body: Data("{}".utf8), declaredLength: false)
        var progress: [Double] = []
        let data = try await source().fetch { progress.append($0) }
        #expect(data == Data("{}".utf8))
        #expect(progress.isEmpty)
    }

    @Test func aNon200StatusIsAServerError() async {
        StubProtocol.reply = .init(status: 503, body: Data("busy".utf8))
        await #expect(throws: CatalogSourceError.httpStatus(503)) { try await source().fetch { _ in } }
    }

    @Test func aBodyOverTheCapIsRefused() async {
        StubProtocol.reply = .init(status: 200, body: Data(repeating: 0x20, count: 200_000))
        await #expect(throws: CatalogSourceError.tooLarge(limit: 100_000)) { try await source(maxBytes: 100_000).fetch { _ in } }
    }

    @Test func anUndeclaredBodyOverTheCapIsRefusedWhileStreaming() async {
        StubProtocol.reply = .init(status: 200, body: Data(repeating: 0x20, count: 200_000), declaredLength: false)
        await #expect(throws: CatalogSourceError.tooLarge(limit: 100_000)) { try await source(maxBytes: 100_000).fetch { _ in } }
    }

    @Test func aMissingBundledResourceIsAbsentNotAnError() async throws {
        let missing = FileManager.default.temporaryDirectory.appendingPathComponent("no-such-catalog-\(UUID().uuidString).json")
        #expect(try await BundledCatalogSource(url: missing).fetch { _ in } == nil)
        #expect(try await BundledCatalogSource(url: nil).fetch { _ in } == nil)
    }
}
