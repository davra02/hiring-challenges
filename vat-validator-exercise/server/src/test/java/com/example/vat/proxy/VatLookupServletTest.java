package com.example.vat.proxy;

import com.example.vat.HostServer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import java.time.Duration;

import org.apache.catalina.startup.Tomcat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Runs against a real server, because the thing under test is a response a
 * browser receives.
 *
 * <p>
 * The first two are green on a fresh clone. The rest describe behavior that
 * does not exist yet.
 * </p>
 */
public class VatLookupServletTest {

	@BeforeAll
	public static void setUpClass() throws Exception {
		_tomcat = HostServer.start(_PORT);
	}

	@AfterAll
	public static void tearDownClass() throws Exception {
		if (_tomcat != null) {
			_tomcat.stop();
			_tomcat.destroy();
		}
	}

	/**
	 * Asking twice for the same number must not cost two lookups against a
	 * registry that allows ten a minute.
	 */
	@Disabled("T3")
	@Test
	public void testRepeatedLookupHitsUpstreamOnce() {
		Assertions.fail("Not implemented");
	}

	@Test
	public void testRegisteredNumber() throws Exception {
		HttpResponse<String> httpResponse = _get("ESB12345678");

		JsonNode jsonNode = _json(httpResponse);

		Assertions.assertEquals(200, httpResponse.statusCode());
		Assertions.assertEquals(
			VatLookupStatus.REGISTERED.name(), _status(jsonNode));
		Assertions.assertTrue(
			jsonNode.hasNonNull("name"),
			"A registered number carries the trader's name: " +
				httpResponse.body());
	}

	/**
	 * A number the registry could not check is not an invalid number, and the
	 * browser has to be able to tell the two apart without parsing somebody
	 * else's error format.
	 */
	@Test
	public void testUncheckableNumberIsNotReportedAsInvalid() throws Exception {

		// The registry cannot reach the member state for anything ending in
		// a nine, and says so without saying anything about the number.

		HttpResponse<String> httpResponse = _get("ESB12345679");

		JsonNode jsonNode = _json(httpResponse);

		Assertions.assertEquals(200, httpResponse.statusCode());

		// The point of the test: an answer we could not get must never reach
		// the customer as a verdict on their number.

		Assertions.assertNotEquals(
			VatLookupStatus.NOT_REGISTERED.name(), _status(jsonNode),
			"An uncheckable number was reported as unregistered: " +
				httpResponse.body());

		Assertions.assertEquals(
			VatLookupStatus.UNAVAILABLE.name(), _status(jsonNode));

		// And it carries the registry's own reason, which is what makes the
		// outcome actionable rather than merely not-wrong.

		Assertions.assertEquals(
			"MEMBER_STATE_UNAVAILABLE", jsonNode.path("reason").asText());
	}

	@Test
	public void testUnregisteredNumber() throws Exception {
		HttpResponse<String> httpResponse = _get("ESB00000000");

		JsonNode jsonNode = _json(httpResponse);

		Assertions.assertEquals(200, httpResponse.statusCode());
		Assertions.assertEquals(
			VatLookupStatus.NOT_REGISTERED.name(), _status(jsonNode));
	}

	/**
	 * A registry that stops answering must not become a request that never
	 * ends.
	 */
	@Disabled("T2")
	@Test
	public void testUpstreamStallIsBounded() {
		Assertions.fail("Not implemented");
	}

	private static HttpResponse<String> _get(String vatId) throws Exception {
		HttpRequest httpRequest = HttpRequest.newBuilder(
			URI.create(
				"http://localhost:" + _PORT + "/o/vat/lookup?vatId=" + vatId)
		).timeout(
			Duration.ofSeconds(30)
		).GET(
		).build();

		return _httpClient.send(
			httpRequest, HttpResponse.BodyHandlers.ofString());
	}

	private static JsonNode _json(HttpResponse<String> httpResponse)
		throws Exception {

		return _objectMapper.readTree(httpResponse.body());
	}

	private static String _status(JsonNode jsonNode) {
		return jsonNode.path(
			"status"
		).asText();
	}

	private static final int _PORT = 8099;

	private static final HttpClient _httpClient = HttpClient.newHttpClient();

	private static final ObjectMapper _objectMapper = new ObjectMapper();

	private static Tomcat _tomcat;

}
