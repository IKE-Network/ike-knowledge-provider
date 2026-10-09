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

import dev.ikm.tinkar.common.service.TrackingCallable;
import network.ike.knowledge.spi.ChangeSetReport;

import java.util.ArrayList;
import java.util.List;

/**
 * What the four change set tool implementations share: running one of the entity module's
 * change set callables in this thread, and shaping its text as a report, the first line the
 * summary and the rest the lines.
 */
final class TinkarChangeSetTools {

    private TinkarChangeSetTools() {
    }

    static <T> T call(TrackingCallable<T> tool) {
        try {
            return tool.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(tool.getTitle() + " failed", e);
        }
    }

    static ChangeSetReport report(boolean ok, String text) {
        List<String> lines = new ArrayList<>(List.of(text.strip().split("\\R")));
        String summary = lines.isEmpty() ? "" : lines.removeFirst();
        return new ChangeSetReport(ok, summary, lines);
    }
}
