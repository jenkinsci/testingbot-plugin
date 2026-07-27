package testingbot.pipeline;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Util;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.util.ListBoxModel;
import java.util.ArrayList;
import java.util.Set;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.verb.POST;
import testingbot.TestingBotCredentials;
import testingbot.TestingBotUploader;

/**
 * {@code testingbotUpload(file: 'build/app.apk')} — uploads a built mobile app to TestingBot
 * Storage and returns the resulting {@code tb://} app URL, for use as the Appium {@code app}
 * capability:
 *
 * <pre>{@code
 * def appUrl = testingbotUpload file: 'build/app.apk'
 * }</pre>
 *
 * <p>The freestyle equivalent is the {@code TestingBotUploadBuilder} build step, which exports the
 * app URL as an environment variable instead of returning it.</p>
 */
public class TestingBotUploadStep extends Step {

    private final String file;
    private String credentialsId;

    @DataBoundConstructor
    public TestingBotUploadStep(String file) {
        this.file = Util.fixEmptyAndTrim(file);
    }

    public String getFile() {
        return file;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = Util.fixEmpty(credentialsId);
    }

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(context, this);
    }

    private static final class Execution extends SynchronousNonBlockingStepExecution<String> {

        private static final long serialVersionUID = 1L;

        private final transient TestingBotUploadStep step;

        Execution(StepContext context, TestingBotUploadStep step) {
            super(context);
            this.step = step;
        }

        @Override
        protected String run() throws Exception {
            if (step.getFile() == null) {
                throw new AbortException("testingbotUpload: 'file' is required.");
            }
            StepContext context = getContext();
            Run<?, ?> run = context.get(Run.class);
            FilePath workspace = context.get(FilePath.class);
            TaskListener listener = context.get(TaskListener.class);
            EnvVars env = context.get(EnvVars.class);

            Job<?, ?> job = run.getParent();
            TestingBotCredentials credentials = TestingBotCredentials.getCredentials(job, step.getCredentialsId());
            if (credentials == null) {
                throw new AbortException("testingbotUpload: no TestingBot credentials found"
                        + (step.getCredentialsId() != null ? " for id '" + step.getCredentialsId() + "'" : "") + ".");
            }
            CredentialsProvider.track(run, credentials);

            String expanded = env != null ? env.expand(step.getFile()) : step.getFile();
            FilePath app = workspace.child(expanded);
            return TestingBotUploader.upload(app, credentials, listener);
        }
    }

    @Extension
    public static final class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "testingbotUpload";
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Upload an app to TestingBot Storage";
        }

        @Override
        public Set<Class<?>> getRequiredContext() {
            return Set.of(Run.class, FilePath.class, TaskListener.class);
        }

        @POST
        @SuppressWarnings("unused")
        public ListBoxModel doFillCredentialsIdItems(final @AncestorInPath Item context) {
            if (context == null ? !Jenkins.get().hasPermission(Jenkins.ADMINISTER)
                    : !context.hasPermission(Item.CONFIGURE)) {
                return new StandardListBoxModel();
            }
            return new StandardListBoxModel().includeEmptyValue().withMatching(
                    CredentialsMatchers.anyOf(CredentialsMatchers.instanceOf(TestingBotCredentials.class)),
                    CredentialsProvider.lookupCredentialsInItem(TestingBotCredentials.class, context, ACL.SYSTEM2,
                            new ArrayList<DomainRequirement>()));
        }
    }
}
