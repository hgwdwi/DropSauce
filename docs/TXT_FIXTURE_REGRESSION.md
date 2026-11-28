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

The TXT import UI now offers six choices: Automatic, UTF-8, UTF-16, GB18030, Shift_JIS, and Windows-31J (CP932). Automatic preserves the existing behavior (UTF-8 first, then GB18030); a UTF-8 or UTF-16 BOM takes precedence over the selected option. Explicit selections use strict decoding and fail rather than silently falling back when the byte sequence is malformed. Shift_JIS and Windows-31J are separate options: Windows-31J/CP932 includes Microsoft extensions that are not part of standard Shift_JIS. The UTF-16 option is most reliable for files carrying a BOM.

For a multi-file selection or TXT-folder import, one encoding choice is applied to all TXT files in that batch. Per-file mixed-encoding detection is not included yet. Non-TXT archives ignore this option. URI permissions are retained only after the user confirms the encoding choice.

## Offset behavior

The V0.2 fixture offsets are relative to decoded source text before newline normalization. They are validated as fixture metadata. \`TxtNovelConverter.Chapter\` currently exposes title and body only; this PR does not introduce a persistent source-offset API or alter EPUB reading-position semantics.

## Run

\`\`\`bash
./gradlew :app:testGithubDebugUnitTest --no-daemon
\`\`\`

All test inputs are synthetic. No user novels, NVL audio, model files, or private voice assets are included.
