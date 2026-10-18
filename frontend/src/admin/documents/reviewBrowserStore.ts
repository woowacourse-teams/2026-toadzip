import { isRecord } from '../management/managementContract'
import type { ExchangeDirectory } from './folderExchange'

export type RecoveryInput = { id: string; context: string; json: string; note: string; updatedAt: string }
async function database(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open('toadzip-admin-review-recovery', 1)
    request.onupgradeneeded = () => { request.result.createObjectStore('inputs', { keyPath: 'id' }); request.result.createObjectStore('connection') }
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(new Error('브라우저 복구 보관함을 열지 못했습니다. 저장 권한과 공간을 확인하세요.'))
    request.onblocked = () => reject(new Error('다른 검토 탭을 닫은 뒤 복구 보관함을 다시 열어주세요.'))
  })
}
function valid(value: unknown): value is RecoveryInput {
  return isRecord(value) && typeof value.id === 'string' && value.id.length <= 100 && typeof value.context === 'string' && value.context.length <= 500
    && typeof value.json === 'string' && value.json.length <= 1000000 && typeof value.note === 'string' && value.note.length <= 2000
    && typeof value.updatedAt === 'string' && Number.isFinite(Date.parse(value.updatedAt))
}
export async function readRecoveryInputs(context: string): Promise<RecoveryInput[]> {
  const db = await database()
  try {
    return await new Promise((resolve, reject) => {
      const request = db.transaction('inputs').objectStore('inputs').getAll()
      request.onsuccess = () => {
        const values: unknown[] = request.result
        if (!values.every(valid)) { reject(new Error('복구 기록 형식이 잘못됐습니다. 기존 입력을 별도로 보관하세요.')); return }
        resolve(values.filter(item => item.context === context).sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)))
      }
      request.onerror = () => reject(new Error('복구 기록을 읽지 못했습니다.'))
    })
  } finally { db.close() }
}
export async function saveRecoveryInput(input: Omit<RecoveryInput, 'id' | 'updatedAt'>): Promise<void> {
  const value = { ...input, id: crypto.randomUUID(), updatedAt: new Date().toISOString() }
  if (!valid(value)) throw new Error('복구 기록의 수정안·메모가 보관 한도를 초과했습니다.')
  const db = await database()
  try {
    await new Promise<void>((resolve, reject) => {
      const transaction = db.transaction('inputs', 'readwrite'); const store = transaction.objectStore('inputs')
      const request = store.getAll()
      request.onsuccess = () => {
        const rows: unknown[] = request.result
        if (!rows.every(valid)) { transaction.abort(); return }
        const same = rows.filter(row => row.context === input.context).sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
        if (same[0]?.json === input.json && same[0]?.note === input.note) return
        value.updatedAt = new Date(Math.max(Date.now(), ...same.map(row => Date.parse(row.updatedAt) + 1))).toISOString()
        const retained = new Set(same.slice(0, 9).map(row => row.id))
        const remaining = rows.filter(row => row.context !== input.context || retained.has(row.id))
        const bytes = (row: RecoveryInput) => new TextEncoder().encode(row.json + row.note).length
        if (remaining.length >= 50 || remaining.reduce((sum, row) => sum + bytes(row), bytes(value)) > 5000000) { transaction.abort(); return }
        same.filter(row => !retained.has(row.id)).forEach(row => store.delete(row.id))
        store.put(value)
      }
      transaction.oncomplete = () => resolve()
      transaction.onerror = transaction.onabort = () => reject(new Error('복구 기록을 보관하지 못했습니다. 최대 50개·텍스트 5MB 또는 브라우저 저장 공간을 확인하세요. 현재 입력은 유지됩니다.'))
    })
  } finally { db.close() }
}
export async function saveExchangeConnection(directory: ExchangeDirectory): Promise<void> {
  const db = await database()
  try {
    await new Promise<void>((resolve, reject) => {
      const transaction = db.transaction('connection', 'readwrite')
      transaction.objectStore('connection').put(directory, 'last')
      transaction.oncomplete = () => resolve()
      transaction.onerror = transaction.onabort = () => reject(new Error('연동 폴더를 기억하지 못했습니다. 다음에 다시 선택하세요.'))
    })
  } finally { db.close() }
}
export async function removeRecoveryInput(id: string, context: string): Promise<void> {
  const db = await database()
  try {
    await new Promise<void>((resolve, reject) => {
      const transaction = db.transaction('inputs', 'readwrite'); const store = transaction.objectStore('inputs')
      const request = store.get(id)
      request.onsuccess = () => {
        const value: unknown = request.result
        if (!valid(value) || value.context !== context) { transaction.abort(); return }
        store.delete(id)
      }
      transaction.oncomplete = () => resolve()
      transaction.onerror = transaction.onabort = () => reject(new Error('선택한 복구 기록을 삭제하지 못했습니다. 다시 조회하세요.'))
    })
  } finally { db.close() }
}
export async function readExchangeConnection(): Promise<ExchangeDirectory | null> {
  const db = await database()
  try {
    return await new Promise((resolve, reject) => {
      const request = db.transaction('connection').objectStore('connection').get('last')
      request.onsuccess = () => {
        const value: unknown = request.result
        resolve(isRecord(value) && typeof value.name === 'string' && typeof value.getFileHandle === 'function' ? value as ExchangeDirectory : null)
      }
      request.onerror = () => reject(new Error('이전 연동 폴더를 읽지 못했습니다. 다시 선택하세요.'))
    })
  } finally { db.close() }
}
