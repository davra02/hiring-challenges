/**
 * A VAT number field.
 *
 * It renders an input, asks the microservice whether the number is registered,
 * and shows the answer as one of the four outcomes of /o/vat/lookup.
 *
 * It asks sparingly: once the customer pauses typing, straight away when they
 * leave the field, and never twice in a row for the same value. It makes no
 * guess about what a VAT number looks like; the registry decides. Every
 * question takes a sequence token, and an answer is only shown if no newer
 * question has been asked since.
 *
 * Everything it knows lives on the instance, so the page can place it as many
 * times as it likes.
 */

type LookupStatus = 'ERROR' | 'NOT_REGISTERED' | 'REGISTERED' | 'UNAVAILABLE';

interface LookupResponse {
	address?: string;
	name?: string;
	reason?: string;
	status: LookupStatus;
}

type FieldState =
	| 'checking'
	| 'error'
	| 'idle'
	| 'not-registered'
	| 'registered'
	| 'unavailable';

// Past the server's own worst case: its 7.5s request timeout, which already
// includes connecting.

const CLIENT_TIMEOUT_MS = 10000;

const DEBOUNCE_MS = 600;

const MESSAGES: Record<Exclude<FieldState, 'registered'>, string> = {
	'checking': 'Checking...',
	'error': 'Something went wrong on our end — please try again.',
	'idle': 'Enter a VAT number',
	'not-registered': 'Not a registered VAT number',
	'unavailable':
		"Couldn't verify this number right now — please try again shortly.",
};

// One state per server outcome, with no translation beyond the spelling.

const STATES: Record<LookupStatus, FieldState> = {
	ERROR: 'error',
	NOT_REGISTERED: 'not-registered',
	REGISTERED: 'registered',
	UNAVAILABLE: 'unavailable',
};

/**
 * The server's answer, or an ERROR of our own for anything that is not one: a
 * network failure, a body that is not JSON, or a body without a status we
 * know, such as the 400 for a missing vatId.
 */
async function fetchLookup(vatId: string): Promise<LookupResponse> {
	try {
		const response = await fetch(
			`/o/vat/lookup?vatId=${encodeURIComponent(vatId)}`
		);

		const body: unknown = await response.json();

		if (isLookupResponse(body)) {
			return body;
		}
	}
	catch {

		// Handled below, the same as an answer we cannot read.

	}

	return {status: 'ERROR'};
}

function isLookupResponse(body: unknown): body is LookupResponse {
	if (typeof body !== 'object' || body === null) {
		return false;
	}

	const {status} = body as {status?: unknown};

	return typeof status === 'string' && Object.hasOwn(STATES, status);
}

class VatField extends HTMLElement {
	connectedCallback() {
		const field = this.getAttribute('data-field') ?? 'billing';

		this.innerHTML = `
			<label for="vat-${field}">VAT number</label>
			<input autocomplete="off" id="vat-${field}" type="text" placeholder="ESB12345678">
			<div class="vat-status"></div>
		`;

		this._input = this.querySelector('input') as HTMLInputElement;
		this._statusElement = this.querySelector('.vat-status') as HTMLElement;

		this._debounceTimer = undefined;
		this._lastRequestedVatId = null;

		// Carried over rather than reset when the element is moved, so no
		// token issued from here on can match an answer still in flight from
		// before.

		this._sequence ??= 0;

		this._reset();

		this._input.addEventListener('blur', () => this._onBlur());
		this._input.addEventListener('input', () => this._onInput());
	}

	disconnectedCallback() {
		clearTimeout(this._debounceTimer);

		this._sequence++;
	}

	private async _lookup(vatId: string) {
		if (vatId === this._lastRequestedVatId) {
			return;
		}

		const sequence = ++this._sequence;

		this._lastRequestedVatId = vatId;

		this._render('checking');

		// The request is not cancelled, only no longer waited for. If it does
		// come back and nothing newer has been asked, its answer replaces
		// this one.

		const timeout = setTimeout(() => {
			if (sequence === this._sequence) {
				this._show(vatId, {status: 'ERROR'});
			}
		}, CLIENT_TIMEOUT_MS);

		const body = await fetchLookup(vatId);

		clearTimeout(timeout);

		if (sequence === this._sequence) {
			this._show(vatId, body);
		}
	}

	private _onBlur() {
		clearTimeout(this._debounceTimer);

		const vatId = this._input.value.trim();

		if (vatId) {
			this._lookup(vatId);
		}
	}

	private _onInput() {
		clearTimeout(this._debounceTimer);

		const vatId = this._input.value.trim();

		if (!vatId) {
			this._reset();

			return;
		}

		// Any edit is asked about once typing pauses, so an answer about a value
		// that is no longer in the field is replaced as soon as the customer
		// stops.

		this._debounceTimer = setTimeout(
			() => this._lookup(vatId),
			DEBOUNCE_MS
		);
	}

	private _render(state: FieldState, name?: string) {
		this._statusElement.setAttribute('data-state', state);
		this._statusElement.textContent =
			state === 'registered' ? `Registered: ${name}` : MESSAGES[state];
	}

	/**
	 * Back to idle. The token moves on, so an answer still in flight cannot
	 * overwrite this, and nothing counts as asked any more.
	 */
	private _reset() {
		this._sequence++;
		this._lastRequestedVatId = null;

		this._render('idle');
	}

	private _show(vatId: string, body: LookupResponse) {
		const state = STATES[body.status];

		// Only a verdict on the number is worth not asking about again. The
		// other two outcomes tell the customer to try again, so the same value
		// has to get through on the next blur.

		if (state === 'registered' || state === 'not-registered') {
			this._lastRequestedVatId = vatId;
		}
		else {
			this._lastRequestedVatId = null;
		}

		this._render(state, body.name ?? vatId);
	}

	private _debounceTimer?: ReturnType<typeof setTimeout>;
	private _input!: HTMLInputElement;
	private _lastRequestedVatId!: string | null;
	private _sequence!: number;
	private _statusElement!: HTMLElement;
}

if (!customElements.get('vat-field')) {
	customElements.define('vat-field', VatField);
}
