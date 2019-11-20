package testingbot.pipeline;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernameListBoxModel;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.google.inject.Inject;
import com.testingbot.tunnel.Api;
import com.testingbot.tunnel.App;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.TopLevelItem;
import hudson.security.ACL;

import hudson.util.ListBoxModel;
import jenkins.security.MasterToSlaveCallable;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.jenkinsci.plugins.workflow.steps.BodyExecution;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.EnvironmentExpander;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.sf.json.JSONObject;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.PosixParser;
import testingbot.TestingBotBuildWrapper;
import static testingbot.TestingBotBuildWrapper.TB_KEY;
import static testingbot.TestingBotBuildWrapper.TB_SECRET;
import static testingbot.TestingBotBuildWrapper.TESTINGBOT_KEY;
import static testingbot.TestingBotBuildWrapper.TESTINGBOT_SECRET;
import testingbot.TestingBotCredentials;

public class TestingBotTunnelStep extends AbstractStepImpl {

    private String options;
    private String credentialsId;
    private static final App app = new App();

    @DataBoundConstructor
    public TestingBotTunnelStep(String credentialsId, String options) {
        this.credentialsId = credentialsId;
        this.options = options;
    }

    public String getOptions() {
        return options;
    }

    @DataBoundSetter
    public void setOptions(String options) {
        this.options = options;
    }

    @Extension
    public static final class DescriptorImpl extends AbstractStepDescriptorImpl {

        public DescriptorImpl() {
            super(TestingBotTunnelStepExecution.class);
        }

        @Override
        public String getDisplayName() {
            return "TestingBotTunnel";
        }

        @Override
        public String getFunctionName() {
            return "testingbotTunnel";
        }

        @Override
        public boolean takesImplicitBlockArgument() {
            return true;
        }

        public ListBoxModel doFillCredentialsIdItems(final @AncestorInPath Item context) {
            return new StandardListBoxModel()
                    .withAll(CredentialsProvider.lookupCredentials(
                            TestingBotCredentials.class,
                            context,
                            ACL.SYSTEM,
                            new ArrayList<DomainRequirement>()
                    ));
        }

    }

    private static final class TbStartTunnelHandler extends MasterToSlaveCallable<Void, Exception> {

        private final TestingBotCredentials tbCredentials;
        private final String tunnelOptions;
        private final TaskListener listener;

        TbStartTunnelHandler(TestingBotCredentials tbCredentials, String tunnelOptions, TaskListener listener) {
            this.tbCredentials = tbCredentials;
            this.tunnelOptions = tunnelOptions;
            this.listener = listener;
            CommandLine commandLine;
            CommandLineParser cmdLinePosixParser = new PosixParser();
            final Options options = new Options();

            options.addOption("h", "help", false, "Displays help text");
            options.addOption("d", "debug", false, "Enables debug messages");

            Option readyfile = new Option("f", "readyfile", true, "This file will be touched when the tunnel is ready for usage");
            readyfile.setArgName("FILE");
            options.addOption(readyfile);

            Option seleniumPort = new Option("P", "se-port", true, "The local port your Selenium test should connect to.");
            seleniumPort.setArgName("PORT");
            options.addOption(seleniumPort);

            Option fastFail = new Option("F", "fast-fail-regexps", true, "Specify domains you don't want to proxy, comma separated.");
            fastFail.setArgName("OPTIONS");
            options.addOption(fastFail);

            Option metrics = OptionBuilder.withLongOpt("metrics-port").hasArg().withValueSeparator().withDescription("Use the specified port to access metrics. Default port 8003").create();
            options.addOption(metrics);

            Option proxy = new Option("Y", "proxy", true, "Specify an upstream proxy.");
            proxy.setArgName("PROXYHOST:PROXYPORT");
            options.addOption(proxy);

            Option basicAuth = new Option("a", "auth", true, "Performs Basic Authentication for specific hosts.");
            basicAuth.setArgs(Option.UNLIMITED_VALUES);
            basicAuth.setArgName("host:port:user:passwd");
            options.addOption(basicAuth);

            Option pac = OptionBuilder.withLongOpt("pac").hasArg().withDescription("Proxy autoconfiguration. Should be a http(s) URL").create();
            options.addOption(pac);

            Option proxyAuth = new Option("z", "proxy-userpwd", true, "Username and password required to access the proxy configured with --proxy.");
            proxyAuth.setArgName("user:pwd");
            options.addOption(proxyAuth);

            Option logfile = new Option("l", "logfile", true, "Write logging to a file.");
            logfile.setArgName("FILE");
            options.addOption(logfile);

            Option identifier = new Option("i", "tunnel-identifier", true, "Add an identifier to this tunnel connection.\n In case of multiple tunnels, specify this identifier in your desired capabilities to use this specific tunnel connection.");
            identifier.setArgName("id");
            options.addOption(identifier);

            Option hubPort = new Option("p", "hubport", true, "Use this if you want to connect to port 80 on our hub instead of the default port 4444");
            hubPort.setArgName("HUBPORT");
            options.addOption(hubPort);

            Option extraHeaders = new Option(null, "extra-headers", true, "Inject extra headers in the requests the tunnel makes.");
            extraHeaders.setArgName("JSON Map with Header Key and Value");
            options.addOption(extraHeaders);

            Option dns = new Option("dns", "dns", true, "Use a custom DNS server. For example: 8.8.8.8");
            dns.setArgName("server");
            options.addOption(dns);

            Option localweb = new Option("w", "web", true, "Point to a directory for testing. Creates a local webserver.");
            localweb.setArgName("directory");
            options.addOption(localweb);

            options.addOption("x", "noproxy", false, "Do not start a local proxy (requires user provided proxy server on port 8087)");
            options.addOption("q", "nocache", false, "Bypass our Caching Proxy running on our tunnel VM.");
            options.addOption("j", "localproxy", true, "The port to launch the local proxy on (default 8087)");
            options.addOption(null, "doctor", false, "Perform checks to detect possible misconfiguration or problems.");
            options.addOption("v", "version", false, "Displays the current version of this program");
            
            try {
                commandLine = cmdLinePosixParser.parse(options, tunnelOptions.split(" "));
                if (commandLine.hasOption("debug")) {
                    app.setDebugMode(true);
                }
            } catch (Exception e) {
                
            }
        }

