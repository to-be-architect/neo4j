/*
 * Copyright (c) 2002-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.transaction.state.storeview;

import java.util.function.IntPredicate;

import javax.annotation.Nullable;

import org.neo4j.internal.helpers.collection.Visitor;
import org.neo4j.internal.index.label.LabelScanStore;
import org.neo4j.io.pagecache.tracing.cursor.PageCursorTracer;
import org.neo4j.lock.LockService;
import org.neo4j.storageengine.api.EntityUpdates;
import org.neo4j.storageengine.api.EntityTokenUpdate;
import org.neo4j.storageengine.api.StorageReader;

/**
 * Store scan view that will try to minimize the amount of scanned nodes by using label scan store {@link LabelScanStore}
 * as a source of known labeled node ids.
 * @param <FAILURE> type of exception thrown on failure
 */
public class LabelViewNodeStoreScan<FAILURE extends Exception> extends NodeStoreScan<FAILURE>
{
    private final LabelScanStore labelScanStore;
    private final PageCursorTracer cursorTracer;

    public LabelViewNodeStoreScan( StorageReader storageReader, LockService locks,
            LabelScanStore labelScanStore,
            @Nullable Visitor<EntityTokenUpdate,FAILURE> labelUpdateVisitor,
            @Nullable Visitor<EntityUpdates,FAILURE> propertyUpdatesVisitor,
            int[] labelIds,
            IntPredicate propertyKeyIdFilter, PageCursorTracer cursorTracer )
    {
        super( storageReader, locks, labelUpdateVisitor, propertyUpdatesVisitor, labelIds,
                propertyKeyIdFilter, cursorTracer );
        this.labelScanStore = labelScanStore;
        this.cursorTracer = cursorTracer;
    }

    @Override
    public EntityIdIterator getEntityIdIterator()
    {
        return new TokenScanViewIdIterator<>( labelScanStore.newReader(), labelIds, entityCursor, cursorTracer );
    }
}
