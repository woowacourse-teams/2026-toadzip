import init, { HwpDocument } from '@rhwp/core'
import wasmUrl from '@rhwp/core/rhwp_bg.wasm?url'

export type HwpRequest = { readonly type: 'open'; readonly url: string }
  | { readonly type: 'page'; readonly page: number }
export type HwpResponse = { readonly type: 'page'; readonly page: number; readonly count: number; readonly svg: string; readonly text: string }
  | { readonly type: 'error' }

let document: HwpDocument | undefined
const scope = self as typeof self & { measureTextWidth: (font: string, text: string) => number }

// Parse away from the UI thread; terminating the worker also releases its WASM heap.
self.onmessage = async ({ data }: MessageEvent<HwpRequest>) => {
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
    }
    if (!document || document.pageCount() < 1) throw new Error('Empty document')
    const page = data.type === 'open' ? 0 : data.page
    if (page < 0 || page >= document.pageCount()) throw new Error('Invalid page')
    const result: HwpResponse = {
      type: 'page', page, count: document.pageCount(),
      svg: document.renderPageSvg(page), text: document.getPageText(page),
    }
    self.postMessage(result)
  } catch {
    document?.free()
    document = undefined
    self.postMessage({ type: 'error' } satisfies HwpResponse)
  }
}
