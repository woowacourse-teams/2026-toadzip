const compact = (value: string) => value.normalize('NFKC').replace(/\s+/g, '').toLowerCase()

/** pdf.js 쪽 텍스트 항목. hasEOL이면 그 항목 뒤에서 줄이 바뀐다. */
export type PdfTextItem = { readonly str: string; readonly hasEOL?: boolean }
/** pdf.js find가 쪽마다 남기는 위치 보정표. 검색 내용의 위치를 텍스트 항목을 이어 붙인 글자 위치로 되돌린다. */
export type PdfFindDiffs = readonly [ArrayLike<number>, ArrayLike<number>] | null | undefined

// pdf.js의 getOriginalIndex와 같은 방식으로 시작 위치만 되돌린다(내보내지 않는 함수다).
function originalIndex(diffs: PdfFindDiffs, position: number) {
  if (!diffs) return position
  const [starts, shifts] = diffs
  let index = 0
  while (index < starts.length && starts[index] < position) index += 1
  if (index >= starts.length || starts[index] > position) index -= 1
  return index < 0 ? position : position + shifts[index]
}

/**
 * 근거 강조 후보 중 같은 텍스트 줄(표에서는 한 행)에 문맥 표기(주택형·면적)가 있는 것만 남긴다. 문맥은 앞의 것부터 확인한다.
 * 같은 쪽의 다른 칸에 같은 숫자가 있어도(건설호수 468 옆의 모집수 200) 그 행의 값만 강조한다. 문맥이 없으면 그대로 둔다.
 * pdf.js find는 줄바꿈을 공백으로 바꾼 내용에서 찾으므로, 후보 위치를 diffs로 텍스트 항목 위치로 되돌린 뒤 hasEOL로 나눈 줄에서 문맥을 확인한다.
 */
export function matchesInContext<T extends { index: number; length: number }>(
  items: readonly PdfTextItem[], diffs: PdfFindDiffs, matches: T[], context: readonly string[] | null | undefined,
): T[] {
  const terms = (context ?? []).map(compact).filter(Boolean)
  if (!terms.length) return matches
  const lineEnds: number[] = [], lines: string[] = []
  let length = 0, line = ''
  for (const [index, item] of items.entries()) {
    length += item.str.length; line += item.str
    if (item.hasEOL || index === items.length - 1) { lineEnds.push(length); lines.push(compact(line)); line = '' }
  }
  const matchLines = matches.map(({ index }) => {
    const position = originalIndex(diffs, index)
    return lineEnds.findIndex(end => position < end)
  })
  // 앞의 문맥(주택형 표기)부터 쓴다. 면적은 다른 단지 표에도 같은 값이 있어, 주택형 표기가 있는 줄에서 못 찾을 때만 쓴다.
  for (const term of terms) {
    const kept = matches.filter((_, index) => matchLines[index] >= 0 && lines[matchLines[index]].includes(term))
    if (kept.length) return kept
  }
  return []
}
