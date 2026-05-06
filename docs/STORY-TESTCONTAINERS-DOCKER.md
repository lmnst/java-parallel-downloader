# A silent skip masquerading as a green build

A short field report on how `IntegrationDockerTest` was passing locally
without ever actually running, what it took to notice, and the small
build change that surfaced it. It is not a bug post-mortem in the
"production was down" sense; nothing was broken in the library. The
breakage was upstream of the assertions, and the test runner was
reporting that breakage as "skipped" instead of "failed."

## Symptom

`./gradlew test -PintegrationTests` printed:

```
> Task :test

IntegrationDockerTest > cliDownload_sha256Matches() SKIPPED

BUILD SUCCESSFUL in 6s
```

with the assumption message `Docker not available; skipping
IntegrationDockerTest` in the JUnit XML output. The exit code was 0.
Reviewer-facing, this was indistinguishable from a green run on a
machine without Docker. CI ran ubuntu-only with Docker available, so
on a hypothetical CI run the same skip would have been quiet. The
bypass was complete: integration tests reported success without ever
launching a container.

The integration test, abridged:

```java
@BeforeAll
static void startContainer(...) {
    Assumptions.assumeTrue(
            DockerClientFactory.instance().isDockerAvailable(),
            "Docker not available; skipping IntegrationDockerTest");
    ...
}
```

`Assumptions.assumeTrue(false, ...)` aborts the test with an
`org.opentest4j.TestAbortedException`, which JUnit Platform reports as
a skip. This is the documented "this test does not apply in this
environment" pattern, and it is the right pattern when the assumption
is true ("if no Docker, skip"). The trap is when the assumption itself
is silently wrong.

## Initial hypothesis

Docker Desktop was not running. Starting it, then re-running, produced
the same skip. `docker ps` confirmed the daemon was up. `curl
--unix-socket /var/run/docker.sock http://localhost/_ping` returned a
200 with `OK`. So Docker, by every external check, was available, but
Testcontainers' `DockerClientFactory.instance().isDockerAvailable()`
was returning `false` against a working daemon.

The second guess was a permissions / socket-path issue specific to
macOS and Docker Desktop's vendor strategies. `~/.docker/contexts`
looked normal; `docker context inspect` pointed at the standard socket
path. Nothing wrong from the Docker side.

## Actual root cause

`DockerClientFactory.isDockerAvailable()` iterates registered
`DockerClientProviderStrategy` implementations and returns true on the
first one that connects. On Testcontainers 1.20.4, every strategy
threw, and each strategy's failure was logged at debug level. Critical
detail: the `build.gradle.kts` test classpath did not have an SLF4J
binding. SLF4J defaults to a NOP binding when none is present, and
prints a one-line warning to stderr at startup that is easy to miss
amid Gradle's own output. Every strategy's diagnostic, including the
HTTP 400 each one was getting from the Docker Engine 29 socket, was
routed to `/dev/null`.

After adding `testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")` and
re-running, the test log showed the actual cause:

```
DEBUG UnixSocketClientProviderStrategy - HTTP 400 calling /_ping
DEBUG DockerDesktopClientProviderStrategy - HTTP 400 calling /_ping
WARN DockerClientProviderStrategy - Could not find a valid Docker
    environment. Please check configuration. Attempted strategies:
        UnixSocketClientProviderStrategy: Could not connect (HTTP 400)
        DockerDesktopClientProviderStrategy: Could not connect (HTTP 400)
        ...
```

Testcontainers 1.20.4's HTTP client signs requests with a payload
shape that Docker Engine 29.x rejects with `400 Bad Request`. (1.20.4
predates Docker Engine 29; the API drift between them is real.)
Testcontainers 2.0.5 ships an updated docker-java that talks to
Engine 29 cleanly. Same socket, same daemon, different client library;
that was the entire delta.

## Fix

Two lines in `build.gradle.kts`:

```kotlin
- testImplementation("org.testcontainers:testcontainers:1.20.4")
+ testImplementation("org.testcontainers:testcontainers:2.0.5")
+ testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
```

After that change, `./gradlew test -PintegrationTests` actually pulls
`httpd:2.4`, starts the container, runs the CLI against the mapped
port, computes the on-disk SHA-256, and asserts equality. Total run
time, ~30 seconds with the image already in the local cache; the
assertion fires.

The slf4j-simple binding is intentional even after 2.0.5: it is the
canary. If a future Testcontainers / Docker version pair regresses
into the same shape, the strategy attempts will be visible in the
test log instead of silently hidden. Cost is one transitive dep at
test runtime. Worth it.

## Lesson

A skip looks identical to a pass in summary output and exit codes,
and the JUnit `assumeTrue` pattern is a one-line invitation to ship
that ambiguity. The pattern is fine when the assumption is asking a
question with a known answer (`assumeTrue(System.getenv("FOO") !=
null)`). It becomes hazardous when the assumption delegates to a
third-party library to answer a brittle external question, because
the library can be wrong without anyone noticing. CI-green is not the
same as tests-running. The cheapest mitigation is to verify, on a
current developer machine and during periodic green builds, that the
integration tests actually fired and not merely passed the
abort-quietly check. The slightly less cheap mitigation is to make
the binding visible (slf4j-simple here) so the next strategy regression
gets reported instead of swallowed.
