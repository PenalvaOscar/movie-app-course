import Foundation
import Testing
@testable import EncoreCore

/// Port of Android `ExploreViewModelTest` (7). Virtual time is replaced by `ManualSleeper`:
/// the debounce sleep only returns once the test opens it.
@MainActor
@Suite struct ExploreModelTests {
    private let catalog = FakeMovieCatalog(
        movies: [summary("1", "Northern Lights"), summary("2", "Southern Tide"), summary("3", "Northern Rail")],
        genreList: ["Drama", "Comedy"],
        featured: [summary("2", "Southern Tide")]
    )

    private func model(_ sleeper: ManualSleeper = ManualSleeper(open: true)) -> ExploreModel {
        ExploreModel(catalog: catalog, epochDay: { 20_000 }, sleep: sleeper.sleepFunction)
    }

    private func idle(_ vm: ExploreModel) async {
        await vm.loadTask?.value
    }

    private func content(_ vm: ExploreModel) -> (movies: [MovieSummary], featured: MovieSummary?)? {
        if case let .content(movies, featured) = vm.results { (movies, featured) } else { nil }
    }

    @Test func firstLoadShowsEveryMovieTheGenresAndAFeaturedMovie() async {
        let vm = model()
        #expect(vm.results == .loading)
        await idle(vm)

        #expect(content(vm)?.movies.map(\.id) == ["1", "2", "3"])
        #expect(content(vm)?.featured?.id == "2")
        #expect(vm.genres == ["Drama", "Comedy"])
        #expect(catalog.queries == [MovieQuery()])
    }

    @Test func typingIsDebouncedIntoASingleSearch() async {
        let sleeper = ManualSleeper()
        let vm = model(sleeper)
        await idle(vm)
        catalog.clearQueries()

        vm.setText("N")
        vm.setText("No")
        vm.setText("North")
        #expect(vm.query.text == "North") // the field updates at once
        await settle()
        #expect(catalog.queries.isEmpty)
        #expect(sleeper.requested.allSatisfy { $0 == ExploreModel.searchDebounce })

        sleeper.open()
        await idle(vm)
        #expect(catalog.queries.map(\.text) == ["North"])
        #expect(content(vm)?.movies.map(\.id) == ["1", "3"])
    }

    @Test func featuredMovieIsHiddenWhileSearchingOrFiltering() async {
        let vm = model()
        vm.setText("North")
        await idle(vm)
        #expect(content(vm) != nil)
        #expect(content(vm)?.featured == nil)

        vm.setText("")
        vm.selectGenre("Drama")
        await idle(vm)
        #expect(content(vm)?.featured == nil)

        vm.selectGenre(nil)
        await idle(vm)
        #expect(content(vm)?.featured?.id == "2")
    }

    @Test func genreAndSortChangesSearchWithoutWaitingForTheDebounce() async {
        let sleeper = ManualSleeper() // never opened: a search that waited would never run
        let vm = model(sleeper)
        await idle(vm)
        catalog.clearQueries()

        vm.selectGenre("Comedy")
        await idle(vm)
        vm.selectSort(.rating)
        await idle(vm)

        #expect(catalog.queries == [MovieQuery(genre: "Comedy"), MovieQuery(genre: "Comedy", sort: .rating)])
    }

    @Test func noMatchShowsTheEmptyStateAndClearingFiltersRestoresTheCatalog() async {
        let vm = model()
        vm.setText("zzz")
        vm.selectGenre("Comedy")
        await idle(vm)
        #expect(vm.results == .empty)

        vm.clearFilters()
        await idle(vm)
        #expect(vm.query == MovieQuery())
        #expect(content(vm)?.movies.count == 3)
    }

    @Test func clearingFiltersKeepsTheChosenSortOrder() async {
        let vm = model()
        vm.selectSort(.title)
        vm.selectGenre("Drama")
        vm.clearFilters()
        await idle(vm)
        #expect(vm.query == MovieQuery(sort: .title))
    }

    @Test func aFailingCatalogShowsTheErrorStateAndRetryRecovers() async {
        catalog.failWith = TestError()
        let vm = model()
        await idle(vm)
        #expect(vm.results == .error)

        catalog.failWith = nil
        vm.retry()
        #expect(vm.results == .loading)
        await idle(vm)
        #expect(content(vm)?.movies.count == 3)
        #expect(vm.genres == ["Drama", "Comedy"])
    }

    // iOS addition: a slow, superseded search never overwrites a newer result.
    @Test func aSupersededSearchIsDropped() async {
        let sleeper = ManualSleeper()
        let vm = model(sleeper)
        await idle(vm)

        vm.setText("North")      // waits in the debounce
        vm.selectGenre("Drama")  // still waits: the text is new
        vm.setText("")           // back to the searched text: runs at once
        await idle(vm)
        sleeper.open()
        await settle()

        #expect(vm.query == MovieQuery(genre: "Drama"))
        #expect(catalog.queries.last == MovieQuery(genre: "Drama"))
        #expect(!catalog.queries.contains { $0.text == "North" })
    }
}

/// Port of the catalog half of Android `MovieDetailsViewModelTest` (3). The two rating cases are in `DetailsRatingTests`.
@MainActor
@Suite struct DetailsModelTests {
    private let ratings = RatingRepository(store: InMemoryRatingStore(), clock: { 1 })

    @Test func loadsTheMovieById() async {
        let vm = DetailsModel(movieId: "7", catalog: FakeMovieCatalog(details: ["7": movie("7")]), ratings: ratings)
        #expect(vm.state == .loading)
        await vm.loadTask?.value
        #expect(vm.state == .content(movie("7")))
    }

    @Test func aMovieThatIsNoLongerInTheCatalogShowsNotFound() async {
        let vm = DetailsModel(movieId: "gone", catalog: FakeMovieCatalog(), ratings: ratings)
        await vm.loadTask?.value
        #expect(vm.state == .notFound)
    }

    @Test func aReadFailureShowsTheErrorAndRetryRecovers() async {
        let catalog = FakeMovieCatalog(details: ["7": movie("7")])
        catalog.failWith = TestError()
        let vm = DetailsModel(movieId: "7", catalog: catalog, ratings: ratings)
        await vm.loadTask?.value
        #expect(vm.state == .error)

        catalog.failWith = nil
        vm.retry()
        #expect(vm.state == .loading)
        await vm.loadTask?.value
        #expect(vm.state == .content(movie("7")))
    }

    @Test func sourceLinksComeFromTheLoadedMovie() async {
        var m = movie("7")
        m.datasetSourceUrl = "https://www.kaggle.com/datasets/x/y"
        let vm = DetailsModel(movieId: "7", catalog: FakeMovieCatalog(details: ["7": m]), ratings: ratings)
        await vm.loadTask?.value
        #expect(vm.sources.map(\.kind) == [.wikipedia, .kaggle])
    }
}
