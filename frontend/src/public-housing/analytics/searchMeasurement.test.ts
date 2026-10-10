import { beforeEach, describe, expect, it, vi } from 'vitest'
import { captureProductEvent } from '../../analytics/productAnalytics'
import { createSearchMeasurement } from './searchMeasurement'
vi.mock('../../analytics/productAnalytics', () => ({ captureProductEvent: vi.fn(() => true), createAnalyticsId: () => crypto.randomUUID() }))
const capture = vi.mocked(captureProductEvent)
beforeEach(() => capture.mockClear())
describe('논리 검색 계측', () => {
  it('세 유형의 요청을 하나의 검색으로 묶고 모두 끝나야 결과를 센다', () => {
    const measurement = createSearchMeasurement('main_search', '개인정보가 섞인 원문', ['REGION', 'ANNOUNCEMENT', 'COMPLEX'])
    measurement.start('REGION'); measurement.start('ANNOUNCEMENT'); measurement.start('COMPLEX')
    expect(capture).toHaveBeenCalledTimes(1)
    measurement.settle('COMPLEX', 2, false); measurement.settle('REGION', 1, false)
    expect(capture).toHaveBeenCalledTimes(1)
    measurement.settle('ANNOUNCEMENT', 3, false)
    expect(capture).toHaveBeenLastCalledWith('search_results_loaded', expect.objectContaining({ result_count: 6, failed_group_count: 0 }))
    measurement.settle('ANNOUNCEMENT', 3, false)
    expect(capture).toHaveBeenCalledTimes(2)
    expect(JSON.stringify(capture.mock.calls)).not.toContain('개인정보')
  })
  it('일부 실패를 빈 성공으로 세지 않고 재시도 결과를 별도로 남긴다', () => {
    const measurement = createSearchMeasurement('welcome', '서울', ['REGION'])
    measurement.start('REGION'); measurement.settle('REGION', 0, true)
    expect(capture).toHaveBeenLastCalledWith('search_failed', expect.objectContaining({ failed_group_count: 1 }))
    measurement.start('REGION'); measurement.settle('REGION', 4, false)
    expect(capture).toHaveBeenLastCalledWith('search_results_loaded', expect.objectContaining({ result_count: 4 }))
    expect(capture.mock.calls.filter(([name]) => name === 'search_executed')).toHaveLength(1)
  })
})
