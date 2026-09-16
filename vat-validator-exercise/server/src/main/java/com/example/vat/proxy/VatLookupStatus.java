package com.example.vat.proxy;

/**
 * The four outcomes of a lookup, as the browser sees them.
 *
 * <p>
 * They are exhaustive and they are the whole vocabulary: server and client
 * share these names with no translation in between. Anything we want to know
 * beyond them -- why exactly a call failed, for instance -- is metadata on
 * the response, never a fifth value here.
 * </p>
 *
 * <p>
 * {@link #NOT_REGISTERED} is the narrow one. It means the registry reached the
 * member state and the member state said no. Nothing else may be reported as
 * it: a call we could not make, an answer we could not read, or a member state
 * the registry could not reach are not verdicts on the customer's number.
 * </p>
 */
public enum VatLookupStatus {

	/**
	 * The registry knows this number. Carries {@code name} and
	 * {@code address}.
	 */
	REGISTERED,

	/**
	 * The registry says this number is not registered. The only outcome that
	 * tells the customer their number is wrong.
	 */
	NOT_REGISTERED,

	/**
	 * The registry answered, but could not check -- typically because the
	 * member state that owns the number is unreachable. Carries the
	 * registry's own business {@code reason}. Transient by nature.
	 */
	UNAVAILABLE,

	/**
	 * We failed: the call did not happen, did not finish, or came back in a
	 * shape we do not understand. Carries our own technical {@code reason}.
	 */
	ERROR

}
