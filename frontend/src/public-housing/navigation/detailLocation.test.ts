import { describe, expect, it } from 'vitest'

import {
  clearAnnouncementIdQuery,
  clearComplexIdQuery,
  clearDetailQuery,
  parseAnnouncementIdQuery,
  parseComplexIdQuery,
  parseDetailLocation,
  setAnnouncementIdQuery,
  setComplexIdQuery,
} from './detailLocation.ts'

describe('parseComplexIdQuery', () => {
  it('complexId가 없으면 absent로 해석한다', () => {
    expect(parseComplexIdQuery(new URLSearchParams('tab=complex'))).toEqual({
      kind: 'absent',
      complexId: null,
    })
  })

  it.each(['1', '42', '9223372036854775807'])(
    '정규 positive Java Long ID %s를 허용한다',
    (complexId) => {
      expect(
        parseComplexIdQuery(new URLSearchParams({ complexId })),
      ).toEqual({ kind: 'valid', complexId })
    },
  )

  it.each([
    '',
    '0',
    '-1',
    '+1',
    '01',
    '1.0',
    ' 1',
    '1 ',
    '9223372036854775808',
    '99999999999999999999999999999999999999999999999999',
  ])('정규 positive Java Long ID가 아닌 %j를 거부한다', (complexId) => {
    expect(
      parseComplexIdQuery(new URLSearchParams({ complexId })),
    ).toEqual({ kind: 'invalid', complexId: null })
  })

  it('같은 값이더라도 complexId가 중복되면 무효로 처리한다', () => {
    const searchParams = new URLSearchParams('complexId=7&tab=map&complexId=7')

    expect(parseComplexIdQuery(searchParams)).toEqual({
      kind: 'invalid',
      complexId: null,
    })
  })
})

describe('setComplexIdQuery', () => {
  it('관련 없는 query 값과 순서를 보존하면서 complexId를 설정한다', () => {
    const current = new URLSearchParams('tab=map&filter=one&filter=two&empty=')

    const next = setComplexIdQuery(current, '9223372036854775807')

    expect(next.toString()).toBe(
      'tab=map&filter=one&filter=two&empty=&complexId=9223372036854775807',
    )
    expect(current.toString()).toBe('tab=map&filter=one&filter=two&empty=')
  })

  it('기존 complexId가 중복되어도 하나의 새 값으로 바꾼다', () => {
    const current = new URLSearchParams('complexId=1&tab=map&complexId=2')

    expect(setComplexIdQuery(current, '3').toString()).toBe(
      'complexId=3&tab=map',
    )
  })

  it('공고 상세 query를 제거하고 단지 상세 하나만 설정한다', () => {
    const current = new URLSearchParams('announcementId=4&tab=map')

    expect(setComplexIdQuery(current, '3').toString()).toBe(
      'tab=map&complexId=3',
    )
  })

  it('잘못된 complexId로 URL을 만들지 않는다', () => {
    expect(() =>
      setComplexIdQuery(new URLSearchParams('tab=map'), '01'),
    ).toThrowError('정규 positive Java Long ID가 아닙니다.')
  })
})

describe('announcementId query', () => {
  it.each(['1', '42', '9223372036854775807'])(
    '정규 positive Java Long ID %s를 허용한다',
    (announcementId) => {
      expect(parseAnnouncementIdQuery(
        new URLSearchParams({ announcementId }),
      )).toEqual({ kind: 'valid', announcementId })
    },
  )

  it('중복되거나 비정규인 ID는 무효로 처리한다', () => {
    expect(parseAnnouncementIdQuery(
      new URLSearchParams('announcementId=01&announcementId=1'),
    )).toEqual({ kind: 'invalid', announcementId: null })
  })

  it('단지 상세 query를 제거하고 공고 상세 하나만 설정한다', () => {
    const current = new URLSearchParams('complexId=4&tab=map')

    expect(setAnnouncementIdQuery(current, '3').toString()).toBe(
      'tab=map&announcementId=3',
    )
  })

  it('공고 상세 query만 모두 제거한다', () => {
    const current = new URLSearchParams(
      'announcementId=1&tab=map&announcementId=2',
    )

    expect(clearAnnouncementIdQuery(current).toString()).toBe('tab=map')
  })
})

describe('parseDetailLocation', () => {
  it('상세 ID가 없으면 none을 반환한다', () => {
    expect(parseDetailLocation(new URLSearchParams('tab=map'))).toEqual({
      kind: 'none',
    })
  })

  it('단지 상세 하나를 반환한다', () => {
    expect(parseDetailLocation(
      new URLSearchParams('tab=map&complexId=17'),
    )).toEqual({ kind: 'complex', complexId: '17' })
  })

  it('공고 상세 하나를 반환한다', () => {
    expect(parseDetailLocation(
      new URLSearchParams('announcementId=201&tab=map'),
    )).toEqual({ kind: 'announcement', announcementId: '201' })
  })

  it.each([
    'complexId=17&announcementId=201',
    'complexId=17&complexId=18',
    'announcementId=201&announcementId=202',
    'complexId=017',
    'announcementId=0',
  ])('모호하거나 비정규인 상세 query %s는 invalid다', (query) => {
    expect(parseDetailLocation(new URLSearchParams(query))).toEqual({
      kind: 'invalid',
    })
  })

  it('두 상세 query를 모두 제거하고 무관 query는 보존한다', () => {
    const current = new URLSearchParams(
      'complexId=1&tab=map&announcementId=2&filter=one',
    )

    expect(clearDetailQuery(current).toString()).toBe('tab=map&filter=one')
  })
})

describe('clearComplexIdQuery', () => {
  it('모든 complexId만 제거하고 관련 없는 query를 보존한다', () => {
    const current = new URLSearchParams(
      'complexId=1&tab=map&filter=one&complexId=2&filter=two',
    )

    const next = clearComplexIdQuery(current)

    expect(next.toString()).toBe('tab=map&filter=one&filter=two')
    expect(current.toString()).toBe(
      'complexId=1&tab=map&filter=one&complexId=2&filter=two',
    )
  })
})
