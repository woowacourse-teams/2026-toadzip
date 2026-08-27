import { describe, expect, it } from 'vitest'

import {
  clearComplexIdQuery,
  parseComplexIdQuery,
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

  it('잘못된 complexId로 URL을 만들지 않는다', () => {
    expect(() =>
      setComplexIdQuery(new URLSearchParams('tab=map'), '01'),
    ).toThrowError('정규 positive Java Long ID가 아닙니다.')
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
