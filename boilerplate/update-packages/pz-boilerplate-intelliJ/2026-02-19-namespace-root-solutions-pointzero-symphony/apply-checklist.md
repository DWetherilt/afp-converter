# Apply Checklist

1. Move Java package directory roots from `com/upland/connect/...` to `solutions/pointzero/symphony/...`.
2. Replace package and import strings from `com.upland.connect...` to `solutions.pointzero.symphony...`.
3. Update Gradle `group` and main-class references to the new namespace.
4. Add/enable hard build gate `enforceNamespaceRoot` and include it in `qualityGate`.
5. Update policy/docs to state the namespace standard.
6. Validate:
   - `./gradlew enforceNamespaceRoot`
   - `./gradlew qualityGate`
