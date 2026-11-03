import type { JsonObject, JsonWorkspace } from '../announcement-json/api'
import type { MatchingHousingType } from '../ingest/supplyMatchingApi'
import type { CorrectionRow } from '../supply/supplyCorrectionApi'
import { evidenceFor, type Evidence, type ExtractedRow, type Extraction, type ExtractionNote, type SharedCount } from './extraction'
import { normalizedName, pickHousingType, sourceComplexNames, typeKey } from './matching'
import { createRowReview, editCell, preserveCellChoice, type Review, type RowReview } from './review'
import { foldRows, parseSourceLabel, pdfTypes, sameTargets, type SourceLabel } from './subtypes'

/**
 * 최초 입주자 공고처럼 단지 DB에 없던 LH 원천행을 공고문 행에 맞춘다.
 * LH는 "26A/B/C(고령자)"처럼 주택형 묶음 × 공급대상으로 행을 주고, 공고문은 세부 타입(26A·26B·26C)마다 행을 준다.
 * - LH 행 하나가 공고문 행 여럿에 대응하면 그 공고문 행들을 LH 행 하나의 세부 타입 묶음 행으로 접는다. 모집수는 LH 행의 이번 공급 수이고
 *   세부 타입 주택형을 모두 그 행에 연결한다. 대상·금액이 행마다 다르면 접지 않고, LH 행을 첫 세부 타입으로 고쳐 쓰고 나머지를 새 행으로 더한 뒤
 *   LH 행의 이번 공급 수를 공통 모집수로 묶는다(원천행 분할). 확인을 받는다.
 * - 공고문 행 하나가 여러 LH 행(공급대상 묶음)에 대응하면 행을 묶음마다 복사하고, 대상은 묶음 이름으로 나눈다.
 */

function sourceComplex(workspace: JsonWorkspace, extraction: Extraction, complexName: string) {
  return sourceComplexNames(extraction, complexName, [...new Set(workspace.supply.rows.filter(row => !row.excluded).map(row => row.complexName))],
    workspace.supply.announcementComplexes)
}

/** 단지에 연결되지 않은 LH·마이홈 원천행 중 이 공고문 단지명이 가리키는 공고 단지의 행. 블록 표기가 달라도 대응한다(sourceComplexNames). */
export function emptySourceRows(workspace: JsonWorkspace, extraction: Extraction, complexName: string) {
  const { names } = sourceComplex(workspace, extraction, complexName)
  return workspace.supply.rows.filter(row => row.id !== null && !row.excluded && row.housingComplexId === null && row.groupId === null
    && names.includes(row.complexName))
}

type Alignment = { source: CorrectionRow; label: SourceLabel; rows: number[]; byArea?: boolean }
type Copy = { index: number; targets: number[] }
type Plan = { from: number; alignment: Alignment | null; extraTargets: string[] }

/** 대상 이름이 LH 공급대상 묶음("대학생/청년", "신혼부부·한부모")에 속하는지 */
function inGroup(target: unknown, group: string) {
  const name = normalizedName(target)
  return group.split('/').map(normalizedName).filter(Boolean).some(token => name.includes(token) || token.includes(name))
}

export function sourceAlignments(review: Review, workspace: JsonWorkspace, indexes: number[], complexName: string): Alignment[] {
  const named = emptySourceRows(workspace, review.extraction, complexName).map(source => {
    const label = parseSourceLabel(source.typeName)
    // 공고문 행이 세부 타입 묶음(26A/B)이면 세부 타입 이름으로도 맞춘다.
    const names = (index: number) => [review.extraction.supplyRows[index].typeName, ...review.extraction.supplyRows[index].subtypes.map(subtype => subtype.name)].map(typeKey)
    return { source, label, rows: indexes.filter(index => label.types.some(type => names(index).includes(typeKey(type)))) }
  })
  // 공고문은 PDF 공급 표 표기(24)를 쓰고 LH는 다른 이름(24A)을 쓰기도 한다. 형명으로 못 맞춘 LH 행은
  // 아직 맞추지 않은 공고문 행 중 전용면적이 같은 행이 하나뿐일 때만 그 행에 맞추고, 확인을 받는다.
  const claimed = new Set(named.flatMap(item => item.rows))
  const areas = (index: number) => pdfTypes(review.extraction.supplyRows[index]).map(type => type.exclusiveArea)
  return named.flatMap(item => {
    if (item.rows.length) return [item]
    const lhArea = item.source.exclusiveArea
    const byArea = lhArea == null ? [] : indexes.filter(index => !claimed.has(index) && areas(index).some(area => area !== null && Math.abs(area - lhArea) < 0.005))
    if (byArea.length !== 1) return []
    claimed.add(byArea[0])
    return [{ ...item, rows: byArea, byArea: true }]
  })
}

