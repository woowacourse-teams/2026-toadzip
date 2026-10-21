import { useEffect, useRef } from 'react'
import type { JsonObject, JsonPreview, JsonUpdateInput } from '../announcement-json/api'
import { allCells, applicableCells, rowFields, scheduleFields, shown, targetFields, type Review } from './review'
import styles from './PdfCorrection.module.css'

function Changes({ before, after, fields }: { before?: JsonObject | null; after: JsonObject; fields: [string, string][] }) {
  const changed = fields.filter(([field]) => JSON.stringify(before?.[field] ?? null) !== JSON.stringify(after[field] ?? null))
  return changed.length > 0 ? <dl className={styles.changes}>{changed.map(([field, label]) => <div key={field}><dt>{label}</dt><dd>{shown(before?.[field])} → <strong>{shown(after[field])}</strong></dd></div>)}</dl> : <p className={styles.hint}>값 변경 없음</p>
}
export function PreviewChanges({ preview, input, review }: { preview: JsonPreview; input: JsonUpdateInput; review: Review }) {
  const heading = useRef<HTMLHeadingElement>(null)
  useEffect(() => { heading.current?.focus({ preventScroll: true }) }, [])
  const active = applicableCells(review)
  const excluded = allCells(review).filter(cell => (!active.includes(cell) || !cell.selected || cell.locked) && cell.state !== 'same')
  const label = (field: string) => allCells(review).find(cell => cell.path === `announcement.${field}` || cell.path === field)?.label ?? field
  return <section className={styles.preview}><h2 ref={heading} tabIndex={-1}>적용할 변경</h2><p>기존 공고에 아래 변경을 함께 저장합니다. 새 공고를 만들지 않습니다.</p>
    {preview.fields.length > 0 && <><h3>기본정보·접수처</h3><dl className={styles.changes}>{preview.fields.map(change => <div key={change.field}><dt>{label(change.field)}</dt><dd>{shown(change.before)} → <strong>{shown(change.after)}</strong></dd></div>)}</dl></>}
    {preview.supply && <><h3>공급정보</h3>{input.correction.newGroups?.map(group => <p key={group.sourceRowId}>기존 행 #{group.sourceRowId}을 {group.rowKeys.length}개 주택형으로 분할 · 공통 모집수 {group.sharedHouseholdCount}세대</p>)}
      {preview.supply.after.map(row => {
        const before = preview.supply?.before.find(item => item.id === row.id)
        if (JSON.stringify(before) === JSON.stringify(row)) return null
        return <section className={styles.supplyBlock} key={row.key}><h4>{row.complexName} · {row.typeName}{!before ? ' · 새 행' : ''}</h4>
          <Changes before={before as unknown as JsonObject} after={row as unknown as JsonObject} fields={[
            ['housingTypeId', '연결 주택형 ID'], ...rowFields.map(([field, name, , target]) => [target ?? field, name] as [string, string]),
          ]} />
          {row.targets.map(target => {
            const old = before?.targets.find(item => item.id === target.id)
            if (JSON.stringify(old) === JSON.stringify(target)) return null
            return <section key={target.key}><h4>{target.target} · {target.supplyRank ?? '순위 없음'}{!old ? ' · 새 조건' : ''}</h4><Changes before={old as unknown as JsonObject} after={target as unknown as JsonObject} fields={targetFields.map(([field, name, , to]) => [to ?? field, name])} /></section>
          })}
        </section>
      })}</>}
    {preview.schedules.length > 0 && <><h3>일정 {preview.schedules.length}건</h3>{preview.schedules.map((change, index) => <section className={styles.supplyBlock} key={index}><h4>{change.existingId ? `기존 일정 #${change.existingId}` : '새 일정'} · {shown(change.after.name)}</h4><Changes before={change.before} after={change.after} fields={scheduleFields.map(([field, name]) => [field, name])} /></section>)}</>}
    <h3>적용하지 않는 항목</h3><p>공급행 {review.rows.filter(row => !row.include).length}개 · 조건 {review.rows.filter(row => row.include).flatMap(row => row.targets).filter(target => target.destination === 'exclude').length}개 · 일정 {review.schedules.filter(schedule => !schedule.include).length}개 제외</p>
    <details><summary>미확인·해당 없음·선택 해제 {excluded.length}개</summary><ul>{excluded.map(cell => <li key={cell.path}>{cell.label} · {cell.path}: {!active.includes(cell) ? '이 행·조건을 제외함' : cell.note || (cell.locked ? '현재값 유지' : cell.state === 'invalid' ? '형식 확인 필요' : '선택하지 않음')}</li>)}</ul></details>
    {preview.warnings.map((warning, index) => <p className={styles.hint} key={index}>{warning}</p>)}{preview.supply?.warnings.map((warning, index) => <p className={styles.hint} key={index}>{warning}</p>)}
    <h3>저장 근거</h3><p>{input.correction.reason}</p><p>{input.correction.sourceUrl} · {input.correction.sourcePage ?? '쪽 미지정'}</p>
  </section>
}
