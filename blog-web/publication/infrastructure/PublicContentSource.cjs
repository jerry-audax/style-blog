'use strict'
const {fetchPublished} = require('../../tools/content.cjs')
class PublicContentSource {
    constructor(baseUrl) {this.baseUrl = baseUrl; this.controller = new AbortController()}
    fetchPosts() {return fetchPublished(this.baseUrl, fetch, {signal: this.controller.signal})}
    stop() {this.controller.abort()}
}
module.exports = {PublicContentSource}
