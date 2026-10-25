package com.blogsystem.domain.asset.reference;

import com.blogsystem.domain.asset.reference.model.valobj.MediaReferenceEvidence;
import com.blogsystem.domain.asset.reference.model.valobj.MediaReferenceEvidence.Source;
import com.blogsystem.domain.asset.reference.model.valobj.MediaReferenceEvidence.Usage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaReferenceEvidenceTest {
    @Test void noUsagesDoesNotProveCoverage() {
        assertThat(new MediaReferenceEvidence(EnumSet.allOf(Source.class), Set.of(), List.of(), Set.of()).complete()).isFalse();
        assertThat(new MediaReferenceEvidence(EnumSet.allOf(Source.class), EnumSet.allOf(Source.class), List.of(), Set.of()).complete()).isTrue();
        assertThat(new MediaReferenceEvidence(Set.of(Source.MUSIC, Source.PUBLICATION), Set.of(Source.MUSIC), List.of(), Set.of()).complete()).isFalse();
    }
    @Test void sourceFailureAlwaysPreventsCompletenessAndDoesNotDiscardPositiveUsage() {
        var usage = new Usage(Source.MUSIC, "disabled-song", "已禁用歌曲仍绑定封面");
        var evidence = new MediaReferenceEvidence(EnumSet.allOf(Source.class), EnumSet.allOf(Source.class),
                List.of(usage), Set.of(Source.PUBLICATION));
        assertThat(evidence.complete()).isFalse();
        assertThat(evidence.usages()).containsExactly(usage);
    }
    @Test void copiesAllCollectionsAndRejectsMalformedEvidence() {
        var required = EnumSet.allOf(Source.class);
        var successes = EnumSet.allOf(Source.class);
        var usages = new ArrayList<>(List.of(new Usage(Source.ARTICLE, "1", "草稿")));
        var failures = EnumSet.noneOf(Source.class);
        var evidence = new MediaReferenceEvidence(required, successes, usages, failures);
        required.clear(); successes.clear(); usages.clear(); failures.add(Source.OWNER);
        assertThat(evidence.complete()).isTrue();
        assertThat(evidence.usages()).hasSize(1);
        assertThatThrownBy(() -> evidence.usages().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> evidence.successfulSources().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new MediaReferenceEvidence(null, Set.of(), List.of(), Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Usage(null, "1", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Usage(Source.OWNER, "", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Usage(Source.OWNER, "1", null)).isInstanceOf(IllegalArgumentException.class);
    }
}
