import init, { HwpDocument } from '@rhwp/core'
import wasmUrl from '@rhwp/core/rhwp_bg.wasm?url'
import { readPageSize, readPageText, searchPageText } from './hwpTextSearch.ts'
import type { PageSize, TextMatch } from './hwpTextSearch.ts'

export type HwpRequest = { readonly type: 'open'; readonly url: string }
  | { readonly type: 'page'; readonly page: number }
  | { readonly type: 'search'; readonly id: number; readonly query: string }
export type HwpResponse = { readonly type: 'ready'; readonly pages: readonly PageSize[] }
  | { readonly type: 'page'; readonly page: number; readonly svg: string; readonly text: string }
  | { readonly type: 'search'; readonly id: number; readonly matches: readonly TextMatch[] }
  | { readonly type: 'searchError'; readonly id: number }
  | { readonly type: 'error' }

let document: HwpDocument | undefined
let searchId = 0
const scope = self as typeof self & { measureTextWidth: (font: string, text: string) => number }
const post = (response: HwpResponse) => self.postMessage(response)

// Yield between pages so a new query or a visible-page request can interrupt a
// long scan. An old scan cannot publish results after a newer query was received.
async function search(id: number, query: string) {
  searchId = id
  const current = document
  if (!current) return
  const matches: TextMatch[] = []
  try {
    if (query.trim()) for (let page = 0; page < current.pageCount(); page++) {
      if (searchId !== id || document !== current) return
      matches.push(...searchPageText(current.getPageTextLayout(page), query, page))
      await new Promise<void>((resolve) => setTimeout(resolve, 0))
    }
    if (searchId === id && document === current) post({ type: 'search', id, matches })
  } catch {
    if (searchId === id && document === current) post({ type: 'searchError', id })
  }
}

// Parse away from the UI thread; terminating the worker releases its WASM heap.
self.onmessage = async ({ data }: MessageEvent<HwpRequest>) => {
  if (data.type === 'search') { await search(data.id, data.query); return }
  try {
    if (data.type === 'open') {
      const context = new OffscreenCanvas(1, 1).getContext('2d')
      if (!context) throw new Error('Text measurement unavailable')
      scope.measureTextWidth = (font, text) => {
        context.font = font
        return context.measureText(text).width
      }
      await init({ module_or_path: wasmUrl })
      const response = await fetch(data.url)
      if (!response.ok) throw new Error('Document unavailable')
      document = new HwpDocument(new Uint8Array(await response.arrayBuffer()))
      const count = document.pageCount()
      if (count < 1) throw new Error('Empty document')
      post({ type: 'ready', pages: Array.from({ length: count }, (_, page) => readPageSize(document!.getPageInfo(page))) })
      return
    }
    if (!document || data.page < 0 || data.page >= document.pageCount()) throw new Error('Invalid page')
    post({ type: 'page', page: data.page, svg: document.renderPageSvg(data.page), text: readPageText(document.getPageTextLayout(data.page)) })
  } catch {
    document?.free()
    document = undefined
    post({ type: 'error' })
  }
}
