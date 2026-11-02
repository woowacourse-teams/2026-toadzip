import { requestManagementApi } from '../management/api'
import { isRecord } from '../management/managementContract'

export type MatchingHousingType = { id: number; name: string; exclusiveArea: number; supplyArea: number | null; householdCount?: number | null; token?: string; basicDeposit?: number | null; basicMonthlyRent?: number | null }
const root = '/api/admin/ingest/announcement-supply-matches'
function number(value: unknown): value is number { return typeof value === 'number' && Number.isFinite(value) }
function id(value: unknown): value is number { return number(value) && Number.isSafeInteger(value) && value > 0 }
function nullableNumber(value: unknown): value is number | null { return value === null || number(value) }

export async function getMatchingHousingTypes(complexId: number, signal?: AbortSignal): Promise<MatchingHousingType[]> {
  const result = await requestManagementApi(`${root}/complexes/${complexId}/housing-types`, 'GET', undefined, signal)
  if (!Array.isArray(result)) throw new Error('주택형 응답이 올바르지 않습니다.')
  return result.map(value => {
    if (!isRecord(value) || !id(value.id) || typeof value.name !== 'string' || !number(value.exclusiveArea)
      || !nullableNumber(value.supplyArea)) throw new Error('주택형 응답이 올바르지 않습니다.')
    if (value.token !== undefined && typeof value.token !== 'string') throw new Error('주택형 응답이 올바르지 않습니다.')
    if (value.householdCount !== undefined && !nullableNumber(value.householdCount)) throw new Error('주택형 응답이 올바르지 않습니다.')
    if (!nullableNumber(value.basicDeposit ?? null) || !nullableNumber(value.basicMonthlyRent ?? null)) throw new Error('주택형 응답이 올바르지 않습니다.')
    return { id: value.id, name: value.name, exclusiveArea: value.exclusiveArea, supplyArea: value.supplyArea,
      ...(value.token !== undefined ? { token: value.token } : {}),
      ...(value.householdCount !== undefined ? { householdCount: value.householdCount } : {}),
      ...(value.basicDeposit !== undefined ? { basicDeposit: value.basicDeposit as number | null } : {}),
      ...(value.basicMonthlyRent !== undefined ? { basicMonthlyRent: value.basicMonthlyRent as number | null } : {}) }
  })
}
