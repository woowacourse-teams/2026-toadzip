import { useState } from 'react'
import { Button } from '../../design-system/components/Button'
import type { JsonWorkspace } from '../announcement-json/api'
import type { MatchingHousingType } from '../ingest/supplyMatchingApi'
import { complexTypeViews } from './complexTypes'
import type { Review } from './review'
import styles from './PdfCorrection.module.css'

const none = (value: number | null | undefined, unit = '') => value == null ? '없음' : `${value.toLocaleString('ko-KR')}${unit}`

/**
 * 고른 공급행이 속한 단지의 주택형 전체. 단지 정보(면적·건설호수·기본 임대조건)와 이번 공고 값(모집수·예비자 수·연결 공급행)을 나눠 보이고,
 * 고른 행에 기존 주택형을 연결하거나 PDF 형명·면적으로 새 주택형을 만들게 한다. 닫아도 편집 중인 값은 그대로다.
 */
export function ComplexTypesPanel({ complexName, complexId, review, workspace, types, rowIndex, linkedId, canLink, onLink, onClose }: {
  complexName: string; complexId: number | null; review: Review; workspace: JsonWorkspace; types: MatchingHousingType[]; rowIndex: number; linkedId: number | null
  canLink: boolean; onLink: (typeId: number) => void; onClose: () => void }) {
  const [query, setQuery] = useState('')
  const key = (value: string) => value.normalize('NFKC').replace(/\s+/g, '').toLowerCase()
  const views = complexTypeViews(complexId, review, workspace, types).filter(view => !query.trim() || key(`${view.type.name} ${view.type.exclusiveArea}`).includes(key(query)))
  return <aside className={styles.typesPanel} aria-label={`${complexName} 주택형`}>
    <header><h3>{complexName} 주택형 {types.length}개</h3><Button data-design-system className={styles.quietButton} onClick={onClose}>주택형 패널 닫기</Button></header>
    {complexId === null ? <p className={styles.hint}>단지를 정하면 주택형을 볼 수 있습니다.</p> : <>
      <label>주택형 찾기<input type="search" value={query} placeholder="형명·전용면적" onChange={event => setQuery(event.target.value)} /></label>
      <h4>기존 주택형 연결 · 공급행 {rowIndex + 1}</h4>
      {!canLink && <p className={styles.hint}>세부 타입 묶음 행은 아래 세부 타입 연결에서 정합니다.</p>}
      <ul className={styles.typeList}>{views.map(view => <li key={view.type.id} data-linked={view.type.id === linkedId || undefined}>
        <div className={styles.typeHead}><strong>{view.type.name}</strong><span>{`전용 ${view.type.exclusiveArea}㎡ · 공급 ${none(view.type.supplyArea, '㎡')}`}</span>
          {view.badges.map(badge => <small key={badge} className={badge === '확인 필요' || badge === '중복 후보' ? styles.badgeWarning : styles.badgeMuted}>{badge}</small>)}</div>
        <dl>
          <div><dt>단지 정보 · 건설호수</dt><dd>{none(view.type.householdCount, '세대')}</dd></div>
          <div><dt>단지 정보 · 기본 임대조건</dt><dd>{view.type.basicDeposit == null && view.type.basicMonthlyRent == null ? '없음' : `보증금 ${none(view.type.basicDeposit, '원')} · 월 ${none(view.type.basicMonthlyRent, '원')}`}</dd></div>
          <div><dt>이번 공고 · 모집수</dt><dd>{none(view.recruitment, '세대')}</dd></div>
          <div><dt>이번 공고 · 예비자 수</dt><dd>{none(view.reserve, '명')}</dd></div>
          <div><dt>연결 공급행</dt><dd>{view.rows.length ? view.rows.map(row => `${row.typeName} #${String(row.id)}`).join(', ') : '없음'}</dd></div>
          <div><dt>PDF 추출 행</dt><dd>{view.extracted.length ? view.extracted.map(index => `공급행 ${index + 1}(${review.extraction.supplyRows[index].typeName ?? '표기 없음'})`).join(', ') : '없음'}</dd></div>
        </dl>
        {view.type.id === linkedId ? <small className={styles.pendingDone}>공급행 {rowIndex + 1}에 연결함</small>
          : <Button data-design-system className={styles.secondaryButton} disabled={!canLink} onClick={() => onLink(view.type.id)}>{`${view.type.name} · 전용 ${view.type.exclusiveArea}㎡를 공급행 ${rowIndex + 1}에 연결`}</Button>}
      </li>)}</ul>
      {!views.length && <p className={styles.hint}>맞는 주택형이 없습니다.</p>}
      <h4>새 주택형 생성</h4>
      <p className={styles.hint}>연결 주택형을 비워 두면 저장할 때 같은 전용면적 주택형에 연결하고, 없으면 PDF 형명·면적으로 새로 만듭니다.</p>
    </>}
  </aside>
}
