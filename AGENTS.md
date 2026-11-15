# MG4 Launcher (SWI68) — Project Instructions

## How to Respond
- Always respond in English, using clear language and simple explanations.
- If something is missing (a file, an APK to analyze, or a tool), stop. Do not make things up.
- When asked to explain, only eplain. Do not change code
- When asked to code, first state a plan. Only start coding when the developer has approved the change.
- Interpret approval from the developer's wording and the conversation context. Clear commands such as "make the change", "go ahead", or "implement it" count as approval for the discussed change; the developer does not need to say exactly "Yes, I approve". If approval is already clear, proceed without asking again. Ask for approval and feedback only when permission or scope is unclear.
- Always document code in simple terms, in english and italian.

## Git and GitHub
- Nothing goes on GitHub (code, APKs, README, or `releases/latest.json`) without explicit approval.
- When asked to commit, review the changes and propose a clear, descriptive commit message to the developer. Wait for approval of the proposed message before creating the commit. The initial request to commit does not itself approve the message.
- When asked to push, tell the developer which remote and branch will be pushed, and list the commits to be pushed with their short hashes and messages. Wait for approval of those push details before pushing. The initial request to push does not itself approve the push.
- Commit approval and push approval are separate: approval to commit does not authorize pushing.
- Do not modify old APKs in `releases/` or existing GitHub Releases: keep all of them.

## Secrets and Files Excluded from Git
- `tools/android.jar`, `tools/d8.jar`, `tools/apksig.jar`, `tools/log-token.txt`, and all tokens are excluded from Git
  (see `.gitignore`): never commit them, print them, or write them into repository files.
- Read the logging token from `tools/log-token.txt` or the `MG4_LOG_TOKEN` environment variable.

## README
- Maintain the changelog in Italian and English (the "Versioni" and "Changelog" sections).
- Use version numbers only, without dates.

## For Every New Version
- Increment `VERSION_CODE` and `VERSION_NAME` in `version.properties`.
- Update the README changelog in both Italian and English.

## Variants
- Keep the appearance identical across SWI68, SWI69, SWI132, and SWI133.

## How to Build
Use build-gijsje.sh, first time in spect in, then update this agents.md file with what you hav found.

- Requires Java (JDK 17 or newer), `python3`, `zip`, `unzip`, and `curl`.
- The version is read from `version.properties`.
- The APK is generated at `build/MG4Launcher-<VERSION_NAME>.apk` (the `build/` directory is excluded from Git).
