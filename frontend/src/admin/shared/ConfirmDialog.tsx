import { useCallback, useEffect, useId, useRef, useState, type ReactNode } from 'react'

export type ConfirmRequest = {
  /** 무엇을 하려는지 묻는 한 문장. */
  title: string
  /** 실행하면 달라지는 것(대상 수, 공개 여부, 되돌릴 수 있는지). */
  body?: ReactNode
  /** 실행 버튼 글자. '확인' 대신 실제 동작 이름을 쓴다. */
  confirmLabel: string
  cancelLabel?: string
  /** danger는 지우거나 되돌릴 수 없는 동작에만 쓴다. */
  tone?: 'primary' | 'danger'
}

/**
 * 되돌리기 어렵거나 범위가 큰 실행 앞에서 묻는 확인 창. 관리자 화면의 실행 확인은 모두 이 창 하나로 한다.
 * `const [confirm, confirmDialog] = useConfirm()`로 받고, `confirmDialog`를 화면에 한 번 그린 뒤
 * `if (!(await confirm({ ... }))) return`으로 쓴다.
 */
export function useConfirm(): [(request: ConfirmRequest) => Promise<boolean>, ReactNode] {
  const dialog = useRef<HTMLDialogElement>(null)
  const settle = useRef<((confirmed: boolean) => void) | null>(null)
  const [request, setRequest] = useState<ConfirmRequest | null>(null)
  const titleId = useId()

  const confirm = useCallback((next: ConfirmRequest) => new Promise<boolean>(resolve => {
    settle.current?.(false)
    settle.current = resolve
    setRequest(next)
  }), [])

  useEffect(() => { if (request && !dialog.current?.open) dialog.current?.showModal() }, [request])
  // 화면을 떠나면 기다리던 실행은 취소로 끝낸다.
  useEffect(() => () => { settle.current?.(false); settle.current = null }, [])

  function close(confirmed: boolean) {
    if (dialog.current?.open) dialog.current.close()
    setRequest(null)
    settle.current?.(confirmed)
    settle.current = null
  }

  const element = request ? <dialog ref={dialog} className="admin-dialog" aria-labelledby={titleId}
    onCancel={event => { event.preventDefault(); close(false) }}>
    <h2 id={titleId}>{request.title}</h2>
    {request.body ? <div className="admin-dialog-body">{request.body}</div> : null}
    <div className="admin-dialog-actions">
      <button type="button" autoFocus onClick={() => close(false)}>{request.cancelLabel ?? '취소'}</button>
      <button type="button" className={request.tone === 'danger' ? 'admin-danger' : 'admin-primary'} onClick={() => close(true)}>{request.confirmLabel}</button>
    </div>
  </dialog> : null

  return [confirm, element]
}
