package com.example.vat.proxy;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;

import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;

import java.util.Locale;

/**
 * The microservice endpoint the client extension calls. Mapped at
 * <code>/o/vat/lookup</code>.
 *
 * <p>
 * The answer here is ours, not the registry's. Every call resolves to one of
 * the four {@link VatLookupStatus} outcomes, so the browser tells them apart
 * by reading one field of our own rather than somebody else's error format.
 * The classification is deliberately one-directional: only an explicit
 * {@code "valid":false} from the registry becomes
 * {@link VatLookupStatus#NOT_REGISTERED}; everything we cannot make sense of
 * falls to {@link VatLookupStatus#UNAVAILABLE} or
 * {@link VatLookupStatus#ERROR}, never to a verdict on the number.
 * </p>
 *
 * <p>
 * Every call to the registry is bounded in time by {@link UpstreamVatClient}
 * and rationed by one {@link UpstreamRateLimiter} shared across the process.
 * When the ration is spent we refuse before calling, with our own
 * <code>429</code>.
 * </p>
 *
 * <p>
 * TODO (T3): the call to the registry is still unshared.
 * </p>
 */
public class VatLookupServlet extends HttpServlet {

	public VatLookupServlet(
		UpstreamVatClient upstreamVatClient,
		UpstreamRateLimiter upstreamRateLimiter) {

		_upstreamVatClient = upstreamVatClient;
		_upstreamRateLimiter = upstreamRateLimiter;
	}

	@Override
	protected void doGet(
			HttpServletRequest httpServletRequest,
			HttpServletResponse httpServletResponse)
		throws IOException {

		String vatId = httpServletRequest.getParameter("vatId");

		httpServletResponse.setContentType("application/json;charset=UTF-8");

		// Null and blank are checked on the raw value, before anything
		// touches it. A missing parameter is a malformed request, not one of
		// the four business outcomes, so it stays outside the enum.

		if ((vatId == null) || vatId.isBlank()) {
			httpServletResponse.setStatus(HttpServletResponse.SC_BAD_REQUEST);

			_write(httpServletResponse, "{\"error\":\"MISSING_VAT_ID\"}");

			return;
		}

		// Normalizing is the server's job and happens exactly once, here.
		// Everything downstream -- the registry call today, the cache key in
		// T3 -- sees the normalized value only, so "es b12345678 " and
		// "ESB12345678" cannot become two different lookups.

		vatId = vatId.trim(
		).toUpperCase(
			Locale.ROOT
		);

		// The budget is decided before the call, not read off its failure. A
		// spent budget is not a registry failure, so it does not take the 502
		// below: HTTP has a status for "slow down", and the registry uses the
		// same one.

		long retryAfterSeconds = _upstreamRateLimiter.tryAcquire();

		if (retryAfterSeconds > 0) {
			log(
				"VAT lookup for " + vatId + " refused, upstream budget spent " +
					"for another " + retryAfterSeconds + "s");

			httpServletResponse.setStatus(_SC_TOO_MANY_REQUESTS);
			httpServletResponse.setHeader(
				"Retry-After", String.valueOf(retryAfterSeconds));

			_write(
				httpServletResponse,
				_objectMapper.writeValueAsString(
					VatLookupResponse.error("OWN_RATE_LIMIT_EXCEEDED")));

			return;
		}

		VatLookupResponse vatLookupResponse = _lookup(vatId);

		if (vatLookupResponse.getStatus() == VatLookupStatus.ERROR) {

			// 502 is reserved for this one controlled case, so a plain 500
			// stays recognizable as a bug we did not plan for.

			httpServletResponse.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
		}
		else {
			httpServletResponse.setStatus(HttpServletResponse.SC_OK);
		}

		_write(
			httpServletResponse,
			_objectMapper.writeValueAsString(vatLookupResponse));
	}

	/**
	 * Turns the registry's answer into one of ours.
	 */
	private VatLookupResponse _classify(HttpResponse<String> httpResponse) {
		int statusCode = httpResponse.statusCode();

		if (statusCode != HttpServletResponse.SC_OK) {
			return VatLookupResponse.error(_reasonFor(statusCode));
		}

		JsonNode jsonNode = null;

		try {
			jsonNode = _objectMapper.readTree(httpResponse.body());
		}
		catch (IOException ioException) {
			return VatLookupResponse.error("UPSTREAM_MALFORMED_RESPONSE");
		}

		JsonNode validJsonNode = jsonNode.path("valid");

		// A boolean means the registry reached the member state and the
		// member state answered. This is the only road to NOT_REGISTERED.

		if (validJsonNode.isBoolean()) {
			if (!validJsonNode.booleanValue()) {
				return VatLookupResponse.notRegistered();
			}

			return VatLookupResponse.registered(
				_text(jsonNode, "name"), _text(jsonNode, "address"));
		}

		// An explicit null is the registry saying it could not check, and
		// saying nothing about the number. Its business, not our failure.

		if (validJsonNode.isNull()) {
			String reason = _text(jsonNode, "reason");

			if (reason == null) {
				reason = "UNSPECIFIED";
			}

			return VatLookupResponse.unavailable(reason);
		}

		// Absent, or a shape we do not understand: our problem to report and
		// to monitor, and still not a verdict on the number.

		return VatLookupResponse.error("UPSTREAM_MALFORMED_RESPONSE");
	}

	private VatLookupResponse _lookup(String vatId) {
		try {
			return _classify(_upstreamVatClient.lookup(vatId));
		}
		catch (HttpTimeoutException httpTimeoutException) {

			// Either bound in UpstreamVatClient. A registry that is slow or
			// silent is worth watching apart from one we cannot reach at all.

			log("VAT lookup timed out for " + vatId, httpTimeoutException);

			return VatLookupResponse.error("UPSTREAM_TIMEOUT");
		}
		catch (Exception exception) {
			if (exception instanceof InterruptedException) {
				Thread.currentThread(
				).interrupt();
			}

			// The reason we hand back is coarse on purpose. The detail
			// belongs in the log, where it can be read without shipping our
			// internals to a browser.

			log("VAT lookup failed for " + vatId, exception);

			return VatLookupResponse.error("UPSTREAM_CALL_FAILED");
		}
	}

	private String _reasonFor(int statusCode) {
		return switch (statusCode) {
			case HttpServletResponse.SC_UNAUTHORIZED ->
				"UPSTREAM_AUTH_FAILURE";
			case 429 -> "UPSTREAM_RATE_LIMITED";
			default -> "UPSTREAM_STATUS_" + statusCode;
		};
	}

	private String _text(JsonNode jsonNode, String fieldName) {
		JsonNode fieldJsonNode = jsonNode.path(fieldName);

		if (!fieldJsonNode.isTextual()) {
			return null;
		}

		return fieldJsonNode.textValue();
	}

	private void _write(HttpServletResponse httpServletResponse, String body)
		throws IOException {

		PrintWriter printWriter = httpServletResponse.getWriter();

		printWriter.write(body);
	}

	private static final int _SC_TOO_MANY_REQUESTS = 429;

	private static final ObjectMapper _objectMapper = new ObjectMapper(
	).setSerializationInclusion(
		JsonInclude.Include.NON_NULL
	);

	private final UpstreamRateLimiter _upstreamRateLimiter;
	private final UpstreamVatClient _upstreamVatClient;

}
