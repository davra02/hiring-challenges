package com.example.vat.proxy;

/**
 * What <code>/o/vat/lookup</code> answers, as a value.
 *
 * <p>
 * Immutable, and deliberately ignorant of how it travels: it holds nothing
 * from <code>java.net.http</code> and nothing of the registry's wire format.
 * That is what lets it be handed to a browser today and held in a cache
 * later (T3) without either concern leaking into the other.
 * </p>
 *
 * <p>
 * Which fields are populated follows from the status, and only the static
 * factories can build one, so no combination exists that the contract does
 * not describe:
 * </p>
 *
 * <ul>
 * <li>{@link VatLookupStatus#REGISTERED}: {@code name}, {@code address}</li>
 * <li>{@link VatLookupStatus#NOT_REGISTERED}: nothing further</li>
 * <li>{@link VatLookupStatus#UNAVAILABLE}: {@code reason}, the registry's
 * business reason, e.g. {@code MEMBER_STATE_UNAVAILABLE}</li>
 * <li>{@link VatLookupStatus#ERROR}: {@code reason}, our own technical one</li>
 * </ul>
 *
 * <p>
 * Null fields are left out of the JSON, so each outcome serializes to exactly
 * the shape above.
 * </p>
 */
public final class VatLookupResponse {

	/**
	 * Our failure, described for us rather than for the customer. The reason
	 * is metadata -- for logs and monitoring -- and never a fifth outcome.
	 */
	public static VatLookupResponse error(String reason) {
		return new VatLookupResponse(
			VatLookupStatus.ERROR, null, null, reason);
	}

	/**
	 * The registry's verdict on the number, and the only way to produce it.
	 */
	public static VatLookupResponse notRegistered() {
		return new VatLookupResponse(
			VatLookupStatus.NOT_REGISTERED, null, null, null);
	}

	public static VatLookupResponse registered(String name, String address) {
		return new VatLookupResponse(
			VatLookupStatus.REGISTERED, name, address, null);
	}

	/**
	 * The registry could not check. The reason is the registry's word, passed
	 * through as it came.
	 */
	public static VatLookupResponse unavailable(String reason) {
		return new VatLookupResponse(
			VatLookupStatus.UNAVAILABLE, null, null, reason);
	}

	public String getAddress() {
		return _address;
	}

	public String getName() {
		return _name;
	}

	public String getReason() {
		return _reason;
	}

	public VatLookupStatus getStatus() {
		return _status;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();

		sb.append("VatLookupResponse[status=");
		sb.append(_status);

		if (_name != null) {
			sb.append(", name=");
			sb.append(_name);
		}

		if (_reason != null) {
			sb.append(", reason=");
			sb.append(_reason);
		}

		sb.append("]");

		return sb.toString();
	}

	private VatLookupResponse(
		VatLookupStatus status, String name, String address, String reason) {

		_status = status;
		_name = name;
		_address = address;
		_reason = reason;
	}

	private final String _address;
	private final String _name;
	private final String _reason;
	private final VatLookupStatus _status;

}
