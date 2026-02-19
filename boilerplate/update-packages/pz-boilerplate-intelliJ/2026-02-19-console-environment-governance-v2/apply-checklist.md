# Apply Checklist
1. Apply files in `package-manifest.json` order.
2. Run `./gradlew pmEnvironmentReady environmentHealthSchemaCheck`.
3. Run `./gradlew pmConsoleCleanupStaleSessions` and review warning threshold behavior.
4. Run `./gradlew :pm-console:classes` and `./pm-console/build/install/pmconsole/bin/pmconsole status`.
5. Run `./gradlew qualityGate`.
