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

import dev.ikm.tinkar.entity.changeset.ChangeSetInspection;
import network.ike.knowledge.spi.ChangeSetInspector;
import network.ike.knowledge.spi.ChangeSetReport;
import network.ike.knowledge.spi.ChangeSetRequest;

/** The {@code ike:changeset-inspect} implementation over the entity module's {@link ChangeSetInspection}. */
public final class TinkarChangeSetInspector implements ChangeSetInspector {

    public TinkarChangeSetInspector() {
    }

    @Override
    public ChangeSetReport inspect(ChangeSetRequest request) {
        ChangeSetInspection.Report report = TinkarChangeSetTools.call(new ChangeSetInspection(request.changeSet().toFile()));
        return TinkarChangeSetTools.report(true, report.text());
    }
}
