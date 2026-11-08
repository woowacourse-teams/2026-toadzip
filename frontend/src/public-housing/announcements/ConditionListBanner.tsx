import { useState, type ReactNode } from 'react'
import { useConditions } from '../../user/conditions/ConditionsContext'
import { ChevronRightIcon, CloseIcon, StatusMark } from '../../user/conditions/conditionIcons'
import { bannerHiddenThisVisit, hideBannerThisVisit } from '../../user/conditions/conditionStorage'
import type { ListConditionResult } from '../../user/conditions/conditionsModel'
import { useMissingConditionFields } from './useConditionListResults'
import { HouseCharacterIcon } from '../components/HouseCharacterIcon'
import type { AnnouncementListItem } from '../model/publicHousing'
import styles from './ConditionListBanner.module.css'

export function ConditionListBanner({ items, results, loading = false, filtering, onToggleFilter }: {
  readonly items: readonly AnnouncementListItem[]
  readonly results: ReadonlyMap<string, ListConditionResult>
  /** 불러온 공고를 내 조건과 비교하는 중 */
  readonly loading?: boolean
  readonly filtering: boolean
  readonly onToggleFilter: () => void
}) {
  const { mode, hasProfile, memberDraft, openInput, openNudge, notify } = useConditions()
  const missing = useMissingConditionFields()
  const [hidden, setHidden] = useState(bannerHiddenThisVisit)
  if (mode === 'disabled' || mode === 'loading' || mode === 'error') return null
  const compared = mode === 'member' && hasProfile
  if (compared && hidden) return null

  const fill = () => missing.length
    ? openInput({ mode: 'partial', fields: missing, origin: 'list' }, {})
    : openInput({ mode: 'full', step: 1, origin: 'list' }, {})
  const shell = (icon: ReactNode, title: string, sub: ReactNode, action: ReactNode) => <div className={styles.banner} data-closable={compared || undefined}>
    <span className={styles.icon}>{icon}</span>
    <div className={styles.text}><b>{title}</b>{sub}</div>
    {action}
    {compared && <button type="button" className={styles.close} aria-label="안내 닫기" onClick={() => {
      hideBannerThisVisit()
      setHidden(true)
      notify('이번 방문 동안 안내를 숨겼어요')
    }}><CloseIcon size={16} /></button>}
  </div>

  if (!compared) {
    const draft = mode === 'member' ? memberDraft : null
    if (draft) {
      return shell(<HouseCharacterIcon size={22} />, '작성하던 내 조건이 있어요',
        <span className={styles.sub}>{draft.step > 3 ? '입력 완료 · 저장만 남았어요' : `${draft.step}단계까지 입력했어요`}</span>,
        <button type="button" className={styles.action} onClick={() => openInput({ mode: 'full', step: 1, origin: 'list' }, { resume: true })}>이어서</button>)
    }
    return shell(<HouseCharacterIcon size={22} />, '나에게 맞는 공고 찾기', <span className={styles.sub}>3분 입력하면 조건을 비교해 드려요</span>,
      <button type="button" className={styles.action} onClick={() => openNudge('list')}>시작</button>)
  }

  if (loading) {
    return shell(<HouseCharacterIcon size={22} />, '내 조건과 비교하고 있어요', <span className={styles.sub}>불러온 공고에서 지원 가능 공고를 찾고 있어요</span>, null)
  }
  const matched = items.filter((item) => results.get(item.announcementId)?.generalMatched).length
  if (matched > 0) {
    const applying = items.filter((item) => item.applicationStatus === 'APPLYING').length
    return shell(<StatusMark status="ok" size={20} className={styles.mark} />, `지원 가능 공고 ${matched}건`,
      missing.length
        ? <button type="button" className={styles.subLink} onClick={fill}>미입력 {missing.length}개를 채우면 더 찾을 수 있어요<ChevronRightIcon size={14} /></button>
        : <span className={styles.sub}>접수 중 공고 {applying}건 · 내 조건 기준</span>,
      <button type="button" className={styles.action} data-variant={filtering ? 'secondary' : undefined} onClick={onToggleFilter}>{filtering ? '전체 보기' : `${matched}건 보기`}</button>)
  }
  return shell(<StatusMark status="tri" size={20} className={styles.mark} />, '지원 가능 공고 0건',
    <span className={styles.sub}>{missing.length ? `미입력 ${missing.length}개를 채우면 더 찾을 수 있어요` : '바뀐 정보가 있다면 고쳐 보세요'}</span>,
    <button type="button" className={styles.action} onClick={fill}>{missing.length ? '채우기' : '수정'}</button>)
}
