import { beforeEach, describe, expect, it, vi } from 'vitest'
import { requestManagementApi } from '../management/api'
import { getSupplyMatches, searchMatchingComplexes, getMatchingHousingTypes, refineSupplyMatches, releaseSupplyMatch,
  getLhSupplyLinks, saveLhSupplyLinks } from './supplyMatchingApi'

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
  it('원천 PNU가 있으면 같은 필지 후보를 함께 요청한다', async () => {
    vi.mocked(requestManagementApi).mockResolvedValue([])
    await searchMatchingComplexes('대전중촌2', undefined, '3014010400101280001')
    expect(requestManagementApi).toHaveBeenCalledWith('/api/admin/ingest/announcement-supply-matches/complexes?query=%EB%8C%80%EC%A0%84%EC%A4%91%EC%B4%8C2&pnu=3014010400101280001',
      'GET', undefined, undefined)
  })
  it('기억할 선택의 근거와 후보 토큰을 기존 정제 API에 함께 보낸다', async () => {
    vi.mocked(requestManagementApi).mockResolvedValue({ createdAnnouncementCount: 1, updatedAnnouncementCount: 0,
      failedSourceRowCount: 0 })
    const rows = [{ rowIdentifier: '21395:1', token: 'row', complexId: 1, housingTypeId: 10, targetToken: 'type' }]
    const evidence = { remember: true as const, sourceUrl: 'https://example.com/official', reason: '3쪽 확인' }
    await refineSupplyMatches('21395', rows, evidence)
    expect(requestManagementApi).toHaveBeenCalledWith(expect.stringContaining('/21395/refine'), 'POST', { rows, ...evidence })
  })
  it('잘못된 저장 매칭의 근거 URL을 화면에 전달하지 않는다', async () => {
    vi.mocked(requestManagementApi).mockResolvedValue([{ rowIdentifier: '21395:1', token: 'row', sourceComplexName: '단지',
      sourceHousingTypeName: '26', pnu: '123', exclusiveArea: null, supplyArea: null, complexId: 1, housingTypeId: 10,
      failure: null, savedMatch: { complexId: 1, housingTypeId: 10, sourceUrl: 'javascript:alert(1)', reason: '3쪽',
        actor: 'admin', updatedAt: '2026-10-18T00:00:00Z', reviewRequired: false } }])
    await expect(getSupplyMatches('21395')).rejects.toThrow('저장한 매칭 응답')
  })
  it('선택 해제는 조회 토큰과 사유를 전달하고 최신 매칭 목록을 반환한다', async () => {
    vi.mocked(requestManagementApi).mockResolvedValue([])
    const row = { rowIdentifier: '21395:1', token: 'row', sourceComplexName: '단지', sourceHousingTypeName: '26',
      pnu: '123', exclusiveArea: null, supplyArea: null, complexId: 1, housingTypeId: 10, failure: null }
    expect(await releaseSupplyMatch('21395', row, '재검토')).toEqual([])
    expect(requestManagementApi).toHaveBeenCalledWith(expect.stringContaining('/21395/release'), 'POST',
      { rowIdentifier: '21395:1', token: 'row', reason: '재검토' })
  })
  it('LH 공급 연결을 조회 토큰과 근거로 저장하고 응답을 검증한다', async () => {
    const view = { token: 'token', myhomeRows: [{ rowIdentifier: 'myhome-4', complexName: '평화주공4단지',
      housingTypeName: '아파트', supplyHouseholdCount: 300, splitCorrected: false }],
    lhRows: [{ rowIdentifier: 'LH:pan:SUPPLY:0', complexLabel: '전주평화4 영구임대주택', typeName: '26',
      exclusiveArea: '26.37', supplyArea: null, suppliedUnitCount: '12', linkedRowIdentifier: null, savedLink: null }] }
    vi.mocked(requestManagementApi).mockResolvedValue(view)
    await saveLhSupplyLinks('19585', view, 'myhome-4', ['LH:pan:SUPPLY:0'], 'https://apply.lh.or.kr', '공고문 3쪽')
    expect(requestManagementApi).toHaveBeenCalledWith(
      '/api/admin/ingest/announcement-supply-matches/19585/lh-links', 'POST', { token: 'token',
        myhomeRowIdentifier: 'myhome-4', lhRowIdentifiers: ['LH:pan:SUPPLY:0'], sourceUrl: 'https://apply.lh.or.kr',
        reason: '공고문 3쪽' })
    vi.mocked(requestManagementApi).mockResolvedValue({ token: 'token', myhomeRows: [], lhRows: [{ rowIdentifier: 1 }] })
    await expect(getLhSupplyLinks('19585')).rejects.toThrow('LH 공급 연결 응답')
  })
})
