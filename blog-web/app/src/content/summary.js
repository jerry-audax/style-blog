export function visibleSummary(value) {
    if (typeof value !== 'string') return ''
    // Inert detached template: extract text, never mount untrusted summary HTML.
    const template = document.createElement('template')
    template.innerHTML = value
    template.content.querySelectorAll('script,style,textarea,option,template,noscript').forEach(node => node.remove())
    return template.content.textContent.replace(/[\s\u200b\ufeff]+/gu, ' ').trim()
}
