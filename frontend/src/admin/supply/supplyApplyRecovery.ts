import { parseCorrectionInput, parseSupplyPreview, type SupplyCorrectionInput, type SupplyPreview } from './supplyCorrectionApi'
import { isRecord } from '../management/managementContract'
export type PendingSupplyApply = { input: SupplyCorrectionInput; result: SupplyPreview }
const key = (id: string) => `admin:pending-supply-apply:${id}`
/** 응답을 확인하지 못한 적용 작업. 읽을 수 없는 기록(이전 공급행 형식 등)은 같은 작업으로 다시 보낼 수 없으므로 지우고 없는 것으로 본다. */
export function pendingApply(id: string): PendingSupplyApply | null {
  const stored = sessionStorage.getItem(key(id))
  if (!stored) return null
  try {
    const value: unknown = JSON.parse(stored)
    if (!isRecord(value)) throw new Error('적용 대기 기록을 읽지 못했습니다.')
    return { input: parseCorrectionInput(value.input), result: parseSupplyPreview(value.result) }
  } catch {
    forgetApply(id)
    return null
  }
}
export function rememberApply(id: string, pending: PendingSupplyApply) { sessionStorage.setItem(key(id), JSON.stringify(pending)) }
export function forgetApply(id: string) { sessionStorage.removeItem(key(id)) }
