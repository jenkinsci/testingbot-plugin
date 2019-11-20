package testingbot;

import hudson.Launcher;
import hudson.Extension;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.AbstractProject;
import hudson.model.Item;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import hudson.util.ListBoxModel;
import java.io.*;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.nio.file.Paths;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import hudson.FilePath;
import hudson.security.ACL;
import hudson.util.FormValidation;
import java.util.ArrayList;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.QueryParameter;
import hudson.Util;
import org.apache.commons.lang.StringUtils;
import org.apache.tools.ant.FileScanner;
import org.apache.tools.ant.types.FileSet;

/**
 * @author testingbot.com
 */
public class TestingBotBuilder extends Builder {

    private final String name;

    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public TestingBotBuilder(String name) {
        this.name = name;
    }

    /**
     *
     * @return String name
     */
    public String getName() {
        return name;
    }

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) {
        return true;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        private String apiKey;
        private String apiSecret;

        public String getApiKey() {
            return apiKey;
        }

        public String getApiSecret() {
            return apiSecret;
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types 
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         *
         * @return String
         */
        @Override
        public String getDisplayName() {
            return "TestingBot";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            if (formData.has("testingBot")) {
                JSONObject config = formData.getJSONObject("testingBot");
                req.bindJSON(this, config);
                save();
            }

            return true;
        }

        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath final Item context) {
            if (context != null && !context.hasPermission(Item.CONFIGURE)) {
                return new StandardListBoxModel();
            }

            return new StandardListBoxModel()
                    .withMatching(CredentialsMatchers.anyOf(
                            CredentialsMatchers.instanceOf(TestingBotCredentials.class)),
                            CredentialsProvider.lookupCredentials(
                                    TestingBotCredentials.class,
                                    context,
                                    ACL.SYSTEM,
                                    new ArrayList<DomainRequirement>()));
        }

        public FormValidation doCheckLocalPath(@AncestorInPath final AbstractProject project,
                @QueryParameter final String localPath) {
            final String path = Util.fixEmptyAndTrim(localPath);
            if (StringUtils.isBlank(path)) {
                return FormValidation.ok();
            }

            try {
                File f = resolvePath(project, localPath);
                if (f != null) {
                    return FormValidation.ok();
                }
            } catch (Exception e) {
                return FormValidation.error(e.getMessage());
            }

            return FormValidation.error("Invalid path.");
        }

        public File resolvePath(final AbstractProject project, final String path) throws IOException, InterruptedException {
            File f = new File(path);
            if (f.isAbsolute() && (!f.isFile() || !f.canExecute())) {
                return null;
            }

            // For absolute paths
            FormValidation validateExec = FormValidation.validateExecutable(path);
            if (validateExec.kind == FormValidation.Kind.OK) {
                return f;
            }

            // Ant style path definitions
            FilePath workspace = project.getSomeWorkspace();
            if (workspace != null) {
                File workspaceRoot = new File(workspace.toURI());
                FileSet fileSet = Util.createFileSet(workspaceRoot, path);
                FileScanner fs = fileSet.getDirectoryScanner();
                fs.setIncludes(new String[]{path});
                fs.scan();

                String[] includedFiles = fs.getIncludedFiles();
                if (includedFiles.length > 0) {
                    File includedFile = new File(workspaceRoot, includedFiles[0]);
                    if (includedFile.exists() && includedFile.isFile() && includedFile.canExecute()) {
                        return includedFile;
                    }
                }
            }

            return null;
        }
    }
}
