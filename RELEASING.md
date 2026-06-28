# Releasing

Each release has **two** things that must stay in sync: a `CHANGELOG.md` entry and a matching
**GitHub Release**, so the repo's Releases page mirrors the changelog. We use
[Semantic Versioning](https://semver.org) and the [Keep a Changelog](https://keepachangelog.com) format.

Version lives in two places — keep them equal:
- `app/build.gradle.kts` — `versionName` (the SemVer string) and `versionCode` (increment by 1 each release).
- `README.md` — the `# HardwareOne Console (Android) — vX.Y.Z` title line.

The repo is **trunk-based**: work on a short-lived branch, then `git merge --ff-only` into `main` and
push. Never rewrite published history or force-push. Release notes are **public** — no secrets, tokens,
private IPs/hostnames, MAC addresses, Wi-Fi SSIDs, or local file paths; describe changes user-facing.

## Cutting a release

1. **Pick the version (SemVer vs the last release):** MAJOR = breaking, MINOR = backward-compatible
   features, PATCH = fixes/docs. Ask the maintainer if it's ambiguous.
2. **Draft the notes** from `git log <last-tag>..HEAD --oneline`, grouped into
   `### Added / Changed / Fixed / Security` (include only the sections that apply). Lead the entry with
   a one-line theme. Write for someone deciding whether to upgrade, not a commit dump.
3. **Bump the version** in both places above so they agree.
4. **Add the `## [X.Y.Z] — <today>` section** to the top of `CHANGELOG.md`. Get the date from
   `date +%F` — do not guess it.
5. **Commit.** Preferred: content commits (`feat:` / `fix:` / `docs:` …) then a final
   `chore(release): X.Y.Z` carrying the version bump + changelog entry. Branch → `merge --ff-only` →
   push, as above.
6. **Tag** `vX.Y.Z` (leading `v`) and push the tag: `git tag -a vX.Y.Z -m "vX.Y.Z" && git push origin vX.Y.Z`.
7. **Create the GitHub Release**, notes mirroring that version's CHANGELOG body (minus the `##` line).
   Write the body to a file and use `--notes-file` (NOT `--notes`) so backticks/quotes aren't mangled:

   ```sh
   gh release create vX.Y.Z --title "vX.Y.Z — <theme>" --notes-file notes.md
   ```

   This is an app repo with no committed distributable — don't attach a build (and never `git add` an
   APK/zip); GitHub adds the source archive automatically.
8. **Verify:** `gh release view vX.Y.Z --json tagName,assets,url`; confirm the tree is clean and
   `main` == `origin/main`; report the release URL.
