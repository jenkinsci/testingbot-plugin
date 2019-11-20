package testingbot;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.export.Exported;

import com.cloudbees.plugins.credentials.BaseCredentials;
import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsDescriptor;
import com.cloudbees.plugins.credentials.CredentialsMatcher;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsNameProvider;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.NameWith;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.common.IdCredentials;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.impl.BaseStandardCredentials;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractItem;
import hudson.model.Item;
import hudson.model.ModelObject;
import hudson.model.User;
import hudson.util.FormValidation;
import hudson.util.Secret;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import jenkins.model.Jenkins;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

@NameWith(value = TestingBotCredentials.NameProvider.class)
public class TestingBotCredentials extends BaseCredentials implements StandardCredentials {
    private static final Logger logger = Logger.getLogger(TestingBotCredentials.class.getName());
    private static final String CREDENTIAL_DISPLAY_NAME = "TestingBot";
    private static final String OK_VALID_AUTH = "Success";
    private static final String ERR_INVALID_AUTH = "Invalid key or secret.";

    private final String id;
    private final String description;
    private final String key;
    private final Secret secret;

    @DataBoundConstructor
    public TestingBotCredentials(String id, String description, String key, String secret) {
        super(CredentialsScope.GLOBAL);
        this.id = IdCredentials.Helpers.fixEmptyId(id);
        this.description = Util.fixNull(description);
        this.key = Util.fixNull(key);
        this.secret = Secret.fromString(secret);
    }

    @Exported
    public String getKey() {
        return key;
    }

    public boolean hasKey() {
        return StringUtils.isNotBlank(key);
    }

    @Exported
    public Secret getSecret() {
        return secret;
    }

    public String getDecryptedSecret() {
        return secret.getPlainText();
    }

    public boolean hasSecret() {
        return (secret != null);
    }

    @NonNull
    @Exported
    public String getDescription() {
        return description;
    }

    @NonNull
    @Exported
    public String getId() {
        return id;
    }

    @Override
    public final boolean equals(Object o) {
        return IdCredentials.Helpers.equals(this, o);
    }

    @Override
    public final int hashCode() {
        return IdCredentials.Helpers.hashCode(this);
    }

    public static FormValidation testAuthentication(final String key, final String secret) {
        return FormValidation.ok();
    }

    private static List<String> getLegacyCredentials() {
        try {
            String apiKey = null;
            String apiSecret = null;
            FileInputStream fstream = new FileInputStream(Paths.get(System.getProperty("user.home"), ".testingbot").toFile());
            // Get the object of DataInputStream
            DataInputStream in = new DataInputStream(fstream);
            BufferedReader br = new BufferedReader(new InputStreamReader(in));
            String strLine = br.readLine();
            String[] data = strLine.split(":");
            apiKey = data[0];
            apiSecret = data[1];

            List<String> credentials = new ArrayList<>();
            credentials.add(apiKey);
            credentials.add(apiSecret);

            return credentials;
        } catch (IOException e) {
            Logger.getLogger(TestingBotCredentials.class.getName()).log(Level.SEVERE, null, e);
        }

        return null;
    }

    public static void migrate() throws IOException {
        final List<TestingBotCredentials> existingCredentials = TestingBotCredentials.availableCredentials(null);
Logger.getLogger(TestingBotCredentials.class.getName()).log(Level.INFO, "existingCredentials " + existingCredentials.size());
          
        if (existingCredentials == null || existingCredentials.isEmpty()) {
            Logger.getLogger(TestingBotCredentials.class.getName()).log(Level.INFO, "No existing credentials, will try to migrate");
          
            String createdCredentialId = UUID.randomUUID().toString();

            final List<String> legacyCredentials = TestingBotCredentials.getLegacyCredentials();
            if (legacyCredentials == null) {
                return;
            }
            Logger.getLogger(TestingBotCredentials.class.getName()).log(Level.INFO, "Found existing TestingBot credentials");
          
            final StandardCredentials credentialsToCreate;
            credentialsToCreate = new TestingBotCredentials(
                    createdCredentialId,
                    "migrated from old TestingBot credentials",
                    legacyCredentials.get(0),
                    legacyCredentials.get(1)
            );
            final SystemCredentialsProvider credentialsProvider = SystemCredentialsProvider.getInstance();
            final Map<Domain, List<Credentials>> credentialsMap = credentialsProvider.getDomainCredentialsMap();

            final Domain domain = Domain.global();
            if (credentialsMap.get(domain) == null) {
                credentialsMap.put(domain, Collections.EMPTY_LIST);
            }
            credentialsMap.get(domain).add(credentialsToCreate);

            credentialsProvider.setDomainCredentialsMap(credentialsMap);
            credentialsProvider.save();
        }
    }