/**
 * 고른 단지로 공고문 행과 LH 원천행을 맞춘 검토를 만든다. 맞출 LH 행이 없으면 null을 돌려준다.
 * 처음 고르는 단지명은 지금 검토 위에서 맞춰 다른 단지명의 맞춤과 선택을 그대로 둔다.
 * 이미 맞춘 단지명을 다시 고르면 맞추기 전 검토에서 다른 단지명의 맞춤부터 다시 적용한다. 이미 복사한 행끼리 다시 맞추지 않는다.
 */
export function alignSourceRows(current: Review, workspace: JsonWorkspace, complexName: string,
  complex: { id: number; name: string }, candidates: MatchingHousingType[]): Review | null {
  const choice = { complexName, complex, candidates }, base = current.unaligned ?? current
  const others = (current.alignments ?? []).filter(item => item.complexName !== complexName)
  const start = others.length === current.alignments?.length ? current
    : others.reduce((review, item) => alignOn(review, workspace, item.complexName, item.complex, item.candidates) ?? review, base)
  const aligned = alignOn(start, workspace, complexName, complex, candidates)
  return aligned && { ...aligned, unaligned: base, alignments: [...others, choice] }
}

/** 이 검토 위에서 한 단지명의 공고문 행을 LH 원천행에 맞춘다. 행 번호가 바뀌므로 메모·근거·공통 모집수 경로를 새 번호로 옮기고, 다른 행의 선택은 그대로 유지한다. */
function alignOn(review: Review, workspace: JsonWorkspace, complexName: string, complex: { id: number; name: string }, candidates: MatchingHousingType[]): Review | null {
  const indexes = review.rows.map((_, index) => index).filter(index => review.extraction.supplyRows[index].complexName === complexName)
  const alignments = sourceAlignments(review, workspace, indexes, complexName)
  if (!alignments.length) return null
  // 블록 번호나 주소 한쪽만 맞은 공고 단지의 LH 행이면 1:1로 맞아도 확인하게 한다.
  const certain = sourceComplex(workspace, review.extraction, complexName).certain
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
  const planIndexes = (alignment: Alignment) => alignment.rows.map(from => plans.findIndex(plan => plan.from === from && plan.alignment === alignment))
  // LH 한 행에 맞춘 공고문 행 여럿은 대상·금액이 같으면 세부 타입 묶음 행 하나로 접고, 다르면 행마다 두고 LH 공급 수를 공통 모집수로 묶는다.
  const divided = alignments.filter(alignment => alignment.rows.length > 1)
  const folds = divided.filter(alignment => sameTargets(planIndexes(alignment).map(index => rows[index])))
  const split: SharedCount[] = divided.filter(alignment => !folds.includes(alignment)).map(alignment => {
    const paths = planIndexes(alignment).map(index => `supplyRows[${index}].totalSupplyHouseholdCount`)
    const evidence = evidenceFor(source.evidence, `supplyRows[${alignment.rows[0]}]`)
    return { paths, count: alignment.source.householdCount, page: evidence?.page ?? null, quote: evidence?.quote ?? '' }
  })
  const note = (item: ExtractionNote) => remap(item.path).map(path => ({ ...item, path }))
  const unfolded: Extraction = { ...source, supplyRows: rows, sharedCounts: [...kept, ...split],
    unresolved: source.unresolved.flatMap(note), notApplicable: source.notApplicable.flatMap(note),
    evidence: source.evidence.flatMap((item: Evidence) => remap(item.path).map(path => ({ ...item, path }))) }
  const folded = foldRows(unfolded, folds.map(alignment => ({ indexes: planIndexes(alignment), typeName: alignment.source.typeName, count: alignment.source.householdCount })))
  const extraction = folded.extraction
  const sharedIndex = (index: number) => {
    const at = extraction.sharedCounts.findIndex(group => group.paths.includes(`supplyRows[${index}].totalSupplyHouseholdCount`))
    return at < 0 ? null : at
  }
  // 접은 행은 첫 공고문 행의 계획만 남긴다.
  const keptPlans = plans.filter((_, at) => folded.position.indexOf(folded.position[at]) === at)
  return { ...review, extraction, rows: keptPlans.map((plan, index) => {
    const old = review.rows[plan.from]
    const pathOf = (path: string) => remap(path).flatMap(folded.remap).find(next => next.startsWith(`supplyRows[${index}].`))
    const retain = (cell: RowReview['cells'][number]) => preserveCellChoice(cell, [...old.cells, ...old.targets.flatMap(target => target.cells)].find(item => pathOf(item.path) === cell.path))
    if (!plan.alignment) {
      const next = createRowReview(extraction, index, { ...old.proposal, sharedIndex: sharedIndex(index) }, workspace)
      return { ...next, key: old.key, include: old.include, reviewed: old.reviewed, newHousingType: old.newHousingType,
        cells: next.cells.map(retain), targets: next.targets.map(target => ({ ...target, cells: target.cells.map(retain) })) }
    }
    const { alignment } = plan, first = alignment.rows[0] === plan.from, grouped = extraction.supplyRows[index].subtypes.length > 0
    const name = typeKey(extraction.supplyRows[index].typeName)
    // 세부 타입 묶음 행은 세부 타입마다 주택형을 고르고(createRowReview) 대표를 정한다.
    const housingTypeId = grouped ? null : candidates.find(type => typeKey(type.name) === name)?.id ?? pickHousingType(extraction, index, candidates).housingTypeId
    const pdfNames = alignment.rows.map(from => source.supplyRows[from].typeName).join('·')
    const reason = [alignment.rows.length > 1
      ? folds.includes(alignment)
        ? `LH 원천 '${alignment.source.typeName}' 한 행에 공고문 ${pdfNames}를 세부 타입으로 함께 연결합니다. 이번 공급 ${alignment.source.householdCount ?? '미확인'}세대는 이 공급행 하나의 모집수입니다.`
        : `LH 원천 '${alignment.source.typeName}'을(를) 공고문 ${pdfNames}로 나눕니다. 대상·금액이 행마다 달라 한 행으로 합치지 않고 이번 공급 ${alignment.source.householdCount ?? '미확인'}세대를 공통 모집수로 둡니다.`
      : `LH 원천 '${alignment.source.typeName}'에 연결합니다.`,
    alignment.byArea ? `형명이 달라(LH '${alignment.source.typeName}', 공고문 '${source.supplyRows[alignment.rows[0]].typeName ?? ''}') 전용면적으로 맞췄습니다. 확인하세요.` : '',
    (copies.get(plan.from)?.length ?? 0) > 1 && alignment.label.group ? `대상은 '${alignment.label.group}' 묶음만 넣었습니다.` : '',
    plan.extraTargets.length ? `${plan.extraTargets.join(', ')}은(는) LH 공급대상 묶음에 없어 이 행에 넣었습니다. 확인하세요.` : ''].filter(Boolean).join(' ')
    const next = createRowReview(extraction, index, { destination: first ? alignment.source.key : 'new', anchorKey: '', complexId: complex.id,
      housingTypeId, candidates, recommended: first ? [alignment.source.key] : [], reason, sharedIndex: sharedIndex(index),
      // LH 행 하나(형 하나)에 공고문 행 하나가 형명으로 맞고 대상을 나누지 않았으며 주택형도 정해졌으면 확인 없이 둔다.
      needsConfirmation: !certain || Boolean(alignment.byArea) || alignment.rows.length > 1 || alignment.label.types.length > 1 || (copies.get(plan.from)?.length ?? 0) > 1 || plan.extraTargets.length > 0 || housingTypeId === null,
      // 새 행은 LH 행과 같은 소재지·공급 구분이어야 공통 모집 묶음으로 저장된다.
      newComplex: { id: complex.id, name: complex.name, pnu: alignment.source.pnu, category: alignment.source.category } }, workspace)
    const newType = !grouped && housingTypeId === null && Boolean(extraction.supplyRows[index].typeName)
    // 연결 주택형은 맞춘 LH 행 기준으로 새로 정하므로 맞추기 전 선택을 이어받지 않는다.
    return { ...next, include: old.include, reviewed: false, newHousingType: newType,
      cells: next.cells.map(cell => cell.field === 'housingTypeId' ? cell : retain(cell)).map(cell => newType && cell.field === 'housingTypeId' ? editCell(cell, '') : cell),
      targets: next.targets.map(target => ({ ...target, cells: target.cells.map(retain) })) }
  }) }
}
