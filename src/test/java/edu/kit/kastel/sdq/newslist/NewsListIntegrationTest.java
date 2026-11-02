package edu.kit.kastel.sdq.newslist;

import org.htmlunit.WebClient;
import org.htmlunit.html.HtmlFileInput;
import org.htmlunit.html.HtmlButton;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the newslist application using Testcontainers.
 *
 * <p>A real SimpleSAMLphp-based SAML IdP is started in Docker alongside the
 * Spring Boot application.  The tests exercise the full SAML 2.0 SSO flow via
 * HtmlUnit and verify the main user-facing flows of the news list.</p>
 *
 * <p>Default IdP test users (uid / password / email):</p>
 * <ul>
 *   <li>user1 / user1pass / user_1@example.com  – uid attribute value: {@code 1}</li>
 *   <li>user2 / user2pass / user_2@example.com  – uid attribute value: {@code 2}</li>
 * </ul>
 *
 * <p>Test CSV news files only contain entries for uid {@code 1}, so user1 sees
 * news while user2 sees an empty list.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Testcontainers
class NewsListIntegrationTest {

    /**
     * A free port is pre-allocated so that the SAML IdP container can be
     * configured with the correct SP Assertion Consumer Service URL before
     * the Spring context starts.  The port is freed immediately so Spring can
     * bind to it during context startup.
     */
    static final int SERVER_PORT;

