import { describe, expect, it } from 'vitest'
import { parseDetailLocation } from './detailLocation.ts'
import {
  clearDetailHistoryState,
  popDetailHistoryState,
  readDetailReturnFocusStack,
  sameDetailLocation,
  toDetailReturnFocusLocation,
  withDetailHistoryState,
  type DetailReturnFocus,
} from './detailHistory.ts'

const complex: DetailReturnFocus = { kind: 'complex', id: '17', actionKey: 'complex-17' }
const announcement: DetailReturnFocus = { kind: 'announcement', id: '201', actionKey: 'announcement-201' }

describe('상세 간 이동 history', () => {
  it('기존 state를 바꾸지 않고 방문 순서대로 쌓고 마지막 상세로 돌아간다', () => {
    const original = { otherFeature: 'keep' }
    const first = withDetailHistoryState(original, complex)
    const second = withDetailHistoryState(first, announcement)
    const returned = popDetailHistoryState(second)

    expect(readDetailReturnFocusStack(second)).toEqual([complex, announcement])
    expect(readDetailReturnFocusStack(returned)).toEqual([complex])
    expect(returned).toMatchObject(original)
    expect(original).toEqual({ otherFeature: 'keep' })
    expect(readDetailReturnFocusStack(first)).toEqual([complex])
  })

  it('상세를 닫으면 과거 버전의 표식도 정리하고 무관한 state는 유지한다', () => {
    const original = { ...withDetailHistoryState({ otherFeature: 0 }, complex), toadzipDetailEntry: true }
    expect(clearDetailHistoryState(original)).toEqual({ otherFeature: 0 })
    expect(readDetailReturnFocusStack(original)).toEqual([complex])
    expect(original.toadzipDetailEntry).toBe(true)
  })

  it.each([null, undefined, 1, 'bad', [], { toadzipDetailReturnFocusStack: {} }])
   ('잘못된 외부 history state %j를 빈 탐색 기록으로 처리한다', (state) => {
      expect(readDetailReturnFocusStack(state)).toEqual([])
      expect(readDetailReturnFocusStack(withDetailHistoryState(state, complex))).toEqual([complex])
      expect(readDetailReturnFocusStack(popDetailHistoryState(state))).toEqual([])
    })

  it('유효하지 않은 복귀 대상만 제외하고 나머지 순서를 유지한다', () => {
    const state = { toadzipDetailReturnFocusStack: [complex, null, {}, { ...complex, id: 17 },
      { ...complex, kind: 'unknown' }, { ...complex, actionKey: null }, announcement] }
    expect(readDetailReturnFocusStack(state)).toEqual([complex, announcement])
  })

  it('복귀 대상이 없는 이동은 기존 스택을 유지한다', () => {
    expect(readDetailReturnFocusStack(withDetailHistoryState(withDetailHistoryState({}, complex), null)))
      .toEqual([complex])
  })

  it.each([
    ['complexId=17', { kind: 'complex', id: '17' }],
    ['announcementId=201', { kind: 'announcement', id: '201' }],
    ['', null],
    ['complexId=invalid', null],
  ])('URL %s의 상세만 복귀 대상으로 만든다', (query, expected) => {
    expect(toDetailReturnFocusLocation(parseDetailLocation(new URLSearchParams(query)))).toEqual(expected)
  })

  it('같은 숫자라도 단지와 공고의 위치를 구분한다', () => {
    expect(sameDetailLocation(complex, { kind: 'complex', id: '17' })).toBe(true)
    expect(sameDetailLocation(complex, { kind: 'announcement', id: '17' })).toBe(false)
    expect(sameDetailLocation(complex, { kind: 'complex', id: '18' })).toBe(false)
  })
})
