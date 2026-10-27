import type { JsonObject, JsonWorkspace } from '../announcement-json/api'
import type { MatchingHousingType } from '../ingest/supplyMatchingApi'
import type { CorrectionRow } from '../supply/supplyCorrectionApi'
import { evidenceFor, type Evidence, type ExtractedRow, type Extraction, type ExtractionNote, type SharedCount } from './extraction'
import { normalizedName, pickHousingType, sameComplex } from './matching'
import { createRowReview, editCell, preserveCellChoice, type Review, type RowReview } from './review'

/**
 * 최초 입주자 공고처럼 단지 DB에 없던 LH 원천행을 공고문 행에 맞춘다.
 * LH는 "26A/B/C(고령자)"처럼 주택형 묶음 × 공급대상으로 행을 주고, 공고문은 세부 타입(26A·26B·26C)마다 행을 준다.
 * - LH 행 하나가 공고문 행 여럿에 대응하면 그 LH 행을 첫 세부 타입으로 고쳐 쓰고 나머지를 새 행으로 더한 뒤,
 *   LH 행의 이번 공급 수를 공통 모집수로 묶는다(원천행 분할). 다음 정제에서도 LH 행 단위 묶음으로 유지된다.
 * - 공고문 행 하나가 여러 LH 행(공급대상 묶음)에 대응하면 행을 묶음마다 복사하고, 대상은 묶음 이름으로 나눈다.
 */
export type SourceLabel = { types: string[]; group: string | null }

/** "26A/B/C(고령자)" → 26A·26B·26C와 공급대상 묶음 "고령자". "26A,B"처럼 쉼표로 묶어도 같다. */
export function parseSourceLabel(label: string): SourceLabel {
  const match = /^(.*?)\s*(?:\(([^)]*)\))?\s*$/.exec(label.trim())
  const tokens = (match?.[1] ?? '').split(/[/,]/).map(token => token.trim()).filter(Boolean)
  const prefix = /^\d+/.exec(tokens[0] ?? '')?.[0] ?? ''
  return { types: tokens.map((token, at) => at > 0 && /^[A-Za-z]+$/.test(token) ? prefix + token : token), group: match?.[2]?.trim() || null }
}

/** 단지에 연결되지 않은 LH·마이홈 원천행 중 이 공고문 단지명과 같은 단지의 행 */
export function emptySourceRows(workspace: JsonWorkspace, complexName: string) {
  return workspace.supply.rows.filter(row => row.id !== null && !row.excluded && row.housingComplexId === null && row.groupId === null
    && sameComplex(complexName, row.complexName))
}

type Alignment = { source: CorrectionRow; label: SourceLabel; rows: number[] }
type Copy = { index: number; targets: number[] }
type Plan = { from: number; alignment: Alignment | null; extraTargets: string[] }

/** 대상 이름이 LH 공급대상 묶음("대학생/청년", "신혼부부·한부모")에 속하는지 */
function inGroup(target: unknown, group: string) {
  const name = normalizedName(target)
  return group.split('/').map(normalizedName).filter(Boolean).some(token => name.includes(token) || token.includes(name))
}

export function sourceAlignments(review: Review, workspace: JsonWorkspace, indexes: number[], complexName: string): Alignment[] {
  return emptySourceRows(workspace, complexName).flatMap(source => {
    const label = parseSourceLabel(source.typeName)
    const rows = indexes.filter(index => label.types.some(type => normalizedName(type) === normalizedName(review.extraction.supplyRows[index].typeName)))
    return rows.length ? [{ source, label, rows }] : []
  })
}

/**
 * 고른 단지로 공고문 행과 LH 원천행을 맞춘 검토를 만든다. 맞출 LH 행이 없으면 null을 돌려준다.
 * 행 번호가 바뀌므로 메모·근거·공통 모집수 경로를 새 번호로 옮기고, 다른 행의 선택은 그대로 유지한다.
 */
