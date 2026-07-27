package testingbot;

import com.testingbot.models.TestingbotStorageUploadResponse;
import com.testingbot.testingbotrest.TestingbotApiException;
import com.testingbot.testingbotrest.TestingbotREST;
import com.testingbot.testingbotrest.TestingbotUnauthorizedException;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.FilePath;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import java.io.File;
import java.io.IOException;
import jenkins.MasterToSlaveFileCallable;

/**
 * Uploads a built app ({@code .apk}/{@code .ipa}, or a {@code .zip} of an iOS Simulator
 * {@code .app}) to TestingBot Storage and returns the resulting {@code tb://} app URL. Shared by the
 * freestyle {@link TestingBotUploadBuilder} build
 * step and the pipeline {@code testingbotUpload} step.
 *
 * <p>The upload runs on the node that holds the artifact (the build's agent), mirroring how the
 * tunnel boots on the agent: the app bytes are read locally and POSTed straight to the TestingBot
 * API, rather than streaming the whole artifact back to the controller. Only the decrypted
 * key/secret cross the (encrypted) remoting link, never the credential object.</p>
 */
public final class TestingBotUploader {

    private TestingBotUploader() {
    }

    /**
     * Uploads {@code app} to TestingBot Storage using the given credentials and returns the app URL
     * (for example {@code tb://app.apk}). Runs on the file's node.
     *
     * @throws AbortException if the file is missing, is a directory, or the API returns no app URL.
     */
    public static String upload(@NonNull FilePath app, @NonNull TestingBotCredentials credentials,
            @NonNull TaskListener listener) throws IOException, InterruptedException {
        if (!app.exists()) {
            throw new AbortException("TestingBot: app file to upload not found: " + app.getRemote());
        }
        if (app.isDirectory()) {
            throw new AbortException("TestingBot: expected an app file to upload but found a directory: "
                    + app.getRemote());
        }
        listener.getLogger().println("[TestingBot] Uploading " + app.getName() + " to TestingBot Storage...");
        String appUrl = app.act(new UploadToStorage(credentials.getKey(), credentials.getDecryptedSecret()));
        listener.getLogger().println("[TestingBot] Uploaded " + app.getName() + " to " + appUrl);
        return appUrl;
    }

    /** Reads the local app file on the node and uploads it through the TestingBot REST API. */
    private static final class UploadToStorage extends MasterToSlaveFileCallable<String> {

        private static final long serialVersionUID = 1L;
        private final String key;
        private final String secret;

        UploadToStorage(String key, String secret) {
            this.key = key;
            this.secret = secret;
        }

        @Override
        public String invoke(File file, VirtualChannel channel) throws IOException {
            try (TestingbotREST rest = new TestingbotREST(key, secret)) {
                TestingbotStorageUploadResponse response = rest.uploadToStorage(file);
                String appUrl = response == null ? null : response.getAppUrl();
                if (appUrl == null || appUrl.isEmpty()) {
                    throw new IOException("TestingBot Storage upload did not return an app URL for "
                            + file.getName() + " — check that the file is a valid app.");
                }
                return appUrl;
            } catch (TestingbotUnauthorizedException e) {
                throw new IOException("TestingBot rejected the credentials while uploading "
                        + file.getName() + ".", e);
            } catch (TestingbotApiException e) {
                throw new IOException("TestingBot Storage upload failed for " + file.getName()
                        + ": " + e.getMessage(), e);
            }
        }
    }
}
