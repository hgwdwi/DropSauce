# TXT Novel Import Regression Tests

This PR adds the V0.2 synthetic novel fixtures to the real TsubakiReader TXT-to-EPUB converter tests.

## Coverage

- UTF-8, UTF-8 BOM, UTF-16 BOM, GB18030, and explicitly selected Shift_JIS decoding.
- Chinese chapter, volume, episode, extra-story, preface/epilogue/postscript headings.
- Japanese \`プロローグ\`, \`エピローグ\`, and \`第１話\` headings.
- False-positive prose such as \`第一章的内容很有趣\` (without terminal punctuation).
- Empty input, CRLF, full-width digits, emoji and supplementary Unicode characters.
- Source fixture offsets are checked in code points and JVM UTF-16 code units.

## Charset behavior

The import flow still uses the existing automatic decoder: UTF-8, UTF-8 BOM, UTF-16 BOM, and GB18030 fallback. The converter now also accepts an explicit \`Charset\` override for callers that know the source encoding, including Shift_JIS. This PR does not add an encoding picker to the import UI, so Shift_JIS is testable through the converter API but is not yet selectable by end users.

## Offset behavior

The V0.2 fixture offsets are relative to decoded source text before newline normalization. They are validated as fixture metadata. \`TxtNovelConverter.Chapter\` currently exposes title and body only; this PR does not introduce a persistent source-offset API or alter EPUB reading-position semantics.

## Run

\`\`\`bash
./gradlew :app:testGithubDebugUnitTest --no-daemon
\`\`\`

All test inputs are synthetic. No user novels, NVL audio, model files, or private voice assets are included.
