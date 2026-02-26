# Repository Guidelines

## Project Structure & Module Organization
This repository is currently documentation-first.
- `docs/`: core design documents (`dynamis_audio_architecture_v11.docx`, `dynamis_voice_lifecycle_v11.docx`).
- `.java-version`: pinned Java runtime version for future tooling consistency.

If application code is added, keep a predictable layout:
- `src/` for production code
- `test/` (or `src/test/`) for automated tests
- `assets/` for static media

Use descriptive file names with domain context (for example, `voice-session-state.md`, `audio-pipeline-spec.md`).

## Build, Test, and Development Commands
No build/test automation is configured yet (no `Makefile`, Gradle/Maven config, or package manifest found).

Current useful commands:
- `ls docs/` - list available design artifacts.
- `git log --oneline` - review change history.
- `java -version` - verify local JDK matches `.java-version`.

When adding executable code, include repository-level commands (for example, `./gradlew test` or `mvn test`) and document them here.

## Coding Style & Naming Conventions
For documentation updates:
- Keep sections short, scannable, and technically precise.
- Prefer lowercase, hyphenated filenames for new Markdown docs.
- Use consistent terminology across architecture and lifecycle docs.

For future source code:
- Use language-standard formatters/linters.
- Keep naming consistent (`PascalCase` for classes, `camelCase` for methods/variables, unless language conventions differ).

## Testing Guidelines
There is no test framework configured yet.

When code is introduced:
- Add automated tests alongside new modules.
- Mirror source paths in test paths.
- Name tests by behavior (example: `AudioRouterRoutesByPriorityTest`).
- Require tests to pass in CI before merge.

## Commit & Pull Request Guidelines
Current Git history is minimal (`initial commit`), so conventions are not yet established.

Use these standards going forward:
- Commit messages: short, imperative subject (example: `docs: clarify voice lifecycle states`).
- PRs: include purpose, changed files, validation steps, and linked issue/ticket.
- For document-only PRs, include a brief summary of architectural impact.

## Security & Configuration Tips
- Do not commit secrets, credentials, or environment-specific config files.
- Treat architecture docs as internal design artifacts; review before sharing externally.
