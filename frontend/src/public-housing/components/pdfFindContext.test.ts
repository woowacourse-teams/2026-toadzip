import { expect, it, vi } from 'vitest'
import 'pdfjs-dist/legacy/build/pdf.mjs'
import { matchesInContext, type PdfFindDiffs, type PdfTextItem } from './pdfFindContext'

type Match = { index: number; length: number }

// 실제 pdf.js find로 찾게 해서, 줄바꿈을 공백으로 바꾼 검색 내용과 위치 보정표(diffs)를 그대로 받는다.
async function pdfFind(items: PdfTextItem[], word: string) {
  const { PDFFindController, EventBus } = await import('pdfjs-dist/legacy/web/pdf_viewer.mjs')
  const eventBus = new EventBus()
  const controller = new PDFFindController({ eventBus, linkService: { pagesCount: 1, page: 1 } as never })
  controller.setDocument({ numPages: 1, getPage: async () => ({ getTextContent: async () => ({ items }) }) } as never)
  const state = controller as unknown as { _pageContents: string[]; _pageDiffs: PdfFindDiffs[]; _pageMatches: number[][]; _pageMatchesLength: number[][] }
  eventBus.dispatch('find', { source: null, type: '', query: [word], caseSensitive: false, entireWord: true, highlightAll: true, findPrevious: false, matchDiacritics: false })
  const content = await vi.waitFor(() => state._pageContents[0] ?? Promise.reject(new Error('검색 내용 대기')))
  const matches: Match[] = [...content.matchAll(new RegExp(`(?<![\\d.])${word}(?![\\d.])`, 'g'))].map(match => ({ index: match.index, length: word.length }))
  return { content, diffs: state._pageDiffs[0], matches }
}

const table: PdfTextItem[] = [
  { str: '■ 대구대곡 수목원 호정포레스트', hasEOL: true },
  { str: '059.9000B', hasEOL: true },
  { str: '59B 59.93 21.5130 48 13 25', hasEOL: true },
  { str: '■ 대구테크노폴리스 4단지 : 예비입주자 240세대', hasEOL: true },
  { str: '51.98 20.3924 36 468 98 0 200 24', hasEOL: true },
  { str: '059.9100A 59A 59.91 23.5232 42 183 12 0 25 25', hasEOL: true },
  { str: '059.9300B 59B 59.93 23.7695 42 68 8 0 15 21', hasEOL: true },
]

it('pdf.js 검색 내용에는 줄바꿈이 없어도 같은 줄에 주택형·면적이 있는 값만 남긴다', async () => {
  const { content, diffs, matches } = await pdfFind(table, '25')
  expect(content).not.toContain('\n')
  const kept = matchesInContext(table, diffs, matches, ['059.9100A', '59.91'])
  expect(kept.map(match => content.slice(match.index - 5, match.index + 2))).toEqual(['12 0 25', '0 25 25'])
})

it('면적만 문맥으로 있어도 그 줄의 값만 남긴다', async () => {
  const { content, diffs, matches } = await pdfFind(table, '200')
  expect(matchesInContext(table, diffs, matches, ['051.9800A', '51.98']).map(match => content.slice(match.index - 5, match.index + 3))).toEqual(['98 0 200'])
})

it('다른 단지 표에 같은 면적이 있으면 주택형 표기가 있는 줄의 값만 남긴다', async () => {
  const { content, diffs, matches } = await pdfFind(table, '59.93')
  expect(matches).toHaveLength(2)
  expect(matchesInContext(table, diffs, matches, ['059.9300B', '59.93']).map(match => content.slice(match.index - 14, match.index + 5))).toEqual(['059.9300B 59B 59.93'])
})

it('문맥 표기가 있는 줄이 없으면 아무것도 강조하지 않고, 문맥이 없으면 후보를 그대로 둔다', async () => {
  const { diffs, matches } = await pdfFind(table, '25')
  expect(matchesInContext(table, diffs, matches, ['084.9800A'])).toEqual([])
  expect(matchesInContext(table, diffs, matches, [])).toHaveLength(matches.length)
})
