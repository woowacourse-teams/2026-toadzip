import { Fragment, useState } from 'react'
import { ComplexPicker } from '../management/ComplexPicker'
import { ManagementError } from '../management/api'
import { linkAnnouncementComplex, type AnnouncementComplex, type SupplyWorkspace } from './supplyCorrectionApi'
import styles from './SupplyCorrection.module.css'

const methodLabels = { AUTO: '자동 연결 · 확인 전', ADMIN: '관리자 확인', MIGRATED: '기존 연결 이관 · 확인 전' } as const

/**
 * 공고문의 단지(공고 단지)마다 연결한 등록 단지를 보여 주고 관리자가 바꾸거나 맞다고 확인한다.
 * 바꾸면 그 공고 단지의 공급행도 새 단지의 같은 전용면적 주택형으로 옮긴다. 편집 중인 공급정보가 있으면 덮지 않도록 막는다.
 */
export function AnnouncementComplexLinks({ id, workspace, disabled, onLinked, onBusyChange }: {
  id: string; workspace: SupplyWorkspace; disabled: boolean; onLinked: (workspace: SupplyWorkspace, notice: string) => void; onBusyChange: (busy: boolean) => void
}) {
  const [changing, setChanging] = useState<number | null>(null)
  const [busy, setBusy] = useState(false), [error, setError] = useState('')
  const complexes = workspace.announcementComplexes ?? []
  if (!complexes.length) return null
  async function link(target: AnnouncementComplex, housingComplexId: number, housingComplexName: string) {
    const moving = target.housingComplexId !== housingComplexId
    if (moving && !window.confirm(`'${target.name}'을(를) '${housingComplexName}'에 연결합니다. 이 공고 단지의 공급행 ${target.rowCount}개도 새 단지의 같은 전용면적 주택형으로 옮깁니다.`)) return
    setBusy(true); onBusyChange(true); setError('')
    try {
      const next = await linkAnnouncementComplex(id, target.id, { snapshotToken: workspace.snapshotToken, housingComplexId })
      setChanging(null)
      onLinked(next, moving ? `'${target.name}'을(를) '${housingComplexName}'에 연결하고 공급행을 옮겼습니다.` : `'${target.name}'의 연결을 확인했습니다.`)
    } catch (cause) {
      setError(cause instanceof ManagementError && cause.code === 'ADMIN_DATA_STALE' ? '그사이 공급정보가 바뀌었습니다. 공급정보를 다시 불러온 뒤 연결하세요.'
        : cause instanceof Error ? cause.message : '연결하지 못했습니다.')
    } finally { setBusy(false); onBusyChange(false) }
  }
  return <section aria-label="공고 단지 연결">
    <h3>공고 단지 연결</h3>
    <p className={styles.hint}>공고문의 단지마다 연결한 등록 단지입니다. 자동 연결은 주소·공급유형이 같은 단지를 정제가 고른 것이라 확인하세요.</p>
    {disabled && <p className={styles.hint}>편집 중인 공급정보를 저장하거나 취소한 뒤 연결을 바꿀 수 있습니다.</p>}
    <div className={styles.tableScroll}><table aria-label="공고 단지 연결 표"><thead><tr><th scope="col">공고 단지</th><th scope="col">연결 단지</th><th scope="col">연결 방법</th><th scope="col">공급행</th><th scope="col">변경</th></tr></thead><tbody>
      {complexes.map(target => <Fragment key={target.id}><tr>
        <th scope="row">{target.name}<small className={`${styles.hint} ${styles.complexAddress}`}>{target.roadAddress ?? '주소 미확인'}</small></th>
        <td>{target.housingComplexName ?? '미연결'}</td>
        <td>{target.linkMethod ? methodLabels[target.linkMethod] : '미연결'}</td>
        <td>{target.rowCount}개</td>
        <td><div className={styles.rowActions}>
          {target.housingComplexId !== null && target.linkMethod !== 'ADMIN' && <button type="button" disabled={disabled || busy} aria-label={`${target.name} 연결 맞음`}
            onClick={() => void link(target, target.housingComplexId!, target.housingComplexName ?? '')}>연결 맞음</button>}
          <button type="button" disabled={disabled || busy} aria-expanded={changing === target.id} aria-label={`${target.name} 단지 바꾸기`}
            onClick={() => setChanging(changing === target.id ? null : target.id)}>{changing === target.id ? '닫기' : '단지 바꾸기'}</button>
        </div></td>
      </tr>{changing === target.id && <tr><td colSpan={5} className={styles.expandedCell}><div role="group" aria-label={`${target.name} 연결 단지 찾기`}>
        <p className={styles.hint}>{`고른 단지에 연결하고 공급행 ${target.rowCount}개를 그 단지의 같은 전용면적 주택형으로 옮깁니다.`}</p>
        <ComplexPicker disabled={disabled || busy} initialQuery={target.housingComplexName ?? target.name} onSelect={item => void link(target, item.id, item.name)} />
      </div></td></tr>}</Fragment>)}
    </tbody></table></div>
    {error && <p role="alert" className="registration-error">{error}</p>}
  </section>
}
