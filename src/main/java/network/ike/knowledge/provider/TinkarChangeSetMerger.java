/*
 * Copyright © 2026 IKE Network (support@ike.network)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package network.ike.knowledge.provider;

import dev.ikm.tinkar.common.service.CachingService;
import dev.ikm.tinkar.common.service.EntityCountSummary;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.service.ServiceKeys;
import dev.ikm.tinkar.common.service.ServiceProperties;
import dev.ikm.tinkar.entity.aggregator.TemporalEntityAggregator;
import dev.ikm.tinkar.entity.changeset.ChangeSetVerification;
import dev.ikm.tinkar.entity.export.ExportEntitiesToProtobufFile;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import network.ike.knowledge.spi.ChangeSetMerger;
import network.ike.knowledge.spi.ChangeSetReport;
import network.ike.knowledge.spi.ChangeSetRequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The chronology-store {@link ChangeSetMerger}: verifies each change set against its format,
 * loads them all into an ephemeral store, where an entity in several of them merges into one
 * with every version kept, and exports the store, from the beginning of time, as one change
 * set in the current format. The change sets need no base: what they reference but do not
 * carry is written by public id, as each of them writes it.
 */
public final class TinkarChangeSetMerger implements ChangeSetMerger {

    /** Creates the provider (ServiceLoader requirement). */
    public TinkarChangeSetMerger() {
    }

    @Override
    public ChangeSetReport merge(ChangeSetRequest request) {
        Path target = request.requireTarget("ike:changeset-merge");
        List<Path> changeSets = changeSets(request.changeSet());
        List<String> lines = new ArrayList<>();
        boolean verified = true;
        for (Path changeSet : changeSets) {
            ChangeSetVerification.Verification verification =
                    TinkarChangeSetTools.call(new ChangeSetVerification(changeSet.toFile()));
            lines.add((verification.ok() ? "verified " : "error: failed verification ") + changeSet.getFileName());
            if (!verification.ok()) {
                verified = false;
                for (String line : verification.text().strip().split("\\R")) {
                    lines.add("  " + line);
                }
            }
        }
        if (!verified) {
            return new ChangeSetReport(false, "Not merged: a change set failed verification", lines);
        }
        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
            Path storeRoot = Files.createTempDirectory("ike-changeset-merge");
            CachingService.clearAll();
            ServiceProperties.set(ServiceKeys.DATA_STORE_ROOT, storeRoot.toFile());
            PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
            PrimitiveData.start();
            try {
                for (Path changeSet : changeSets) {
                    new LoadEntitiesFromProtobufFile(changeSet.toFile()).compute();
                }
                // With modules, only the versions stamped in them: the extension's content, not
                // what a browser wrote on its own in the same session.
                java.util.function.LongPredicate keep = null;
                if (!request.modules().isEmpty()) {
                    java.util.Set<Long> moduleNids = new java.util.HashSet<>();
                    for (String uuid : request.modules()) {
                        // A module none of the change sets holds stamps nothing, so it keeps nothing;
                        // looking it up must not mint it, which a store keyed by pattern refuses.
                        dev.ikm.tinkar.common.id.PublicId module = dev.ikm.tinkar.common.id.PublicIds.of(java.util.UUID.fromString(uuid));
                        if (PrimitiveData.get().hasPublicId(module)) {
                            moduleNids.add(PrimitiveData.nid(module));
                        }
                    }
                    keep = stampNid -> dev.ikm.tinkar.entity.EntityService.get().getStamp(stampNid)
                            .map(stamp -> moduleNids.contains(stamp.moduleNid())).orElse(false);
                    lines.add("keeping only versions in modules " + new java.util.TreeSet<>(request.modules()));
                }
                EntityCountSummary summary = new ExportEntitiesToProtobufFile(target.toFile(),
                        new TemporalEntityAggregator(Long.MIN_VALUE, Long.MAX_VALUE), keep).compute();
                return new ChangeSetReport(true, "Merged " + changeSets.size() + " change set(s) into "
                        + target.getFileName() + ": " + summary.conceptCount() + " concepts, "
                        + summary.semanticCount() + " semantics, " + summary.patternCount() + " patterns, "
                        + summary.stampCount() + " stamps", lines);
            } finally {
                PrimitiveData.stop();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Merging change sets into " + target + " failed", e);
        }
    }

    /** The change sets a request names: the file, or every zip in the directory in name order. */
    private static List<Path> changeSets(Path source) {
        if (!Files.isDirectory(source)) {
            return List.of(source);
        }
        try (Stream<Path> list = Files.list(source)) {
            return list.filter(f -> Files.isRegularFile(f) && f.getFileName().toString().endsWith(".zip"))
                    .sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Listing change sets in " + source, e);
        }
    }
}
