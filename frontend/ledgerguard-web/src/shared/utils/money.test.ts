import { describe, it } from 'vitest';
import assert from 'node:assert/strict';
import { parseInrToMinorUnits, formatMinorUnitsToInr } from './money.ts';

describe('Financial Money Parsing (parseInrToMinorUnits)', () => {
  it('parses fractional amounts correctly into minor units (paise)', () => {
    assert.deepEqual(parseInrToMinorUnits('0.01'), {
      ok: true,
      minorUnits: 1,
      minorUnitsBigInt: 1n,
    });
    assert.deepEqual(parseInrToMinorUnits('0.10'), {
      ok: true,
      minorUnits: 10,
      minorUnitsBigInt: 10n,
    });
    assert.deepEqual(parseInrToMinorUnits('1'), {
      ok: true,
      minorUnits: 100,
      minorUnitsBigInt: 100n,
    });
    assert.deepEqual(parseInrToMinorUnits('1.2'), {
      ok: true,
      minorUnits: 120,
      minorUnitsBigInt: 120n,
    });
    assert.deepEqual(parseInrToMinorUnits('524.75'), {
      ok: true,
      minorUnits: 52475,
      minorUnitsBigInt: 52475n,
    });
    assert.deepEqual(parseInrToMinorUnits('999999.99'), {
      ok: true,
      minorUnits: 99999999,
      minorUnitsBigInt: 99999999n,
    });
  });

  it('rejects empty and whitespace-only inputs', () => {
    assert.equal(parseInrToMinorUnits('').ok, false);
    assert.equal(parseInrToMinorUnits('   ').ok, false);
  });

  it('rejects negative numbers', () => {
    assert.equal(parseInrToMinorUnits('-1').ok, false);
    assert.equal(parseInrToMinorUnits('-0.01').ok, false);
    assert.equal(parseInrToMinorUnits('-524.75').ok, false);
  });

  it('rejects numbers with more than two decimal places', () => {
    assert.equal(parseInrToMinorUnits('1.234').ok, false);
    assert.equal(parseInrToMinorUnits('0.001').ok, false);
    assert.equal(parseInrToMinorUnits('10.999').ok, false);
  });

  it('rejects exponential and scientific notation', () => {
    assert.equal(parseInrToMinorUnits('1e5').ok, false);
    assert.equal(parseInrToMinorUnits('1.2e3').ok, false);
    assert.equal(parseInrToMinorUnits('1E2').ok, false);
  });

  it('rejects alphabetic and non-numeric characters', () => {
    assert.equal(parseInrToMinorUnits('abc').ok, false);
    assert.equal(parseInrToMinorUnits('$100').ok, false);
    assert.equal(parseInrToMinorUnits('100.50.25').ok, false);
    assert.equal(parseInrToMinorUnits('NaN').ok, false);
    assert.equal(parseInrToMinorUnits('Infinity').ok, false);
  });

  it('rejects comma-containing inputs regardless of grouping', () => {
    assert.equal(parseInrToMinorUnits('1,000').ok, false);
    assert.equal(parseInrToMinorUnits('1,,0').ok, false);
    assert.equal(parseInrToMinorUnits(',100').ok, false);
    assert.equal(parseInrToMinorUnits('100,').ok, false);
    assert.equal(parseInrToMinorUnits('1,2,3').ok, false);
    assert.equal(parseInrToMinorUnits('12,34,567.89').ok, false);
  });

  it('rejects zero or non-positive values', () => {
    assert.equal(parseInrToMinorUnits('0').ok, false);
    assert.equal(parseInrToMinorUnits('0.0').ok, false);
    assert.equal(parseInrToMinorUnits('0.00').ok, false);
  });

  it('rejects unsafe integer overflow', () => {
    const huge = (BigInt(Number.MAX_SAFE_INTEGER) + 100n).toString();
    const result = parseInrToMinorUnits(huge);
    assert.equal(result.ok, false);
  });
});

