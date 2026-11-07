import { useCallback, useId, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { listFeedback } from '../feedback/api'
import { pipelineStatusLabels } from '../ingest/pipelineLabels'
import { loadAnnouncementOverview, loadCollectionOverview, loadComplexOverview, loadUserOverview } from './api'
import { OperationsIcon } from './OperationsIcon'
import { OperationsMetric } from './OperationsMetric'
import { OperationsNotifications } from './OperationsNotifications'
import { OperationsTrend } from './OperationsTrend'
import { CheckedAt, OperationsStatus } from './OperationsStatus'
import { comparison, operationCount, operationTime } from './presentation'
import { useOperationsQuery } from './useOperationsQuery'
import styles from './OperationsHome.module.css'

const loadFeedback = (signal: AbortSignal) => listFeedback(new URLSearchParams({ status: 'OPEN', page: '0', size: '1' }), signal)
const helpText = {
  complex: ['단지 집계', '휴지통을 제외한 현재 단지를 집계합니다. 모집중은 공개된 공고중·접수중 공고가 연결된 단지이며, 정정·취소 공고로 대체된 공고는 제외합니다. 여러 공고가 연결돼도 단지는 한 번만 셉니다. 좌표 미확인은 위도 또는 경도가 없는 단지, 재검토는 원천 변경 확인이 필요한 단지입니다. 기간 선택은 적용하지 않습니다.'],
  announcement: ['공고 집계', '휴지통을 제외한 전체 공고의 접수 상태를 기존 일정 판정 기준으로 집계합니다. 검토된 일정은 확정·조건부 접수를 구분하고, 일정을 정할 수 없으면 일정 미정으로 표시합니다. 접수 상태와 검토·공개 상태는 별도입니다. 최근 수집의 마지막 성공은 수집·정제 실행 전체가 성공한 종료 시각입니다. 일부 실패·건너뜀·중지·실패는 성공 시각을 갱신하지 않습니다. 모든 시각은 한국 시각입니다.'],
  review: ['확인 필요', '전체는 중복을 제외한 공고 수입니다. 사유별 건수는 중복될 수 있습니다. 일정 누락은 접수 시작·종료일이 없거나 검토된 접수 일정이 없는 경우, 공급정보 누락은 제외되지 않은 공급 행이 없는 경우, 미확정은 공개 전 확인이 필요한 공고입니다.'],
  users: ['유저 집계', '전체 회원과 새 사용자 의견은 조회 시점 기준입니다. 신규 가입·추이에만 선택 기간을 적용하고 바로 앞의 같은 길이 기간과 비교합니다. 오늘은 조회 시각까지 포함하므로 이전 기간과 비교할 때 참고해 주세요. 홈에는 회원 개인정보를 표시하지 않습니다.'],
  notifications: ['알림 집계', '활성 알림은 현재 유효한 신청 설정 수이며 신청 유저는 중복을 제외합니다. 신청·취소는 선택 기간의 실제 상태 변화이며 재신청을 포함하고 자동 만료는 제외합니다. 신청−취소는 활성 알림의 순증이 아닙니다. 미집계 날짜가 포함된 기간은 합계·증감을 미집계로 표시합니다. 오늘과 집계 시작일은 일부 시간만 포함됩니다. 발송 실패·재시도 대기는 현재 상태입니다.'],
  messages: ['카카오톡 메시지 발송', '관리자가 수신 대상을 정하고 카카오톡 API로 메시지를 보내는 기능은 후속 작업입니다. 현재는 준비중 진입점만 제공합니다.'],
  links: ['연결 기준', '각 숫자는 기존 관리 화면으로 연결합니다. 기관별 단지·원천 변경 재검토·미확정 공고·새 의견·활성 알림·신청 취소 추이·발송 상태는 기존 필터를 적용합니다. 모집중 단지·좌표 미확인·공고 접수 상태와 누락 사유·가입 기간 필터는 후속 작업이며, 현재는 기존 전체 목록으로 연결합니다. 기관별 수집 링크는 기존 공고 수집 이력으로 이동합니다.'],
  period: ['기간 적용', '7일·30일 선택은 신규 가입과 알림 신청·취소에만 적용합니다. 오늘을 포함한 한국 시각 기준이며 증감은 바로 앞의 같은 길이 기간과 비교합니다. 전체·활성·현재 상태 지표는 조회 시점 기준으로 유지합니다.'],
} as const
export type OperationsHelp = keyof typeof helpText

export function OperationsHome() {
  const [params, setParams] = useSearchParams()
  const days = params.get('days') === '30' ? 30 : 7
  const [revision, setRevision] = useState(0)
  const [help, setHelp] = useState<OperationsHelp>('period')
  const dialog = useRef<HTMLDialogElement>(null)
  const helpId = useId()
  const complex = useOperationsQuery(loadComplexOverview, revision)
  const announcement = useOperationsQuery(loadAnnouncementOverview, revision)
  const sources = useOperationsQuery(loadCollectionOverview, revision)
  const users = useOperationsQuery(useCallback((signal: AbortSignal) => loadUserOverview(days, signal), [days]), revision)
  const feedback = useOperationsQuery(loadFeedback, revision)
  function openHelp(key: OperationsHelp) { setHelp(key); dialog.current?.showModal() }
  function info(key: OperationsHelp, label: string) { return <button type="button" className={styles.info} aria-label={label} onClick={() => openHelp(key)}><OperationsIcon name="info" /></button> }
  return <section className={styles.page}>
    <header className={styles.heading}><div><h1>운영 홈</h1><p>현재 현황 · 한국 시각</p></div><div className={styles.headingActions}>
      <div className={styles.period} role="group" aria-label="변화 지표 기간">{[7, 30].map(value => <button key={value} type="button" aria-pressed={days === value} onClick={() => setParams(current => { const next = new URLSearchParams(current); next.set('days', String(value)); return next })}>{value}일</button>)}</div>
      {info('period', '기간 적용 기준')}<button type="button" className={styles.info} aria-label="현황 새로고침" onClick={() => setRevision(value => value + 1)}><OperationsIcon name="refresh" /></button>
    </div></header>
    <div className={styles.grid}>
      <section className={`${styles.panel} ${styles.complexes}`} aria-label="단지">
        <div className={styles.panelTop}><div className={styles.panelTitle}><OperationsIcon name="building" /><h2>단지</h2>{info('complex', '단지 집계 기준')}</div><Link to="/admin/complexes">관리 ↗</Link></div>
        <div className={styles.panelBody}>
          <div className={styles.stats}><OperationsMetric title="전체 단지" value={complex.data?.total} query={complex} href="/admin/complexes" primary />
            <OperationsMetric title="모집중" value={complex.data?.recruiting} query={complex} href="/admin/complexes" blue pendingFilter /></div>
          <OperationsStatus query={complex} />
          <div className={styles.compactStats}><Link to="/admin/complexes" title="좌표 미확인 필터 추가 예정 · 기존 단지 관리로 이동">좌표 미확인 <strong>{operationCount(complex.data?.missingCoordinates, complex)}</strong></Link>
            <Link to="/admin/complexes?review=true"><OperationsIcon name="refresh" />재검토 <strong>{operationCount(complex.data?.reviewRequired, complex)}</strong></Link></div>
          <div className={styles.agencySection}><h3>기관별 단지</h3><div className={styles.agencyBar} aria-hidden="true">{(['LH', 'SH', 'GH'] as const).map(agency => <span key={agency} data-agency={agency} style={{ flex: complex.data?.agencies[agency] ?? 0 }} />)}</div>
            <div className={styles.agencyCounts}>{(['LH', 'SH', 'GH'] as const).map(agency => <Link key={agency} to={`/admin/complexes?provider=${agency}`}><span data-agency={agency}>{agency}</span>{' '}<strong>{operationCount(complex.data?.agencies[agency], complex)}</strong></Link>)}</div></div>
          <CheckedAt value={complex.data?.asOf ?? null} />
        </div>
      </section>
      <section className={styles.panel} aria-label="공고">
        <div className={styles.panelTop}><div className={styles.panelTitle}><OperationsIcon name="document" /><h2>공고</h2>{info('announcement', '공고 집계 기준')}</div><Link to="/admin/announcements">관리 ↗</Link></div>
        <div className={styles.panelBody}>
          <div className={`${styles.stats} ${styles.announcementStats}`}>{([['BEFORE_APPLICATION', '공고중'], ['APPLYING', '접수중'], ['CLOSED', '접수마감']] as const).map(([status, label]) => <OperationsMetric key={status} title={label} value={announcement.data?.statuses[status]} query={announcement} href="/admin/announcements" blue={status === 'APPLYING'} pendingFilter />)}</div>
          <div className={styles.otherStatuses}>{[['CONDITIONAL', '조건부 접수'], ['CANCELLED', '공고취소'], ['UNKNOWN', '일정 미정']].map(([status, label]) => <Link key={status} to="/admin/announcements" title="접수 상태 필터 추가 예정 · 기존 공고 관리로 이동">{label} <strong>{operationCount(announcement.data?.statuses[status], announcement)}</strong></Link>)}</div>
          <div className={styles.review}><div className={styles.reviewHead}><Link to="/admin/announcements" title="확인 필요 통합 필터 추가 예정"><OperationsIcon name="alert" />확인 필요 <strong>{operationCount(announcement.data?.attention, announcement)}</strong> ↗</Link>{info('review', '확인 필요 집계 기준')}</div>
            <div className={styles.reviewReasons}>{[['SCHEDULE_MISSING', '일정 누락'], ['SUPPLY_MISSING', '공급정보 누락'], ['UNCONFIRMED', '미확정']].map(([reason, label]) => <Link key={reason} to={reason === 'UNCONFIRMED' ? '/admin/announcements?unconfirmed=true' : '/admin/announcements'} title={reason === 'UNCONFIRMED' ? undefined : '사유 필터 추가 예정 · 기존 공고 관리로 이동'}>{label} <strong>{operationCount(announcement.data?.reasons[reason], announcement)}</strong></Link>)}</div>
          </div><OperationsStatus query={announcement} />
          <table className={styles.sourceTable}><caption className={styles.srOnly}>기관별 공고 수집·정제 실행</caption><thead><tr><th>최근 수집</th><th>마지막 성공</th><th>최근 실행</th></tr></thead><tbody>{(['LH', 'SH', 'GH'] as const).map(agency => {
            const source = sources.data?.sources.find(item => item.agency === agency)
            return <tr key={agency}><td><Link data-agency={agency} to="/admin/ingest/announcements">{agency}</Link></td><td><Link to="/admin/ingest/announcements">{source ? source.lastSuccessAt ? operationTime(source.lastSuccessAt) : '성공 이력 없음' : sources.pending ? '조회 중' : '조회 실패'}</Link></td><td><Link className={styles.sourceResult} data-status={source?.latestStatus} to="/admin/ingest/announcements">{source ? source.latestStartedAt ? <><time dateTime={source.latestStartedAt}>{operationTime(source.latestStartedAt)}</time><span>{source.latestStatus ? pipelineStatusLabels[source.latestStatus] : '이력 없음'}</span></> : '이력 없음' : sources.pending ? '조회 중' : '조회 실패'}</Link></td></tr>
          })}</tbody></table><OperationsStatus query={sources} /><CheckedAt value={announcement.data?.asOf ?? null} />
        </div>
      </section>
      <section className={styles.panel} aria-label="유저">
        <div className={styles.panelTop}><div className={styles.panelTitle}><OperationsIcon name="users" /><h2>유저</h2>{info('users', '유저 집계 기준')}</div><Link to="/admin/users">관리 ↗</Link></div>
        <div className={styles.panelBody}><div className={styles.stats}><OperationsMetric title="전체 회원" value={users.data?.total} query={users} href="/admin/users" primary /><div><OperationsMetric title="신규 가입" value={users.data?.newUsers} query={users} href="/admin/users" pendingFilter />
          <p className={styles.change}>{users.data ? comparison(users.data.newUsers, users.data.previousNewUsers) : '—'} <span>이전 {days}일 대비</span></p></div></div>
          <OperationsStatus query={users} /><div className={styles.trendHeading}><Link to="/admin/users" title="가입 기간 필터 추가 예정">신규 가입 추이 ↗</Link><span>최근 {days}일</span></div>
          {users.data ? <OperationsTrend points={users.data.trend} label="가입" /> : <p className={styles.chartEmpty}>{users.pending ? '조회 중' : '조회 실패'}</p>}
          <Link className={styles.feedbackRow} to="/admin/feedback?status=OPEN"><OperationsIcon name="message" />새 사용자 의견 <strong>{operationCount(feedback.data?.totalElements, feedback)}</strong><span aria-hidden="true">→</span></Link><OperationsStatus query={feedback} /><CheckedAt value={users.data?.asOf ?? null} />
        </div>
      </section>
      <OperationsNotifications revision={revision} days={days} onHelp={openHelp} />
    </div>
    <div className={styles.footer}><button type="button" onClick={() => openHelp('links')}>연결 기준 · 추가 예정 필터 ⓘ</button></div>
    <dialog ref={dialog} className={styles.helpDialog} aria-labelledby={helpId}><header><h2 id={helpId}>{helpText[help][0]}</h2><button type="button" aria-label="도움말 닫기" onClick={() => dialog.current?.close()}>×</button></header><p>{helpText[help][1]}</p></dialog>
  </section>
}
