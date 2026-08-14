# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and test

```bash
mvn verify                                   # what CI runs (JDK 17 and 21)
mvn package                                  # build target/testingbot.hpi
mvn test -Dtest=TunnelManagerOptionsTest     # single test class
mvn test -Dtest=TunnelManagerOptionsTest#appliesDebug           # single method
mvn hpi:run                                  # local Jenkins at :8080/jenkins with the plugin loaded
```

Tests are plain JUnit 4 + AssertJ unit tests — there are no `JenkinsRule` tests. `**/InjectedTest.java`
(the parent POM's auto-generated harness test) is excluded in `pom.xml`: the
`com.testingbot:TestingBotTunnel` uber-jar bundles an old Jetty that clashes with the Jenkins test
harness' Jetty 12 on the flat test classpath. Adding a `JenkinsRule` test will hit the same clash.

Because of that, **nothing loads this plugin under a real Jenkins plugin classloader**. Unit tests run
on Maven's flat classpath, where every transitive jar is visible — so a missing plugin dependency is
invisible to `mvn test` and only fails in production. `PluginLinkageIT` (failsafe, so it needs
`mvn verify`, not `mvn test`) closes that gap by inspecting the packaged `.hpi`. Keep it passing; if you
add a `<exclusion>`, it will tell you what you owe.

Releases are automatic (JEP-229 incrementals): merging to `master` triggers `.github/workflows/cd.yaml`.
Never run `mvn release:prepare`.

## Dependency rules (this is where breakage comes from)

The two TestingBot artifacts fight with Jenkins' managed dependency versions, so the POM handles both
specially. Read the comments in `pom.xml` before touching any `<exclusion>`.

- **`TestingBotTunnel`** is consumed with the `shaded` classifier and *all* transitives excluded. It
  embeds its own Jetty 11 / Jackson / Apache HTTP / commons-cli. Consequence for code: you cannot use
  commons-cli to parse tunnel options at runtime — the tunnel's older copy shadows the plugin's. That
  is why `TunnelManager` hand-rolls its tokenizer and option parser.
- **`testingbotrest`** has `org.json:json`, `httpcore` and `httpmime` excluded so the HPI does not
  bundle a second copy of classes Jenkins already ships. Those classes come from the **`json-api`** and
  **`apache-httpcomponents-client-4-api`** plugins, which must stay *direct* dependencies in `pom.xml`.
  Reaching a Jenkins plugin transitively through another plugin does **not** put it in this plugin's
  `Plugin-Dependencies` manifest entry, and the plugin classloader then throws `NoClassDefFoundError`
  at runtime while the build stays green — that is how `NoClassDefFoundError: org/json/JSONException`
  shipped. The same applies to any future library excluded in favour of a Jenkins `*-api` plugin.

Two guards enforce this, and between them they cover both directions:

- `PluginLinkageIT` — scans the bundled `testingbotrest` jar's constant pool and asserts every package
  it needs is either shipped in `WEB-INF/lib` or provided by a declared, non-optional plugin
  dependency. Fails closed: an unmapped package is an error, not a silent pass.
- `hpi.bundledArtifacts` + `hpi.strictBundledArtifacts` in `pom.xml` — pins the exact set of jars the
  HPI ships, so removing an exclusion (and silently shipping a duplicate) fails the build.

Manual inspection when working through a dependency problem:

```bash
unzip -p target/testingbot.hpi META-INF/MANIFEST.MF | tr ',' '\n' | grep -i <plugin>   # Plugin-Dependencies
unzip -l target/testingbot.hpi | grep WEB-INF/lib                                      # bundled jars
jdeps -verbose:package WEB-INF/lib/testingbotrest-*.jar                                # what a jar needs
```

## Architecture

Two entry paths (freestyle and Pipeline) converge on shared helpers. Anything touching tunnels or
credential env vars belongs in `TunnelManager`, not in a call site.

**Credential injection**
- `TestingBotCredentials` — a `BaseStandardCredentials` implementation (`@Symbol("testingbot")`, so it
  is JCasC-configurable) holding key + `Secret`. Also handles legacy `~/.testingbot` migration and the
  **Test Connection** form validation.
- `TestingBotBuildWrapper` (freestyle, Build Environment) and `TestingBotStep` (`testingbot { }`) both
  resolve credentials, attach a `TestingBotBuildAction` to the run, and populate `TESTINGBOT_KEY`/`TB_KEY`
  /`TESTINGBOT_SECRET`/`TB_SECRET` via `TunnelManager.populateCredentialEnv`.
- `TestingBotBuildAction` is an `InvisibleAction` that carries the credentials on the run. It is the
  hook two other things depend on: secret masking, and report publishing when no build wrapper is
  configured. If a new entry point injects credentials, it must add this action.
- Secret masking has two implementations because Jenkins has two mechanisms:
  `TestingBotSecretConsoleLogFilter` (a global `@Extension` that resolves the secret lazily from the
  run's `TestingBotBuildAction`) and `pipeline/SecretMaskingConsoleLogFilter` (merged into a step's
  body via `BodyInvoker.mergeConsoleLogFilters`).

**Tunnel lifecycle** — `TunnelManager` is the single owner.
- The tunnel must boot **on the build's node**, not the controller, so a test hitting `localhost`
  reaches it. Both entry points call `startOnChannel`/`stopOnChannel`, which run a
  `MasterToSlaveCallable` on the agent channel. Only decrypted plaintext crosses the channel, never the
  credential object.
- The live `App` handle cannot be serialized back, so it lives in a `RUNNING_TUNNELS` static map **in
  the agent JVM**, keyed by tunnel identifier. Teardown re-resolves the node's *current* channel
  (`currentChannel`) rather than reusing one captured at setup, since the agent may have reconnected.
- Every tunnel gets a unique identifier (`generateTunnelIdentifier`, or the user's
  `--tunnel-identifier` if supplied) so parallel builds and parallel pipeline branches stay isolated.
- `applyOptions` supports only a subset of tunnel flags; `unsupportedOptions` mirrors that set for
  config-time form validation. **Keep the two switch statements in sync.**

**Test reporting** — how a Selenium session becomes an embedded video in Jenkins.
1. Tests print `TestingBotSessionID=<id>` to stdout/stderr.
2. `TestingBotReportFactory.SESSION_PATTERN` scrapes those ids out of the JUnit `CaseResult`
   (stdout, stderr, class name, full name).
3. `TestReporter` (a `TestDataPublisher`, "Embed TestingBot reports") pushes pass/fail back to the
   TestingBot API and returns a `TestingBotReportFactory` as the `TestResultAction.Data`.
4. `TestingBotReport` (a `TestAction`) renders per-test media through the Jelly views.
5. `pipeline/TestingBotTestPublisher` (`testingbotPublisher()`) is the Pipeline equivalent; it wraps
   `TestReporter` for both the `Run` and legacy `AbstractBuild` paths.

**Build-level report** — `TestingBotBuildReportAction` exposes `TESTINGBOT_BUILD` (tests pass it as the
`build` capability) and adds a "TestingBot Build" page embedding
`/mini/builds/<key>/<buildId>?auth=MD5(key:secret:buildId)`. The token is computed once at attach time
so the raw secret is never persisted on the build. `attach()` synchronizes on the run and replaces the
action if the client key changed.

**App upload** — `TestingBotUploadBuilder` (freestyle) and `pipeline/TestingBotUploadStep`
(`testingbotUpload`) both delegate to `TestingBotUploader`, which runs on the node holding the
artifact (same rationale as the tunnel) and calls `TestingbotREST.uploadToStorage`. That is a multipart
request, so it needs `org.apache.http.entity.mime` — one of the packages supplied by the
`apache-httpcomponents-client-4-api` plugin rather than bundled. See the dependency rules above.

**GitHub checks** — `TestingBotChecksPublisher` uses the generic `checks-api`. Actual delivery needs the
`github-checks` plugin at runtime; without it publishing is a safe no-op, so never assume it is present.

## Conventions

- Jelly views live in `src/main/resources/testingbot/<ClassName>/`, with per-field help as
  `help-<fieldName>.html` in the same directory.
- `doFillCredentialsIdItems` methods are `@POST` and permission-checked (`Item.CONFIGURE`, or
  `Jenkins.ADMINISTER` when there is no item context). Follow that pattern for any new form method.
- Deprecated persisted fields are migrated in `readResolve()` (see `enableSSH` → `useTunnel` in
  `TestingBotBuildWrapper`); keep the old field around, nullable, rather than deleting it.
- Tunnel teardown is best-effort and must never fail a build, but failures are logged to the build
  console rather than swallowed.
