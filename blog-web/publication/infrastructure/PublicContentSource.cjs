'use strict'
const {fetchPublished} = require('../../tools/content.cjs')
class PublicContentSource {
    constructor(baseUrl) {this.baseUrl = baseUrl}
    fetchPosts() {return fetchPublished(this.baseUrl)}
}
module.exports = {PublicContentSource}
