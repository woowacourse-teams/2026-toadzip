import { Component, type ReactNode } from 'react'
import styles from './AnnouncementAttachments.module.css'

export class PdfPreviewBoundary extends Component<{ readonly children: ReactNode; readonly label?: string }, { readonly failed: boolean }> {
  state = { failed: false }

  static getDerivedStateFromError() {
    return { failed: true }
  }

  render() {
    if (this.state.failed) {
      return <p className={styles.message} role="alert">{this.props.label ?? 'PDF'} 뷰어를 불러오지 못했습니다. 다운로드해서 확인해 주세요.</p>
    }
    return this.props.children
  }
}
