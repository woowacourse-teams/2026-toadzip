import { afterEach, expect, it, vi } from 'vitest'
import { changePdfAutoExtraction, loadCollectionOverview, loadComplexOverview, loadPdfAutoExtraction, loadUserOverview } from './api'
const signal = new AbortController().signal
const asOf = '2026-11-07T00:00:00Z'
const respond = (data: unknown) => vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ data }), { status: 200 })))
afterEach(() => vi.unstubAllGlobals())
it('실제 0건은 유지하고 AbortSignal과 관리자 집계 경로를 전달한다', async () => {
  respond({ total: 0, recruiting: 0, missingCoordinates: 0, reviewRequired: 0, agencies: { LH: 0, SH: 0, GH: 0 }, asOf })
  expect((await loadComplexOverview(signal)).total).toBe(0)
  expect(fetch).toHaveBeenCalledWith(expect.stringContaining('/api/admin/operations/complexes'), expect.objectContaining({ signal, credentials: 'include' }))
})
it.each([null, -1, 1.5, '0'])('잘못된 건수 %s는 0건으로 보정하지 않는다', async total => {
  respond({ total, recruiting: 0, missingCoordinates: 0, reviewRequired: 0, agencies: { LH: 0, SH: 0, GH: 0 }, asOf })
  await expect(loadComplexOverview(signal)).rejects.toThrow('응답이 올바르지 않습니다')
})
it('가입 추이는 실제 날짜와 요청한 일수를 검증한다', async () => {
  respond({ total: 0, newUsers: 0, previousNewUsers: 0, trend: [{ day: '2026-02-31', count: 0 }], asOf })
  await expect(loadUserOverview(7, signal)).rejects.toThrow('응답이 올바르지 않습니다')
})
it('수집 이력 없음은 null로 유지하며 기관 누락·중복을 거부한다', async () => {
  const sources = (['LH', 'SH', 'GH'] as const).map(agency => ({ agency, lastSuccessAt: null, latestStartedAt: null, latestStatus: null }))
  respond({ sources, asOf })
  expect((await loadCollectionOverview(signal)).sources[0].lastSuccessAt).toBeNull()
  respond({ sources: [sources[0], sources[0], sources[2]], asOf })
  await expect(loadCollectionOverview(signal)).rejects.toThrow('응답이 올바르지 않습니다')
})

const autoExtraction = { enabled: false, updatedBy: null, updatedAt: null, publicationMode: 'SHADOW', notExtracted: 0, inFlight: 0, maxInFlight: 2, asOf }
it('자동 추출 상태는 알 수 없는 자동 공개 모드와 잘못된 건수를 거부한다', async () => {
  respond(autoExtraction)
  expect((await loadPdfAutoExtraction(signal)).publicationMode).toBe('SHADOW')
  respond({ ...autoExtraction, publicationMode: 'ON' })
  await expect(loadPdfAutoExtraction(signal)).rejects.toThrow('응답이 올바르지 않습니다')
  respond({ ...autoExtraction, notExtracted: -1 })
  await expect(loadPdfAutoExtraction(signal)).rejects.toThrow('응답이 올바르지 않습니다')
})
it('자동 추출을 바꿀 때 CSRF 토큰과 켜짐 여부를 PUT으로 보낸다', async () => {
  const fetch = vi.fn(async (url: string) => new Response(JSON.stringify(url.endsWith('/api/admin/auth/csrf')
    ? { headerName: 'X-XSRF-TOKEN', token: 'csrf-token' } : { data: { ...autoExtraction, enabled: true, updatedBy: 'admin', updatedAt: asOf } }), { status: 200 }))
  vi.stubGlobal('fetch', fetch)
  expect((await changePdfAutoExtraction(true)).enabled).toBe(true)
  expect(fetch).toHaveBeenLastCalledWith(expect.stringContaining('/api/admin/operations/pdf-auto-extraction'), expect.objectContaining({
    method: 'PUT', body: JSON.stringify({ enabled: true }), headers: expect.objectContaining({ 'X-XSRF-TOKEN': 'csrf-token' }) }))
})
