import type { Worker } from 'tesseract.js'
import { ocrDpi, ocrReadingText } from './pdfOcrLayout'

export type OcrText = { text: string; confidence: number }
export function validateOcrText(text: string, confidence: number): OcrText {
  if (typeof text !== 'string' || text.length > 200000 || !Number.isFinite(confidence) || confidence < 0 || confidence > 100) throw new Error('OCR 결과가 너무 크거나 형식이 올바르지 않습니다.')
  return { text: text.trim(), confidence }
}

export async function configurePdfOcr(worker: Pick<Worker, 'setParameters'>): Promise<void> {
  const { PSM } = await import('tesseract.js')
  await worker.setParameters({ tessedit_pageseg_mode: PSM.SPARSE_TEXT,
    preserve_interword_spaces: '1', user_defined_dpi: String(ocrDpi) })
}

export async function withPdfOcr<T>(signal: AbortSignal, progress: (message: string) => void,
  work: (recognize: (canvas: HTMLCanvasElement) => Promise<OcrText>) => Promise<T>): Promise<T> {
  signal.throwIfAborted()
  const { createWorker } = await import('tesseract.js')
  signal.throwIfAborted()
  const assets = new URL(`${import.meta.env.BASE_URL}ocr-7.0.0/`, window.location.href)
  let worker: Worker | undefined
  let finished = false
  let timer: ReturnType<typeof setTimeout> | undefined
  let abort: (() => void) | undefined
  const stopped = new Promise<never>((_, reject) => {
    abort = () => { reject(signal.reason ?? new DOMException('OCR 취소', 'AbortError')); void worker?.terminate().catch(() => {}) }
    signal.addEventListener('abort', abort, { once: true })
    timer = setTimeout(() => { reject(new Error('OCR이 5분 안에 끝나지 않았습니다. 페이지 범위를 줄여 다시 시도하세요.')); void worker?.terminate().catch(() => {}) }, 300000)
  })
  try {
    const initializing = createWorker(['kor', 'eng'], 1, {
      workerPath: new URL('worker.min.js', assets).href, corePath: new URL('core/', assets).href,
      langPath: new URL('lang', assets).href, cacheMethod: 'none',
      logger: message => { if (!signal.aborted) progress(`OCR 처리 중 ${Math.round(message.progress * 100)}%`) },
    }).then(created => { worker = created; if (signal.aborted || finished) void created.terminate().catch(() => {}); return created })
    const ready = await Promise.race([initializing, stopped])
    await Promise.race([configurePdfOcr(ready), stopped])
    signal.throwIfAborted()
    return await Promise.race([work(async canvas => {
      signal.throwIfAborted()
      const { data } = await Promise.race([ready.recognize(canvas, {}, { text: true, tsv: true }), stopped])
      signal.throwIfAborted()
      return validateOcrText(ocrReadingText(data.text, data.tsv, canvas.width, canvas.height), data.confidence)
    }), stopped])
  } finally {
    finished = true
    if (timer) clearTimeout(timer)
    if (abort) signal.removeEventListener('abort', abort)
    await worker?.terminate().catch(() => {})
  }
}
