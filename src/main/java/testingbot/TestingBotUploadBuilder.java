package testingbot;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractProject;
import hudson.model.EnvironmentContributingAction;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.io.IOException;
import java.util.ArrayList;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/**
 * Freestyle build step that uploads a built mobile app ({@code .apk}/{@code .ipa}) to TestingBot
 * Storage before the tests run, and exports the resulting {@code tb://} app URL as an environment
 * variable (default {@code TESTINGBOT_APP_URL}) for later build steps to use as their Appium
 * {@code app} capability.
 *
 * <p>The pipeline equivalent is the {@code testingbotUpload} step, which returns the app URL
 * directly instead of exporting an environment variable.</p>
 */
public class TestingBotUploadBuilder extends Builder implements SimpleBuildStep {

    static final String DEFAULT_VARIABLE_NAME = "TESTINGBOT_APP_URL";

    private final String file;
    private String credentialsId;
    private String variableName;

    @DataBoundConstructor
    public TestingBotUploadBuilder(String file) {
        this.file = Util.fixEmptyAndTrim(file);
    }

    /** Workspace-relative path to the app file to upload. */
    public String getFile() {
        return file;
    }

    /** Id of the TestingBot credential to authenticate with; the first available when empty. */
    @CheckForNull
    public String getCredentialsId() {
        return credentialsId;
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = Util.fixEmpty(credentialsId);
    }

    /** Environment variable to export the uploaded app URL under. Defaults to {@code TESTINGBOT_APP_URL}. */
    public String getVariableName() {
        return Util.fixEmptyAndTrim(variableName) == null ? DEFAULT_VARIABLE_NAME : variableName;
    }

    @DataBoundSetter
    public void setVariableName(String variableName) {
        this.variableName = Util.fixEmptyAndTrim(variableName);
    }

    @Override
    public void perform(@NonNull Run<?, ?> run, @NonNull FilePath workspace, @NonNull EnvVars env,
            @NonNull Launcher launcher, @NonNull TaskListener listener) throws InterruptedException, IOException {
        if (file == null) {
            throw new AbortException("TestingBot: no app file configured to upload.");
        }
        Job<?, ?> job = run.getParent();
        TestingBotCredentials credentials = TestingBotCredentials.getCredentials(job, credentialsId);
        if (credentials == null) {
            throw new AbortException("TestingBot: no credentials found"
                    + (credentialsId != null ? " for id '" + credentialsId + "'" : "") + ".");
        }
        CredentialsProvider.track(run, credentials);

        FilePath app = workspace.child(env.expand(file));
        String appUrl = TestingBotUploader.upload(app, credentials, listener);

        // Expose the app URL to the following build steps of this freestyle build.
        run.addAction(new ExportedAppUrlAction(getVariableName(), appUrl));
    }

    /** Contributes the uploaded app URL as an environment variable to subsequent build steps. */
    static final class ExportedAppUrlAction implements EnvironmentContributingAction {

        private final String name;
        private final String value;

        ExportedAppUrlAction(String name, String value) {
            this.name = name;
            this.value = value;
        }

        @Override
        public void buildEnvironment(@NonNull Run<?, ?> run, @NonNull EnvVars env) {
            env.put(name, value);
        }

        @Override
        public String getIconFileName() {
            return null;
        }

        @Override
        public String getDisplayName() {
            return null;
        }

        @Override
        public String getUrlName() {
            return null;
        }
    }

    @Extension
    @Symbol("testingbotUpload")
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Upload an app to TestingBot Storage";
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        @POST
        @SuppressWarnings("unused")
        public FormValidation doCheckFile(@QueryParameter String value) {
            return Util.fixEmptyAndTrim(value) == null
                    ? FormValidation.error("Enter the path to the app (.apk/.ipa) to upload.")
                    : FormValidation.ok();
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
