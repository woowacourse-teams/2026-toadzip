import { useState } from 'react'
import { ComplexPicker } from '../management/ComplexPicker'
import { detail, type Values } from '../management/api'
export function AddressPicker({ onSelect }: { onSelect: (address: Values) => void }) {
  const [error,setError] = useState('')
  const [busy,setBusy] = useState(false)
  return <details className="address-picker"><summary>등록된 단지 주소 검색으로 채우기</summary><p>기존 단지의 주소·행정구역·좌표를 가져옵니다. 새 주소는 아래에 직접 입력해 주세요.</p>
    <ComplexPicker disabled={busy} onSelect={item => {
      setBusy(true);setError('')
      void detail('complexes',String(item.id)).then(value => {
        const address = value.data.address
        if(address && typeof address === 'object' && !Array.isArray(address)) onSelect(address)
      }).catch(cause => setError(cause instanceof Error ? cause.message : '주소를 불러오지 못했습니다.')).finally(() => setBusy(false))
    }} />{error ? <p role="alert">{error}</p> : null}</details>
}