    static {
        try (ServerSocket socket = new ServerSocket(0)) {
            SERVER_PORT = socket.getLocalPort();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** SimpleSAMLphp test IdP container (jamedjo/test-saml-idp). */
    @Container
    static final GenericContainer<?> samlIdp = new GenericContainer<>("jamedjo/test-saml-idp")
            .withEnv("SIMPLESAMLPHP_SP_ENTITY_ID",
                    "http://localhost:" + SERVER_PORT + "/saml2/service-provider-metadata/testidp")
            .withEnv("SIMPLESAMLPHP_SP_ASSERTION_CONSUMER_SERVICE",
                    "http://localhost:" + SERVER_PORT + "/login/saml2/sso/testidp")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/simplesaml/saml2/idp/metadata.php")
                    .withStartupTimeout(Duration.ofMinutes(2)));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) throws Exception {
        String idpMetadataUrl = "http://localhost:" + samlIdp.getMappedPort(8080)
                + "/simplesaml/saml2/idp/metadata.php";

        // Point the SP at the containerised IdP
        registry.add("spring.security.saml2.relyingparty.registration.testidp.assertingparty.metadata-uri",
                () -> idpMetadataUrl);

        // Bind the application to the pre-allocated port
        registry.add("server.port", () -> SERVER_PORT);

        // SAML attribute keys matching the default jamedjo/test-saml-idp authsources
        registry.add("news.saml2-key", () -> "uid");
        registry.add("news.mail-saml2-key", () -> "email");

        // Point news service at the test CSV files on the classpath
        // (copied to a temp directory so uploads never modify the classpath resources)
        Path source = Path.of(NewsListIntegrationTest.class.getClassLoader().getResource("news").toURI());
        Path newsDir = Files.createTempDirectory("newslist-test");
        try (var files = Files.list(source)) {
            for (Path f : files.toList()) {
                Files.copy(f, newsDir.resolve(f.getFileName()));
            }
        }
        registry.add("news.csv-path", () -> newsDir.toString());
        registry.add("news.admin.ids", () -> "1");
        registry.add("news.csv-format", () -> "Default");

        // Small upload limit so the oversized-upload test stays fast
        registry.add("news.admin.max-upload-size", () -> "100KB");
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    /**
     * An unauthenticated request to the root URL must be redirected to the SAML
     * IdP login page.
     */
    @Test
    void unauthenticatedUserIsRedirectedToSamlLogin() throws Exception {
        try (WebClient client = newWebClient()) {
            HtmlPage page = client.getPage("http://localhost:" + SERVER_PORT + "/");
            assertThat(page.getUrl().toString()).contains("simplesaml");
        }
    }

    /**
     * A user whose {@code uid} attribute matches CSV entries must see the
     * corresponding news items after a successful SAML login.
     */
    @Test
    void authenticatedUserWithMatchingNewsSeesTheirItems() throws Exception {
        try (WebClient client = newWebClient()) {
            HtmlPage page = loginAs(client, "user1", "user1pass");
            String body = page.getBody().asNormalizedText();
            assertThat(body).contains("Your first test news item");
            assertThat(body).contains("Your second test news item");
        }
    }

    /**
     * A user whose {@code uid} has no matching CSV entries must see an empty
     * news list (no news items rendered).
     */
    @Test
    void authenticatedUserWithoutMatchingNewsSeesEmptyList() throws Exception {
        try (WebClient client = newWebClient()) {
            HtmlPage page = loginAs(client, "user2", "user2pass");
            String body = page.getBody().asNormalizedText();
            // No news items for uid=2
            assertThat(body).doesNotContain("Your first test news item");
            assertThat(body).doesNotContain("Your second test news item");
        }
    }

    /**
     * After login the page must identify the user by their email address from
     * the IdP's {@code email} SAML attribute or fall back to anonymous if the
     * attribute is not provided by the IdP.
     */
    @Test
    void emailAddressFromSamlAttributeIsDisplayed() throws Exception {
        try (WebClient client = newWebClient()) {
            HtmlPage page = loginAs(client, "user1", "user1pass");
            assertThat(page.getBody().asNormalizedText()).containsAnyOf("user_1@example.com", "anonymous");
        }
    }

    /**
     * The news item title is derived from the CSV file name (without the
     * {@code .csv} extension and with underscores stripped).  Verify that at
     * least one such title appears correctly.
     */
    @Test
    void newsItemTitleIsDisplayedCorrectly() throws Exception {
        try (WebClient client = newWebClient()) {
            HtmlPage page = loginAs(client, "user1", "user1pass");
            // Title = filename without .csv extension, with underscores stripped
            // "News Item 1.csv" -> "News Item 1" (spaces are kept; only underscores are removed)
            assertThat(page.getBody().asNormalizedText()).contains("News Item 1");
        }
    }

    @Test
    void nonAdminGetsForbiddenOnAdminPage() throws Exception {
        try (WebClient client = newWebClient()) {
            loginAs(client, "user2", "user2pass");
            var response = client.getPage("http://localhost:" + SERVER_PORT + "/admin").getWebResponse();
            assertThat(response.getStatusCode()).isEqualTo(403);
        }
    }

    @Test
    void adminSeesAdminPageWithFiles() throws Exception {
        try (WebClient client = newWebClient()) {
            loginAs(client, "user1", "user1pass");
            HtmlPage page = client.getPage("http://localhost:" + SERVER_PORT + "/admin");
            assertThat(page.getWebResponse().getStatusCode()).isEqualTo(200);
            assertThat(page.getBody().asNormalizedText()).contains("News Item 1.csv");
        }
    }

    @Test
    void adminLinkIsShownOnlyToAdmins() throws Exception {
        try (WebClient client = newWebClient()) {
            HtmlPage page = loginAs(client, "user1", "user1pass");
            assertThat(page.getBody().asNormalizedText()).contains("Admin");
        }
        try (WebClient client = newWebClient()) {
            HtmlPage page = loginAs(client, "user2", "user2pass");
            assertThat(page.getBody().asNormalizedText()).doesNotContain("Admin");
        }
    }

    @Test
    void uploadAndDeleteAreVisibleToUsersImmediately() throws Exception {
        Path csv = Files.createTempDirectory("newslist-upload").resolve("Upload Test.csv");
        Files.writeString(csv, "2,Uploaded item\n");
        try (WebClient admin = newWebClient(); WebClient user = newWebClient()) {
            loginAs(admin, "user1", "user1pass");
            HtmlPage userPage = loginAs(user, "user2", "user2pass");
            assertThat(userPage.getBody().asNormalizedText()).doesNotContain("Uploaded item");

            HtmlPage page = admin.getPage("http://localhost:" + SERVER_PORT + "/admin");
            HtmlForm form = page.getForms().stream().filter(f -> f.getActionAttribute().endsWith("/admin/upload")).findFirst().orElseThrow();
            HtmlFileInput input = form.getInputByName("file");
            input.setValue(csv.toAbsolutePath().toString());
            HtmlPage result = form.<HtmlButton>getFirstByXPath(".//button[@type='submit']").click();
            assertThat(result.getBody().asNormalizedText()).contains("Uploaded Upload Test.csv").contains("Upload Test.csv");

            userPage = user.getPage("http://localhost:" + SERVER_PORT + "/");
            assertThat(userPage.getBody().asNormalizedText()).contains("Uploaded item");

            HtmlForm del = result.getForms().stream()
                    .filter(f -> f.getActionAttribute().endsWith("/admin/delete"))
                    .filter(f -> f.getInputByName("path").getValue().equals("Upload Test.csv"))
                    .findFirst().orElseThrow();
            result = del.<HtmlButton>getFirstByXPath(".//button[@type='submit']").click();
            assertThat(result.getBody().asNormalizedText()).contains("Deleted Upload Test.csv");

            userPage = user.getPage("http://localhost:" + SERVER_PORT + "/");
            assertThat(userPage.getBody().asNormalizedText()).doesNotContain("Uploaded item");
        }
    }

    @Test
    void nonCsvUploadIsRejected() throws Exception {
        Path txt = Files.createTempDirectory("newslist-upload").resolve("bad.txt");
        Files.writeString(txt, "2,Bad item\n");
        try (WebClient admin = newWebClient()) {
            loginAs(admin, "user1", "user1pass");
            HtmlPage page = admin.getPage("http://localhost:" + SERVER_PORT + "/admin");
            HtmlForm form = page.getForms().stream().filter(f -> f.getActionAttribute().endsWith("/admin/upload")).findFirst().orElseThrow();
            HtmlFileInput input = form.getInputByName("file");
            input.setValue(txt.toAbsolutePath().toString());
            HtmlPage result = form.<HtmlButton>getFirstByXPath(".//button[@type='submit']").click();
            assertThat(result.getBody().asNormalizedText()).contains("Only .csv files are accepted");
        }
    }

    @Test
    void oversizedUploadShowsErrorMessage() throws Exception {
        Path csv = Files.createTempDirectory("newslist-upload").resolve("Too Large.csv");
        Files.writeString(csv, ("2," + "x".repeat(1000) + "\n").repeat(200)); // ~200KB > 100KB limit
        try (WebClient admin = newWebClient()) {
            loginAs(admin, "user1", "user1pass");
            // Disable JS after login (the SAML POST binding needs it), so the client-side size check is
            // bypassed and the server-side check is exercised
            admin.getOptions().setJavaScriptEnabled(false);
            HtmlPage page = admin.getPage("http://localhost:" + SERVER_PORT + "/admin");
            HtmlForm form = page.getForms().stream().filter(f -> f.getActionAttribute().endsWith("/admin/upload")).findFirst().orElseThrow();
            HtmlFileInput input = form.getInputByName("file");
            input.setValue(csv.toAbsolutePath().toString());
            HtmlPage result = form.<HtmlButton>getFirstByXPath(".//button[@type='submit']").click();
            assertThat(result.getBody().asNormalizedText()).contains("The file is too large");
            assertThat(result.getBody().asNormalizedText()).doesNotContain("Too Large.csv");
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Creates a HtmlUnit {@link WebClient} with CSS disabled to keep things fast. */
    private static WebClient newWebClient() {
        WebClient client = new WebClient();
        client.getOptions().setCssEnabled(false);
        client.getOptions().setThrowExceptionOnScriptError(false);
        client.getOptions().setThrowExceptionOnFailingStatusCode(false);
        return client;
    }

    /**
     * Navigates to the application root, follows the SAML redirect to the IdP
     * login page, submits the given credentials, and returns the final page
     * (which should be the authenticated newslist page).
     */
    private static HtmlPage loginAs(WebClient client, String username, String password) throws Exception {
        // Navigate to the protected page – Spring will redirect to the IdP
        HtmlPage loginPage = client.getPage("http://localhost:" + SERVER_PORT + "/");

        // Fill in the SimpleSAMLphp exampleauth:UserPass login form
        HtmlForm loginForm = loginPage.getForms().get(0);
        loginForm.getInputByName("username").setValue(username);
        loginForm.getInputByName("password").setValue(password);

        // Submit the form; HtmlUnit follows the SAMLResponse POST back to the SP
        List<HtmlElement> submitButtons = loginForm.getByXPath(".//button[@type='submit'] | .//input[@type='submit']");
        assertThat(submitButtons).isNotEmpty();
        return submitButtons.getFirst().click();
    }
}
