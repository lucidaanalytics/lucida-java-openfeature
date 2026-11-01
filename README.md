# lucida-openfeature (Java)

OpenFeature (Apache-2.0) server provider over the Lucida Java SDK. Install the sibling SDK first (`cd ../java && mvn install`), then register the provider:

```java
OpenFeatureAPI.getInstance().setProviderAndWait(new LucidaProvider(new Lucida("lc_pk_...", opts)));
```

The targeting key is the Lucida distinct id and every other context attribute becomes a person property. Flags evaluate locally; a variant resolves as a string, multivariate flags are "on" for boolean evaluations, and number/object evaluations read the flag payload. Errors: `FLAG_NOT_FOUND`, `TYPE_MISMATCH`, `TARGETING_KEY_MISSING`. Group context is not mapped.

---

This repository is a read-only mirror of the Lucida monorepo (synced by a script, never edited by hand); releases and provenance attestations are built here by GitHub Actions. Issues are welcome, changes land upstream.
