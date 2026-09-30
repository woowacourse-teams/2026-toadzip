export interface HwpOutlineEntry {
  readonly id: string
  readonly title: string
  readonly depth: number
  readonly pageNumber: number
  readonly y: number
}

interface HwpOutlineSource {
  getStructure(mode: string): string
  getPageOfPosition(section: number, paragraph: number): string
  getCursorRect(section: number, paragraph: number, offset: number): string
}

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function index(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}

export function readHwpOutline(document: HwpOutlineSource, pageCount: number): readonly HwpOutlineEntry[] {
  if (!Number.isSafeInteger(pageCount) || pageCount < 1) return []
  try {
    const structure: unknown = JSON.parse(document.getStructure('outline'))
    if (!record(structure) || structure.mode !== 'outline' || !index(structure.nodeCount)
      || structure.nodeCount === 0 || !Array.isArray(structure.roots)) return []

    const entries: HwpOutlineEntry[] = []
    const ids = new Set<string>()
    const visit = (nodes: readonly unknown[]): boolean => {
      for (const value of nodes) {
        if (!record(value) || value.kind !== 'outline' || typeof value.heading !== 'string'
          || !value.heading.trim() || !index(value.section) || !index(value.paragraph)
          || !index(value.level) || value.level < 1) return false
        const id = `${value.section}:${value.paragraph}`
        if (ids.has(id)) return false
        ids.add(id)

        const position: unknown = JSON.parse(document.getPageOfPosition(value.section, value.paragraph))
        if (!record(position) || position.ok !== true || !index(position.page) || position.page >= pageCount) return false
        let y = 0
        try {
          const cursor: unknown = JSON.parse(document.getCursorRect(value.section, value.paragraph, 0))
          if (record(cursor) && cursor.pageIndex === position.page && typeof cursor.y === 'number'
            && Number.isFinite(cursor.y) && cursor.y >= 0) y = cursor.y
        } catch { /* Page position still supports navigation when cursor geometry is unavailable. */ }
        entries.push({ id, title: value.heading.trim(), depth: value.level - 1, pageNumber: position.page + 1, y })
        if (value.children !== undefined && (!Array.isArray(value.children) || !visit(value.children))) return false
      }
      return true
    }
    return visit(structure.roots) && entries.length === structure.nodeCount ? entries : []
  } catch {
    return []
  }
}
