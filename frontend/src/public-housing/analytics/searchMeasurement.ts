import { captureProductEvent, createAnalyticsId } from '../../analytics/productAnalytics'
import type { SearchType } from '../search/integratedSearchRepository'

export function createSearchMeasurement(surface: 'main_search' | 'welcome', query: string, types: readonly SearchType[]) {
  const searchId = createAnalyticsId()
  const length = query.replaceAll(' ', '').length
  const properties = { search_id: searchId, surface, query_length_bucket: length <= 5 ? '2_5' : length <= 10 ? '6_10' : '11_plus' }
  const finished = new Map<SearchType, { count: number; failed: boolean }>()
  let started = false
  let completed = false
  return {
    properties,
    start(type: SearchType) {
      finished.delete(type)
      completed = false
      if (started) return
      started = true
      captureProductEvent('search_executed', { ...properties, group_count: types.length })
    },
    settle(type: SearchType, count: number, failed: boolean) {
      finished.set(type, { count, failed })
      if (completed || !types.every(item => finished.has(item))) return
      completed = true
      const results = [...finished.values()]
      const failureCount = results.filter(item => item.failed).length
      captureProductEvent(failureCount > 0 ? 'search_failed' : 'search_results_loaded', {
        ...properties, result_count: results.reduce((sum, item) => sum + item.count, 0), failed_group_count: failureCount,
      })
    },
  }
}
export type SearchMeasurement = ReturnType<typeof createSearchMeasurement>
