# Comic and Novel Library — Implementation Plan

Status: implementation planning, not yet device-verified.

## Goal

Make the local library usable as two clear reading shelves without replacing the existing Kotatsu-derived storage, indexing, import, or reader pipelines:

- **Comics**: CBZ/ZIP image archives, image folders, and PDF page collections.
- **Novels**: imported EPUB/TXT-converted EPUB and supported downloaded web-novel EPUBs.

The existing local library remains the single source of truth. This feature must not duplicate files, create a second index, or fork reader progress.

## Repository facts verified before implementation

- `ImportDialogFragment` already imports archives, PDFs, EPUB and TXT, plus image folders and TXT folders.
- `LocalMangaParser` already parses image archives/folders, PDF and EPUB.
- `EpubParser` extracts EPUB metadata, spine and TOC.
- `LocalMangaRepository` currently returns one undifferentiated local list.
- Downloaded novels and manga can both carry `index.json`; index presence alone is therefore not a reliable content-type classifier.
- Imported EPUBs have no Kotatsu index and are parsed through the EPUB spine path.
- `LocalManga` retains the backing `File`, so classification can be derived without modifying persisted book metadata.

## Classification contract

Initial conservative classification:

| Backing content | Shelf |
|---|---|
| EPUB file / EPUB collection | Novel |
| CBZ / ZIP / CBR archive | Comic |
| Image directory | Comic |
| PDF | Comic (document-style PDF support remains available) |
| Unknown or unreadable content | Keep visible in All; do not silently discard |

Do not classify based only on the presence or absence of `index.json`. Do not mutate user tags to encode shelf type.

The first implementation should expose **All / Comics / Novels** as a view filter over the existing local list. Switching shelves must preserve the same repository, IDs, reading history, bookmarks, and source linkage.

## UX requirements

1. Keep the current local library as the entry point.
2. Add a compact, accessible shelf selector: All, Comics, Novels.
3. Keep the existing import action available from every shelf.
4. Keep the current list/grid mode, sorting, search, filters, and selection/deletion behavior.
5. Empty states must explain the active shelf and offer the existing import action.
6. Do not add a new bottom-navigation destination until the in-screen shelf selector has been validated.

## Implementation sequence

### Phase 1 — Classification and filtering

- Add a pure local-content classifier based on the backing file type.
- Add unit tests for EPUB, CBZ/ZIP/CBR, PDF, image directories, extension case handling, and unknown files.
- Filter the already indexed local results at the repository boundary; do not scan storage a second time.
- Keep All as the default and preserve existing behavior.

### Phase 2 — Shelf selector

- Add All / Comics / Novels selector to the local library screen.
- Store the selection in saved UI state; do not change global library data.
- Refresh the existing list when the selection changes.
- Ensure search, sort and current list mode remain intact.

### Phase 3 — Comic import and reading acceptance

- Validate CBZ/ZIP, image-folder and PDF imports.
- Verify natural filename ordering (for example, 1, 2, 10), nested chapter folders, cover selection and corrupt archive handling.
- Verify page/scroll modes, RTL, Webtoon and double-page settings remain unchanged.

### Phase 4 — Novel acceptance

- Validate EPUB and TXT-converted EPUB import, metadata, TOC and chapter navigation.
- Verify scroll/page modes, resume position, font/theme settings and Android TTS.
- Verify novel downloads that retain source indexes still map to the original source and chapter IDs.

## Non-goals for this change

- No new reader engine.
- No custom TTS/model integration.
- No online source scraping or new Legado compatibility.
- No file duplication or migration.
- No destructive rewrite of the existing local index.
- No merge into `main` before device acceptance.

## Release gate

- Unit tests pass.
- Debug APK builds.
- Existing import and reader regression tests pass.
- Manual Android acceptance is recorded separately; CI success must not be represented as physical-device validation.
