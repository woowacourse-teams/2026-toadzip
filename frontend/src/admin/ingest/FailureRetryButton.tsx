import { useConfirm } from '../shared/ConfirmDialog'

export function FailureRetryButton({label, disabled, onRetry}: {
  label: string; disabled: boolean; onRetry: () => void
}) {
  const [confirm, confirmDialog] = useConfirm()
  async function retry() {
    if (!(await confirm({
      title: `저장된 ${label} 원천 전체를 다시 정제합니까?`,
      body: `이 목록에 없는 ${label}도 함께 처리합니다. 외부 원천을 새로 수집하지는 않으며, 끝나면 목록을 다시 불러옵니다.`,
      confirmLabel: `전체 ${label} 정제 실행`,
    }))) return
    onRetry()
  }
  return <><button type="button" disabled={disabled} onClick={() => void retry()}>{label} 정제 다시 실행</button>{confirmDialog}</>
}
