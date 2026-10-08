# Feature Flag Java SDK

Java 21 client for the platform's runtime evaluation API. Build it with your
installed Maven:

```shell
mvn package
```

Artifact coordinates:

```text
com.featureflag:feature-flag-java-sdk:0.1.0-SNAPSHOT
```

Usage:

```java
FeatureFlagClient client = FeatureFlagClient.builder()
        .baseUrl("https://flags.example.com")
        .sdkKey(System.getenv("FEATURE_FLAG_SDK_KEY"))
        .build();

boolean enabled = client.isEnabled(
        "new-checkout",
        "user-123",
        false
);
```

The SDK key is sent only in the `X-Feature-Flag-Key` header. Keep it in a
secret manager or environment variable; never commit it to source control.
The third argument is returned for network failures, timeouts, non-200 responses,
or invalid evaluation responses. A valid response is a single JSON object with a
boolean `enabled` field. Duplicate fields and extra content after the JSON object
cause the SDK to return the default. Unknown fields and surrounding JSON whitespace
are accepted. If present, `flagKey` must match the requested flag and `environment`
must be a nonblank string.
