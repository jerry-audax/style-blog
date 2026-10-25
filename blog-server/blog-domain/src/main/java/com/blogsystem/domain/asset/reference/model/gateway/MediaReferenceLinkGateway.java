package com.blogsystem.domain.asset.reference.model.gateway;

import com.blogsystem.domain.asset.reference.model.valobj.MediaLinkScan;

/** Technical parsing returns object identities, not vendor URLs or parser nodes. */
public interface MediaReferenceLinkGateway {
    MediaLinkScan parseLink(String value);
    MediaLinkScan parseContent(String value);
    /** Known rendered HTML must not be interpreted again as Markdown or indented code. */
    MediaLinkScan parseHtmlContent(String value);
}
