import { expect, it } from 'vitest'
import { ocrReadingText, ocrRenderScale } from './pdfOcrLayout'

const header = 'level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext'
const word = (text: string, left: number, top: number, height = 20) => `5\t1\t1\t1\t1\t1\t${left}\t${top}\t100\t${height}\t80\t${text}`

it('작은 표 글자를 300dpi 이상으로 렌더링하고 큰 페이지의 메모리와 길이를 제한한다', () => {
  expect(ocrRenderScale(595, 842)).toBeGreaterThanOrEqual(300 / 72)
  expect(ocrRenderScale(595, 842)).toBeLessThanOrEqual(400 / 72)
  const scale = ocrRenderScale(4000, 6000)
  expect(4000 * 6000 * scale ** 2).toBeLessThanOrEqual(16000000)
  expect(ocrRenderScale(1, 100000) * 100000).toBeLessThanOrEqual(10000)
  for (const size of [0, -1, NaN, Infinity]) expect(() => ocrRenderScale(size, 842)).toThrow()
})

it('분리된 표 셀을 위치에 따라 같은 행으로 모으고 왼쪽부터 읽는다', () => {
  const tsv = [header, word('29,400,000', 300, 100), word('99,000,000', 100, 50),
    word('33,660,000', 300, 51), word('98,000,000', 100, 100)].join('\n')
  expect(ocrReadingText('순서 없는 인식 결과', tsv, 1000, 1000))
    .toBe('99,000,000 33,660,000\n98,000,000 29,400,000')
  expect(ocrReadingText('', tsv.slice(tsv.indexOf('\n') + 1), 1000, 1000))
    .toBe('99,000,000 33,660,000\n98,000,000 29,400,000')
})

it('인식한 숫자와 기호를 임의로 고치거나 서로 다른 숫자를 붙이지 않는다', () => {
  const tsv = [header, word('48', 100, 50), word('240,000', 300, 50), word('O.5', 500, 50)].join('\n')
  expect(ocrReadingText('', tsv, 1000, 1000)).toBe('48 240,000 O.5')
  expect(ocrReadingText('텍스트만 있는 응답', undefined, 1000, 1000)).toBe('텍스트만 있는 응답')
})

it('같은 제목에서 글자 높이가 달라도 행을 불필요하게 나누지 않는다', () => {
  const tsv = [header, word('공식', 100, 50, 40), word('A105', 300, 50, 10), word('금액', 100, 120)].join('\n')
  expect(ocrReadingText('', tsv, 1000, 1000)).toBe('공식 A105\n금액')
})

it('잘못된 좌표나 과도한 인식 결과를 검토 근거로 사용하지 않는다', () => {
  for (const tsv of [[header, word('120', -1, 50)].join('\n'),
    [header, word('120', 950, 50)].join('\n'), '다른 응답',
    [header, ...Array.from({ length: 20001 }, () => word('1', 0, 0))].join('\n')]) {
    expect(() => ocrReadingText('원문', tsv, 1000, 1000)).toThrow()
  }
})
