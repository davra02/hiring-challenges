package com.example.vat.proxy;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import java.nio.charset.StandardCharsets;

import java.time.Duration;

/**
 * Talks to the external VAT registry.
 *
 * <p>
 * Every call is bounded. When either bound is hit, {@link #lookup} throws
 * {@link java.net.http.HttpTimeoutException} (the connect bound throws its
 * subclass, {@link java.net.http.HttpConnectTimeoutException}), and the caller
 * decides what that means.
 * </p>
 *
 * <p>
 * The response bound is the JDK's request timeout, which stops running once
 * the response headers arrive. A registry that sends headers and then stalls
 * in the middle of the body is not covered by it.
 * </p>
 */
public class UpstreamVatClient {

	/**
	 * How long we wait to open a connection. Set on the {@link HttpClient},
	 * because it applies to opening connections rather than to any one
	 * request.
	 */
	public static final Duration CONNECT_TIMEOUT = Duration.ofMillis(1500);

	/**
	 * How long we wait for the registry to answer a request. It must stay well
	 * above the five seconds a legitimately slow member state takes
	 * (<code>DE811907980</code>): below that, a slow answer is reported as no
	 * answer, every time.
	 */
	public static final Duration RESPONSE_TIMEOUT = Duration.ofMillis(7500);

	public UpstreamVatClient(String baseURL, String apiKey) {
		_baseURL = baseURL;
		_apiKey = apiKey;
	}

	public HttpResponse<String> lookup(String vatId) throws Exception {
		HttpRequest httpRequest = HttpRequest.newBuilder(
			URI.create(
				_baseURL + "/vat/check?vatId=" +
					URLEncoder.encode(vatId, StandardCharsets.UTF_8))
		).header(
			"X-Api-Key", _apiKey
		).timeout(
			RESPONSE_TIMEOUT
		).GET(
		).build();

		return _httpClient.send(
			httpRequest, HttpResponse.BodyHandlers.ofString());
	}

	private final String _apiKey;
	private final String _baseURL;
	private final HttpClient _httpClient = HttpClient.newBuilder(
	).connectTimeout(
		CONNECT_TIMEOUT
	).build();

}
