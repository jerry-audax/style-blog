package com.blogsystem.domain.asset.reference;

import com.blogsystem.domain.asset.metadata.model.valobj.MediaObjectKey;
import com.blogsystem.domain.asset.reference.model.valobj.MediaLinkScan;
import com.blogsystem.domain.asset.reference.model.valobj.MediaLinkScan.Failure;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaLinkScanTest {
    @Test void emptySuccessfulScanIsDistinctFromAnIncompleteScan() {
        assertThat(new MediaLinkScan(Set.of(), Set.of()).complete()).isTrue();
        for (Failure failure : Failure.values()) {
            assertThat(new MediaLinkScan(Set.of(), Set.of(failure)).complete()).isFalse();
        }
    }

    @Test void positiveIdentitySurvivesAmbiguityAndCannotMakeEvidenceComplete() {
        var key = new MediaObjectKey("blog/covers/中文+100%.png");
        var scan = new MediaLinkScan(Set.of(key), Set.of(Failure.AMBIGUOUS));
        assertThat(scan.complete()).isFalse();
        assertThat(scan.objectKeys()).containsExactly(key);
        assertThat(scan.failures()).containsExactly(Failure.AMBIGUOUS);
    }

    @Test void ownsDeduplicatedImmutableSetsWithoutChangingObjectKeyIdentity() {
        var keys = new HashSet<MediaObjectKey>();
        keys.add(new MediaObjectKey("blog/A.png")); keys.add(new MediaObjectKey("blog/A.png"));
        keys.add(new MediaObjectKey("blog/a.png"));
        var failures = EnumSet.of(Failure.UNSUPPORTED);
        var scan = new MediaLinkScan(keys, failures);
        keys.clear(); failures.clear();
        assertThat(scan.objectKeys()).containsExactlyInAnyOrder(new MediaObjectKey("blog/A.png"), new MediaObjectKey("blog/a.png"));
        assertThat(scan.complete()).isFalse();
        assertThatThrownBy(() -> scan.objectKeys().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> scan.failures().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void rejectsNullCollectionsOrElementsRatherThanInventingAnEmptySuccess() {
        assertThatThrownBy(() -> new MediaLinkScan(null, Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MediaLinkScan(Set.of(), null)).isInstanceOf(IllegalArgumentException.class);
        var keys = new HashSet<MediaObjectKey>(); keys.add(null);
        assertThatThrownBy(() -> new MediaLinkScan(keys, Set.of())).isInstanceOf(IllegalArgumentException.class);
        var failures = new HashSet<Failure>(); failures.add(null);
        assertThatThrownBy(() -> new MediaLinkScan(Set.of(), failures)).isInstanceOf(IllegalArgumentException.class);
    }
}
