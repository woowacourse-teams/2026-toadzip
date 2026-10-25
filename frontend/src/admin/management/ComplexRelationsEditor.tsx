import { useCallback, useState } from 'react'
import { Link } from 'react-router'
import { getManagementDetail, managementResourcePath, requestManagementApi } from './api'
import { HousingTypeCreator } from './HousingTypeCreator'
import { HousingTypeEditor } from './HousingTypeEditor'
import { ManagementDetail } from './ManagementDetail'
import { ManagementSummaryTable } from './ManagementSummaryTable'
import { parseManagementDetail, type ManagementDetailData, type ManagementHousingType } from './managementContract'

export function ComplexRelationsEditor({ value, id, onSaved, onBusyChange }: {
  value: ManagementDetailData; id: string; onSaved: (value: ManagementDetailData) => void;
  onBusyChange: (busy: boolean) => void;
}) {
  const [typeId, setTypeId] = useState<number | null>(null)
  const [announcementId, setAnnouncementId] = useState<number | null>(null)
  const [busy, setBusy] = useState(false)
  const [adding, setAdding] = useState(false)
  const [refreshError, setRefreshError] = useState('')
  const [typeError, setTypeError] = useState('')
  const type = value.housingTypes.find(item => item.id === typeId)
  const pending = useCallback((next: boolean) => { setBusy(next); onBusyChange(next) }, [onBusyChange])
  async function refresh() {
    setRefreshError('')
    try { onSaved(await getManagementDetail('complexes', id)) }
    catch (cause) { setRefreshError(cause instanceof Error ? cause.message : '연결 공고 목록을 갱신하지 못했습니다.') }
  }
  // 휴지통은 복구할 수 있어 확인 창 없이 바로 옮기고, 같은 줄의 복구 버튼으로 되돌린다.
  async function toggleTrash(item: ManagementHousingType) {
    pending(true); setTypeError('')
    try {
      const path = `${managementResourcePath('complexes')}/${id}/housing-types/${item.id}${item.deleted ? '/restore' : ''}?version=${value.data.version}`
      onSaved(parseManagementDetail(await requestManagementApi(path, item.deleted ? 'POST' : 'DELETE')))
      if (typeId === item.id) setTypeId(null)
    } catch (cause) { setTypeError(cause instanceof Error ? cause.message : '주택형을 처리하지 못했습니다.') }
    finally { pending(false) }
  }
  return <>
    <section className="admin-relations-section" aria-label="주택형 관리"><header className="admin-inline"><h2>주택형</h2>
      {!value.summary.deleted && !adding ? <button type="button" className="admin-tonal" data-admin-navigation disabled={busy} onClick={() => { setAdding(true); setTypeId(null) }}>주택형 추가</button> : null}</header>
      {adding ? <HousingTypeCreator key={value.data.version as number} complexId={id} version={Number(value.data.version)} onSaved={onSaved} onClose={() => setAdding(false)} onBusyChange={pending} /> : null}
      {value.housingTypes.length ? <div className="admin-table-scroll"><table className="admin-table" aria-label="단지 주택형">
        <thead><tr><th scope="col">이름</th><th scope="col">전용면적</th><th scope="col">세대수</th><th scope="col">관리</th></tr></thead>
        <tbody>{value.housingTypes.map(item => <tr key={item.id} aria-selected={typeId === item.id} data-deleted={item.deleted || undefined}>
          <th scope="row">{item.name}{item.deleted ? <small> · 휴지통</small> : null}</th><td>{item.exclusiveArea}㎡</td><td>{item.householdCount ?? '미확인'}</td>
          <td>{!value.summary.deleted ? <div className="admin-inline">
            {!item.deleted ? <button type="button" data-admin-navigation disabled={busy} aria-label={`${item.name} 수정`}
              onClick={() => { setTypeId(item.id); setAnnouncementId(null); setAdding(false) }}>수정</button> : null}
            <button type="button" className={item.deleted ? undefined : 'admin-danger'} disabled={busy} aria-label={`${item.name} ${item.deleted ? '복구' : '삭제'}`}
              onClick={() => void toggleTrash(item)}>{item.deleted ? '복구' : '삭제'}</button></div> : '휴지통'}</td>
        </tr>)}</tbody></table></div> : <p>등록된 주택형이 없습니다.</p>}
      {typeError ? <p className="registration-error" role="alert">{typeError}</p> : null}
      {type ? <HousingTypeEditor key={`${type.id}-${value.data.version}`} type={type} complexId={id} version={Number(value.data.version)}
        onSaved={onSaved} onClose={() => setTypeId(null)} onBusyChange={pending} /> : null}
    </section>
    <section className="admin-relations-section" aria-label="연결 공고 관리">
      <header className="admin-inline"><h2>연결된 공고</h2>{!value.summary.deleted ? <Link to={`/admin/announcements/new?mode=direct&complexId=${id}`}>이 단지에 공고 등록</Link> : null}</header>
      {refreshError ? <div role="alert" className="registration-error"><p>{refreshError}</p><button type="button" onClick={() => void refresh()}>연결 공고 다시 조회</button></div> : null}
      <ManagementSummaryTable items={value.announcements} resource="announcements" editDisabled={busy} onEdit={!value.summary.deleted ? item => {
        setAnnouncementId(item.id); setTypeId(null)
      } : undefined} />
      {announcementId !== null ? <section className="admin-linked-announcement" role="region" aria-label="연결 공고 편집">
        <header className="admin-inline"><h3>연결 공고 편집</h3><button type="button" data-admin-navigation disabled={busy}
          onClick={() => setAnnouncementId(null)}>공고 편집 닫기</button></header>
        <ManagementDetail key={announcementId} detailId={String(announcementId)} resource="announcements" embedded onChanged={() => void refresh()} onBusyChange={pending} />
      </section> : null}
    </section>
  </>
}
