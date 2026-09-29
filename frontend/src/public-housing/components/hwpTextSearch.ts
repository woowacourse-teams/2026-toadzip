export type PageSize = { readonly width: number; readonly height: number }
export type TextRect = { readonly x: number; readonly y: number; readonly width: number; readonly height: number }
export type TextMatch = { readonly page: number; readonly rects: readonly TextRect[] }
type Run = { text: string; x: number; y: number; h: number; charX: number[]; charStart: number; group: string }
type Position = { run: Run; char: number }

function record(value: unknown): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new Error('Invalid HWP layout')
  return value as Record<string, unknown>
}
function number(value: unknown): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) throw new Error('Invalid HWP coordinate')
  return value
}
export function readPageSize(json: string): PageSize {
  const value = record(JSON.parse(json))
  const width = number(value.width), height = number(value.height)
  if (width <= 0 || height <= 0) throw new Error('Invalid HWP page size')
  return { width, height }
}
function readRuns(json: string): Run[] {
  const value = record(JSON.parse(json))
  if (!Array.isArray(value.runs)) throw new Error('Invalid HWP text layout')
  return value.runs.map((item: unknown, index: number) => {
    const run = record(item)
    if (typeof run.text !== 'string' || !Array.isArray(run.charX)) throw new Error('Invalid HWP text run')
    const charX = run.charX.map(number)
    if (charX.length !== Array.from(run.text).length + 1 || charX.some((x, i) => i > 0 && x < charX[i - 1]!)) throw new Error('Invalid HWP character positions')
    return {
      text: run.text, x: number(run.x), y: number(run.y), h: number(run.h), charX, charStart: run.charStart === undefined ? 0 : number(run.charStart),
      group: run.charStart === undefined ? `generated-${index}` : JSON.stringify([run.secIdx, run.paraIdx, run.parentParaIdx, run.controlIdx, run.cellIdx, run.cellParaIdx, run.cellPath]),
    }
  })
}

// Search rendered text, including tables, without injecting document markup. Keep
// code-point positions while matching JS strings so highlights also fit emoji.
export function searchPageText(json: string, query: string, page: number): TextMatch[] {
  const needle = query.toLowerCase().replace(/\s+/gu, ' ').trim()
  if (!needle) return []
  const groups = new Map<string, Run[]>()
  for (const run of readRuns(json)) {
    const group = groups.get(run.group)
    if (group) group.push(run)
    else groups.set(run.group, [run])
  }
  const matches: TextMatch[] = []
  for (const runs of groups.values()) {
    let text = ''
    const positions: (Position | null)[] = []
    let end = 0
    const append = (char: string, position: Position | null) => {
      const normalized = /\s/u.test(char) ? ' ' : char.toLowerCase()
      if (normalized === ' ' && text.endsWith(' ')) return
      text += normalized
      for (let i = 0; i < normalized.length; i++) positions.push(position)
    }
    for (const run of runs) {
      if (run.charStart > end && text) append(' ', null)
      Array.from(run.text).forEach((char, index) => append(char, { run, char: index }))
      end = run.charStart + Array.from(run.text).length
    }
    for (let start = text.indexOf(needle); start >= 0; start = text.indexOf(needle, start + needle.length)) {
      const ranges = new Map<Run, { start: number; end: number }>()
      for (const position of positions.slice(start, start + needle.length)) {
        if (!position) continue
        const range = ranges.get(position.run)
        ranges.set(position.run, { start: range?.start ?? position.char, end: position.char + 1 })
      }
      const rects = Array.from(ranges, ([run, range]) => ({
        x: run.x + run.charX[range.start]!, y: run.y,
        width: run.charX[range.end]! - run.charX[range.start]!, height: run.h,
      })).filter((rect) => rect.width > 0 && rect.height > 0)
      if (rects.length) matches.push({ page, rects })
    }
  }
  return matches.sort((a, b) => a.rects[0]!.y - b.rects[0]!.y || a.rects[0]!.x - b.rects[0]!.x)
}

export function readPageText(json: string): string {
  let previous: Run | undefined
  return readRuns(json).map((run) => {
    const separator = previous && (previous.group !== run.group || previous.y !== run.y) ? '\n' : ''
    previous = run
    return separator + run.text
  }).join('')
}
