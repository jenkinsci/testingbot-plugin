package testingbot.pipeline;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import hudson.util.ListBoxModel;
import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.jenkinsci.plugins.workflow.steps.BodyExecution;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.EnvironmentExpander;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.export.ExportedBean;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernameListBoxModel;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.google.inject.Inject;
import hudson.Extension;
import hudson.Util;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TopLevelItem;
import hudson.security.ACL;
import java.util.ArrayList;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.HashMap;
import java.util.Set;
import testingbot.TestingBotBuildAction;
import static testingbot.TestingBotBuildWrapper.TB_KEY;
import static testingbot.TestingBotBuildWrapper.TB_SECRET;
import static testingbot.TestingBotBuildWrapper.TESTINGBOT_KEY;
import static testingbot.TestingBotBuildWrapper.TESTINGBOT_SECRET;
import testingbot.TestingBotCredentials;

@ExportedBean
public class TestingBotStep extends AbstractStepImpl {
    private final String credentialsId;

    @DataBoundConstructor
    public TestingBotStep(String credentialsId) {
        this.credentialsId = Util.fixEmpty(credentialsId);
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public static class Execution extends AbstractStepExecutionImpl {
        private static final long serialVersionUID = 1;

        @Inject(optional=true) private transient TestingBotStep step;
        @StepContextParameter private transient Run<?,?> run;

        private BodyExecution body;

        @Override public boolean start() throws Exception {
            Job<?,?> job = run.getParent();
            if (!(job instanceof TopLevelItem)) {
                throw new Exception(job + " must be a top-level job");
            }
            
            final TestingBotCredentials credentials = TestingBotCredentials.getCredentials(job, step.getCredentialsId());

            if (credentials == null) {
                throw new Exception("no credentials provided");
            }
            CredentialsProvider.track(run, credentials);


            HashMap<String,String> env = new HashMap<String,String>();
            env.put(TESTINGBOT_KEY, credentials.getKey());
            env.put(TB_KEY, credentials.getKey());
            env.put(TESTINGBOT_SECRET, credentials.getDecryptedSecret());
            env.put(TB_SECRET, credentials.getDecryptedSecret());
            TestingBotBuildAction buildAction = run.getAction(TestingBotBuildAction.class);
            if (buildAction == null) {
                buildAction = new TestingBotBuildAction(credentials);
                run.addAction(buildAction);
            }

            body = getContext().newBodyInvoker()
                .withContext(credentials)
                .withContext(EnvironmentExpander.merge(getContext().get(EnvironmentExpander.class), new ExpanderImpl(env)))
                .withCallback(BodyExecutionCallback.wrap(getContext()))
                .start();
            return false;
        }

        @Override public void stop(@Nonnull Throwable cause) throws Exception {
            // should be no need to do anything special (but verify in JENKINS-26148)
            if (body!=null) {
                body.cancel(cause);
            }
        }
    }

    @Extension
    public static final class DescriptorImpl extends AbstractStepDescriptorImpl {
        public DescriptorImpl() {
            super(Execution.class);
        }

        @Override public String getDisplayName() {
            return "TestingBot";
        }

        @Override public String getFunctionName() {
            return "testingbot";
        }

        @Override public boolean takesImplicitBlockArgument() {
            return true;
        }

        @Override
        public Set<Class<?>> getProvidedContext() {
            return Collections.<Class<?>>singleton(TestingBotCredentials.class);
        }

        @SuppressWarnings("unused")
        public ListBoxModel doFillCredentialsIdItems(final @AncestorInPath Item context) {
            return new StandardListBoxModel().withMatching(
                  CredentialsMatchers.anyOf(CredentialsMatchers.instanceOf(TestingBotCredentials.class)),
                  CredentialsProvider.lookupCredentials(TestingBotCredentials.class, context, ACL.SYSTEM,
                      new ArrayList<DomainRequirement>()));
        }

    }
}
