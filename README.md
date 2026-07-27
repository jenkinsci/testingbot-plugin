Jenkins TestingBot Plugin
====================

[![Jenkins Plugin](https://img.shields.io/jenkins/plugin/v/testingbot.svg)](https://plugins.jenkins.io/testingbot)
[![Jenkins Plugin Installs](https://img.shields.io/jenkins/plugin/i/testingbot.svg?color=blue)](https://plugins.jenkins.io/testingbot)

This Jenkins plugin integrates TestingBot.com features inside Jenkins.

## Features

* Setup and teardown TestingBot Tunnel for testing internal websites, dev or staging environments. 
* Embed TestingBot Reports in your Jenkins job results; see screenshots/video of each tests from inside Jenkins.
* Use the plugin in combination with Pipeline tests: `testingbot()`, `testingbotTunnel()` and `testingbotPublisher()`

## Prerequisites

* Minimum supported Jenkins version is 2.541.3 (the plugin builds and runs on Java 17+)
* A TestingBot account

## Setting up the plugin
Look for the plugin on the Jenkins Manage Plugins page and click 'install'.

Once installed, go to **Manage Jenkins > Configure System**, scroll down to where you can enter the TestingBot credentials.

![credentials](https://github.com/jenkinsci/testingbot-plugin/raw/master/help/credentials.png)

The plugin uses the Credentials plugin. Click the 'Add' button and enter your key and secret, which you can obtain from the [TestingBot Member area](https://testingbot.com/members). Use **Test Connection** to verify your key and secret before saving.

## Configuring a Job to use the TestingBot Plugin
![build environment](https://github.com/jenkinsci/testingbot-plugin/raw/master/help/buildenv.png)

In the **Build Environment** section, enable the 'TestingBot' option.
The API key you entered previously should be visible there, together with an option to use the [TestingBot Tunnel](https://testingbot.com/support/other/tunnel) during your build.

## Embedded TestingBot Reports
If you want to see the test results (screenshots, logs and a video screencast of the test) from inside Jenkins, then please follow these steps.

The plugin will parse the JUnit test result files in the post-build step to associate test results with TestingBot jobs. Please make sure that JUnit plugin is installed.

Add the **Publish JUnit test result report** post-build action and point it at your report files (for example `test-reports/*.xml`). Then, under **Additional test report features**, add **Embed TestingBot reports**.

![postbuild action](https://github.com/jenkinsci/testingbot-plugin/raw/master/help/postbuild.png)

The TestingBot plugin will parse both `stdout` and `stderr`, looking for lines that have this format:
`TestingBotSessionID=<sessionId>`

The `sessionId` can be obtained from the `RemoteWebDriver` instance of Selenium. Depending on the test framework/language you are using, the syntax may be different.

An example on how to do this: `((RemoteWebDriver) driver).getSessionId().toString()`

A full example that you can use is available on our GitHub [Jenkins-Demo](https://github.com/testingbot/Jenkins-Demo) page.

On the build page, each JUnit test that used TestingBot is shown with a preview thumbnail of its session (the first one, if a test records several) and expands in place to the full session media (video screencast, screenshots and logs) — no need to leave Jenkins. The embedded media loads lazily, so collapsed sessions stay lightweight.

## Embedded TestingBot Build report

Whenever the plugin injects credentials — through the freestyle **Build Environment** option or the `testingbot { }` / `testingbotTunnel { }` pipeline steps — it exposes a `TESTINGBOT_BUILD` environment variable and adds a **TestingBot Build** page to the build.

Set your test's `build` desired capability to `$TESTINGBOT_BUILD` so all sessions from a single Jenkins build are grouped under one TestingBot build. The **TestingBot Build** link on the build page then embeds that build's TestingBot report (every session, with status and video) directly inside Jenkins, without leaving the CI UI.

## Mobile app testing

To test a native mobile app (Appium) built by your Jenkins job, upload the `.apk`/`.ipa` to
[TestingBot Storage](https://testingbot.com/support/mobile/storage) and reference the returned
`tb://` app URL as the Appium `app` capability.

In a **freestyle** job, add the **Upload an app to TestingBot Storage** build step *before* your
test step. Point it at the built app (for example `build/outputs/apk/debug/app-debug.apk`); it
uploads the file and exports the app URL as an environment variable (default `TESTINGBOT_APP_URL`)
for the following steps to use as their `app` capability.

In a **pipeline**, the `testingbotUpload` step returns the app URL:

```groovy
testingbot('251ca561abdfewf285') {
    def appUrl = testingbotUpload(file: 'build/app.apk')
    // pass appUrl as the Appium "app" capability in your tests
    sh "APP_URL=${appUrl} ./run-appium-tests.sh"
}
```

The upload runs on the agent that holds the artifact and only the credentials are sent over the
(encrypted) remoting link — the app bytes are POSTed straight from the agent to TestingBot.

## Pipeline
The plugin offers pipeline support, which can be used with a Jenkinsfile.

Currently the plugin offers these commands:
* `testingbot(String credentialId)`
* `testingbotTunnel(credentialsId: '', options: ' -d -a')`
* `testingbotUpload(file: 'build/app.apk')`
* `testingbotPublisher()`

The `testingbot()` command requires a `credentialId` which is the Id you can find on the Jenkins Credentials page, the unique Id connected to the TestingBot API key and Secret you entered previously. This command will set environment variables which you can use in your test, including `TB_KEY` and `TB_SECRET`.

The `testingbotTunnel()` command requires both a `credentialId` and `options`. The options are the options you can specify with the TestingBot Tunnel.
This will start the tunnel before your job runs. Once the job finishes, the tunnel will be shutdown.

`testingbotPublisher()` will try to read the JUnit report files and show the test results from TestingBot.

You can build these steps interactively with the Jenkins **Pipeline Syntax** (Snippet Generator):

![pipeline snippet generator](https://github.com/jenkinsci/testingbot-plugin/raw/master/help/snippet-generator.png)

### Environment variables

Inside **both** `testingbot { }` and `testingbotTunnel { }` blocks:

* `TESTINGBOT_KEY` / `TB_KEY` – your TestingBot API key
* `TESTINGBOT_SECRET` / `TB_SECRET` – your TestingBot API secret (masked in the build log)
* `TESTINGBOT_BUILD` – a per-build identifier. Pass its value as your test's `build` desired capability so all sessions from this Jenkins build are grouped together and shown on the build's embedded **TestingBot Build** page (see below).

Inside a `testingbotTunnel { }` block only (these describe the tunnel started for that block):

* `HUB_HOST` / `HUB_PORT` – host and port to point your Selenium or Appium client at when using the tunnel
* `SELENIUM_HOST` / `SELENIUM_PORT` – Selenium-specific aliases of `HUB_HOST`/`HUB_PORT`, kept for backwards compatibility
* `TESTINGBOT_TUNNEL_IDENTIFIER` – the identifier of the tunnel started for this block. Pass it in your desired capabilities so parallel builds each use their own tunnel.

Each `testingbotTunnel { }` block starts an isolated tunnel with its own identifier, so parallel pipeline branches no longer interfere with one another.

### Declarative Pipeline example

```groovy
pipeline {
   agent any

   tools {
      // Install the Maven version configured as "M3" and add it to the path.
      maven "M3"
      ant "ant"
   }

   stages {
      stage('Build') {
         steps {
            // Get some code from a GitHub repository
            git 'https://github.com/testingbot/Jenkins-Demo.git'
            
            testingbot('251ca561abdfewf285') {
               testingbotTunnel(credentialsId: '251ca561abdfewf285', options: '-d') {
                    sh "ant test"
               }
            }
         }

         post {
            success {
               junit 'test-reports/*.xml'
            }
            always {
                testingbotPublisher()
            }
         }
      }
   }
}
```

### Scripted Pipeline example

```groovy
node {
    git 'https://github.com/testingbot/Jenkins-Demo.git'

    // Inject credentials and start an isolated tunnel around the tests
    testingbot('251ca561abdfewf285') {
        testingbotTunnel(credentialsId: '251ca561abdfewf285', options: "--tunnel-identifier ci-${env.BUILD_NUMBER}") {
            sh 'ant test'
        }
    }

    junit 'test-reports/*.xml'
    // Embed TestingBot screenshots/video into the test report
    testingbotPublisher()
}
```

## Publishing results as a GitHub check

The plugin can publish the TestingBot outcome of a build as a GitHub check (✅/❌ on the commit and pull request) through the [Checks API](https://plugins.jenkins.io/checks-api/) plugin.

Add the **Publish TestingBot results as a GitHub check** post-build action (freestyle), or call the `testingbotChecks` step in a pipeline:

![github check](https://github.com/jenkinsci/testingbot-plugin/raw/master/help/testingbot-checks.png)

```groovy
testingbotChecks(name: 'TestingBot', message: 'End-to-end tests on TestingBot')
```

* `name` — the check name (its *context* on the commit/PR). Defaults to `TestingBot`. This is the modern equivalent of the custom context you would set for the GitHub Pull Request Builder Plugin.
* `message` — an optional summary; when omitted, a summary is generated from the TestingBot sessions in the build. The check links to the embedded TestingBot build report.

The check conclusion reflects the TestingBot sessions found in the build (all passed → success), falling back to the overall build result when no sessions are present. Actual delivery to GitHub is handled by the [GitHub Checks](https://plugins.jenkins.io/github-checks/) plugin together with a GitHub App; if that is not installed, the step is a safe no-op.

## Configuration as Code (JCasC)

The TestingBot credentials can be configured with the [Configuration as Code](https://plugins.jenkins.io/configuration-as-code/) plugin using the `testingbot` symbol:

```yaml
credentials:
  system:
    domainCredentials:
      - credentials:
          - testingbot:
              id: "testingbot"
              description: "TestingBot key/secret"
              key: "${TESTINGBOT_KEY}"
              secret: "${TESTINGBOT_SECRET}"
```

Reference the `id` (here `testingbot`) as the `credentialsId` in your job or pipeline.

## Building the Plugin

To build the plugin, use:

`mvn package`

## Releasing the Plugin

Releases are published automatically through the Jenkins [continuous delivery](https://www.jenkins.io/doc/developer/publishing/releasing-cd/) (JEP-229) flow: merging to the default branch triggers the `cd` GitHub Actions workflow, which builds and publishes an incremental release. There is no manual `mvn release:prepare` step.

## Reporting Issues

Please [file a new issue](https://github.com/jenkinsci/testingbot-plugin/issues) on the GitHub repository.
