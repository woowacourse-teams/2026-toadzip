import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react'
import type { JsonWorkspace } from '../announcement-json/api'
import { Button } from '../../design-system/components/Button'
import chevron from '../../public-housing/filters/filter-select-chevron.svg'
import styles from './PdfCorrection.module.css'

type File = NonNullable<JsonWorkspace['detail']['attachments']>[number]

export function PdfFilePicker({ files, value, onChange }: { files: File[]; value: string; onChange: (id: string) => void }) {
  const [open, setOpen] = useState(false)
  const selected = Math.max(0, files.findIndex(file => String(file.attachmentId) === value))
  const [active, setActive] = useState(selected)
  const root = useRef<HTMLDivElement>(null), button = useRef<HTMLButtonElement>(null)
  const id = useId(), listId = `${id}-files`

  useEffect(() => {
    if (!open) return
    function outside(event: MouseEvent) { if (!root.current?.contains(event.target as Node)) setOpen(false) }
    document.addEventListener('mousedown', outside)
    return () => document.removeEventListener('mousedown', outside)
  }, [open])
  useEffect(() => {
    if (open) document.getElementById(`${id}-file-${active}`)?.scrollIntoView?.({ block: 'nearest' })
  }, [open, active, id])

  function choose(index: number) {
    onChange(String(files[index].attachmentId)); setOpen(false); button.current?.focus({ preventScroll: true })
  }
  function keyDown(event: KeyboardEvent<HTMLButtonElement>) {
    if (event.key === 'Tab') { setOpen(false); return }
    if (event.key === 'Escape') { if (open) { event.preventDefault(); event.stopPropagation(); setOpen(false) } return }
    if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
      event.preventDefault()
      setActive(event.key === 'Home' ? 0 : event.key === 'End' ? files.length - 1 : !open ? selected : Math.max(0, Math.min(files.length - 1, active + (event.key === 'ArrowDown' ? 1 : -1))))
      setOpen(true)
    } else if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      if (open) choose(active)
      else { setActive(selected); setOpen(true) }
    }
  }

  return <div ref={root} className={styles.filePicker} onBlur={event => { if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false) }}>
    <span id={`${id}-label`} className={styles.filePickerLabel}>공고문 PDF</span>
    <Button data-design-system ref={button} role="combobox" aria-labelledby={`${id}-label`} aria-describedby={`${id}-value`} aria-haspopup="listbox" aria-expanded={open} aria-controls={listId} aria-activedescendant={open ? `${id}-file-${active}` : undefined}
      className={styles.filePickerButton} title={files[selected]?.fileName} onKeyDown={keyDown} onClick={() => { setActive(selected); setOpen(value => !value) }}>
      <span id={`${id}-value`}>{files[selected]?.fileName}</span><img src={chevron} alt="" />
    </Button>
    {open && <ul id={listId} role="listbox" aria-labelledby={`${id}-label`} className={styles.fileOptions}>
      {files.map((file, index) => <li id={`${id}-file-${index}`} key={file.attachmentId} role="option" aria-selected={index === selected} data-active={index === active} title={file.fileName}
        onMouseDown={event => event.preventDefault()} onMouseMove={() => setActive(index)} onClick={() => choose(index)}>{file.fileName}</li>)}
    </ul>}
  </div>
}
