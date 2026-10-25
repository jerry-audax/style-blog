package com.blogsystem.domain.asset.reference.adapter.repository;

import com.blogsystem.domain.asset.reference.model.valobj.MediaReferenceWriteIntent;

/** Protection lasts for the caller's original transaction, not for a detached validation request. */
public interface MediaReferenceWriteRepository {
    void lockForWrite(MediaReferenceWriteIntent previous, MediaReferenceWriteIntent desired);
}
