type Props = { page: number; totalPages: number; onMove: (page: number) => void }

export function ListPagination({ page, totalPages, onMove }: Props) {
  const empty = totalPages === 0
  const start = Math.max(0, Math.min(page - 2, totalPages - 5))
  const pages = Array.from({ length: Math.min(5, totalPages) }, (_, index) => start + index)
  return <nav className="admin-pagination admin-list-pagination" aria-label="목록 페이지">
    <div className="admin-page-buttons">
      <button type="button" disabled={empty || page === 0} onClick={() => onMove(0)}>처음</button>
      <button type="button" disabled={empty || page === 0} onClick={() => onMove(page - 1)}>이전</button>
      {pages.map(number => <button type="button" key={number} aria-label={`${number + 1}페이지`}
        aria-current={page === number ? 'page' : undefined} disabled={page === number}
        onClick={() => onMove(number)}>{number + 1}</button>)}
      <button type="button" disabled={empty || page >= totalPages - 1} onClick={() => onMove(page + 1)}>다음</button>
      <button type="button" disabled={empty || page >= totalPages - 1} onClick={() => onMove(totalPages - 1)}>마지막</button>
    </div>
    <span>{empty ? 0 : page + 1} / {totalPages} 페이지</span>
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
      if (number - 1 !== page) onMove(number - 1)
    }}>
      <label>페이지 바로가기<input name="destination" type="number" inputMode="numeric" min={1}
        max={totalPages || 1} step={1} required disabled={empty} defaultValue={empty ? '' : page + 1}
        onInput={event => event.currentTarget.setCustomValidity('')} /></label>
      <button type="submit" disabled={empty}>이동</button>
    </form>
  </nav>
}
