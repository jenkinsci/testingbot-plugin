package testingbot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

/**
 * Verifies the {@link TestingBotUploadBuilder} configuration contract without a running Jenkins:
 * the app URL is exported under {@code TESTINGBOT_APP_URL} by default, a custom variable name is
 * honored, and blank inputs fall back rather than producing empty tokens.
 */
public class TestingBotUploadBuilderTest {

    @Test
    public void defaultsToTestingbotAppUrlVariable() {
        TestingBotUploadBuilder builder = new TestingBotUploadBuilder("build/app.apk");
        assertThat(builder.getVariableName()).isEqualTo(TestingBotUploadBuilder.DEFAULT_VARIABLE_NAME);
        assertThat(builder.getFile()).isEqualTo("build/app.apk");
        assertThat(builder.getCredentialsId()).isNull();
    }

    @Test
    public void honorsACustomVariableName() {
        TestingBotUploadBuilder builder = new TestingBotUploadBuilder("build/app.apk");
        builder.setVariableName("MY_APP");
        assertThat(builder.getVariableName()).isEqualTo("MY_APP");
    }

    @Test
    public void blankVariableNameFallsBackToDefault() {
        TestingBotUploadBuilder builder = new TestingBotUploadBuilder("build/app.apk");
        builder.setVariableName("   ");
        assertThat(builder.getVariableName()).isEqualTo(TestingBotUploadBuilder.DEFAULT_VARIABLE_NAME);
    }

    @Test
    public void exportedActionContributesTheAppUrl() {
        TestingBotUploadBuilder.ExportedAppUrlAction action =
                new TestingBotUploadBuilder.ExportedAppUrlAction("TESTINGBOT_APP_URL", "tb://app.apk");
        hudson.EnvVars env = new hudson.EnvVars();
        action.buildEnvironment(null, env);
        assertThat(env).containsEntry("TESTINGBOT_APP_URL", "tb://app.apk");
        assertThat(action.getUrlName()).isNull();
    }
}
