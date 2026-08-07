/*******************************************************************************
 * Copyright 2017-2026 TAXTELECOM, LLC
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
 *******************************************************************************/
package org.pgcodekeeper.core.database.base.loader;

import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.Queue;

import org.pgcodekeeper.core.database.api.launcher.IAnalysisLauncher;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.base.parser.AntlrTask;
import org.pgcodekeeper.core.database.base.parser.AntlrTaskManager;

/**
 * Thread-confined state of one lane of the parallel catalog readers: a worker
 * connection sharing the primary snapshot, a private ANTLR pipeline, the
 * per-lane error-location fields, and the launcher buffer of the reader that
 * is currently running on the lane.
 * <p>
 * A lane is bound to exactly one worker thread while that thread runs catalog
 * readers; every field except the connection is accessed only from that
 * thread. This is an internal bridge type, not a supported extension API.
 */
public final class JdbcCatalogLane {

    private final Connection connection;
    private final Queue<AntlrTask<?>> antlrTasks;

    private ObjectReference currentObject;
    private String currentOperation;
    private List<IAnalysisLauncher> launcherSink;

    /**
     * Creates a lane around an already snapshot-synchronized worker connection.
     *
     * @param connection worker connection owned by the parallel orchestrator
     */
    public JdbcCatalogLane(Connection connection) {
        this(connection, AntlrTaskManager.createTaskQueue());
    }

    /**
     * Creates a lane using a parser queue borrowed from the root operation.
     *
     * @param connection worker connection owned by the parallel orchestrator
     * @param antlrTasks thread-confined queue sharing the root parser executor
     */
    public JdbcCatalogLane(Connection connection, Queue<AntlrTask<?>> antlrTasks) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.antlrTasks = Objects.requireNonNull(antlrTasks, "antlrTasks");
    }

    public Connection getConnection() {
        return connection;
    }

    public Queue<AntlrTask<?>> getAntlrTasks() {
        return antlrTasks;
    }

    ObjectReference getCurrentObject() {
        return currentObject;
    }

    void setCurrentObject(ObjectReference currentObject) {
        this.currentObject = currentObject;
    }

    String getCurrentOperation() {
        return currentOperation;
    }

    void setCurrentOperation(String currentOperation) {
        this.currentOperation = currentOperation;
    }

    /**
     * Returns the launcher buffer of the reader currently running on this
     * lane, or {@code null} before the first reader starts.
     */
    public List<IAnalysisLauncher> getLauncherSink() {
        return launcherSink;
    }

    /**
     * Installs the launcher buffer of the next reader running on this lane.
     *
     * @param launcherSink canonical-slot buffer of the reader
     */
    public void setLauncherSink(List<IAnalysisLauncher> launcherSink) {
        this.launcherSink = launcherSink;
    }
}
