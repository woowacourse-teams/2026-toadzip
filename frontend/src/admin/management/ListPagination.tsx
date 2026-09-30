import { useId, useRef } from 'react'

type Props = { page: number; totalPages: number; onMove: (page: number) => void }

export function ListPagination({ page, totalPages, onMove }: Props) {
  const dialog = useRef<HTMLDialogElement>(null)
  const destination = useRef<HTMLInputElement>(null)
  const headingId = useId()
  const empty = totalPages === 0
  const start = Math.floor(page / 15) * 15
  const pages = Array.from({ length: Math.max(0, Math.min(15, totalPages - start)) }, (_, index) => start + index)
  return <nav className="admin-pagination admin-list-pagination" aria-label="목록 페이지">
    <div className="admin-page-buttons">
      <button type="button" disabled={empty || page === 0} onClick={() => onMove(0)} aria-label="처음" title="첫 페이지">⏮</button>
      <button type="button" disabled={empty || page === 0} onClick={() => onMove(page - 1)} aria-label="이전" title="이전 페이지">◀</button>
      {pages.map(number => <button type="button" key={number} aria-label={`${number + 1}페이지`}
        aria-current={page === number ? 'page' : undefined} disabled={page === number}
        onClick={() => onMove(number)}>{number + 1}</button>)}
      <button type="button" disabled={empty || page >= totalPages - 1} onClick={() => onMove(page + 1)} aria-label="다음" title="다음 페이지">▶</button>
      <button type="button" disabled={empty || page >= totalPages - 1} onClick={() => onMove(totalPages - 1)} aria-label="마지막" title="마지막 페이지">⏭</button>
    </div>
    <span className="admin-page-count">{empty ? 0 : page + 1} / {totalPages} 페이지</span>
    <button type="button" className="admin-page-open" disabled={empty} aria-haspopup="dialog" onClick={() => {
      if (destination.current) destination.current.value = String(page + 1)
      destination.current?.setCustomValidity('')
      dialog.current?.showModal()
      destination.current?.focus()
      destination.current?.select()
    }}>페이지 이동 <span aria-hidden="true">➜</span></button>
    <dialog ref={dialog} className="admin-page-dialog" aria-labelledby={headingId}>
      <header><h2 id={headingId}>페이지 이동</h2><button type="button" aria-label="페이지 이동 닫기" onClick={() => dialog.current?.close()}>×</button></header>
      <div className="admin-page-dialog-body"><p>이동할 페이지 번호를 입력하세요.</p>
    <form key={`${page}-${totalPages}`} className="admin-page-jump" onSubmit={event => {
      event.preventDefault()
      const input = event.currentTarget.elements.namedItem('destination')
      if (!(input instanceof HTMLInputElement)) return
      const number = input.valueAsNumber
      if (!Number.isInteger(number) || number < 1 || number > totalPages) {
        input.setCustomValidity(`1부터 ${totalPages} 사이의 페이지 번호를 입력하세요.`)
        input.reportValidity()
        return
      }
      dialog.current?.close()
      if (number - 1 !== page) onMove(number - 1)
    }}>
      <label>페이지<input ref={destination} aria-label="페이지 바로가기" name="destination" type="number" inputMode="numeric" min={1}
        max={totalPages || 1} step={1} required disabled={empty} defaultValue={empty ? '' : page + 1}
        onInput={event => event.currentTarget.setCustomValidity('')} /></label>
      <span>/ {totalPages}</span>
      <button type="submit" disabled={empty}>이동</button>
    </form></div>
    </dialog>
  </nav>
}