describe('Financial Math & Invariant Calculations', () => {
  it('calculates remaining balance accurately in integer minor units (BigInt)', () => {
    const availableBalanceMinor = 150000n; // ₹1,500.00
    const parsedWithdrawal = parseInrToMinorUnits('524.75');
    assert.equal(parsedWithdrawal.ok, true);
    assert.ok(parsedWithdrawal.minorUnitsBigInt !== undefined);

    const remainingMinor = availableBalanceMinor - parsedWithdrawal.minorUnitsBigInt;
    assert.equal(remainingMinor, 97525n); // ₹975.25
    assert.equal(formatMinorUnitsToInr(remainingMinor), '₹975.25');
  });

  it('verifies platform fee calculation policy (100 bps floor rounding)', () => {
    const calculatePlatformFee = (grossMinor: bigint): { feeMinor: bigint; netMinor: bigint } => {
      // 100 bps = 1% = gross * 100 / 10000 = gross / 100
      const feeMinor = (grossMinor * 100n) / 10000n;
      const netMinor = grossMinor - feeMinor;
      return { feeMinor, netMinor };
    };

    // ₹150.00 (15,000 paise) -> fee = ₹1.50 (150 paise), net = ₹148.50 (14,850 paise)
    const { feeMinor, netMinor } = calculatePlatformFee(15000n);
    assert.equal(feeMinor, 150n);
    assert.equal(netMinor, 14850n);
    assert.equal(feeMinor + netMinor, 15000n);

    // Floor rounding: 99 paise -> fee = 0 paise, net = 99 paise
    const small = calculatePlatformFee(99n);
    assert.equal(small.feeMinor, 0n);
    assert.equal(small.netMinor, 99n);
  });

  it('distinguishes original payment net before refunds from net retained revenue after refunds', () => {
    const grossReceivedMinor = 2000000n; // ₹20,000.00
    const platformFeesMinor = 20000n;    // ₹200.00
    const originalNetCreditedMinor = grossReceivedMinor - platformFeesMinor; // ₹19,800.00
    const totalRefundedMinor = 500000n;  // ₹5,000.00 refunded to customers

    assert.equal(originalNetCreditedMinor, 1980000n);

    // Net retained revenue reflects money kept by merchant after refunds are debited
    const netRetainedRevenueMinor = originalNetCreditedMinor - totalRefundedMinor;
    assert.equal(netRetainedRevenueMinor, 1480000n); // ₹14,800.00
    assert.equal(formatMinorUnitsToInr(netRetainedRevenueMinor), '₹14,800.00');
  });

  it('resolves payout hold lifecycle states deterministically', () => {
    const resolveHoldStatus = (status: string): string => {
      switch (status) {
        case 'SUCCEEDED': return 'Consumed (Settled)';
        case 'FAILED': return 'Released (Available balance restored)';
        default: return 'Active (Funds held in wallet)';
      }
    };

    assert.equal(resolveHoldStatus('CREATED'), 'Active (Funds held in wallet)');
    assert.equal(resolveHoldStatus('PROCESSING'), 'Active (Funds held in wallet)');
    assert.equal(resolveHoldStatus('UNKNOWN'), 'Active (Funds held in wallet)');
    assert.equal(resolveHoldStatus('RECONCILIATION_REQUIRED'), 'Active (Funds held in wallet)');
    assert.equal(resolveHoldStatus('SUCCEEDED'), 'Consumed (Settled)');
    assert.equal(resolveHoldStatus('FAILED'), 'Released (Available balance restored)');
  });
});

describe('Currency & Amount Formatting (formatMinorUnitsToInr)', () => {
  it('formats minor units to INR with Indian number formatting (lakhs/crores)', () => {
    assert.equal(formatMinorUnitsToInr(100), '₹1.00');
    assert.equal(formatMinorUnitsToInr(52475n), '₹524.75');
    assert.equal(formatMinorUnitsToInr(10000000n), '₹1,00,000.00');
    assert.equal(formatMinorUnitsToInr(1000000000n), '₹1,00,00,000.00'); // 1 crore
    assert.equal(formatMinorUnitsToInr(0n), '₹0.00');
    assert.equal(formatMinorUnitsToInr(null), '—');
    assert.equal(formatMinorUnitsToInr(undefined), '—');
  });
});
