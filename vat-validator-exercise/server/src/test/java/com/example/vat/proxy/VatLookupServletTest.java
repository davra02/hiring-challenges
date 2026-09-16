package com.example.vat.proxy;

import com.example.vat.HostServer;
import com.example.vat.upstream.UpstreamVatServlet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import java.nio.charset.StandardCharsets;

import java.time.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Runs against a real server, because the thing under test is a response a
 * browser receives.
 *
 * <p>
 * The tests that need the registry to misbehave in a particular way, stalling
 * or counting its calls, start their own lookup endpoint against a registry of
 * their own, so they neither wait on nor spend the shared server's budget.
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
	@Test
	public void testRepeatedLookupHitsUpstreamOnce() throws Exception {

		// A registry of our own, so that every call it counts is one this
		// test made. It takes as long as the stand-in's ordinary lookup, which
		// is what gives concurrent requests a call in flight to share.

		AtomicInteger calls = new AtomicInteger();

		HttpServer httpServer = HttpServer.create(
			new InetSocketAddress("localhost", 0), 0);

		httpServer.createContext(
			"/vat/check",
			httpExchange -> {
				calls.incrementAndGet();

				try {
					Thread.sleep(UpstreamVatServlet.LATENCY);
				}
				catch (InterruptedException interruptedException) {
					Thread.currentThread(
					).interrupt();
				}

				byte[] body = (
					"{\"valid\":true,\"name\":\"Registered Trader ES\"," +
						"\"address\":\"1 Example Street, Springfield\"}"
				).getBytes(
					StandardCharsets.UTF_8
				);

				httpExchange.getResponseHeaders(
				).set(
					"Content-Type", "application/json"
				);
				httpExchange.sendResponseHeaders(200, body.length);

				try (OutputStream outputStream =
						httpExchange.getResponseBody()) {

					outputStream.write(body);
				}
			});

		// Answers concurrently, so that concurrent calls, if they were made,
		// would be counted as concurrent rather than queued behind each other

		httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

		httpServer.start();

		Tomcat tomcat = _startVatLookup(
			new UpstreamVatClient(
				"http://localhost:" + httpServer.getAddress(
				).getPort(),
				UpstreamVatServlet.API_KEY));

		try {
			int port = tomcat.getConnector(
			).getLocalPort();

			// Two people checking the same number at once. As many requests as
			// the budget has slots, so that calls which are not shared show
			// up as extra calls rather than as refusals.

			int requests = UpstreamRateLimiter.LIMIT;

			CyclicBarrier cyclicBarrier = new CyclicBarrier(requests);
			ExecutorService executorService = Executors.newFixedThreadPool(
				requests);

			try {
				List<Future<HttpResponse<String>>> futures = new ArrayList<>();

				for (int i = 0; i < requests; i++) {
					futures.add(
						executorService.submit(
							() -> {
								cyclicBarrier.await();

								return _get(port, "ESB12345678");
							}));
				}

				for (Future<HttpResponse<String>> future : futures) {
					_assertRegistered(future.get());
				}
			}
			finally {
				executorService.shutdownNow();
			}

			Assertions.assertEquals(
				1, calls.get(),
				"Concurrent lookups of one number did not share a call");

			// One person checking the same number twice, the second time
			// after the answer is in

			_assertRegistered(_get(port, "ESB12345678"));

			Assertions.assertEquals(
				1, calls.get(), "A repeated lookup called the registry again");
		}
		finally {
			tomcat.stop();
			tomcat.destroy();

			httpServer.stop(0);
		}
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
	@Test
	public void testUpstreamStallIsBounded() throws Exception {

		// The stand-in registry cannot stall us: its slowest answer, five
		// seconds, is inside our timeout on purpose. This registry takes the
		// connection and then says nothing for as long as the test runs.

		List<Socket> sockets = new CopyOnWriteArrayList<>();

		try (ServerSocket serverSocket = new ServerSocket(0)) {
			Thread.ofVirtual(
			).start(
				() -> {
					try {
						while (true) {
							sockets.add(serverSocket.accept());
						}
					}
					catch (IOException ioException) {
						// The server socket was closed: the test is over
					}
				}
			);

			Tomcat tomcat = _startVatLookup(
				new UpstreamVatClient(
					"http://localhost:" + serverSocket.getLocalPort(),
					UpstreamVatServlet.API_KEY));

			try {
				long start = System.nanoTime();

				HttpResponse<String> httpResponse = _get(
					tomcat.getConnector(
					).getLocalPort(),
					"ESB12345678");

				Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

				JsonNode jsonNode = _json(httpResponse);

				Assertions.assertEquals(502, httpResponse.statusCode());
				Assertions.assertEquals(
					VatLookupStatus.ERROR.name(), _status(jsonNode));
				Assertions.assertEquals(
					"UPSTREAM_TIMEOUT", jsonNode.path("reason").asText());

				// The connection was made, so what gave up was the wait for an
				// answer: not before its bound, and not long after it.

				Assertions.assertFalse(
					sockets.isEmpty(), "The silent registry was never reached");
				Assertions.assertTrue(
					elapsed.compareTo(UpstreamVatClient.RESPONSE_TIMEOUT) >= 0,
					"Gave up before the timeout, after " + elapsed.toMillis() +
						"ms");
				Assertions.assertTrue(
					elapsed.compareTo(
						UpstreamVatClient.RESPONSE_TIMEOUT.plusSeconds(2)) < 0,
					"Gave up too long after the timeout, after " +
						elapsed.toMillis() + "ms");
			}
			finally {
				for (Socket socket : sockets) {
					socket.close();
				}

				tomcat.stop();
				tomcat.destroy();
			}
		}
	}

	private static void _assertRegistered(HttpResponse<String> httpResponse)
		throws Exception {

		Assertions.assertEquals(
			200, httpResponse.statusCode(), httpResponse.body());
		Assertions.assertEquals(
			VatLookupStatus.REGISTERED.name(), _status(_json(httpResponse)));
	}

	private static HttpResponse<String> _get(int port, String vatId)
		throws Exception {

		HttpRequest httpRequest = HttpRequest.newBuilder(
			URI.create(
				"http://localhost:" + port + "/o/vat/lookup?vatId=" + vatId)
		).timeout(
			Duration.ofSeconds(30)
		).GET(
		).build();

		return _httpClient.send(
			httpRequest, HttpResponse.BodyHandlers.ofString());
	}

	private static HttpResponse<String> _get(String vatId) throws Exception {
		return _get(_PORT, vatId);
	}

	private static JsonNode _json(HttpResponse<String> httpResponse)
		throws Exception {

		return _objectMapper.readTree(httpResponse.body());
	}

	/**
	 * Starts the lookup endpoint alone, on a free port, against a registry of
	 * the test's choosing, with a cache and a budget of its own.
	 */
	private static Tomcat _startVatLookup(UpstreamVatClient upstreamVatClient)
		throws Exception {

		File baseDir = new File(
			System.getProperty("java.io.tmpdir"), "vat-lookup-test");

		baseDir.mkdirs();

		Tomcat tomcat = new Tomcat();

		tomcat.setBaseDir(baseDir.getAbsolutePath());
		tomcat.setPort(0);
		tomcat.getConnector();

		Context context = tomcat.addContext("", baseDir.getAbsolutePath());

		Tomcat.addServlet(
			context, "vatLookup",
			new VatLookupServlet(
				upstreamVatClient,
				new VatLookupCache(new UpstreamRateLimiter())));

		context.addServletMappingDecoded("/o/vat/lookup", "vatLookup");

		tomcat.start();

		return tomcat;
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
