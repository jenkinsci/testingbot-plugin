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

* Minimum supported Jenkins version is 1.609.2.
* A TestingBot account.

## Setting up the plugin
Look for the plugin on the Jenkins Manage Plugins page and click 'install'.

![credentials](https://github.com/jenkinsci/testingbot-plugin/raw/master/help/credentials.png)
Once installed, go to **Manage Jenkins > Configure System**, scroll down to where you can enter the TestingBot credentials.

The plugin uses the Credentials plugin. Click the 'Add' button and enter your key and secret, which you can obtain from the [TestingBot Member area](https://testingbot.com/members).

## Configuring a Job to use the TestingBot Plugin
![build environment](https://github.com/jenkinsci/testingbot-plugin/raw/master/help/buildenv.png)
In the **Build Environment** section, enable the 'TestingBot' option.
The API key you entered previously should be visible there, together with an option to use the [TestingBot Tunnel](https://testingbot.com/support/other/tunnel) during your build.

## Embedded TestingBot Reports
If you want to see the test results (screenshots, logs and a video screencast of the test) from inside Jenkins, then please follow these steps.

The plugin will parse the JUnit test result files in the post-build step to associate test results with TestingBot jobs. Please make sure that JUnit plugin is installed.

![postbuild action](https://github.com/jenkinsci/testingbot-plugin/raw/master/help/postbuild.png)

Click on **Add post-build action** in **Post-build Actions**. Make sure you enable **Publish JUnit test result report** and point to the correct test report files (for example `test-reports/*.xml`).

![publisher](https://github.com/jenkinsci/testingbot-plugin/raw/master/help/publisher.png)

Select the **Run TestingBot Test Publisher** option from the **Post Build Action** list.

The TestingBot plugin will parse both `stdout` and `stderr`, looking for lines that have this format:
`TestingBotSessionID=<sessionId>`

The `sessionId` can be obtained from the `RemoteWebDriver` instance of Selenium. Depending on the test framework/language you are using, the syntax may be different.

An example on how to do this: `((RemoteWebDriver) driver).getSessionId().toString()`

A full example that you can use is available on our GitHub [Jenkins-Demo](https://github.com/testingbot/Jenkins-Demo) page.

## Pipeline
The plugin offers pipeline support, which can be used with a Jenkinsfile.

Currently the plugin offers these commands:
* `testingbot(String credentialId)`
* `testingbotTunnel(credentialsId: '', options: ' -d -a')`
* `testingbotPublisher()`

The `testingbot()` command requires a `credentialId` which is the Id you can find on the Jenkins Credentials page, the unique Id connected to the TestingBot API key and Secret you entered previously. This command will set environment variables which you can use in your test, including `TB_KEY` and `TB_SECRET`.

The `testingbotTunnel()` command requires both a `credentialId` and `options`. The options are the options you can specify with the TestingBot Tunnel.
This will start the tunnel before your job runs. Once the job finishes, the tunnel will be shutdown.

`testingbotPublisher()` will try to read the JUnit report files and show the test results from TestingBot.

An example:

```
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

## Building the Plugin

To build the plugin, use:

`mvn package`

## Reporting Issues

Please [file a new issue](https://github.com/testingbot/TestingBot-Jenkins-Plugin/issues).
