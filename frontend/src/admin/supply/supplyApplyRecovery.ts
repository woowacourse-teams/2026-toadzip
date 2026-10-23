import { parseCorrectionInput, parseSupplyPreview, type SupplyCorrectionInput, type SupplyPreview } from './supplyCorrectionApi'
import { isRecord } from '../management/managementContract'
export type PendingSupplyApply = { input: SupplyCorrectionInput; result: SupplyPreview }
const key = (id: string) => `admin:pending-supply-apply:${id}`
export function pendingApply(id: string): PendingSupplyApply | null {
  const stored = sessionStorage.getItem(key(id))
  if (!stored) return null
  const value: unknown = JSON.parse(stored)
  if (!isRecord(value)) throw new Error('적용 대기 기록을 읽지 못했습니다.')
  return { input: parseCorrectionInput(value.input), result: parseSupplyPreview(value.result) }
}
export function rememberApply(id: string, pending: PendingSupplyApply) { sessionStorage.setItem(key(id), JSON.stringify(pending)) }
export function forgetApply(id: string) { sessionStorage.removeItem(key(id)) }
