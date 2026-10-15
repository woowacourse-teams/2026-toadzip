import { expect, it, vi } from 'vitest'
import { extractPages, parsePageSelection, pageText } from './pdfExtraction'

it('쪽수 범위를 정렬·중복 제거하고 과도하거나 잘못된 범위를 거부한다', () => {
  expect(parsePageSelection('3, 1-2, 2', 4)).toEqual([1, 2, 3])
  for (const value of ['', '0', '101', '3-1', '1.5', '1-51', '1-1000000000']) {
    expect(() => parsePageSelection(value, 100)).toThrow()
  }
})
it('줄바꿈과 위치를 보존하고 표의 열을 임의로 합산하지 않는다', () => {
  expect(pageText([{ str: '단지', x: 0, y: 20, width: 20, height: 10, endOfLine: false },
    { str: '120', x: 100, y: 20, width: 20, height: 10, endOfLine: true },
    { str: '모집 5', x: 0, y: 10, width: 20, height: 10, endOfLine: true }])).toBe('단지 120\n모집 5')
})
it('재추출한 페이지는 재사용하고 텍스트 없는 페이지를 OCR 필요로 남긴다', async () => {
  const cleanup = vi.fn()
  const getPage = vi.fn().mockResolvedValue({ getTextContent: vi.fn().mockResolvedValue({ items: [] }), cleanup })
  const cache = new Map()
  const pdf = { numPages: 2, getPage }
  await extractPages(pdf, [1], cache, new AbortController().signal)
  const pages = await extractPages(pdf, [1, 2], cache, new AbortController().signal)
  expect(getPage).toHaveBeenCalledTimes(2)
  expect(pages.map(page => page.requiresOcr)).toEqual([true, true])
  expect(cleanup).toHaveBeenCalledTimes(2)
})
it('취소된 추출은 다음 페이지를 처리하지 않는다', async () => {
  const controller = new AbortController()
  controller.abort()
  const getPage = vi.fn()
  await expect(extractPages({ numPages: 2, getPage }, [1], new Map(), controller.signal)).rejects.toThrow()
  expect(getPage).not.toHaveBeenCalled()
})
