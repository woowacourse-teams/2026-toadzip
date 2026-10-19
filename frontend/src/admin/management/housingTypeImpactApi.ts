import { requestManagementApi } from './api'
import { isRecord } from './managementContract'
export type HousingTypeImpact = { token: string; rows: { announcementId: number; announcementName: string; supplyRowId: number; excluded: boolean; areaProtected: boolean; reviewedAreas: boolean; announcementVersion: number }[] }
export async function loadHousingTypeImpact(complexId: string, typeId: number): Promise<HousingTypeImpact> {
  const value = await requestManagementApi(`/api/admin/housing-complexes/${complexId}/housing-types/${typeId}/impact`, 'GET')
  if (!isRecord(value) || typeof value.token !== 'string' || !Array.isArray(value.rows) || !value.rows.every(row => isRecord(row)
    && ['announcementId', 'supplyRowId', 'announcementVersion'].every(field => typeof row[field] === 'number' && Number.isSafeInteger(row[field]))
    && typeof row.announcementName === 'string' && ['excluded', 'areaProtected', 'reviewedAreas'].every(field => typeof row[field] === 'boolean'))) throw new Error('주택형 영향 범위를 불러오지 못했습니다.')
  return value as HousingTypeImpact
}