        @Override
        public Void call() {
            app.setClientKey(tbCredentials.getKey());
            app.setClientSecret(tbCredentials.getDecryptedSecret());
            try {
                app.boot();
                Api api = app.getApi();
                JSONObject response;
                boolean ready = false;
                String tunnelID = Integer.toString(app.getTunnelID());
                while (!ready) {
                    try {
                        response = api.pollTunnel(tunnelID);
                        ready = response.getString("state").equals("READY");
                    } catch (Exception ex) {
                        Logger.getLogger(TestingBotBuildWrapper.class.getName()).log(Level.SEVERE, null, ex);
                        break;
                    }
                    Thread.sleep(3000);
                }
            } catch (Exception ex) {
                Logger.getLogger(TestingBotBuildWrapper.class.getName()).log(Level.SEVERE, null, ex);
            }
            return null;
        }
    }

    private static final class TbStopTunnelHandler extends MasterToSlaveCallable<Void, Exception> {

        private final TestingBotCredentials tbCredentials;
        private final String options;
        private final TaskListener listener;

        TbStopTunnelHandler(TestingBotCredentials tbCredentials, String options, TaskListener listener) {
            this.tbCredentials = tbCredentials;
            this.options = options;
            this.listener = listener;
        }

        @Override
        public Void call() {
            listener.getLogger().println("Stopping TestingBot Tunnel");
            app.stop();
            return null;
        }
    }

    @SuppressFBWarnings("SE_NO_SERIALVERSIONID")
    public static class TestingBotTunnelStepExecution extends AbstractStepExecutionImpl {

        @Inject(optional = true)
        private transient TestingBotTunnelStep step;
        @StepContextParameter
        private transient TestingBotCredentials tbCredentials;
        @StepContextParameter
        private transient Computer computer;
        @StepContextParameter
        private transient Run<?, ?> run;
        @StepContextParameter
        private transient TaskListener listener;

        private BodyExecution body;

        @Override
        public boolean start() throws Exception {
            Job<?, ?> job = run.getParent();
            if (!(job instanceof TopLevelItem)) {
                throw new Exception(job + " must be a top-level job");
            }
            Node node = computer.getNode();
            if (node == null) {
                throw new Exception("computer does not correspond to a live node");
            }

            ArrayList<String> optionsArray = new ArrayList<String>();
            optionsArray.add(step.getOptions());
            optionsArray.removeAll(Collections.singleton("")); // remove the empty strings

            String options = StringUtils.join(optionsArray, " ");
            if (tbCredentials == null) {
                tbCredentials = TestingBotCredentials.getCredentials(job, step.getCredentialsId());
            }

            HashMap<String, String> env = new HashMap<String, String>();
            env.put(TESTINGBOT_KEY, tbCredentials.getKey());
            env.put(TB_KEY, tbCredentials.getKey());
            env.put(TESTINGBOT_SECRET, tbCredentials.getDecryptedSecret());
            env.put(TB_SECRET, tbCredentials.getDecryptedSecret());
            env.put("SELENIUM_PORT", "4445");
            env.put("SELENIUM_HOST", "localhost");

            listener.getLogger().println("Starting TestingBot Tunnel");

            TbStartTunnelHandler handler = new TbStartTunnelHandler(
                    tbCredentials,
                    options,
                    listener
            );
            computer.getChannel().call(handler);

            body = getContext().newBodyInvoker()
                    .withContext(EnvironmentExpander.merge(getContext().get(EnvironmentExpander.class), new ExpanderImpl(env)))
                    .withCallback(new Callback(tbCredentials, options))
                    .withDisplayName(null)
                    .start();

            return false;
        }

        @Override
        public void stop(@Nonnull Throwable cause) throws Exception {
            if (body != null) {
                body.cancel(cause);
            }
        }

        private static final class Callback extends BodyExecutionCallback.TailCall {

            private final String options;
            private final TestingBotCredentials tbCredentials;

            Callback(TestingBotCredentials tbCredentials, String options) {
                this.tbCredentials = tbCredentials;
                this.options = options;
            }

            @Override
            protected void finished(StepContext context) throws Exception {
                TaskListener listener = context.get(TaskListener.class);
                Computer computer = context.get(Computer.class);

                TbStopTunnelHandler stopTunnelHandler = new TbStopTunnelHandler(
                        tbCredentials,
                        options,
                        listener
                );
                computer.getChannel().call(stopTunnelHandler);
            }

        }
    }

    /**
     * @return the credentialsId
     */
    public String getCredentialsId() {
        return credentialsId;
    }

    /**
     * @param credentialsId the credentialsId to set
     */
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = credentialsId;
    }
}
