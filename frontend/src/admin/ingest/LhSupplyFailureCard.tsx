import { useEffect, useState } from 'react'
import { getLhSupplyLinks, type LhLinkMyHomeRow, type LhLinkSupplyRow, type LhMatchCandidate,
  type LhSupplyLinkView, type LhSupplyMatchFailure } from './supplyMatchingApi'
import styles from './AnnouncementSupplyMatchingForm.module.css'

export type LhLinkPrefill = { lhRowIdentifier: string; myhomeRowIdentifier: string }

/**
 * LH 보강이 실패한 주택형을 LH 공급 원본 값과 등록된 공급행 값으로 나란히 보여 준다.
 * 현재 데이터로 매칭을 다시 해 본 결과라 오류 기록 시점과 다를 수 있다.
 */
export function LhSupplyFailureCard({ identifier, onLink, onMatch }: {
  identifier: string; onLink: (prefill: LhLinkPrefill) => void; onMatch: () => void
}) {
  const [view, setView] = useState<LhSupplyLinkView | null>(null)
  const [error, setError] = useState('')
  useEffect(() => {
    const controller = new AbortController()
    setView(null); setError('')
    void getLhSupplyLinks(identifier, controller.signal).then(result => {
      if (!controller.signal.aborted) setView(result)
    }).catch(cause => {
      if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '실패한 주택형을 확인하지 못했습니다.')
    })
    return () => controller.abort()
  }, [identifier])

  if (error) return <p role="alert">실패한 주택형을 확인하지 못했습니다: {error}</p>
  if (!view) return <p role="status">실패한 주택형을 확인하는 중…</p>
  const failures = view.failures ?? []
  return <section className={styles.workspace} aria-label="LH 보강 실패 주택형">
    <AnnouncementHeading view={view} />
    {failures.length === 0
      ? <p className={styles.notice}>현재 데이터로 다시 맞춰 보면 LH 공급행이 모두 공고 공급행에 이어집니다.
        이 오류는 이전 정제 때 기록된 것이니 아래의 공고 정제 다시 실행으로 정리하세요.</p>
      : <p className={styles.warning}>LH 공급 원본 {view.lhRows.length}개 중 {failures.length}개 주택형이 공고 공급행과 이어지지 않습니다.</p>}
    {failures.map(failure => <FailureDetail key={failure.lhRowIdentifier} failure={failure}
      row={view.lhRows.find(value => value.rowIdentifier === failure.lhRowIdentifier)}
      myhomeRows={view.myhomeRows} onLink={onLink} onMatch={onMatch} />)}
  </section>
}

function AnnouncementHeading({ view }: { view: LhSupplyLinkView }) {
  const announcement = view.announcement
  return <header className={styles.heading}>
    <div>
      <span className={styles.eyebrow}>공고 {announcement?.identifier}{announcement?.panId ? ` · LH 공고 ${announcement.panId}` : ''}</span>
      <h4>{announcement?.name ?? '공고명 없음'}</h4>
    </div>
    {announcement?.sourceUrl && <a href={announcement.sourceUrl} target="_blank" rel="noreferrer">공고 원문 보기</a>}
  </header>
}

function FailureDetail({ failure, row, myhomeRows, onLink, onMatch }: {
  failure: LhSupplyMatchFailure; row: LhLinkSupplyRow | undefined; myhomeRows: LhLinkMyHomeRow[]
  onLink: (prefill: LhLinkPrefill) => void; onMatch: () => void
}) {
  const lhComplex = row?.complexLabel ?? '단지명 없음'
  const lhType = row?.typeName ?? '주택형 없음'
  return <div className={styles.card}>
    <h5>실패한 주택형: {lhType} · 전용 {row?.exclusiveArea ?? '미제공'}㎡ · 모집 {row?.suppliedUnitCount ?? '미제공'}</h5>
    <table className={styles.compare}>
      <thead><tr><th scope="col"></th><th scope="col">LH 공급 원본</th>
        {failure.candidates.length === 0
          ? <th scope="col">우리 공고의 공급행</th>
          : failure.candidates.map((candidate, index) => <th key={candidate.supplyRowId} scope="col">
            우리 공고의 공급행{failure.candidates.length > 1 ? ` ${index + 1}` : ''}</th>)}</tr></thead>
      <tbody>
        <tr><th scope="row">단지명</th><td>{lhComplex}</td>{cells(failure.candidates, candidate => candidate.sourceComplexName,
          candidate => failure.reason === 'COMPLEX_NOT_FOUND' && candidate.sourceComplexName !== lhComplex)}</tr>
        <tr><th scope="row">주택형</th><td>{lhType}</td>{cells(failure.candidates, candidate => candidate.sourceHousingTypeName,
          () => failure.reason === 'HOUSING_TYPE_NOT_FOUND')}</tr>
        <tr><th scope="row">연결 단지</th><td>—</td>{cells(failure.candidates, candidate => candidate.complexName ?? '없음', () => false)}</tr>
      </tbody>
    </table>
    <p><strong>왜 실패했나:</strong> {why(failure)}</p>
    <Decision failure={failure} row={row} myhomeRows={myhomeRows} lhComplex={lhComplex} onLink={onLink} onMatch={onMatch} />
  </div>
}

