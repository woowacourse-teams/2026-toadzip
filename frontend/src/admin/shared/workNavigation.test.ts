import { expect, it } from 'vitest'
import { workDetailUrl, workReturnTo } from './workNavigation'

it.each(['https://evil.example/admin/failures', '//evil.example/admin/failures', '/admin/failures/../../login', '/admin/failures\\evil'])('외부 또는 허용되지 않은 복귀 주소 %s를 거절한다', value => {
  expect(workReturnTo(new URLSearchParams({ workReturnTo: value }))).toBeNull()
})
it('기존 오류 처리 링크와 중첩된 관리 목록 복귀를 보존한다', () => {
  const origin = '/admin/failures?domain=announcement&failure=announcement%3A7'
  expect(workReturnTo(new URLSearchParams({ returnTo: `/admin/announcements?${new URLSearchParams({ failureReturnTo: origin })}` }))).toBe(origin)
})

it('공고 원천 비교는 이력의 비교 영역을 열고 단지 검증과 공급정보 경로는 유지한다', () => {
  const url = new URL(workDetailUrl('ANNOUNCEMENT', 7, '/admin/failures'), 'https://local.invalid')
  expect(url.searchParams.get('section')).toBe('history')
  expect(url.searchParams.get('comparison')).toBe('source')
  expect(url.searchParams.get('workReturnTo')).toBe('/admin/failures')
  expect(workDetailUrl('COMPLEX', 7, '/admin/failures')).toContain('section=verification')
  expect(workDetailUrl('ANNOUNCEMENT', 7, '/admin/failures', 'relations')).toContain('section=relations')
})
