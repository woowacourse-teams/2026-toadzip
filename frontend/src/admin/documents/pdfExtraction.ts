import workerUrl from 'pdfjs-dist/legacy/build/pdf.worker.min.mjs?url'

export type PdfTextItem = { str: string; x: number; y: number; width: number; height: number; endOfLine: boolean }
export type ExtractedPage = { page: number; text: string; items: PdfTextItem[]; requiresOcr: boolean }
export type ExtractedDocument = { fileName: string; sha256: string; totalPages: number; pages: ExtractedPage[] }
type PdfPage = { getTextContent: () => Promise<{ items: unknown[] }>; cleanup: () => void }
type PdfReader = { numPages: number; getPage: (page: number) => Promise<PdfPage> }
export type PdfSession = {
  fileName: string; sha256: string; totalPages: number
  extract: (selection: string, signal: AbortSignal, progress?: (done: number, total: number) => void) => Promise<ExtractedDocument>
  destroy: () => Promise<void>
}
export const maxPdfBytes = 25 * 1024 * 1024

export function parsePageSelection(selection: string, totalPages: number): number[] {
  if (!selection.trim() || selection.length > 500) throw new Error('쪽수를 입력해 주세요. 예: 1-3, 7')
  const pages = new Set<number>()
  for (const part of selection.split(',')) {
    const match = /^\s*(\d+)(?:\s*-\s*(\d+))?\s*$/.exec(part)
    if (!match) throw new Error('쪽수 형식을 확인해 주세요. 예: 1-3, 7')
    const first = Number(match[1]); const last = Number(match[2] ?? match[1])
    if (first < 1 || last < first || last > totalPages || last - first >= 50) {
      throw new Error('문서 안의 쪽수를 선택해 주세요. 한 번에 최대 50쪽입니다.')
    }
    for (let page = first; page <= last; page++) pages.add(page)
    if (pages.size > 50) throw new Error('한 번에 최대 50쪽을 추출할 수 있습니다.')
  }
  return [...pages].sort((a, b) => a - b)
}

export function pageText(items: PdfTextItem[]): string {
  let text = ''; let previous: PdfTextItem | undefined
  for (const item of items) {
    if (previous && !text.endsWith('\n')) {
      text += Math.abs(previous.y - item.y) > Math.max(2, previous.height / 2) ? '\n' : ' '
    }
    text += item.str
    if (item.endOfLine) text += '\n'
    previous = item
  }
  return text.trim()
}

function textItem(value: unknown): PdfTextItem | null {
  if (!value || typeof value !== 'object' || !('str' in value) || typeof value.str !== 'string'
    || !('transform' in value) || !Array.isArray(value.transform)) return null
  const numbers = value.transform
  if (numbers.length !== 6 || !numbers.every(item => typeof item === 'number' && Number.isFinite(item))) return null
  return { str: value.str, x: numbers[4], y: numbers[5],
    width: 'width' in value && typeof value.width === 'number' ? value.width : 0,
    height: 'height' in value && typeof value.height === 'number' ? value.height : 0,
    endOfLine: 'hasEOL' in value && value.hasEOL === true }
}

export async function extractPages(pdf: PdfReader, selection: number[], cache: Map<number, ExtractedPage>,
  signal: AbortSignal, progress?: (done: number, total: number) => void): Promise<ExtractedPage[]> {
  const pages: ExtractedPage[] = []; let length = 0
  for (const number of selection) {
    signal.throwIfAborted()
    let extracted = cache.get(number)
    if (!extracted) {
      const page = await pdf.getPage(number)
      try {
        signal.throwIfAborted()
        const content = await page.getTextContent()
        signal.throwIfAborted()
        if (content.items.length > 20000) throw new Error(`${number}쪽이 너무 복잡합니다. 범위를 줄이거나 수기로 확인해 주세요.`)
        const items = content.items.map(textItem).filter((item): item is PdfTextItem => item !== null)
        const text = pageText(items)
        if (text.length > 200000) throw new Error(`${number}쪽의 텍스트가 너무 많습니다.`)
        extracted = { page: number, text, items, requiresOcr: text.trim().length === 0 }
        cache.set(number, extracted)
      } finally { page.cleanup() }
    }
    length += extracted.text.length
    if (length > 500000) throw new Error('추출 자료가 너무 큽니다. 필요한 페이지만 선택해 주세요.')
    pages.push(extracted)
    progress?.(pages.length, selection.length)
  }
  return pages
}

export async function openPdf(file: File, signal: AbortSignal): Promise<PdfSession> {
  if (file.size === 0 || file.size > maxPdfBytes) throw new Error('25MB 이하의 PDF 파일을 선택해 주세요.')
  if (file.name.length > 255) throw new Error('파일명은 255자 이하여야 합니다.')
  const buffer = await file.arrayBuffer()
  signal.throwIfAborted()
  if (!new TextDecoder().decode(buffer.slice(0, 1024)).includes('%PDF-')) throw new Error('PDF 형식의 파일이 아닙니다.')
  const hash = await crypto.subtle.digest('SHA-256', buffer)
  const sha256 = [...new Uint8Array(hash)].map(value => value.toString(16).padStart(2, '0')).join('')
  const { getDocument, GlobalWorkerOptions, version } = await import('pdfjs-dist/legacy/build/pdf.mjs')
  signal.throwIfAborted()
  const workerSource = new URL(workerUrl, window.location.href)
  workerSource.searchParams.set('mime', 'javascript')
  GlobalWorkerOptions.workerSrc = workerSource.href
  const assets = `${import.meta.env.BASE_URL}pdfjs-${version}/`
  const loading = getDocument({ data: buffer, cMapUrl: `${assets}cmaps/`, cMapPacked: true,
    standardFontDataUrl: `${assets}standard_fonts/`, wasmUrl: `${assets}wasm/` })
  const abort = () => { void loading.destroy().catch(() => {}) }
  signal.addEventListener('abort', abort, { once: true })
  try {
    const pdf = await loading.promise
    signal.throwIfAborted()
    if (pdf.numPages > 10000) throw new Error('10,000쪽 이하의 문서만 지원합니다.')
    const cache = new Map<number, ExtractedPage>()
    return { fileName: file.name, sha256, totalPages: pdf.numPages,
      extract: async (selection, extractionSignal, progress) => ({ fileName: file.name, sha256,
        totalPages: pdf.numPages, pages: await extractPages(pdf, parsePageSelection(selection, pdf.numPages), cache, extractionSignal, progress) }),
      destroy: () => loading.destroy() }
  } catch (error) {
    await loading.destroy().catch(() => {})
    if (signal.aborted) throw signal.reason
    throw new Error('PDF를 읽지 못했습니다. 암호·손상 여부를 확인해 주세요.', { cause: error })
  } finally { signal.removeEventListener('abort', abort) }
}
