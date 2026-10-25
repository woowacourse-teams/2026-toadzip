import { useEffect, useRef, useState } from 'react'
import { connectUnsavedChangesDialog } from './useUnsavedChanges'
import styles from './UnsavedChangesDialog.module.css'

/** 편집 중 다른 화면으로 이동할 때 묻는 확인 창. 관리자 레이아웃에 한 번만 둔다. */
export function UnsavedChangesDialog() {
  const dialog = useRef<HTMLDialogElement>(null)
  const [leave, setLeave] = useState<(() => void) | null>(null)
  useEffect(() => connectUnsavedChangesDialog(next => setLeave(() => next)), [])
  useEffect(() => { if (leave && !dialog.current?.open) dialog.current?.showModal() }, [leave])
  function close(confirmed: boolean) {
    dialog.current?.close()
    setLeave(null)
    if (confirmed) leave?.()
  }
  return <dialog ref={dialog} className={styles.dialog} aria-labelledby="unsaved-changes-title" onCancel={() => setLeave(null)}>
    <h2 id="unsaved-changes-title">저장하지 않은 변경사항이 있습니다</h2>
    <p>이동하면 작성 중인 내용이 저장되지 않습니다.</p>
    <div className={styles.actions}>
      <button type="button" autoFocus onClick={() => close(false)}>계속 수정</button>
      <button type="button" className="admin-danger" onClick={() => close(true)}>저장하지 않고 이동</button>
    </div>
  </dialog>
}
