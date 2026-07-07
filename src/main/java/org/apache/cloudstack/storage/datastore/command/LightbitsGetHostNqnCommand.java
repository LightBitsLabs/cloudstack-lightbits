/*
 * Copyright 2026 Lightbits Labs Ltd.
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
package org.apache.cloudstack.storage.datastore.command;

import com.cloud.agent.api.Command;

/**
 * Asks a KVM host for its NVMe host NQN (/etc/nvme/hostnqn).
 *
 * Sent by the LightbitsHostListener on host connect so the management server can persist
 * the NQN as a host detail; the driver's grantAccess/revokeAccess use it for volume ACLs.
 * Serialized by full class name (ArrayTypeAdaptor), so it only needs the plugin jar on
 * both the management-server and agent classpaths.
 */
public class LightbitsGetHostNqnCommand extends Command {

    public LightbitsGetHostNqnCommand() {
        super();
    }

    @Override
    public boolean executeInSequence() {
        return false;
    }
}
