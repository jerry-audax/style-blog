// Spring WebFlux sends raw text in SSE data fields, not OpenAI JSON deltas.
export function createSseParser(onData) {
    let buffer = ''
    const emit = (event) => {
        const data = event
            .split(/\r\n|\r|\n/)
            .filter((line) => line.startsWith('data:'))
            .map((line) => line.slice(5).replace(/^ /, ''))
        if (data.length) onData(data.join('\n'))
    }
    return {
        push(chunk) {
            buffer += chunk
            let boundary
            while ((boundary = /\r\n\r\n|\n\n|\r\r/.exec(buffer))) {
                emit(buffer.slice(0, boundary.index))
                buffer = buffer.slice(boundary.index + boundary[0].length)
            }
        },
        finish() {
            if (buffer) emit(buffer)
            buffer = ''
        },
    }
}
