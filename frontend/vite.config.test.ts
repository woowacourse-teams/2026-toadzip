import { copyFile, mkdir, mkdtemp, realpath, rm, symlink, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { build, createServer, type ViteDevServer } from 'vite'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

let rootDirectory: string
let frontendDirectory: string
let server: ViteDevServer | undefined
const posthogKeys = [
  'VITE_POSTHOG_KEY', 'VITE_POSTHOG_HOST',
  'VITE_ANALYTICS_ENV', 'VITE_POSTHOG_LOCAL_ENABLED',
]

beforeEach(async () => {
  for (const key of [...posthogKeys, 'VITE_USER_NODE_ENV', 'BROWSER', 'BROWSER_ARGS']) {
    vi.stubEnv(key, undefined)
  }
  rootDirectory = await realpath(await mkdtemp(join(tmpdir(), 'posthog-vite-env-')))
  frontendDirectory = join(rootDirectory, 'frontend')
  await mkdir(frontendDirectory)
  await symlink(resolve(process.cwd(), 'node_modules'), join(rootDirectory, 'node_modules'))
  await copyFile(resolve(process.cwd(), 'vite.config.ts'), join(frontendDirectory, 'vite.config.ts'))
  await writeFile(join(frontendDirectory, 'package.json'), '{"type":"module"}')
  await writeFile(join(frontendDirectory, 'probe.ts'), 'console.log(import.meta.env)')
  const html = '<script type="module" src="/probe.ts"></script>'
  await writeFile(join(frontendDirectory, 'index.html'), html)
  await writeFile(join(frontendDirectory, 'street-view.html'), html)
})

afterEach(async () => {
  await server?.close()
  server = undefined
  vi.unstubAllEnvs()
  await rm(rootDirectory, { recursive: true, force: true })
})

function fixtureConfig(mode: string) {
  return {
    root: frontendDirectory,
    configFile: join(frontendDirectory, 'vite.config.ts'),
    mode,
    logLevel: 'silent' as const,
  }
}

describe('PostHog root environment boundary', () => {
  it('loads only the four root values in development while retaining frontend settings', async () => {
    await writeFile(join(rootDirectory, '.env'), [
      'VITE_POSTHOG_KEY=phc_rootpublicfixture',
      'VITE_POSTHOG_HOST=https://us.i.posthog.com',
      'VITE_ANALYTICS_ENV=dev',
      'VITE_POSTHOG_LOCAL_ENABLED=false',
      'VITE_API_BASE_URL=root-api-must-not-load',
      'VITE_ROOT_UNRELATED=root-public-must-not-load',
      'VITE_POSTHOG_KEY_EXTRA=root-prefix-must-not-load',
      'SERVER_SECRET=root-private-must-not-load',
      'NODE_ENV=production',
      'BROWSER=root-browser-must-not-load',
      'BROWSER_ARGS=root-browser-args-must-not-load',
    ].join('\n'))
    await writeFile(join(rootDirectory, '.env.development.local'), 'VITE_POSTHOG_LOCAL_ENABLED=true')
    await writeFile(join(frontendDirectory, '.env'), [
      'VITE_POSTHOG_KEY=stale-frontend-key',
      'VITE_API_BASE_URL=frontend-api-kept',
      'VITE_GA_MEASUREMENT_ID=frontend-ga-kept',
      'VITE_NAVER_MAPS_CLIENT_ID=frontend-map-kept',
    ].join('\n'))

    server = await createServer(fixtureConfig('development'))
    const transformed = await server.transformRequest('/probe.ts')
    expect(server.config.envDir).toBe(frontendDirectory)
    expect(Object.keys(server.config.define)).toEqual(posthogKeys.map((key) => `import.meta.env.${key}`))
    for (const value of ['phc_rootpublicfixture', 'https://us.i.posthog.com', 'frontend-api-kept', 'frontend-ga-kept', 'frontend-map-kept']) {
      expect(transformed?.code).toContain(value)
    }
    expect(server.config.define['import.meta.env.VITE_POSTHOG_LOCAL_ENABLED']).toBe('"true"')
    for (const value of ['stale-frontend-key', 'root-api-must-not-load', 'root-public-must-not-load', 'root-prefix-must-not-load', 'root-private-must-not-load']) {
      expect(transformed?.code).not.toContain(value)
    }
    for (const key of ['VITE_USER_NODE_ENV', 'BROWSER', 'BROWSER_ARGS']) expect(process.env[key]).toBeUndefined()
  })

  it('leaves missing root values empty instead of using old frontend copies', async () => {
    await writeFile(join(frontendDirectory, '.env'), posthogKeys.map((key) => `${key}=stale-frontend-value`).join('\n'))
    server = await createServer(fixtureConfig('development'))
    const transformed = await server.transformRequest('/probe.ts')
    for (const key of posthogKeys) expect(server.config.define[`import.meta.env.${key}`]).toBe('""')
    expect(transformed?.code).not.toContain('stale-frontend-value')
  })

  it('uses mode-specific root files and process overrides in a production build', async () => {
    await writeFile(join(rootDirectory, '.env'), 'VITE_POSTHOG_KEY=base-root-value\nVITE_ANALYTICS_ENV=dev')
    await writeFile(join(rootDirectory, '.env.production'), [
      'VITE_POSTHOG_KEY=mode-root-value',
      'VITE_POSTHOG_HOST=https://us.i.posthog.com',
      'VITE_ANALYTICS_ENV=prod',
      'VITE_POSTHOG_LOCAL_ENABLED=false',
      'VITE_ROOT_UNRELATED=production-root-must-not-load',
    ].join('\n'))
    await writeFile(join(frontendDirectory, '.env.production'), 'VITE_API_BASE_URL=frontend-production-api')
    vi.stubEnv('VITE_POSTHOG_KEY', 'phc_processpublicfixture')

    const result = await build({ ...fixtureConfig('production'), build: { write: false, minify: false } })
    const outputs = Array.isArray(result) ? result : [result]
    const code = outputs.flatMap((output) => 'output' in output
      ? output.output.filter((item) => item.type === 'chunk').map((item) => item.code)
      : []).join('\n')
    for (const value of ['phc_processpublicfixture', 'https://us.i.posthog.com', 'frontend-production-api', 'prod']) {
      expect(code).toContain(value)
    }
    for (const value of ['base-root-value', 'mode-root-value', 'production-root-must-not-load']) {
      expect(code).not.toContain(value)
    }
  })
})
