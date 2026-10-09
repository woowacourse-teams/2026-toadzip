import { beforeEach, describe, expect, it, vi } from 'vitest'
import { requestManagementApi } from '../management/api'
import { getSupplyMatches, searchMatchingComplexes, getMatchingHousingTypes, refineSupplyMatches } from './supplyMatchingApi'

vi.mock('../management/api', () => ({ requestManagementApi: vi.fn() }))
describe('선택 적용 정제 API 계약', () => {
  beforeEach(() => vi.resetAllMocks())
  it('여러 공급행의 선택과 조회 토큰을 한 정제 요청으로 보낸다', async () => {
    vi.mocked(requestManagementApi).mockResolvedValue({
      createdAnnouncementCount: 1, updatedAnnouncementCount: 0, failedSourceRowCount: 0,
    })
    const rows = [{ rowIdentifier: '21395:LH:pan:2', token: 'token', complexId: 556, housingTypeId: 1745 }]
    await refineSupplyMatches('21395', rows)
    expect(requestManagementApi).toHaveBeenCalledWith('/api/admin/ingest/announcement-supply-matches/21395/refine',
      'POST', { rows })
  })
  it('실패나 잘못된 결과를 완료로 표시하지 않는다', async () => {
    vi.mocked(requestManagementApi).mockResolvedValue({ failedSourceRowCount: 1 })
    await expect(refineSupplyMatches('21395', [])).rejects.toThrow('정제 결과 응답')
  })
  it('원천면적과 실패 사유를 검증하여 읽는다', async () => {
    const row = { rowIdentifier: '21395:1', token: 'token', sourceComplexName: '익산한스빌', sourceHousingTypeName: '26A,B',
      pnu: '123', exclusiveArea: 26.5195, supplyArea: null, complexId: 556, housingTypeId: null, failure: '주택형 모호' }
    vi.mocked(requestManagementApi).mockResolvedValue([row])
    expect(await getSupplyMatches('21395')).toEqual([row])
  })
  it('잘못된 매칭 응답을 성공으로 처리하지 않는다', async () => {
    vi.mocked(requestManagementApi).mockResolvedValue([{ rowIdentifier: '21395:1', complexId: '556' }])
    await expect(getSupplyMatches('21395')).rejects.toThrow('매칭 정보 응답')
  })
  it('선택한 단지의 주택형만 조회한다', async () => {
    vi.mocked(requestManagementApi).mockResolvedValue([{ id: 1745, name: '26', exclusiveArea: 26.5195, supplyArea: 40.6486 }])
    expect(await getMatchingHousingTypes(556)).toHaveLength(1)
    expect(requestManagementApi).toHaveBeenCalledWith('/api/admin/ingest/announcement-supply-matches/complexes/556/housing-types',
      'GET', undefined, undefined)
  })
  it('단지 검색어를 쿼리 매개변수로 인코딩한다', async () => {
    vi.mocked(requestManagementApi).mockResolvedValue([])
    await searchMatchingComplexes('한스빌 & 26')
    expect(requestManagementApi).toHaveBeenCalledWith('/api/admin/ingest/announcement-supply-matches/complexes?query=%ED%95%9C%EC%8A%A4%EB%B9%8C+%26+26',
      'GET', undefined, undefined)
  })
})
