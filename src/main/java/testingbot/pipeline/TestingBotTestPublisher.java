package testingbot.pipeline;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.*;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.tasks.junit.TestDataPublisher;
import hudson.tasks.junit.TestResultAction;
import hudson.util.DescribableList;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import testingbot.TestReporter;

public class TestingBotTestPublisher extends Recorder implements SimpleBuildStep {
    private DescribableList<TestDataPublisher, Descriptor<TestDataPublisher>> testDataPublishers = new DescribableList<TestDataPublisher, Descriptor<TestDataPublisher>>(Saveable.NOOP);
    
    @DataBoundConstructor
    public TestingBotTestPublisher() {
        super();
    }
    
    public TestingBotTestPublisher(DescribableList<TestDataPublisher, Descriptor<TestDataPublisher>> testDataPublishers) {
        super();
        this.testDataPublishers = testDataPublishers;
    }
    
    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    /**
     * {@inheritDoc}
     *
     * Created for implementing SimpleBuildStep / pipeline
     */
    @Override
    public void perform(@Nonnull Run<?, ?> run, @Nonnull FilePath workspace, @Nonnull Launcher launcher, @Nonnull TaskListener listener) throws InterruptedException, IOException {
        TestResultAction report = run.getAction(TestResultAction.class);
        if (report != null) {
            List<TestResultAction.Data> data = new ArrayList<TestResultAction.Data>();
            if (testDataPublishers != null) {
                for (TestDataPublisher tdp : testDataPublishers) {
                    TestResultAction.Data d = tdp.contributeTestData(run, workspace, launcher, listener, report.getResult());
                    if (d != null) {
                        data.add(d);
                    }
                }
            }
            TestReporter tbPublisher = createReportPublisher();
            TestResultAction.Data d = tbPublisher.contributeTestData(run, workspace, launcher, listener, report.getResult());
            data.add(d);

            report.setData(data);
            run.save();
        } else {
            //no test publisher defined, process stdout only
            TestReporter tbPublisher = createReportPublisher();
            tbPublisher.contributeTestData(run, workspace, launcher, listener, null);
        }
    }

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
        addTestDataPublishersToBuildReport(build, launcher, listener);

        return true;
    }

    private void addTestDataPublishersToBuildReport(AbstractBuild build, Launcher launcher, BuildListener listener) throws IOException,
            InterruptedException {
        TestResultAction report = build.getAction(TestResultAction.class);
        if (report != null) {
            List<TestResultAction.Data> data = new ArrayList<TestResultAction.Data>();
            if (testDataPublishers != null) {
                for (TestDataPublisher tdp : testDataPublishers) {
                    TestResultAction.Data d = tdp.getTestData(build, launcher, listener, report.getResult());
                    if (d != null) {
                        data.add(d);
                    }
                }
            }
            TestReporter tbPublisher = createReportPublisher();
            TestResultAction.Data d = tbPublisher.getTestData(build, launcher, listener, report.getResult());
            data.add(d);

            report.setData(data);
            build.save();
        } else {
            //no test publisher defined, process stdout only
            TestReporter tbPublisher = createReportPublisher();
            tbPublisher.getTestData(build, launcher, listener, null);
        }
    }

    protected TestReporter createReportPublisher() {
        return new TestReporter();
    }

    public List<TestDataPublisher> getTestDataPublishers() {
        return testDataPublishers == null ? Collections.<TestDataPublisher>emptyList() : testDataPublishers;
    }

    @DataBoundSetter public final void setTestDataPublishers(@Nonnull List<TestDataPublisher> testDataPublishers) {
        this.testDataPublishers = new DescribableList<TestDataPublisher,Descriptor<TestDataPublisher>>(Saveable.NOOP);
        this.testDataPublishers.addAll(testDataPublishers);
    }

    @Extension
    @Symbol("testingbotPublisher")
    public static class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        @Override
        public String getDisplayName() {
            return "Run TestingBot Test Publisher";
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return !TestDataPublisher.all().isEmpty();
        }
    }
}
