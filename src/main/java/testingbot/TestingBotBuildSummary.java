package testingbot;

import com.testingbot.models.TestingbotTest;
import com.testingbot.testingbotrest.TestingbotREST;
import hudson.Extension;
import hudson.model.AbstractBuild;
import hudson.model.InvisibleAction;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.tasks.junit.CaseResult;
import hudson.tasks.junit.SuiteResult;
import hudson.tasks.junit.TestResult;
import hudson.tasks.test.AbstractTestResultAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TestingBotBuildSummary extends InvisibleAction {
    private final AbstractBuild<?,?> build;
    public List<TestingBotBuildObject> sessionIds = new ArrayList<>();
    private static final Map<Integer,List<String>> sessions = new HashMap<Integer,List<String>>();
    
    public TestingBotBuildSummary(AbstractBuild<?,?> build, List<TestingBotBuildObject> sessionIds) {
        this.build = build;
        this.sessionIds = sessionIds;
    }

    public List<TestingBotBuildObject> getSessionIds() {
        return this.sessionIds;
    }

    @Extension
    public static final class RunListenerImpl extends RunListener<AbstractBuild<?,?>> {

        @Override
        public void onCompleted(AbstractBuild<?, ?> r, TaskListener listener) {
            if (r == null) {
                Logger.getLogger(TestingBotBuildSummary.class.getName()).log(Level.INFO, "r is null", "r is null");
            }
            if (r.getAction(AbstractTestResultAction.class) == null) {
                Logger.getLogger(TestingBotBuildSummary.class.getName()).log(Level.INFO, "getaction is null", "getaction is null");
            }
            
            TestingBotBuildAction buildAction = r.getAction(TestingBotBuildAction.class);
            TestingBotCredentials credentials = null;
            if (buildAction != null) {
              credentials = buildAction.getCredentials();
            } else {
              TestingBotBuildWrapper.BuildWrapperItem<TestingBotBuildWrapper> wrapperItem =
                  TestingBotBuildWrapper.findBuildWrapper(r.getParent());
              credentials = TestingBotCredentials.getCredentials(wrapperItem.buildItem,
                  wrapperItem.buildWrapper.getCredentialsId());
            }
        
            TestingbotREST apiClient = new TestingbotREST(credentials.getKey(), credentials.getDecryptedSecret());
            TestResult testResult = (TestResult) r.getAction(AbstractTestResultAction.class).getResult();
            List<TestingBotBuildObject> ids = new ArrayList<>();
            for (SuiteResult sr : testResult.getSuites()) {
                for (CaseResult cr : sr.getCases()) {
                    List<String> sessionIds = TestingBotReportFactory.findSessionIDs(cr);
                    TestingbotTest test = apiClient.getTest(sessionIds.get(0));
                    TestingBotBuildObject tbo = new TestingBotBuildObject(sessionIds.get(0), cr.getClassName(), cr.getName(), cr.isPassed(), apiClient.getAuthenticationHash(sessionIds.get(0)), test);
                    ids.add(tbo);
                }
            }
            Logger.getLogger(TestingBotBuildSummary.class.getName()).log(Level.INFO, "try to get ids");
            r.addAction(new TestingBotBuildSummary(r, ids));
        }
    }
}