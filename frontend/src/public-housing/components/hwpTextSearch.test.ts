import { expect, it } from 'vitest'
import { readPageSize, searchPageText } from './hwpTextSearch.ts'

const run = (text: string, charStart: number, x: number, y = 20, cellIdx = 0) => ({
  text, charStart, x, y, w: Array.from(text).length * 10, h: 12,
  charX: Array.from({ length: Array.from(text).length + 1 }, (_, i) => i * 10),
  secIdx: 0, paraIdx: 0, parentParaIdx: 0, controlIdx: 0, cellIdx, cellParaIdx: 0,
})

it('서식 run과 줄바꿈으로 나뉜 표 안의 검색어를 찾아 각 위치를 강조한다', () => {
  const layout = JSON.stringify({ runs: [run('행복', 0, 10), run('주택', 2, 30, 40)] })
  expect(searchPageText(layout, '행복주택', 4)).toEqual([{ page: 4, rects: [
    { x: 10, y: 20, width: 20, height: 12 }, { x: 30, y: 40, width: 20, height: 12 },
  ] }])
})
it('별도 표 셀의 단어를 이어 붙여 거짓 결과를 만들지 않는다', () => {
  expect(searchPageText(JSON.stringify({ runs: [run('행복', 0, 10), run('주택', 0, 10, 40, 1)] }), '행복주택', 0)).toEqual([])
})
it('대소문자·연속 공백을 정규화하고 한 페이지의 모든 일치를 반환한다', () => {
  const matches = searchPageText(JSON.stringify({ runs: [run('LH  임대 lh 임대', 0, 0)] }), 'lh 임대', 0)
  expect(matches).toHaveLength(2)
  expect(matches[0]!.rects[0]).toEqual({ x: 0, y: 20, width: 60, height: 12 })
  expect(matches[1]!.rects[0]).toEqual({ x: 70, y: 20, width: 50, height: 12 })
})
it('보조 평면 문자를 포함한 글자 위치와 빈 검색을 처리한다', () => {
  const layout = JSON.stringify({ runs: [run('😀주택', 0, 5)] })
  expect(searchPageText(layout, '주택', 0)[0]!.rects).toEqual([{ x: 15, y: 20, width: 20, height: 12 }])
  expect(searchPageText(layout, ' ', 0)).toEqual([])
  expect(searchPageText('{"runs":[]}', '주택', 0)).toEqual([])
})
it('잘못된 SDK 좌표는 조용히 검색 결과 없음으로 처리하지 않는다', () => {
  expect(() => readPageSize('{"width":0,"height":10}')).toThrow()
  expect(() => searchPageText(JSON.stringify({ runs: [{ ...run('주택', 0, 0), charX: [0] }] }), '주택', 0)).toThrow()
  expect(readPageSize('{"width":794,"height":1123}')).toEqual({ width: 794, height: 1123 })
})

it('자동 쪽번호처럼 문단 위치가 없는 run도 독립 검색한다', () => {
  expect(searchPageText(JSON.stringify({ runs: [{ text: '- 1 -', x: 10, y: 20, h: 12, charX: [0, 10, 20, 30, 40, 50] }] }), '1', 0)).toEqual([{ page: 0, rects: [{ x: 30, y: 20, width: 10, height: 12 }] }])
})
