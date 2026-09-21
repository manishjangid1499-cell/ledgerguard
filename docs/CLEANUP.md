# Production cleanup audit

Scope: the five Maven modules, React/Vite frontend, migrations, tests, scripts,
CI, containers, deployment configuration, assets, and documentation. Existing
uncommitted feature and authentication changes are outside this cleanup.

## Inventory recorded before removal

| Classification | Candidate | Evidence and decision |
| --- | --- | --- |
| SAFE TO REMOVE | Root `-files --others --exclude-standard` | Accidentally captured Git warnings and file listing. Repository-wide search finds only its Docker ignore entry; no build, runtime, script, or documentation consumer. Remove both the dump and that obsolete ignore entry. |
| SAFE TO REMOVE | 88 Java import declarations in 44 files | Each imported simple name occurs only in its import declaration, including searches of comments and annotations. Remove declarations only; retain all classes, methods, annotations, and test assertions. |
| SAFE TO REMOVE | `micrometer-tracing-test` in API and notification worker | No test tracer or assertion classes used anywhere. The resolved JAR contains helper classes without service registration or Spring auto-configuration. Existing tracing tests exercise Kafka headers and MDC using production tracing dependencies. |
| SAFE TO REMOVE | `spring-kafka-test` in notification worker | Tests use real Kafka Testcontainers and Kafka client APIs. No embedded broker annotations, test utilities, or enabling configuration exists. Its service-loaded global embedded broker listener is disabled by default; its Spring customizer requires an absent `@EmbeddedKafka` annotation. |
| SAFE TO REMOVE | `testcontainers-junit-jupiter` in failure lab and E2E | These modules start and stop containers explicitly and do not use the extension annotations. The resolved JAR has no automatic service registration. Retain the extension in API, PSP, and worker where annotations use it. |
| SAFE TO REMOVE | Direct `awaitility` declaration in failure lab | No Awaitility APIs or configuration used in this module, including the benchmark profile. The Spring test starter retains its own transitive dependency; other modules' direct Awaitility use remains intact. |
| REQUIRED | Frontend source, public SVG, runtime dependencies | Entry-point traversal includes lazy routes and reaches all source modules. The SVG is the HTML favicon. Emotion is required by MUI even without direct application imports. |
| REQUIRED | Spring components, repositories, entities, DTOs, migrations | Component/repository scanning, scheduling, Kafka listeners, Jackson, JPA, and Flyway load these indirectly. Preserve all migration history and public API contracts. |
| REQUIRED | `SnapshotFaultInjector` and its validation/resource handling | Called by `RealCorruptedSnapshotScenario`, registered by `FailureLabApplication`, and exercised by `CorruptedSnapshotScenarioTest` and `FailureLabSuiteRunnerTest`. All imports and parameters are used. Connection validation precedes the restricted parameterized update; callers check that exactly one row changed. Retain the file unchanged, including its safety documentation. |
| KEEP FOR TOOLING/DEPLOYMENT | Maven wrapper, npm lockfile, both `.nvmrc` files, Docker/Compose, Nginx, monitoring, scripts, CI, benchmark profiles, E2E reactor dependencies | Used by their respective tooling or documented operational workflows; imports alone do not describe their dependencies. Maven has no dependency lockfile in this repository. |
| KEEP FOR TOOLING/DEPLOYMENT | `docs/diagrams/.gitkeep`, certificate directory placeholder | Intentionally reserved directories. Architectural diagrams currently also exist inline in Markdown; this does not prove the reserved directory is obsolete. |
| UNCERTAIN — DO NOT DELETE | Dependency-check suppressions; unused public convenience methods and accessors | Manual security tooling and external/internal API consumers cannot be excluded confidently. Keep these rather than changing compatibility or security behavior. |

Candidates initially considered probably unused were promoted only after the
reference, framework-loading, and resolved-library checks above. No production
dependency, test case, migration, route, environment variable, or domain logic
was removed.

## Validation

