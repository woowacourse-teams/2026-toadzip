import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  enrichRecentComplexes,
  readRecentComplexes,
  RECENT_COMPLEXES_KEY,
  rememberComplex,
  type RecentComplex,
} from './recentComplexes.ts'

const first: RecentComplex = { complexId: '1', name: '첫 번째 단지', address: '수원시 장안구' }
const second: RecentComplex = { complexId: '2', name: '두 번째 단지', address: null }
const third: RecentComplex = { complexId: '3', name: '세 번째 단지', address: '수원시 팔달구' }
const fourth: RecentComplex = { complexId: '4', name: '네 번째 단지', address: '수원시 영통구' }

beforeEach(() => {
  localStorage.clear()
})

afterEach(() => {
  vi.restoreAllMocks()
  localStorage.clear()
})

describe('최근 본 단지', () => {
  it('처음 방문했을 때는 빈 기록으로 시작한다', () => {
    expect(readRecentComplexes()).toEqual([])
  })

  it('최근에 본 순서로 세 단지만 남기고 이전 기록은 변경하지 않는다', () => {
    const previous: readonly RecentComplex[] = [third, second, first]

    const next = rememberComplex(previous, fourth)

    expect(next).toEqual([fourth, third, second])
    expect(previous).toEqual([third, second, first])
  })

  it('다시 본 단지는 맨 앞으로 옮기고 최신 표시 정보로 갱신한다', () => {
    const updatedSecond = { ...second, name: '이름이 바뀐 두 번째 단지', address: '수원시 권선구' }

    const next = rememberComplex([third, second, first], updatedSecond)

    expect(next).toEqual([updatedSecond, third, first])
  })

  it('저장 후 다시 읽어도 마지막 방문의 순서와 표시 정보를 복원한다', () => {
    const afterFirstVisit = rememberComplex([], first)
    rememberComplex(afterFirstVisit, second)

    expect(readRecentComplexes()).toEqual([second, first])
    expect(JSON.parse(localStorage.getItem(RECENT_COMPLEXES_KEY) ?? 'null')).toEqual([second, first])
  })

  it('공급기관과 임대유형도 저장하고 다시 읽는다', () => {
    const complex = { ...first, agencyCode: 'SH', agencyName: '서울주택도시공사', rentalType: 'NATIONAL_RENTAL' }
    rememberComplex([], complex)
    expect(readRecentComplexes()).toEqual([complex])
  })

  it('목록에서 확인한 기존 기록의 기관과 임대유형은 지도 영역이 바뀌어도 유지한다', () => {
    const current = [second, first]
    const metadata = { complexId: first.complexId, agencyCode: 'SH', agencyName: '서울주택도시공사', rentalType: 'NATIONAL_RENTAL' }

    const enriched = enrichRecentComplexes(current, [metadata])

    expect(enriched).toEqual([second, { ...first, ...metadata }])
    expect(current).toEqual([second, first])
    expect(enrichRecentComplexes(enriched, [])).toBe(enriched)
    expect(readRecentComplexes()).toEqual(enriched)
  })

  it('이미 확인한 정보는 덮어쓰지 않고 기록이 같으면 다시 저장하지 않는다', () => {
    const complex = { ...first, agencyCode: 'SH', agencyName: '서울주택도시공사', rentalType: 'NATIONAL_RENTAL' }
    const current = [complex, second]
    const persist = vi.spyOn(Storage.prototype, 'setItem')

    expect(enrichRecentComplexes(current, [{ complexId: first.complexId, agencyCode: 'LH', agencyName: null, rentalType: null }])).toBe(current)
    expect(persist).not.toHaveBeenCalled()
  })

  it.each(['agencyCode', 'agencyName', 'rentalType'])('잘못 저장된 %s 값이 있는 항목은 제외한다', (field) => {
    localStorage.setItem(RECENT_COMPLEXES_KEY, JSON.stringify([{ ...first, [field]: { invalid: true } }, second]))
    expect(readRecentComplexes()).toEqual([second])
  })

  it.each(['{broken', 'null', '{}', '"not an array"', '42'])
  ('손상되거나 배열이 아닌 저장값 %s는 빈 기록으로 처리한다', (stored) => {
    localStorage.setItem(RECENT_COMPLEXES_KEY, stored)

    expect(readRecentComplexes()).toEqual([])
  })

  it('저장된 항목 중 잘못된 모양만 제외하고 정상 기록은 복원한다', () => {
    localStorage.setItem(RECENT_COMPLEXES_KEY, JSON.stringify([
      null,
      1,
      'invalid',
      {},
      { ...first, complexId: '' },
      { ...first, complexId: 1 },
      { ...first, name: '' },
      { ...first, name: false },
      { complexId: '5', name: '주소 필드가 없는 단지' },
      { ...first, address: {} },
      first,
      second,
    ]))

    expect(readRecentComplexes()).toEqual([first, second])
  })

  it('저장된 중복 ID는 가장 앞의 기록을 유지하며 세 단지만 복원한다', () => {
    localStorage.setItem(RECENT_COMPLEXES_KEY, JSON.stringify([
      fourth,
      { ...fourth, name: '과거 이름' },
      third,
      second,
      first,
    ]))

    expect(readRecentComplexes()).toEqual([fourth, third, second])
  })

  it('저장소 읽기가 차단되어도 빈 기록으로 탐색을 시작할 수 있다', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('Storage unavailable', 'SecurityError')
    })

    expect(readRecentComplexes()).toEqual([])
  })

  it('저장소 쓰기가 실패해도 현재 방문의 최근 기록은 갱신한다', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('Storage full', 'QuotaExceededError')
    })

    expect(rememberComplex([third, second, first], fourth)).toEqual([fourth, third, second])
  })
})
