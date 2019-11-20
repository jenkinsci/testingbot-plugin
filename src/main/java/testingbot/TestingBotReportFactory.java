package testingbot;

import hudson.tasks.junit.CaseResult;
import hudson.tasks.junit.TestAction;
import hudson.tasks.junit.TestObject;
import hudson.tasks.junit.TestResultAction.Data;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 *
 * @author testingbot.com
 */
public class TestingBotReportFactory extends Data {
    TestingBotCredentials credentials;
    public TestingBotReportFactory(TestingBotCredentials credentials) {
        super();
        this.credentials = credentials;
    }
    
    public static List<String> findSessionIDs(CaseResult testResult) {
        List<String> sessions = new ArrayList<>();
        Pattern p = Pattern.compile("TestingBotSessionID=(.*)");
        if (testResult.getStdout() != null) {
            Matcher matchOut = p.matcher(testResult.getStdout());
            while (matchOut.find()) {
                String sessionId = matchOut.group(1);
                sessions.add(sessionId);
            }
        }
        
        if (testResult.getStderr() != null) {
            Matcher matchError = p.matcher(testResult.getStderr());
            while (matchError.find()) {
                String sessionId = matchError.group(1);
                sessions.add(sessionId);
            }
        }
        
        if (testResult.getClassName() != null) {
            Matcher matchName = p.matcher(testResult.getClassName());
            while (matchName.find()) {
                String sessionId = matchName.group(1);
                sessions.add(sessionId);
            }
        }
        
        if (testResult.getFullName() != null) {
            Matcher matchFullName = p.matcher(testResult.getFullName());
            while (matchFullName.find()) {
                String sessionId = matchFullName.group(1);
                sessions.add(sessionId);
            }
        }
        
        return sessions;
    }

    @Override
    public List<? extends TestAction> getTestAction(TestObject to) {
        if (to instanceof CaseResult) {
            CaseResult cr = (CaseResult) to;
            List<String> ids = findSessionIDs(cr);
            if (!ids.isEmpty()) {
                return Collections.singletonList(new TestingBotReport(credentials, cr,ids));
            }
        }
        return Collections.emptyList();
    }
    
}
