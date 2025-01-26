package testingbot;

import hudson.model.Run;
import jenkins.model.RunAction2;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;

public class TestingBotTestEmbed implements RunAction2 {
    private static final Logger logger = Logger.getLogger(TestingBotTestEmbed.class.getName());
    private transient Run run;
    private List<TestingBotBuildObject> ids;
    private TestingBotBuildObject tbo;

    public TestingBotTestEmbed(List<TestingBotBuildObject> ids) {
        this.ids = ids;
    }

    @Override
    public String getIconFileName() {
        return "/plugin/testingbot/images/logo.svg";
    }

    @Override
    public String getDisplayName() {
        return "TestingBot";
    }

    @Override
    public String getUrlName() {
        return "testingbot-embed";
    }

    @Override
    public void onAttached(Run<?, ?> run) {
        this.run = run;
    }

    @Override
    public void onLoad(Run<?, ?> run) {
        this.run = run;
    }

    /**
     *
     * @param req Standard Request Object
     * @param rsp Standard Response Object
     * @throws IOException Unable to load show.jelly template
     */
    @SuppressWarnings("unused") // used by stapler
    public void doIndex(StaplerRequest req, StaplerResponse rsp)
            throws IOException {
        String sessionId = req.getParameter("sessionId");
        for (TestingBotBuildObject tbo : this.ids) {
            if (tbo.getSessionId().equals(sessionId)) {
                this.tbo = tbo;
            }
        }
        if (this.tbo != null) {
            try {
                req.getView(this, "show.jelly").forward(req, rsp);
            } catch (ServletException e) {
                throw new IOException(e);
            }
        } else {
            try {
                req.getView(this, "index.jelly").forward(req, rsp);
            } catch (ServletException e) {
                throw new IOException(e);
            }
        }
    }

    public Run getRun() {
        return run;
    }

    public TestingBotBuildObject getTbo() {
        return tbo;
    }

    public List<TestingBotBuildObject> getSessionIds() {
        return ids;
    }
}
