# NovaBird Mail

App module of NovaBird Mail, a fork of Thunderbird for Android. It is based on `app-thunderbird` (flavor `foss` only)
and uses its own application ID `org.novabird.mail` (debug: `org.novabird.mail.debug`), name and logo (swallow with envelope, see `feature/novabird/branding/artwork`).

## Differences to `app-thunderbird`

- No Thunderbird OAuth client IDs (`NbOAuthConfigurationFactory` is empty), no telemetry, no funding, no migration from
  Thunderbird, Thundermail disabled (`thundermail:k9mail`).
- New accounts sync all messages of the last year (`NovaBirdAccountDefaultsProvider`).
- The Compose screens (welcome, account setup) show the NovaBird logo via `NovaBirdBoltTheme` from
  `feature:novabird:branding`.
- Sync limits for mobile data and Wi-Fi and completing partially downloaded messages on Wi-Fi come from
  `feature:novabird:sync`. The settings screen is reachable via a launcher shortcut (long-press the app icon) and via
  the system's app info screen.

## Changes to upstream code

Keep these small so that merging new release tags stays easy:

- `settings.gradle.kts`: includes `:app-novabird`, `:feature:novabird:branding` and `:feature:novabird:sync`.
- `legacy/core/.../controller/MessagingController.java` (`createSyncConfig`): uses `SyncDownloadLimits` (new file
  `SyncDownloadLimitProvider.kt`) instead of the account setting directly.
- `app-common/.../account/AppCommonAccountModule.kt`: an app may bind its own `AccountDefaultsProvider` as `named("app")`.

## Build

```bash
./gradlew --no-build-cache :app-novabird:assembleFossDebug
```

