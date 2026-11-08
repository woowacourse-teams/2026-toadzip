import type { CheckStatus, TypeStatus } from './conditionsModel'

export type MarkStatus = 'ok' | 'no' | 'tri' | 'na'

export function markOfCheck(status: CheckStatus): MarkStatus {
  if (status === 'SATISFIED') return 'ok'
  if (status === 'NOT_SATISFIED') return 'no'
  return 'tri'
}

export function markOfType(status: TypeStatus): MarkStatus {
  if (status === 'ELIGIBLE') return 'ok'
  if (status === 'INELIGIBLE') return 'no'
  if (status === 'NOT_EVALUATED') return 'na'
  return 'tri'
}
