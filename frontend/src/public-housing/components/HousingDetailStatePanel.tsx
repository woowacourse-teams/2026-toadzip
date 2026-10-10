import { useEffect, useRef, type KeyboardEvent, type ReactNode } from 'react'
import { DetailCloseButton } from './DetailPrimitives.tsx'

export type DetailStatus = 'closed' | 'loading' | 'ready' | 'not-found' | 'error'

interface HousingDetailStatePanelProps {
  readonly kind: 'complex' | 'announcement'
  readonly id: string | null
  readonly status: DetailStatus
  readonly errorMessage: string | null
  readonly backButton?: ReactNode
  readonly onClose: () => void
  readonly onRetry: () => void
}

const DETAIL_LABELS = {
  complex: {
    name: '단지',
    loadingDescription: '선택한 단지의 기본 정보와 주택형을 확인하고 있습니다.',
    notFoundDescription: '삭제되었거나 아직 제공되지 않는 단지일 수 있습니다.',
    notFoundHeading: '단지를 찾을 수 없습니다.',
  },
  announcement: {
    name: '공고',
    loadingDescription: '접수 일정과 공급 단지 정보를 확인하고 있습니다.',
    notFoundDescription: '삭제되었거나 아직 제공되지 않는 공고일 수 있습니다.',
    notFoundHeading: '공고를 찾을 수 없습니다.',
  },
} as const

export function HousingDetailStatePanel({
  kind, id, status, errorMessage, backButton, onClose, onRetry,
}: HousingDetailStatePanelProps) {
  const panelRef = useRef<HTMLElement>(null)
  const labels = DETAIL_LABELS[kind]
  const heading = status === 'loading'
    ? `${labels.name} 상세를 불러오고 있습니다.`
    : status === 'not-found'
      ? labels.notFoundHeading
      : `${labels.name} 상세를 불러오지 못했습니다.`
  const description = status === 'loading'
    ? labels.loadingDescription
    : status === 'not-found'
      ? labels.notFoundDescription
      : errorMessage ?? '잠시 후 다시 시도해 주세요.'

  useEffect(() => {
    panelRef.current?.focus({ preventScroll: true })
  }, [id, status])

  function handleKeyDown(event: KeyboardEvent<HTMLElement>) {
    if (event.key !== 'Escape') return
    event.stopPropagation()
    onClose()
  }

  return (
    <aside ref={panelRef} className="housing-detail-state"
      aria-label={`${labels.name} 상세 정보`} tabIndex={-1} onKeyDown={handleKeyDown}>
      <header>
        {backButton}
        <div>
          <span>{labels.name} 상세 정보</span>
          <strong>{`${labels.name} ${id ?? ''}`.trim()}</strong>
        </div>
        <DetailCloseButton label={`${labels.name} 상세 닫기`} onClose={onClose} />
      </header>
      <div className="housing-detail-state__content" role={status === 'loading' ? 'status' : 'alert'}>
        <strong>{heading}</strong>
        <span>{description}</span>
        {status === 'error' && <button type="button" onClick={onRetry}>다시 시도</button>}
      </div>
    </aside>
  )
}
