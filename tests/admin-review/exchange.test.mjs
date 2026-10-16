import assert from 'node:assert/strict'
import { test } from 'node:test'
import { randomUUID } from 'node:crypto'
import { mkdir, mkdtemp, readFile, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { join } from 'node:path'
import { parseRequest, processRequest } from '../../scripts/admin-review-exchange.mjs'

const root = fileURLToPath(new URL('../../.local/admin-review-exchange-tests/', import.meta.url))
async function fixture() {
  await mkdir(root, { recursive: true })
  const directory = await mkdtemp(join(root, 'case-')); const id = randomUUID()
  const packet = { targetKey: 'complexes:7:basic', version: 2, sourceUrl: 'https://example.com/official',
    document: { sha256: 'a'.repeat(64), pages: [{ page: 1, text: '전체 120세대' }] },
    allowedFields: [{ field: 'totalHouseholdCount', unit: '세대', scope: '단지 전체' }] }
  const fileName = `review-${id}.request.json`
  await writeFile(join(directory, fileName), JSON.stringify({ schemaVersion: 'document-exchange/v1', requestId: id, packet }))
  return { directory, id, packet, fileName }
}
test('요청 파일과 같은 식별자·대상·해시의 결과를 발행하고 중복 실행하지 않는다', async () => {
  const { directory, id, packet, fileName } = await fixture(); let calls = 0
  const agent = async () => { calls++; return { schemaVersion: 'document-review/v1', targetKey: packet.targetKey,
    version: packet.version, sha256: packet.document.sha256, proposals: [], unresolved: [] } }
  assert.equal(await processRequest(directory, fileName, agent), 'completed')
  assert.equal(await processRequest(directory, fileName, agent), 'completed')
  assert.equal(calls, 1)
  const result = JSON.parse(await readFile(join(directory, `review-${id}.result.json`), 'utf8'))
  assert.equal(result.requestId, id); assert.equal(result.result.targetKey, packet.targetKey)
})
test('실행 실패와 다른 대상 결과는 오류 파일로 남기고 재실행하지 않는다', async () => {
  const { directory, id, fileName } = await fixture()
  assert.equal(await processRequest(directory, fileName, async () => ({ targetKey: 'wrong' })), 'failed')
  assert.match(JSON.parse(await readFile(join(directory, `review-${id}.error.json`), 'utf8')).message, /대상/)
  assert.equal(await processRequest(directory, fileName, async () => { throw new Error('실행되면 안 됨') }), 'failed')
})
test('경로 이탈·다른 요청·과도한 자료를 거부한다', async () => {
  const { directory, id, packet, fileName } = await fixture()
  await assert.rejects(processRequest(directory, '../outside.request.json'), /파일명/)
  assert.throws(() => parseRequest(JSON.stringify({ schemaVersion: 'document-exchange/v1', requestId: 'other', packet }), fileName))
  assert.throws(() => parseRequest('null', fileName))
  assert.throws(() => parseRequest(JSON.stringify({ schemaVersion: 'document-exchange/v1', requestId: id,
    packet: { ...packet, document: { ...packet.document, pages: Array.from({ length: 51 }, () => ({ page: 1, text: '' })) } } }), fileName))
})
