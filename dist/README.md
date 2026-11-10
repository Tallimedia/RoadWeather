# Release bundles

Signed with the **upload key**, which lives outside this repo at
`~/Documents/keystores/firoadweather-upload.jks` with its password in
`firoadweather-keystore.properties` beside it. Neither is committed, and
`*.jks` / `keystore.properties` are gitignored so they cannot be.

**Back both up somewhere that outlives the laptop.** Play App Signing holds the
real app signing key, so a lost upload key is recoverable by asking Google to
reset it — but that is a support round-trip, not a command.

| Bundle | versionCode | versionName | Notes |
|---|---|---|---|
| `FIRoadWeather-v0.1.0.aab` | 1 | 0.1.0 | First build. For the **internal test track** only. |
| `FIRoadWeather-v0.8.0.aab` | 15 | 0.8.0 | Rain radar (`RadarScreen`, `MapWithContentTemplate`) — see `CHANGELOG.md` and `claude-docs` `RESEARCH.md` §5.5 for the full build. |
| `FIRoadWeather-v0.8.1.aab` | 16 | 0.8.1 | Crash fix: reopening the radar screen a second time crashed the app (stale `SurfaceCallback` registration). Real-car bug report, reproduced and fixed same day. See `CHANGELOG.md`. |
| `FIRoadWeather-v0.8.2.aab` | 17 | 0.8.2 | **No code changes.** Version-bump-only release for the production track — versionCode 16 was already used on closed testing and Play rejects a repeat, same reason as 0.7.0. |

Rebuild with `./gradlew :automotive:bundleRelease`.

**Every upload needs a new `versionCode`** — Play rejects a repeat, and two
different binaries sharing a number makes a tester's report unplaceable.
