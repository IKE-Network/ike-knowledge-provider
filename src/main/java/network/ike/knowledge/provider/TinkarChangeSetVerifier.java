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

import dev.ikm.tinkar.entity.changeset.ChangeSetVerification;
import network.ike.knowledge.spi.ChangeSetReport;
import network.ike.knowledge.spi.ChangeSetRequest;
import network.ike.knowledge.spi.ChangeSetVerifier;

/** The {@code ike:changeset-verify} implementation over the entity module's {@link ChangeSetVerification}. */
public final class TinkarChangeSetVerifier implements ChangeSetVerifier {

    public TinkarChangeSetVerifier() {
    }

    @Override
    public ChangeSetReport verify(ChangeSetRequest request) {
        ChangeSetVerification.Verification verification =
                TinkarChangeSetTools.call(new ChangeSetVerification(request.changeSet().toFile()));
        return TinkarChangeSetTools.report(verification.ok(), verification.text());
    }
}
