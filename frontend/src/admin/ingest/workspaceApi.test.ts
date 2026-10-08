import { beforeEach, expect, it, vi } from 'vitest'
import { requestManagementApi } from '../management/api'
import { getCorrection, getWorkspace, saveCorrection, type CorrectionDetail } from './workspaceApi'

vi.mock('../management/api', () => ({ requestManagementApi: vi.fn() }))
beforeEach(() => { vi.resetAllMocks() })

const detail: CorrectionDetail = {
  domain: 'complex', identifier: '338:NATIONAL_RENTAL', token: 'version', productId: null,
  managementOnly: false, editableFields: ['hsmpNm'], rows: [], latitude: null, longitude: null, changes: [],
}

it('도메인·상태·페이지를 서버 조회 조건으로 보낸다', async () => {
  const page = { items: [], counts: { WAITING: 0, FAILED: 0, INCOMPLETE: 0, READY: 0 },
    page: 1, totalElements: 0, hasNext: false }
  vi.mocked(requestManagementApi).mockResolvedValue(page)
  expect(await getWorkspace('complex', 'FAILED', 1)).toEqual(page)
  expect(requestManagementApi).toHaveBeenCalledWith(
    '/api/admin/ingest/workspace/complex?status=FAILED&page=1&size=20', 'GET', undefined, undefined)
})

it('잘못된 집계 숫자를 정상 데이터로 표시하지 않는다', async () => {
  vi.mocked(requestManagementApi).mockResolvedValue({ items: [],
    counts: { WAITING: 0, FAILED: -1, INCOMPLETE: 0, READY: 0 }, page: 0, totalElements: 0, hasNext: false })
  await expect(getWorkspace('complex', 'ALL', 0)).rejects.toThrow('응답이 올바르지 않습니다')
})

it('다른 대상의 상세 응답은 거절한다', async () => {
  vi.mocked(requestManagementApi).mockResolvedValue({ ...detail, identifier: '다른 대상' })
  await expect(getCorrection('complex', detail.identifier)).rejects.toThrow('응답이 올바르지 않습니다')
})

it('최종 저장에는 충돌 토큰과 원천 행별 보완값을 함께 보낸다', async () => {
  vi.mocked(requestManagementApi).mockResolvedValue({ productId: 338 })
  const rows = [{ sourceKey: '원천', changes: { parkngCo: 0 } }]
  expect(await saveCorrection(detail, rows, null, null)).toBe(338)
  expect(requestManagementApi).toHaveBeenCalledWith(
    '/api/admin/ingest/workspace/complex/338%3ANATIONAL_RENTAL', 'PUT',
    { token: 'version', rows, latitude: null, longitude: null })
})

it('잘못된 최종 ID를 저장 성공으로 처리하지 않는다', async () => {
  vi.mocked(requestManagementApi).mockResolvedValue({ productId: 0 })
  await expect(saveCorrection(detail, [], null, null)).rejects.toThrow('저장 결과 응답')
})
