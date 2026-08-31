/**
 * Exact string and BigInt financial parsing and formatting utilities for INR currency.
 * Zero floating-point arithmetic is used to prevent precision loss.
 */

export interface ParseInrResult {
  ok: boolean;
  minorUnits?: number;
  minorUnitsBigInt?: bigint;
  error?: string;
}

/**
 * Parses user INR decimal string input (e.g. "100", "125.50", "0.25") into minor units.
 * Validates that input has at most 2 decimal places, is strictly positive, and is within safe integer range.
 */
export function parseInrToMinorUnits(input: string): ParseInrResult {
  if (!input) {
    return { ok: false, error: 'Amount is required' };
  }

  const trimmed = input.trim();
  if (!trimmed) {
    return { ok: false, error: 'Amount is required' };
  }

  // Regex strictly matching positive decimal amounts with up to 2 decimal places
  // Disallows scientific notation, leading/trailing extraneous characters, commas, signs
  const inrRegex = /^\d+(\.\d{1,2})?$/;
  if (!inrRegex.test(trimmed)) {
    return {
      ok: false,
      error: 'Invalid amount format. Enter a valid positive amount (e.g. 100.50)',
    };
  }

  const parts = trimmed.split('.');
  const integerPart = parts[0].replace(/^0+/, '') || '0';
  let decimalPart = parts[1] || '';

  if (decimalPart.length === 0) {
    decimalPart = '00';
  } else if (decimalPart.length === 1) {
    decimalPart = decimalPart + '0';
  }

  const combinedString = (integerPart === '0' ? '' : integerPart) + decimalPart;
  const normalizedString = combinedString.replace(/^0+/, '') || '0';

  let minorUnitsBigInt: bigint;
  try {
    minorUnitsBigInt = BigInt(normalizedString);
  } catch {
    return { ok: false, error: 'Amount is too large or invalid' };
  }

  if (minorUnitsBigInt <= 0n) {
    return { ok: false, error: 'Amount must be strictly greater than zero' };
  }

  if (minorUnitsBigInt > BigInt(Number.MAX_SAFE_INTEGER)) {
    return {
      ok: false,
      error: 'Amount exceeds maximum supported safe integer value',
    };
  }

  const minorUnits = Number(minorUnitsBigInt);
  if (!Number.isSafeInteger(minorUnits)) {
    return {
      ok: false,
      error: 'Amount cannot be safely represented as an integer',
    };
  }

  return {
    ok: true,
    minorUnits,
    minorUnitsBigInt,
  };
}

/**
 * Formats a minor-unit amount (e.g. "125000" or 125000n) to formatted INR string with symbol (e.g. "₹1,250.00").
 * Uses exact BigInt math and Indian number grouping (lakhs/crores).
 */
export function formatMinorUnitsToInr(
  minorUnits: string | bigint | number | null | undefined
): string {
  if (minorUnits === null || minorUnits === undefined || minorUnits === '') {
    return '₹0.00';
  }

  let val: bigint;
  try {
    val = typeof minorUnits === 'bigint' ? minorUnits : BigInt(String(minorUnits).trim());
  } catch {
    return '₹0.00';
  }

  const isNegative = val < 0n;
  const absVal = isNegative ? -val : val;

  const major = absVal / 100n;
  const minor = absVal % 100n;
  const minorStr = minor.toString().padStart(2, '0');

  // Format integer part using Indian numbering grouping:
  // Last 3 digits, then groups of 2 digits
  const majorStr = major.toString();
  let formattedMajor: string;

  if (majorStr.length <= 3) {
    formattedMajor = majorStr;
  } else {
    const lastThree = majorStr.substring(majorStr.length - 3);
    const otherDigits = majorStr.substring(0, majorStr.length - 3);
    const withCommas = otherDigits.replace(/\B(?=(\d{2})+(?!\d))/g, ',');
    formattedMajor = `${withCommas},${lastThree}`;
  }

  return `${isNegative ? '-' : ''}₹${formattedMajor}.${minorStr}`;
}
