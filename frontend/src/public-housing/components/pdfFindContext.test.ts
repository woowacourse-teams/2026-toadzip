import { expect, it } from 'vitest'
import { matchesInContext } from './pdfFindContext'

const find = (content: string, word: string) => [...content.matchAll(new RegExp(`\\b${word}\\b`, 'g'))].map(match => ({ index: match.index, length: word.length }))

it('같은 쪽에 같은 숫자가 여럿이면 주택형 표기가 같은 줄에 있는 것만 남긴다', () => {
  const content = '주택형 건설호수 모집호수\n051.9800A 468 200\n059.9100A 200 25\n'
  const matches = find(content, '200')
  expect(matchesInContext(content, matches, ['051.9800A', '51.98']).map(match => content.slice(match.index - 14, match.index + 3))).toEqual(['051.9800A 468 200'])
})

it('문맥 표기가 있는 줄이 없으면 아무것도 강조하지 않고, 문맥이 없으면 후보를 그대로 둔다', () => {
  const content = '총 468세대 중 200세대\n'
  expect(matchesInContext(content, find(content, '200'), ['059.9300B'])).toEqual([])
  expect(matchesInContext(content, find(content, '200'), [])).toHaveLength(1)
})
