import { expect, it } from 'vitest'
import { workReturnTo } from './workNavigation'

it.each(['https://evil.example/admin/quality', '//evil.example/admin/quality', '/admin/quality/../../login', '/admin/quality\\evil'])('외부 또는 허용되지 않은 복귀 주소 %s를 거절한다', value => {
  expect(workReturnTo(new URLSearchParams({ workReturnTo: value }))).toBeNull()
})
it('기존 오류 처리 링크와 중첩된 관리 목록 복귀를 보존한다', () => {
  const origin = '/admin/failures?domain=announcement&failure=announcement%3A7'
  expect(workReturnTo(new URLSearchParams({ returnTo: `/admin/announcements?${new URLSearchParams({ failureReturnTo: origin })}` }))).toBe(origin)
})