| Check | Result |
| --- | --- |
| Baseline `mvnw.cmd -B -ntp clean verify` | Maven reports BUILD SUCCESS: 792 tests, zero failures/errors/skips (707 API, 18 PSP, 22 worker, 34 lab, 11 E2E). PowerShell treated Mockito's standard-error warning as a shell error; the Maven reactor and test reports succeeded. |
| Final `mvnw.cmd -B -ntp clean verify` recheck | Passed with exit 0: all 792 tests, zero failures/errors/skips. Includes packaged-service startup, authentication/authorization, Flyway migrations, financial workflows, and Kafka/PSP integration coverage. |
| Financial-integrity CI commands | `mvnw.cmd -B -ntp -pl backend/ledgerguard-api -am install -DskipTests`, then `mvnw.cmd -B -ntp -f backend/failure-lab/pom.xml clean test -Pfinancial-failure-ci`: passed, five tests and all four scenario invariant verdicts VERIFIED. |
| Frontend `npm.cmd run lint` and `npm.cmd run build`, before and after | Passed. Build includes TypeScript checking and the Vite production bundle. |
| `mvnw.cmd -B -ntp dependency:tree`, before and after | Passed. Identical compile/runtime dependency coordinates and versions in every module; no added or upgraded test dependencies. Worker test classpath loses 44 artifacts. |
| Packaged runtime libraries | Unchanged library names/versions: 148 API, 74 PSP, and 96 worker libraries. |
| Second source/dependency scan | All 77 frontend modules remain reachable, including dynamic imports. No further Java imports with zero local references found across 453 Java files. |
| Source and diff review | Edited Java files differ only in import declarations; all other pre-existing files match the saved working-tree baseline. `git diff --check` passes. |
| Bash `-n` and PowerShell parser checks | All six shell scripts and three PowerShell scripts parse. |
| `docker compose config --quiet`, development and production | Passed with temporary process-local placeholder credentials. Real environment files were unchanged. |
| Docker image builds | PSP baseline image and post-cleanup worker image passed. Final API, PSP, and frontend attempts encountered external DNS/connection-reset failures reaching Docker Hub or Maven Central; those final images remain unverified. |

The first post-cleanup full run had one failure in
`FundingServiceIntegrationTest.timeoutAfterSuccessScenario`: its immediate
post-timeout polling assertion observed UNKNOWN instead of SUCCEEDED. The
complete 12-test class and the subsequent full suite passed unchanged. No
assertion or timeout was weakened. The intermittent failure remains a test
reliability consideration; its root cause was not established by this cleanup.

No standalone formatter or Java lint check is configured. Benchmark execution,
browser interaction, a deployed TLS stack, and a backup/restore drill are not
covered by the checks above.

Docker commands use `docker build -f backend/<module>/Dockerfile .` for
backend images and
`docker build -f frontend/ledgerguard-web/Dockerfile frontend/ledgerguard-web`
for the frontend. No Dockerfile, registry, or network
configuration was changed to work around download failures.

## Retained uncertain code

Preserved public helpers without repository callers include
`ScenarioId.fromKey`, `ScenarioRunner.getRunResult`, `UserRole.toAuthority`,
`WalletQueryService.findWalletByAccountId`, `Money.negated`, the
`JournalEntry.createDebit/createCredit` factories, PSP retry accessors, and
`CreateAttemptContext` diagnostic accessors. Removing them would change Java
API compatibility without a functional benefit established by this audit.

## Snapshot injector follow-up

Reviewed `SnapshotFaultInjector.java` and retained it unchanged: both runtime
and test callers require it, and no unused imports, parameters, or executable
branches were found. Its null checks, connection validation, prepared statement,
and resource cleanup preserve the fault-injection boundary and failure behavior.

Ran the following against the current API and lab sources:

```powershell
.\mvnw.cmd -B -ntp -pl backend/failure-lab -am '-Dtest=CorruptedSnapshotScenarioTest,EnvironmentGuardTest,FailureLabSuiteRunnerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Result: nine guard tests passed; both integration tests errored during Spring
context initialization (`PaymentService`: `No default constructor found`),
before snapshot injection. Concurrent service edits and Maven processes were
observed, including changes to constructor injection and disappearing compiled
classes. Those edits and processes were left untouched; an overlapping rerun
was avoided. The earlier full-suite success above describes the initial cleanup
state, not verification of these subsequent working-tree changes.

## Resolved migration validation

`scripts/restore-db.sh` has been updated from V1–V17 to validate the complete
Flyway history through V1–V18, matching the current working tree migration set.
