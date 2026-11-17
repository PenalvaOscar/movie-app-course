# Local catalog data

No movie records, trivia records, posters, archives, or Android catalog assets are shipped in this starter repository. `npm --prefix api run setup` downloads the normalized catalog from the existing public API into ignored `data/movies.json` on your machine. The server reads that file without runtime network access. To use another compatible source, set `CATALOG_SOURCE_URL` when running setup. Restart the server after replacing the file.

`SOURCE-ATTRIBUTION.md` records the external catalog sources. Follow its attribution and license notes when using downloaded content. Keep attribution current if you change sources. Do not commit downloaded data or generated Android catalog assets to this repository.
