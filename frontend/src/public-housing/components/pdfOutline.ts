import type { DocumentOutlineEntry } from './DocumentOutline.tsx'

export interface PdfOutlineEntry extends DocumentOutlineEntry {
  readonly destination: unknown[]
  readonly top?: number
}
export interface PdfLocation { readonly pageNumber: number; readonly top: number }
interface OutlineDocument {
  readonly numPages: number
  getOutline: () => Promise<unknown>
  getDestination: (name: string) => Promise<unknown>
  getPageIndex: (ref: { num: number; gen: number }) => Promise<number>
}
const record = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null
const destinationModes = new Set(['XYZ', 'Fit', 'FitH', 'FitV', 'FitR', 'FitB', 'FitBH', 'FitBV'])

export async function readPdfOutline(document: OutlineDocument): Promise<PdfOutlineEntry[]> {
  const entries: PdfOutlineEntry[] = []
  async function visit(nodes: unknown, depth: number, parent: string) {
    if (!Array.isArray(nodes)) return
    for (const [index, node] of nodes.entries()) {
      if (!record(node)) continue
      const id = `${parent}${index}`
      const title = typeof node.title === 'string' ? node.title.trim() : ''
      try {
        const dest: unknown = typeof node.dest === 'string' ? await document.getDestination(node.dest) : node.dest
        if (title && Array.isArray(dest) && record(dest[1]) && typeof dest[1].name === 'string'
          && destinationModes.has(dest[1].name)) {
          const ref: unknown = dest[0]
          let pageNumber = 0
          if (typeof ref === 'number' && Number.isInteger(ref)) pageNumber = ref + 1
          else if (record(ref) && typeof ref.num === 'number' && typeof ref.gen === 'number') {
            pageNumber = await document.getPageIndex({ num: ref.num, gen: ref.gen }) + 1
          }
          if (Number.isInteger(pageNumber) && pageNumber > 0 && pageNumber <= document.numPages) {
            const mode = dest[1].name
            const top: unknown = mode === 'XYZ' ? dest[3] : mode === 'FitR' ? dest[5] : mode === 'FitH' || mode === 'FitBH' ? dest[2] : undefined
            entries.push({ id, title, depth, pageNumber, destination: [pageNumber - 1, ...dest.slice(1)],
              ...(typeof top === 'number' && Number.isFinite(top) ? { top } : {}) })
          }
        }
      } catch {
        // A broken bookmark must not discard its valid siblings or children.
      }
      await visit(node.items, depth + 1, `${id}.`)
    }
  }
  await visit(await document.getOutline(), 0, '')
  return entries
}

export function activePdfOutlineId(entries: readonly PdfOutlineEntry[], location: PdfLocation): string | null {
  let active: PdfOutlineEntry | undefined
  for (const entry of entries) {
    if (entry.pageNumber > location.pageNumber || (entry.pageNumber === location.pageNumber
      && entry.top !== undefined && entry.top < location.top - 2)) continue
    // PDF coordinates run bottom-to-top. Choose the nearest preceding position,
    // even when bookmarks aren't stored in page order; deeper entries win ties.
    if (!active || entry.pageNumber > active.pageNumber || (entry.pageNumber === active.pageNumber
      && (entry.top ?? Infinity) <= (active.top ?? Infinity))) active = entry
  }
  return active?.id ?? null
}