export function alignSourceRows(current: Review, workspace: JsonWorkspace, complexName: string,
  complex: { id: number; name: string }, candidates: MatchingHousingType[]): Review | null {
  // 단지를 다시 고르면 맞추기 전 검토에서 다시 맞춘다. 이미 복사한 행끼리 다시 맞추지 않는다.
  const review = current.unaligned ?? current
  const indexes = review.rows.map((_, index) => index).filter(index => review.extraction.supplyRows[index].complexName === complexName)
  const alignments = sourceAlignments(review, workspace, indexes, complexName)
  if (!alignments.length) return null
  const source = review.extraction, rows: ExtractedRow[] = [], copies = new Map<number, Copy[]>(), plans: Plan[] = []
  source.supplyRows.forEach((row, from) => {
    const matched = alignments.filter(alignment => alignment.rows.includes(from))
    if (!matched.length) {
      copies.set(from, [{ index: rows.length, targets: row.targets.map((_, at) => at) }])
      rows.push(row); plans.push({ from, alignment: null, extraTargets: [] })
      return
    }
    // 공급대상 묶음이 여럿이면 대상을 묶음 이름으로 나누고, 어느 묶음에도 없는 대상은 첫 묶음에 넣어 확인하게 한다.
    const owner = (at: number) => matched.length === 1 ? 0 : Math.max(0, matched.findIndex(alignment => alignment.label.group && inGroup(row.targets[at].target, alignment.label.group)))
    const unmatched = row.targets.map(target => matched.length > 1 && !matched.some(alignment => alignment.label.group && inGroup(target.target, alignment.label.group)) ? String(target.target ?? '') : null)
    copies.set(from, matched.map((alignment, order) => {
      const kept = row.targets.map((_, at) => at).filter(at => owner(at) === order)
      const values: JsonObject = alignment.rows.length > 1 ? { ...row.values, totalSupplyHouseholdCount: null } : { ...row.values }
      rows.push({ ...row, values, targets: kept.map(at => row.targets[at]) })
      plans.push({ from, alignment, extraTargets: order === 0 ? unmatched.filter((name): name is string => name !== null) : [] })
      return { index: rows.length - 1, targets: row.targets.map((_, at) => kept.indexOf(at)) }
    }))
  })
  const remap = (path: string): string[] => {
    const match = /^supplyRows\[(\d+)\](?:\.targets\[(\d+|\*)\])?(.*)$/.exec(path)
    if (!match) return [path]
    return (copies.get(Number(match[1])) ?? []).flatMap(copy => {
      if (match[2] === undefined) return [`supplyRows[${copy.index}]${match[3]}`]
      if (match[2] === '*') return [`supplyRows[${copy.index}].targets[*]${match[3]}`]
      const target = copy.targets[Number(match[2])]
      return target === undefined || target < 0 ? [] : [`supplyRows[${copy.index}].targets[${target}]${match[3]}`]
    })
  }
  const aligned = new Set(alignments.flatMap(alignment => alignment.rows))
  // LH 행에 맞춘 행은 공고문의 묶음 대신 LH 행 단위 묶음을 쓴다. 한 행이 두 묶음에 들어갈 수 없다.
  const kept = source.sharedCounts.filter(group => !group.paths.some(path => {
    const row = Number(/^supplyRows\[(\d+)\]/.exec(path)?.[1])
    return aligned.has(row) && (path.endsWith('.totalSupplyHouseholdCount') || (copies.get(row)?.length ?? 0) > 1)
  })).map(group => ({ ...group, paths: group.paths.flatMap(remap) }))
  const split: SharedCount[] = alignments.filter(alignment => alignment.rows.length > 1).map(alignment => {
    const paths = alignment.rows.map(from => plans.findIndex(plan => plan.from === from && plan.alignment === alignment)).map(index => `supplyRows[${index}].totalSupplyHouseholdCount`)
    const evidence = evidenceFor(source.evidence, `supplyRows[${alignment.rows[0]}]`)
    return { paths, count: alignment.source.householdCount, page: evidence?.page ?? null, quote: evidence?.quote ?? '' }
  })
  const note = (item: ExtractionNote) => remap(item.path).map(path => ({ ...item, path }))
  const extraction: Extraction = { ...source, supplyRows: rows, sharedCounts: [...kept, ...split],
    unresolved: source.unresolved.flatMap(note), notApplicable: source.notApplicable.flatMap(note),
    evidence: source.evidence.flatMap((item: Evidence) => remap(item.path).map(path => ({ ...item, path }))) }
  const sharedIndex = (index: number) => {
    const at = extraction.sharedCounts.findIndex(group => group.paths.includes(`supplyRows[${index}].totalSupplyHouseholdCount`))
    return at < 0 ? null : at
  }
  return { ...review, unaligned: review, extraction, rows: plans.map((plan, index) => {
    const old = review.rows[plan.from]
    const pathOf = (path: string) => remap(path).find(next => next.startsWith(`supplyRows[${index}]`))
    const retain = (cell: RowReview['cells'][number]) => preserveCellChoice(cell, [...old.cells, ...old.targets.flatMap(target => target.cells)].find(item => pathOf(item.path) === cell.path))
    if (!plan.alignment) {
      const next = createRowReview(extraction, index, { ...old.proposal, sharedIndex: sharedIndex(index) }, workspace)
      return { ...next, key: old.key, include: old.include, reviewed: old.reviewed, newHousingType: old.newHousingType,
        cells: next.cells.map(retain), targets: next.targets.map(target => ({ ...target, cells: target.cells.map(retain) })) }
    }
    const { alignment } = plan, first = alignment.rows[0] === plan.from
    const name = normalizedName(extraction.supplyRows[index].typeName)
    const housingTypeId = candidates.find(type => normalizedName(type.name) === name)?.id ?? pickHousingType(extraction, index, candidates).housingTypeId
    const reason = [alignment.rows.length > 1
      ? `LH 원천 '${alignment.source.typeName}'을(를) 공고문 ${alignment.rows.map(from => source.supplyRows[from].typeName).join('·')}로 나눕니다. 이번 공급 ${alignment.source.householdCount ?? '미확인'}세대를 공통 모집수로 둡니다.`
      : `LH 원천 '${alignment.source.typeName}'에 연결합니다.`,
    (copies.get(plan.from)?.length ?? 0) > 1 && alignment.label.group ? `대상은 '${alignment.label.group}' 묶음만 넣었습니다.` : '',
    plan.extraTargets.length ? `${plan.extraTargets.join(', ')}은(는) LH 공급대상 묶음에 없어 이 행에 넣었습니다. 확인하세요.` : ''].filter(Boolean).join(' ')
    const next = createRowReview(extraction, index, { destination: first ? alignment.source.key : 'new', anchorKey: '', complexId: complex.id,
      housingTypeId, candidates, reason, needsConfirmation: true, sharedIndex: sharedIndex(index),
      // 새 행은 LH 행과 같은 소재지·공급 구분이어야 공통 모집 묶음으로 저장된다.
      newComplex: { id: complex.id, name: complex.name, pnu: alignment.source.pnu, category: alignment.source.category } }, workspace)
    const newType = housingTypeId === null && Boolean(extraction.supplyRows[index].typeName)
    // 연결 주택형은 맞춘 LH 행 기준으로 새로 정하므로 맞추기 전 선택을 이어받지 않는다.
    return { ...next, include: old.include, reviewed: false, newHousingType: newType,
      cells: next.cells.map(cell => cell.field === 'housingTypeId' ? cell : retain(cell)).map(cell => newType && cell.field === 'housingTypeId' ? editCell(cell, '') : cell),
      targets: next.targets.map(target => ({ ...target, cells: target.cells.map(retain) })) }
  }) }
}
