'use strict'
class PublicationServingGate {
    #readers = 0
    #exclusive = false
    #queue = []
    constructor({maxWaiters = 1000, timeoutMs = 10000} = {}) {
        if (!Number.isSafeInteger(maxWaiters) || maxWaiters < 1 || maxWaiters > 1000
                || !Number.isSafeInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > 10000) throw new Error('Invalid serving gate')
        this.maxWaiters = maxWaiters; this.timeoutMs = timeoutMs
    }
    #lease() {
        this.#readers++; let released = false
        return () => {if (!released) {released = true; this.#readers--}}
    }
    async acquireRead({signal} = {}) {
        const cancelled = () => Object.assign(new Error('Publication reader cancelled'), {name: 'AbortError'})
        if (signal?.aborted) throw cancelled()
        if (!this.#exclusive) return this.#lease()
        if (this.#queue.length >= this.maxWaiters) throw new Error('Publication readers busy')
        return new Promise((resolve, reject) => {
            const entry = {resolve, reject, timer: null, signal, cleanup: null}
            const remove = error => {
                const index = this.#queue.indexOf(entry)
                if (index < 0) return
                this.#queue.splice(index, 1); entry.cleanup(); reject(error)
            }
            const abort = () => remove(cancelled())
            entry.cleanup = () => {clearTimeout(entry.timer); signal?.removeEventListener('abort', abort)}
            entry.timer = setTimeout(() => {
                remove(new Error('Publication reader wait expired'))
            }, this.timeoutMs)
            this.#queue.push(entry)
            signal?.addEventListener('abort', abort, {once: true})
            if (signal?.aborted) abort()
        })
    }
    async tryExclusive(action) {
        if (this.#readers || this.#exclusive) return false
        this.#exclusive = true
        try {await action(); return true} finally {
            this.#exclusive = false
            for (const entry of this.#queue.splice(0)) {
                entry.cleanup()
                if (entry.signal?.aborted) entry.reject(Object.assign(new Error('Publication reader cancelled'), {name: 'AbortError'}))
                else entry.resolve(this.#lease())
            }
        }
    }
}
module.exports = {PublicationServingGate}
