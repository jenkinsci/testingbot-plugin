package testingbot;

import hudson.model.Action;

public class TestingBotBuildAction implements Action {

    private TestingBotCredentials testingbotCredential;

    public TestingBotBuildAction(TestingBotCredentials testingbotCredentials) {
        super();
        this.testingbotCredential = testingbotCredentials;
    }

    public TestingBotCredentials getCredentials() {
        return testingbotCredential;
    }

    public void setCredentials(TestingBotCredentials testingbotCredential) {
        this.testingbotCredential = testingbotCredential;
    }

    @Override
    public String getIconFileName() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String getDisplayName() {
        // TODO Auto-generated method stub
        return "tb";
    }

    @Override
    public String getUrlName() {
        // TODO Auto-generated method stub
        return "tb";
    }
}
