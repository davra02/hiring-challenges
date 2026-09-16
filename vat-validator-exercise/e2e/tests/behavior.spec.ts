import {expect, test} from '@playwright/test';

// Enable these and make them pass.

test('typing a full number costs one lookup, not one per keystroke', async ({
	page,
}) => {
	const lookups: string[] = [];

	page.on('request', (request) => {
		if (request.url().includes('/o/vat/lookup')) {
			lookups.push(request.url());
		}
	});

	await page.goto('/checkout');

	await page
		.locator('vat-field[data-field="billing"] input')
		.pressSequentially('ESB12345678', {delay: 40});

	await page.waitForTimeout(2000);

	expect(lookups.length).toBeLessThanOrEqual(2);
});

test('the two instances do not write into each other', async ({page}) => {
	await page.goto('/checkout');

	await page
		.locator('vat-field[data-field="billing"] input')
		.fill('ESB12345678');

	await expect(
		page.locator('vat-field[data-field="billing"] .vat-status')
	).toContainText('Registered', {timeout: 15000});

	await expect(
		page.locator('vat-field[data-field="shipping"] .vat-status')
	).toContainText('Enter a VAT number');
});

test('a number the registry cannot check is not called invalid', async ({
	page,
}) => {
	await page.goto('/checkout');

	await page
		.locator('vat-field[data-field="billing"] input')
		.fill('ESB12345679');

	const status = page.locator(
		'vat-field[data-field="billing"] .vat-status'
	);

	await expect(status).not.toHaveAttribute('data-state', 'invalid', {
		timeout: 15000,
	});
});
