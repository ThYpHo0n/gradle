/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.execution.impl.steps;

import org.gradle.api.BuildCancelledException;
import org.gradle.initialization.BuildCancellationToken;
import org.gradle.internal.execution.ExecutionException;
import org.gradle.internal.execution.UnitOfWork;
import org.gradle.internal.execution.WorkOutcome;
import org.gradle.internal.execution.WorkResult;

public class ExecuteStep {

    private final BuildCancellationToken cancellationToken;

    public ExecuteStep(BuildCancellationToken cancellationToken) {
        this.cancellationToken = cancellationToken;
    }

    public WorkResult execute(UnitOfWork work) {
        try {
            boolean didWork = work.execute();
            if (cancellationToken.isCancellationRequested()) {
                return WorkResult.failure(new BuildCancelledException("Build cancelled during executing " + work.getDisplayName()));
            }
            return didWork
                ? WorkResult.success(WorkOutcome.EXECUTED)
                : WorkResult.success(WorkOutcome.UP_TO_DATE);
        } catch (Throwable t) {
            // TODO Should we catch Exception here?
            return WorkResult.failure(new ExecutionException(work, t));
        }
    }
}