function cells(candidates: LhMatchCandidate[], value: (candidate: LhMatchCandidate) => string,
  different: (candidate: LhMatchCandidate) => boolean) {
  if (candidates.length === 0) return <td>비교할 공급행 없음</td>
  return candidates.map(candidate => <td key={candidate.supplyRowId} className={different(candidate) ? styles.differs : undefined}>
    {value(candidate)}{different(candidate) ? ' (다름)' : ''}</td>)
}

function why(failure: LhSupplyMatchFailure): string {
  switch (failure.reason) {
    case 'COMPLEX_NOT_FOUND':
      return failure.candidates.length > 0
        ? '주택형이 같은 공급행은 있지만 단지명이 달라 같은 단지로 판단하지 못했습니다.'
        : '단지명이나 주택형이 같은 공급행이 공고에 없습니다.'
    case 'HOUSING_TYPE_NOT_FOUND': return '같은 단지의 공급행은 있지만 주택형 이름이 달라 하나로 잇지 못했습니다.'
    case 'AMBIGUOUS_COMPLEX': return '단지명이 맞는 공급 단지가 여러 개라 하나로 정하지 못했습니다.'
    case 'AMBIGUOUS_HOUSING_TYPE': return '단지와 주택형이 맞는 공급행이 여러 개라 하나로 정하지 못했습니다.'
    case 'LH_SUPPLY_LINK_REVIEW_REQUIRED': return '관리자가 저장한 연결 이후 LH 행이나 마이홈 원천이 바뀌어 연결을 쓰지 않았습니다.'
    default: return failure.detail
  }
}

function Decision({ failure, row, myhomeRows, lhComplex, onLink, onMatch }: {
  failure: LhSupplyMatchFailure; row: LhLinkSupplyRow | undefined; myhomeRows: LhLinkMyHomeRow[]; lhComplex: string
  onLink: (prefill: LhLinkPrefill) => void; onMatch: () => void
}) {
  // 공급행에 단지가 연결되지 않으면(마이홈 단지 매칭 실패) 정제가 LH 연결도 기록하지 않아 재정제로 풀리지 않는다.
  const unmatched = failure.candidates.filter(candidate => candidate.complexName === null)
  if (unmatched.length > 0) {
    return <div>
      <p className={styles.warning}><strong>판단할 것:</strong> 비교한 공급행({unmatched.map(candidate => candidate.sourceComplexName).join(', ')})에
        단지가 연결되어 있지 않습니다. 마이홈 원천의 단지를 하나로 정하지 못하면 정제가 LH 연결도 기록하지 않으므로,
        먼저 단지·주택형 매칭에서 단지를 정한 뒤 공고 정제를 다시 실행하세요.</p>
      <button className={styles.secondary} type="button" onClick={onMatch}>단지·주택형 매칭 열기</button>
    </div>
  }
  const resolvedRow = row?.savedLink?.reviewRequired ? null : row?.linkedRowIdentifier
  if (resolvedRow && failure.reason === 'COMPLEX_NOT_FOUND') {
    return <p className={styles.notice}><strong>판단할 것:</strong> 정제 기준으로는 이미 {label(myhomeRows, resolvedRow)}에 이어집니다.
      공고 정제를 다시 실행해 해결되는지 확인하세요. 다시 실행해도 남으면 연결 기록 문제이니 개발 확인이 필요합니다.</p>
  }
  const targets = failure.candidates.filter(candidate => candidate.myhomeRowIdentifier)
  return <div>
    <p><strong>판단할 것:</strong> {targets.length > 0
      ? `공고문에서 "${lhComplex}"이(가) ${targets.map(candidate => `"${candidate.sourceComplexName}"`).join(', ')} 모집에 포함되는지 확인하세요.`
      : `공고문에서 "${lhComplex}"이(가) 어느 마이홈 단지 모집에 해당하는지 확인하세요. 해당 단지가 공고에 없다면 단지 등록이 필요합니다.`}</p>
    <div className={styles.heading}>
      {targets.map(candidate => <button key={candidate.supplyRowId} className={styles.secondary} type="button"
        onClick={() => onLink({ lhRowIdentifier: failure.lhRowIdentifier, myhomeRowIdentifier: candidate.myhomeRowIdentifier ?? '' })}>
        포함된다면: 이 LH 행을 {candidate.sourceComplexName}에 연결</button>)}
    </div>
  </div>
}

function label(rows: LhLinkMyHomeRow[], identifier: string): string {
  const row = rows.find(value => value.rowIdentifier === identifier)
  return row?.complexName ?? '마이홈 원천행'
}
