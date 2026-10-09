export interface MapMarkerAmountParts {
  readonly digits: string
  readonly unit: '원' | '만' | '천' | '억'
}

export function formatAmount(amountWon: number | null | undefined, mode: 'COMPACT'): string {
  switch (mode) {
    case 'COMPACT': {
      const parts = compactMarkerAmountParts(amountWon)
      return parts === null ? '-' : `${parts.digits}${parts.unit}`
    }
  }
}

/** 마커·단지 목록 표기만 버림 처리하며 원본 금액은 바꾸지 않는다. */
export function compactMarkerAmountParts(
  amountWon: number | null | undefined,
): MapMarkerAmountParts | null {
  if (amountWon == null || !Number.isSafeInteger(amountWon) || amountWon < 0) return null
  if (amountWon === 0) return { digits: '0', unit: '원' }

  const amount = BigInt(amountWon)
  if (amountWon < 10_000) return tenthsParts(amount, 10_000n, '만')
  if (amountWon < 10_000_000) {
    return { digits: (amount / 10_000n).toLocaleString('ko-KR'), unit: '만' }
  }
  if (amountWon < 100_000_000) return tenthsParts(amount, 10_000_000n, '천')
  return tenthsParts(amount, 100_000_000n, '억')
}

function tenthsParts(
  amount: bigint,
  divisor: bigint,
  unit: '만' | '천' | '억',
): MapMarkerAmountParts {
  const tenths = amount / (divisor / 10n)
  const whole = (tenths / 10n).toLocaleString('ko-KR')
  const fraction = tenths % 10n
  return { digits: fraction === 0n ? whole : `${whole}.${fraction}`, unit }
}
