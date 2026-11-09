# lucida-openfeature (Java)

OpenFeature (Apache-2.0) server provider over the Lucida Java SDK.

## Install

From GitHub, [lucidaanalytics/lucida-java-openfeature](https://github.com/lucidaanalytics/lucida-java-openfeature), release `v0.1.1` (JitPack builds the tag; it needs the core SDK too):

```kotlin
// build.gradle.kts
repositories { maven("https://jitpack.io") }
dependencies {
    implementation("com.github.lucidaanalytics:lucida-java:v0.1.1")
    implementation("com.github.lucidaanalytics:lucida-java-openfeature:v0.1.1") {
        exclude(group = "io.github.lucidaanalytics", module = "lucida") // the core comes from JitPack above
    }
}
```

In Maven, add the JitPack repository, both dependencies and an `<exclusion>` of `io.github.lucidaanalytics:lucida` on the provider. Maven Central (`io.github.lucidaanalytics:lucida-openfeature`) is coming.

Then register the provider:

```java
OpenFeatureAPI.getInstance().setProviderAndWait(new LucidaProvider(new Lucida("lc_pk_...", opts)));
```

The targeting key is the Lucida distinct id and every other context attribute becomes a person property. Flags evaluate locally; a variant resolves as a string, multivariate flags are "on" for boolean evaluations, and number/object evaluations read the flag payload. Errors: `FLAG_NOT_FOUND`, `TYPE_MISMATCH`, `TARGETING_KEY_MISSING`. `groups` (type to key) and `groupProperties` (or `group_properties`) feed Lucida groups. Raw Lucida reasons map through `testdata/openfeature/reasons.json` (SPLIT for a matched variant); `flagMetadata` carries `lucidaReason`, `conditionIndex`, `requestId`, `evaluatedLocally`. A client `fallbacks` value resolves with reason DEFAULT, no error code and `lucidaReason` `fallback`. `track(...)` captures a Lucida event (`$value` from the details value). The provider is an `EventProvider`: READY after the first definitions load, CONFIGURATION_CHANGED on reload, STALE when a reload fails, ERROR when the first load fails.

---

This repository is a read-only mirror of the Lucida monorepo (synced by a script, never edited by hand); releases and provenance attestations are built here by GitHub Actions. Issues are welcome, changes land upstream.
