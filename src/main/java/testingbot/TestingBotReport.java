package testingbot;

/**
 *
 * @author testingbot.com
 */
import com.testingbot.testingbotrest.TestingbotREST;
import hudson.model.AbstractBuild;
import hudson.model.Run;
import hudson.tasks.junit.CaseResult;
import hudson.tasks.junit.TestAction;
import java.util.Collections;
import java.util.List;
import testingbot.TestingBotBuildWrapper.BuildWrapperItem;

/**
 * Show videos for the tests.
 *
 */
public class TestingBotReport extends TestAction {

    public final CaseResult parent;
    private final TestingbotREST apiClient;
    private final TestingBotCredentials credentials;

    /**
     * Session IDs.
     */
    private final List<String> ids;

    public TestingBotReport(TestingBotCredentials credentials, CaseResult parent, List<String> ids) {
        this.parent = parent;
        this.ids = ids;
        this.credentials = credentials;
        this.apiClient = new TestingbotREST(credentials.getKey(), credentials.getDecryptedSecret());
    }

    public AbstractBuild<?, ?> getBuild() {
        return parent.getOwner();
    }

    public List<String> getIDs() {
        return Collections.unmodifiableList(ids);
    }

    public String getId() {
        return ids.get(0);
    }

    public String getClientKey() {
        return credentials.getKey();
    }

    public String getHash() {
        return apiClient.getAuthenticationHash(getId());
    }
    
    public String getTestName() {
        return apiClient.getTest(getId()).getName();
    }

    @Override
    public String getIconFileName() {
        return "/plugin/testingbot/images/24x24/logo.jpg";
    }

    @Override
    public String getDisplayName() {
        return "TestingBot Report";
    }

    @Override
    public String getUrlName() {
        return "testingbot";
    }
}
