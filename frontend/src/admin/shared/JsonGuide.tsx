import { useState, type ReactNode } from 'react'
import styles from './JsonGuide.module.css'

export function JsonGuide({ title, example, prompt, children }: { title: string; example: string; prompt: string; children: ReactNode }) {
  const [notice, setNotice] = useState('')
  const [error, setError] = useState('')
  async function copy(text: string, label: string) {
    setNotice(''); setError('')
    try {
      await navigator.clipboard.writeText(text)
      setNotice(`${label} 복사를 완료했습니다.`)
    } catch { setError('복사하지 못했습니다. 아래 내용을 직접 선택해 복사하세요.') }
  }
  return <details className={styles.guide}><summary>{title}</summary><div className={styles.body}>
    {children}
    <label>JSON 형식 예제<textarea aria-label={`${title} JSON 예제`} readOnly rows={14} value={example} spellCheck={false} /></label>
    <button type="button" onClick={() => void copy(example, 'JSON 예제')}>JSON 예제 복사</button>
    <label>Codex에 요청할 내용<textarea aria-label={`${title} Codex 요청문`} readOnly rows={7} value={prompt} /></label>
    <button type="button" onClick={() => void copy(prompt, 'Codex 요청문')}>Codex 요청문 복사</button>
    {notice && <p role="status">{notice}</p>}{error && <p role="alert">{error}</p>}
  </div></details>
}
