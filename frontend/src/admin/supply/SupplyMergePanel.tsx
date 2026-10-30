import { useState } from 'react'
import { mergeBlocked, mergeCandidates, suggestedMergeTarget } from './lhSupplyOwnership'
import type { CorrectionRow, CorrectionTarget, SupplyWorkspace } from './supplyCorrectionApi'
import styles from './SupplyCorrection.module.css'

function rowLabel(row: CorrectionRow) {
  return [row.typeName || '형명 없음', row.exclusiveArea !== null && `전용 ${row.exclusiveArea}㎡`, row.id && `#${row.id}`].filter(Boolean).join(' · ')
}
function targetLabel(target: CorrectionTarget) {
  return `${target.target} · 보증금 ${target.deposit ?? '미확인'} · 월 ${target.rent ?? '미확인'}`
}

/** LH 원본과 중복 가능한 직접 추가 행을 같은 단지의 LH 펼침 행으로 합친다. 저장 전까지는 편집 초안에만 반영한다. */
export function SupplyMergePanel({ workspace, rows, duplicate, onMerge }: {
  workspace: SupplyWorkspace; rows: CorrectionRow[]; duplicate: CorrectionRow; onMerge: (target: CorrectionRow) => void
}) {
  const candidates = mergeCandidates(workspace, rows, duplicate)
  const suggested = suggestedMergeTarget(candidates, duplicate)
  const [chosen, setChosen] = useState(suggested?.key ?? '')
  const target = candidates.find(row => row.key === chosen) ?? null
  const blocked = target !== null && mergeBlocked(target)
  const moved = duplicate.targets.filter(item => !item.excluded)
  return <section className={styles.merge} aria-label="LH 펼친 행으로 합치기">
    <h4>LH 펼친 행으로 합치기</h4>
    {!candidates.length ? <p className={styles.hint}>같은 단지에 합칠 LH 펼침 행이 없습니다. 같은 주택형이 아니면 이 행을 그대로 두거나 지우세요.</p> : <>
      <p className={styles.hint}>이 행의 공고 표기·모집수·입주예정월·공급구분·면적과 임대조건을 고른 LH 펼침 행으로 옮기고, 이 행은 삭제 예정으로 표시합니다. 비어 있는 칸은 펼침 행 값을 유지합니다.</p>
      <label>합칠 LH 펼침 행<select value={chosen} onChange={event => setChosen(event.target.value)}>
        <option value="">합칠 행을 고르세요</option>
        {candidates.map(row => <option key={row.key} value={row.key}>{rowLabel(row)}{row.key === suggested?.key ? ' (같은 주택형)' : ''}</option>)}
      </select></label>
      {target && <p>{`공고 표기 ${duplicate.typeName || target.typeName} · 모집수 ${duplicate.householdCount ?? target.householdCount ?? '미확인'} · 옮길 임대조건 ${moved.length}개`}</p>}
      {blocked && <div role="note" className={styles.stale}><p>고른 펼침 행에 이미 임대조건이 있어 합칠 수 없습니다. 어느 조건을 남길지 펼침 행에서 먼저 정리하세요(남기지 않을 조건은 제외).</p>
        <ul><li>펼침 행: {target.targets.filter(item => !item.excluded).map(targetLabel).join(' / ')}</li><li>이 행: {moved.map(targetLabel).join(' / ') || '없음'}</li></ul></div>}
      <button type="button" disabled={!target || blocked} onClick={() => target && onMerge(target)}>펼친 행으로 합치기</button>
    </>}
  </section>
}
