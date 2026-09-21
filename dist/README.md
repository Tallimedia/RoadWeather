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

Rebuild with `./gradlew :automotive:bundleRelease`.

**Every upload needs a new `versionCode`** — Play rejects a repeat, and two
different binaries sharing a number makes a tester's report unplaceable.
