package testingbot;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Descriptor;
import hudson.tasks.junit.CaseResult;
import hudson.tasks.junit.SuiteResult;
import hudson.tasks.junit.TestDataPublisher;
import hudson.tasks.junit.TestResult;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.kohsuke.stapler.DataBoundConstructor;
import com.testingbot.testingbotrest.TestingbotREST;
import hudson.FilePath;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.junit.TestResultAction;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import testingbot.TestingBotBuildWrapper.BuildWrapperItem;

/**
 *
 * @author testingbot.com
 */
public class TestReporter extends TestDataPublisher {

    /**
     * Logger instance.
     */
    private static final Logger logger = Logger.getLogger(TestReporter.class.getName());

    @Extension(ordinal = 1000) // JENKINS-12161
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    @DataBoundConstructor
    public TestReporter() {
    }

    @Override
    public TestResultAction.Data contributeTestData(Run<?, ?> run, @Nonnull FilePath workspace, Launcher launcher, TaskListener listener, TestResult testResult) throws IOException, InterruptedException {
        boolean foundSession = false;
        List<String> sessionIDs = null;
        TestingBotCredentials credentials = null;
        TestingBotBuildAction buildAction = run.getAction(TestingBotBuildAction.class);
        if (buildAction != null) {
            credentials = buildAction.getCredentials();
        } else {
            BuildWrapperItem<TestingBotBuildWrapper> wrapperItem
                    = TestingBotBuildWrapper.findBuildWrapper(run.getParent());
            if (wrapperItem == null || wrapperItem.buildWrapper == null) {
                return null;
            }
            credentials = TestingBotCredentials.getCredentials(wrapperItem.buildItem,
                    wrapperItem.buildWrapper.getCredentialsId());
        }

        if (credentials == null) {
            return null;
        }
        TestingbotREST api = new TestingbotREST(credentials.getKey(), credentials.getDecryptedSecret());
        if (testResult != null) {
            for (SuiteResult sr : testResult.getSuites()) {
                for (CaseResult cr : sr.getCases()) {
                    sessionIDs = TestingBotReportFactory.findSessionIDs(cr);
                    if (!sessionIDs.isEmpty()) {
                        String errorDetails = cr.getErrorDetails();
                        if (errorDetails == null) {
                            errorDetails = "";
                        }
                        Map<String, Object> data = new HashMap<>();
                        data.put("success", cr.isPassed() ? "1" : "0");
                        data.put("status_message", errorDetails);
                        data.put("name", cr.getFullName());
                        api.updateTest(sessionIDs.get(0), data);

                        foundSession = true;
                    }
                }
            }
        }

        if (!foundSession) {
            logger.finer("No TestingBot sessionIDs found in test output.");
            return null;
        } else {
            return new TestingBotReportFactory(credentials);
        }
    }

    @Override
    public TestResultAction.Data getTestData(AbstractBuild<?, ?> ab, Launcher lnchr, BuildListener bl, TestResult tr) throws IOException, InterruptedException {
        FilePath filePath = ab.getWorkspace();
        if (filePath == null) {
            return null;
        } else {
            return contributeTestData(ab, filePath, lnchr, bl, tr);
        }
    }

    private static class DescriptorImpl extends Descriptor<TestDataPublisher> {

        @Override
        public String getDisplayName() {
            return "Embed TestingBot reports";
        }
    }
}
