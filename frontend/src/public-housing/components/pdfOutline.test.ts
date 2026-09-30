import { describe, expect, it } from 'vitest'
import { activePdfOutlineId, readPdfOutline } from './pdfOutline.ts'

const node = (title: string, dest: unknown, items: unknown[] = []) => ({ title, dest, items })
const document = (outline: unknown) => ({
  numPages: 10,
  getOutline: async () => outline,
  getDestination: async (name: string) => name === 'conditions' ? [4, { name: 'XYZ' }, 0, 720, null] : null,
  getPageIndex: async (ref: { num: number; gen: number }) => {
    if (ref.num === 30) return 2
    throw new Error('Missing page')
  },
})

describe('PDF 내장 목차', () => {
  it('계층과 이름 목적지를 실제 페이지에 연결한다', async () => {
    const entries = await readPdfOutline(document([
      node('  공급 개요  ', [{ num: 30, gen: 0 }, { name: 'Fit' }], [node('신청 자격', 'conditions')]),
    ]))
    expect(entries).toMatchObject([
      { title: '공급 개요', pageNumber: 3, depth: 0 },
      { title: '신청 자격', pageNumber: 5, depth: 1, top: 720 },
    ])
    expect(entries[1]?.destination).toEqual([4, { name: 'XYZ' }, 0, 720, null])
  })

  it('외부 링크와 잘못된 목적지만 제외하고 정상 하위 목차는 유지한다', async () => {
    const entries = await readPdfOutline(document([
      node('외부 링크', null, [node('정상 항목', [1, { name: 'FitH' }, 700])]),
      node('없는 이름', 'missing'), node('없는 쪽', [99, { name: 'Fit' }]),
      node('깨진 참조', [{ num: 99, gen: 0 }, { name: 'Fit' }]), node('빈 목적지', []),
      node(' ', [0, { name: 'Fit' }]), node('잘못된 보기', [0, { name: 'Unknown' }]),
    ]))
    expect(entries).toHaveLength(1)
    expect(entries[0]).toMatchObject({ title: '정상 항목', pageNumber: 2, top: 700 })
  })

  it.each([null, [], {}, [{ title: '목차', dest: null }]])('목차가 없거나 잘못되면 비워 둔다: %j', async (outline) => {
    expect(await readPdfOutline(document(outline))).toEqual([])
  })

  it('페이지와 페이지 내 제목 위치를 지나면 현재 항목을 바꾸고 역방향도 처리한다', () => {
    const entries = [
      { id: 'first', title: '개요', depth: 0, pageNumber: 2, top: 700, destination: [] },
      { id: 'second', title: '자격', depth: 0, pageNumber: 2, top: 350, destination: [] },
      { id: 'third', title: '일정', depth: 0, pageNumber: 5, destination: [] },
    ]
    expect(activePdfOutlineId(entries, { pageNumber: 1, top: 0 })).toBeNull()
    expect(activePdfOutlineId(entries, { pageNumber: 2, top: 750 })).toBeNull()
    expect(activePdfOutlineId(entries, { pageNumber: 2, top: 700 })).toBe('first')
    expect(activePdfOutlineId(entries, { pageNumber: 2, top: 300 })).toBe('second')
    expect(activePdfOutlineId(entries, { pageNumber: 4, top: 800 })).toBe('second')
    expect(activePdfOutlineId(entries, { pageNumber: 5, top: 800 })).toBe('third')
    expect(activePdfOutlineId(entries, { pageNumber: 2, top: 500 })).toBe('first')
  })
})

it('같은 페이지의 FitR 영역 목차도 세로 위치로 구분한다', async () => {
  const entries = await readPdfOutline(document([
    node('위쪽 영역', [1, { name: 'FitR' }, 0, 500, 400, 700]),
    node('아래쪽 영역', [1, { name: 'FitR' }, 0, 100, 400, 300]),
  ]))
  expect(activePdfOutlineId(entries, { pageNumber: 2, top: 650 })).toBe(entries[0]?.id)
  expect(activePdfOutlineId(entries, { pageNumber: 2, top: 250 })).toBe(entries[1]?.id)
})
