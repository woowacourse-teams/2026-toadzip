import assert from 'node:assert/strict'
import { test } from 'node:test'
import { randomUUID } from 'node:crypto'
import { mkdir, mkdtemp, readFile, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { join, resolve } from 'node:path'
import { parseRequest, processRequest, scanRequests, execute } from '../../scripts/admin-review-exchange.mjs'

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

test('작성 중인 요청이 완성되면 다시 감지하고 완료한 요청은 한 번만 처리한다', async () => {
  const { directory, fileName, packet, id } = await fixture()
  const content = await readFile(join(directory, fileName), 'utf8')
  const state = { finished: new Set(), warned: new Set() }; let calls = 0
  const agent = async () => { calls++; return { schemaVersion: 'document-review/v1', targetKey: packet.targetKey,
    version: packet.version, sha256: packet.document.sha256, proposals: [], unresolved: [] } }
  await writeFile(join(directory, fileName), '')
  await scanRequests(directory, state, agent)
  await writeFile(join(directory, fileName), '{"schemaVersion":')
  await scanRequests(directory, state, agent)
  assert.equal(calls, 0)
  await writeFile(join(directory, fileName), content)
  await scanRequests(directory, state, agent)
  await scanRequests(directory, state, agent)
  assert.equal(calls, 1)
  assert.equal(JSON.parse(await readFile(join(directory, `review-${id}.result.json`), 'utf8')).requestId, id)
})

test('실제 에이전트를 실행하기 전에 같은 요청의 처리 상태를 발행한다', async () => {
  const { directory, fileName, packet, id } = await fixture()
  await processRequest(directory, fileName, async () => {
    const status = JSON.parse(await readFile(join(directory, `review-${id}.status.json`), 'utf8'))
    assert.equal(status.requestId, id); assert.equal(status.workerId, 'worker-test'); assert.equal(status.state, 'processing')
    return { schemaVersion: 'document-review/v1', targetKey: packet.targetKey, version: packet.version,
      sha256: packet.document.sha256, proposals: [], unresolved: [] }
  }, 'worker-test')
})

test('취소한 대기 요청은 실행하지 않고 취소 결과를 남긴다', async () => {
  const { directory, id, fileName } = await fixture()
  await writeFile(join(directory, `review-${id}.cancel.json`), JSON.stringify({ schemaVersion: 'document-exchange/v1', requestId: id }))
  let calls = 0
  assert.equal(await processRequest(directory, fileName, async () => { calls++ }), 'failed')
  assert.equal(calls, 0)
  assert.equal(JSON.parse(await readFile(join(directory, `review-${id}.error.json`), 'utf8')).code, 'CANCELLED')
})

test('실행 중 취소는 자식 실행 종료를 기다린 뒤 결과 발행을 차단한다', async () => {
  const { directory, id, fileName } = await fixture()
  let terminated = false
  await processRequest(directory, fileName, async (_packet, runDirectory, signal) => {
    await writeFile(join(directory, `review-${id}.cancel.json`), JSON.stringify({ schemaVersion: 'document-exchange/v1', requestId: id }))
    try { await execute(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { cwd: runDirectory }, '', signal) }
    finally { terminated = true }
  })
  assert.equal(terminated, true)
  assert.equal(JSON.parse(await readFile(join(directory, `review-${id}.error.json`), 'utf8')).code, 'CANCELLED')
  await assert.rejects(readFile(join(directory, `review-${id}.result.json`)), { code: 'ENOENT' })
})

test('초기 설정은 폴더와 재실행 파일을 만들고 기존 시작 파일은 보존한다', async () => {
  const { directory } = await fixture()
  const script = resolve('scripts/admin-review-exchange.mjs')
  await execute(process.execPath, [script, directory, '--setup'], { cwd: directory })
  const launcher = join(directory, 'start-review-worker.ps1')
  assert.match(await readFile(launcher, 'utf8'), /--watch/)
  await writeFile(launcher, '# user-owned launcher')
  await execute(process.execPath, [script, directory, '--setup'], { cwd: directory })
  assert.equal(await readFile(launcher, 'utf8'), '# user-owned launcher')
})

test('처리 도구 종료 요청 이후 다음 대기 파일은 시작하지 않는다', async () => {
  const { directory, id, packet } = await fixture()
  const second = randomUUID()
  await writeFile(join(directory, `review-${second}.request.json`), JSON.stringify({ schemaVersion: 'document-exchange/v1', requestId: second, packet }))
  const controller = new AbortController(); let calls = 0
  await scanRequests(directory, { finished: new Set(), warned: new Set() }, async () => {
    calls++; controller.abort()
    return { schemaVersion: 'document-review/v1', targetKey: packet.targetKey, version: packet.version, sha256: packet.document.sha256, proposals: [], unresolved: [] }
  }, id, controller.signal)
  assert.equal(calls, 1)
})

test('취소는 중첩 자식 프로세스도 종료한다', { timeout: 10000 }, async () => {
  const { directory } = await fixture()
  const pidFile = join(directory, 'grandchild.pid')
  const controller = new AbortController()
  const code = `const {spawn}=require('node:child_process'); const child=spawn(process.execPath,['-e','setInterval(()=>{},1000)'],{stdio:'inherit'}); require('node:fs').writeFileSync(process.argv[1],String(child.pid)); setInterval(()=>{},1000)`
  const outcome = execute(process.execPath, ['-e', code, pidFile], { cwd: directory }, '', controller.signal).then(() => null, error => error)
  let pid
  try {
    for (let i = 0; i < 100 && !pid; i++) {
      try { pid = Number(await readFile(pidFile, 'utf8')) } catch { await new Promise(done => setTimeout(done, 50)) }
    }
    assert(pid, '중첩 자식이 시작돼야 종료를 검사할 수 있습니다.')
  } finally { controller.abort() }
  assert.match((await outcome).message, /취소/)
  assert.throws(() => process.kill(pid, 0), { code: 'ESRCH' })
})