    public static TestingBotCredentials getCredentials(final AbstractItem buildItem, final String credentialsId) {
        List<TestingBotCredentials> available = availableCredentials(buildItem);
        if (available.isEmpty()) {
            return null;
        }

        CredentialsMatcher matcher;
        if (credentialsId != null) {
            matcher = CredentialsMatchers.allOf(CredentialsMatchers.withId(credentialsId));
        } else {
            matcher = CredentialsMatchers.always();
        }

        return CredentialsMatchers.firstOrDefault(
                available,
                matcher,
                available.get(0));
    }

    public static List<TestingBotCredentials> availableCredentials(final AbstractItem abstractItem) {
        return CredentialsProvider.lookupCredentials(
                TestingBotCredentials.class,
                abstractItem,
                null,
                new ArrayList<>());
    }

    @Extension(ordinal = 1.0D)
    public static class DescriptorImpl extends CredentialsDescriptor {

        public DescriptorImpl() {
            clazz.asSubclass(TestingBotCredentials.class);
        }

        public DescriptorImpl(Class<? extends BaseStandardCredentials> clazz) {
            super(clazz);
        }

        public final FormValidation doAuthenticate(@QueryParameter("key") String key,
                @QueryParameter("secret") String secret) {
            return testAuthentication(key, secret);
        }

        @Override
        public String getDisplayName() {
            return CREDENTIAL_DISPLAY_NAME;
        }

        /**
         * @return always returns false since the scope of Local credentials are
         * always Global.
         */
        @Override
        public boolean isScopeRelevant() {
            return false;
        }

        /**
         * @return always returns false since the scope of Local credentials are
         * always Global.
         */
        @SuppressWarnings("unused") // used by stapler
        public boolean isScopeRelevant(ModelObject object) {
            return false;
        }

        @CheckForNull
        private static FormValidation checkForDuplicates(String value, ModelObject context, ModelObject object) {
            for (CredentialsStore store : CredentialsProvider.lookupStores(object)) {
                if (!store.hasPermission(CredentialsProvider.VIEW)) {
                    continue;
                }
                ModelObject storeContext = store.getContext();
                for (Domain domain : store.getDomains()) {
                    if (CredentialsMatchers.firstOrNull(store.getCredentials(domain), CredentialsMatchers.withId(value))
                            != null) {
                        if (storeContext == context) {
                            return FormValidation.error("This ID is already in use");
                        } else {
                            return FormValidation.warning("The ID ‘%s’ is already in use in %s", value,
                                    storeContext instanceof Item
                                            ? ((Item) storeContext).getFullDisplayName()
                                            : storeContext.getDisplayName());
                        }
                    }
                }
            }
            return null;
        }

        public final FormValidation doCheckId(@QueryParameter String value, @AncestorInPath ModelObject context) {
            if (value.isEmpty()) {
                return FormValidation.ok();
            }
            if (!value.matches("[a-zA-Z0-9_.-]+")) { // anything else considered kosher?
                return FormValidation.error("Unacceptable characters");
            }
            FormValidation problem = checkForDuplicates(value, context, context);
            if (problem != null) {
                return problem;
            }
            if (!(context instanceof User)) {
                User me = User.current();
                if (me != null) {
                    problem = checkForDuplicates(value, context, me);
                    if (problem != null) {
                        return problem;
                    }
                }
            }
            if (!(context instanceof Jenkins)) {
                // CredentialsProvider.lookupStores(User) does not return SystemCredentialsProvider.
                Jenkins j = Jenkins.getInstance();
                if (j != null) {
                    problem = checkForDuplicates(value, context, j);
                    if (problem != null) {
                        return problem;
                    }
                }
            }
            return FormValidation.ok();
        }
    }

    public static class NameProvider extends CredentialsNameProvider<TestingBotCredentials> {

        @Override
        public String getName(TestingBotCredentials credentials) {
            String description = Util.fixEmptyAndTrim(credentials.getDescription());
            return credentials.getKey() + "/******" + (description != null ? " (" + description + ")" : "");
        }
    }
}
