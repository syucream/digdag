package acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import io.digdag.client.DigdagClient;
import io.digdag.client.api.JacksonTimeModule;
import io.digdag.client.api.RestSessionAttempt;
import io.digdag.spi.Notification;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import utils.TemporaryDigdagServer;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;
import static utils.TestUtils.addWorkflow;
import static utils.TestUtils.expect;
import static utils.TestUtils.pushAndStart;
import static utils.TestUtils.pushProject;
import static utils.TestUtils.startMockWebServer;
import static utils.TestUtils.startWorkflow;

public class ExecutionTimeoutIT
{
    private static final String WORKFLOW_NAME = "timeout_test_wf";
    private static final String PROJECT_NAME = "timeout_test_proj";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JacksonTimeModule())
            .registerModule(new GuavaModule());

    private TemporaryDigdagServer server;

    private Path projectDir;
    private DigdagClient client;

    private MockWebServer notificationServer;

    private String notificationUrl;

    @Before
    public void setUp()
            throws Exception
    {
        projectDir = folder.newFolder().toPath();
        notificationServer = startMockWebServer();
        notificationUrl = "http://localhost:" + notificationServer.getPort() + "/notification";
    }

    private void setup(String... configuration)
            throws Exception
    {
        server = TemporaryDigdagServer.builder()
                .configuration(
                        "notification.type = http",
                        "notification.http.url = " + notificationUrl
                )
                .configuration(configuration)
                .inProcess(false)
                .build();

        server.start();

        client = DigdagClient.builder()
                .host(server.host())
                .port(server.port())
                .build();
    }

    @After
    public void tearDownServer()
            throws Exception
    {
        if (server != null) {
            server.close();
            server = null;
        }
    }

    @After
    public void tearDownWebServer()
            throws Exception
    {
        if (notificationServer != null) {
            notificationServer.shutdown();
            notificationServer = null;
        }
    }

    @Test
    public void testAttemptTimeout()
            throws Exception
    {
        setup("executor.attempt_ttl = 10s",
                "executor.task_ttl = 1d",
                "executor.ttl_reaping_interval = 1s");

        addWorkflow(projectDir, "acceptance/attempt_timeout/attempt_timeout.dig", WORKFLOW_NAME + ".dig");
        pushProject(server.endpoint(), projectDir, PROJECT_NAME);
        long attemptId = startWorkflow(server.endpoint(), PROJECT_NAME, WORKFLOW_NAME);

        // Expect the attempt to get canceled
        expect(Duration.ofMinutes(1), () -> client.getSessionAttempt(attemptId).getCancelRequested());

        // And then the attempt should be done pretty soon
        expect(Duration.ofMinutes(1), () -> client.getSessionAttempt(attemptId).getDone());

        // Expect a notification to be sent
        expectNotification(attemptId, Duration.ofMinutes(1), "Workflow execution timeout"::equals);

        RestSessionAttempt attempt = client.getSessionAttempt(attemptId);
        assertThat(attempt.getDone(), is(true));
        assertThat(attempt.getCancelRequested(), is(true));
        assertThat(attempt.getSuccess(), is(false));
    }

    @Test
    public void testTaskTimeout()
            throws Exception
    {
        setup("executor.attempt_ttl = 1d",
                "executor.task_ttl = 10s",
                "executor.ttl_reaping_interval = 1s");

        addWorkflow(projectDir, "acceptance/attempt_timeout/task_timeout.dig", WORKFLOW_NAME + ".dig");
        pushProject(server.endpoint(), projectDir, PROJECT_NAME);
        long attemptId = startWorkflow(server.endpoint(), PROJECT_NAME, WORKFLOW_NAME);


        // Expect the attempt to get canceled when the task times out
        expect(Duration.ofMinutes(1), () -> client.getSessionAttempt(attemptId).getCancelRequested());

        // Expect a notification to be sent
        expectNotification(attemptId, Duration.ofMinutes(1), message -> Pattern.matches("Task execution timeout: \\d+", message));

        // TODO: implement termination of blocking tasks
        // TODO: verify that blocking tasks are terminated when the attempt is canceled

        RestSessionAttempt attempt = client.getSessionAttempt(attemptId);
        assertThat(attempt.getCancelRequested(), is(true));
    }

    private void expectNotification(long attemptId, Duration duration, Predicate<String> messageMatcher)
            throws InterruptedException, IOException
    {
        RecordedRequest recordedRequest = notificationServer.takeRequest(duration.getSeconds(), TimeUnit.SECONDS);
        assertThat(recordedRequest, is(not(nullValue())));
        verifyNotification(attemptId, recordedRequest, messageMatcher);
    }

    private void verifyNotification(long attemptId, RecordedRequest recordedRequest, Predicate<String> messageMatcher)
            throws IOException
    {
        String notificationJson = recordedRequest.getBody().readUtf8();
        Notification notification = mapper.readValue(notificationJson, Notification.class);
        assertThat(notification.getMessage(), messageMatcher.test(notification.getMessage()), is(true));
        assertThat(notification.getAttemptId().get(), is(attemptId));
        assertThat(notification.getWorkflowName().get(), is(WORKFLOW_NAME));
        assertThat(notification.getProjectName().get(), is(PROJECT_NAME));
    }
}
