import { expect, it, vi } from 'vitest'
import { readHwpOutline } from './hwpOutline.ts'

it('실제 outline 구조의 계층과 문단 위치를 쪽 번호·좌표로 변환한다', () => {
  const document = {
    getStructure: vi.fn(() => JSON.stringify({ mode: 'outline', nodeCount: 3, roots: [
      { level: 1, kind: 'outline', heading: '첫 제목', section: 0, paragraph: 1 },
      { level: 1, kind: 'outline', heading: '둘째 제목', section: 0, paragraph: 2, children: [
        { level: 2, kind: 'outline', heading: '하위 제목', section: 0, paragraph: 3 },
      ] },
    ] })),
    getPageOfPosition: vi.fn((_section: number, paragraph: number) => JSON.stringify({ ok: true, page: paragraph === 1 ? 0 : 1 })),
    getCursorRect: vi.fn((_section: number, paragraph: number) => JSON.stringify({ pageIndex: paragraph === 1 ? 0 : 1, x: 123.4, y: paragraph === 1 ? 161.7 : 142.5, height: 0 })),
  }

  expect(readHwpOutline(document, 2)).toEqual([
    { id: '0:1', title: '첫 제목', depth: 0, pageNumber: 1, y: 161.7 },
    { id: '0:2', title: '둘째 제목', depth: 0, pageNumber: 2, y: 142.5 },
    { id: '0:3', title: '하위 제목', depth: 1, pageNumber: 2, y: 142.5 },
  ])
  expect(document.getStructure).toHaveBeenCalledWith('outline')
})

it.each([
  '{"mode":"outline","nodeCount":0,"roots":[]}',
  '{"mode":"clause","nodeCount":1,"roots":[{"heading":"제1조","level":1,"kind":"clause","section":0,"paragraph":1}]}',
  '{"mode":"outline","nodeCount":1,"roots":[{"heading":"제목","level":1,"kind":"outline","section":0,"paragraph":-1}]}',
  'not JSON',
])('개요 메타데이터가 없거나 잘못되면 목차를 숨긴다: %s', (structure) => {
  expect(readHwpOutline({
    getStructure: () => structure,
    getPageOfPosition: () => '{"ok":true,"page":0}',
    getCursorRect: () => '{"pageIndex":0,"x":0,"y":10,"height":10}',
  }, 2)).toEqual([])
})

it('SDK 위치 조회가 실패하면 본문은 유지할 수 있도록 목차만 비운다', () => {
  expect(readHwpOutline({
    getStructure: () => '{"mode":"outline","nodeCount":1,"roots":[{"heading":"제목","level":1,"kind":"outline","section":0,"paragraph":1}]}',
    getPageOfPosition: () => { throw new Error('unsupported') },
    getCursorRect: () => '{"pageIndex":0,"x":0,"y":10,"height":10}',
  }, 2)).toEqual([])
})
