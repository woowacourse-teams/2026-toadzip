import type { PostHog } from 'posthog-js/no-external'

type QueuedRequestWithOptions = Parameters<PostHog['_send_request']>[0]

/**
 * posthog-js 1.438.2 does not recheck consent when its batch/retry queues flush.
 * Its shutdown also flushes. Keep these version-specific transport boundaries
 * together and exercise them with the installed SDK in posthogTransport.test.ts.
 */
export function guardPostHogTransport(instance: PostHog, allowed: () => boolean) {
  let active = true
  const lifetime = new AbortController()
  const current = () => active && allowed()
  const prepare = (options: QueuedRequestWithOptions): QueuedRequestWithOptions => {
    const fetchOptions = {
      ...options.fetchOptions,
      cache: 'no-store' as const,
      credentials: 'omit' as const,
      referrerPolicy: 'no-referrer' as const,
      keepalive: false,
      // The SDK merges these after its own signal. Retain a request deadline.
      signal: AbortSignal.any([lifetime.signal, AbortSignal.timeout(10_000)]),
    }
    return { ...options, transport: 'fetch', disableTransport: ['XHR', 'sendBeacon'], fetchOptions }
  }
  const send = instance._send_request.bind(instance)
  const retry = instance._send_retriable_request.bind(instance)
  instance._send_request = options => { if (current()) send(prepare(options)) }
  instance._send_retriable_request = options => { if (current()) retry(prepare(options)) }

  return {
    ready() {
      const queue = instance._retryQueue
      if (!queue) throw new Error('PostHog retry boundary unavailable')
      const attempt = queue.retriableRequest.bind(queue)
      queue.retriableRequest = options => { if (current()) attempt(prepare(options)) }
      // An in-flight failure can arrive after shutdown. Do not let it recreate
      // the retry queue or retain the previous consent generation's payload.
      const enqueue: unknown = Reflect.get(queue, '_enqueue')
      if (typeof enqueue !== 'function') throw new Error('PostHog retry cancellation unavailable')
      Reflect.set(queue, '_enqueue', (...arguments_: unknown[]) => {
        if (current()) Reflect.apply(enqueue, queue, arguments_)
      })
    },
    stop() {
      if (!active) return
      active = false
      lifetime.abort()
      instance.opt_out_capturing()
      // Transport guards are already closed, so shutdown discards rather than
      // sends both queues. Old pagehide/online callbacks remain unable to send.
      void instance.shutdown()
      instance.__loaded = false
      instance.__request_queue = []
    },
  }
}
