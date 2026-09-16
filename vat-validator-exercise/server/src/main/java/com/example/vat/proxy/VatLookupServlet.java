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
 * TODO (T2, T3): the call to the registry is still unbounded, unrationed and
 * unshared.
 * </p>
 */
public class VatLookupServlet extends HttpServlet {

	public VatLookupServlet(UpstreamVatClient upstreamVatClient) {
		_upstreamVatClient = upstreamVatClient;
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

	private static final ObjectMapper _objectMapper = new ObjectMapper(
	).setSerializationInclusion(
		JsonInclude.Include.NON_NULL
	);

	private final UpstreamVatClient _upstreamVatClient;

}
