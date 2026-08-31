# Developer test workflow

The test suite is divided with JUnit 5 tags and Maven profiles. Tags are assigned at the
test-class boundary so a test cannot silently move tiers because its class name changed.

| Tier | Tag | Scope | Typical use |
| --- | --- | --- | --- |
| L1 Unit / Fast | `unit` | Pure Java tests with no Spring application context | Every coding iteration |
| L2 Feature / Contract | `contract` | Stable domain, API-shape, and search-behavior contracts | Feature-ready changes |
| L3 Integration | `integration` | Spring, SQLite, Flyway, jOOQ, REST, filesystem, transaction, parser, and FTS tests | Affected feature validation |
| L4 Full / Build Integrity | all tests (no tag filter) | Complete regression coverage plus clean Maven lifecycle/code generation | Pull-request gate |

## Commands and default behavior

`mvn test` intentionally runs every test. It remains the safe default and must not be interpreted
as a fast-only run. The explicit profiles are:

```text
mvn test -Pfast         # unit + contract; no Spring context tests
mvn test -Pintegration  # integration-tagged tests
mvn clean verify -Pfull # all tests plus clean package/build-integrity checks
```

The `full` profile deliberately applies no include or exclude filter. This guarantees that adding
a new tagged test cannot accidentally remove it from the final gate. `fast` is feedback only; it
may be skipped while investigating an unrelated build failure, but the affected contract or
integration tests must run before a feature is declared ready. `clean verify -Pfull` is mandatory
before PR readiness and cannot be replaced by `-DskipTests`.

The fast profile is not a substitute for the jOOQ/Flyway clean-build gate. Changes to migrations,
persistence wiring, generated sources, packaging, or build plugins require the full command even
when the coding loop is otherwise limited to unit and contract tests.

## PR CI and merge gate

Every pull request targeting `main` runs `.github/workflows/pr-ci.yml` with three visible jobs:

| CI job | Command | Purpose |
| --- | --- | --- |
| Fast unit and contract tests | `mvn --batch-mode test -Pfast` | Quick feedback for pure Java and contract coverage |
| Integration tests | `mvn --batch-mode test -Pintegration` | Spring, SQLite, Flyway, filesystem, REST, parser, and FTS coverage |
| Full regression and build integrity | `mvn --batch-mode clean verify -Pfull` | Clean Flyway/jOOQ source generation, all tests, compilation, verification, and package |

The full/build-integrity job is the independent PR safety gate. Its `clean` phase removes generated
build output before Maven runs `generate-sources`; the jOOQ generator then applies all published
Flyway migrations to a fresh temporary SQLite database and the generated sources are compiled into
the package. Maven dependency caching only reuses downloaded dependencies and does not replace this
clean-build semantics. The job also runs `git diff --check` and uploads Surefire/package artifacts
when available so a failure can be diagnosed by stage.

Issue #130 measured the local warm `mvn test` at about 25.83 seconds and `mvn clean package` at about
28.45 seconds. The CI workflow keeps the full gate separate from the fast local loop; CI wall-clock
is recorded from the completed workflow run and compared with those baselines in the PR. These
figures are observations rather than an SLA because runner load and dependency-cache state vary.

Repository administrators should mark the `Full regression and build integrity` job as a required
status check in branch protection for `main` after the workflow has completed its first run. If the
repository does not expose branch-protection administration to the contributor, this manual step is
the remaining configuration needed to make the check merge-blocking.

## Tag/profile smoke checks

Profile selection is verified by running each command and inspecting the Surefire summary. The
fast run must report zero skipped integration classes; the integration run must execute the
integration-tagged classes; and the full run must execute the union of both sets. Keep these
checks in the PR description when changing test tags or Maven configuration.
