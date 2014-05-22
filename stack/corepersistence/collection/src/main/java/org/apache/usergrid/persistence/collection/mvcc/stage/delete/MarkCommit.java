/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.usergrid.persistence.collection.mvcc.stage.delete;


import java.util.*;

import com.google.common.base.Optional;
import org.apache.usergrid.persistence.collection.guice.MvccEntityDelete;
import org.apache.usergrid.persistence.collection.mvcc.entity.impl.MvccEntityEvent;
import org.apache.usergrid.persistence.collection.mvcc.stage.write.UniqueValue;
import org.apache.usergrid.persistence.collection.mvcc.stage.write.UniqueValueSerializationStrategy;
import org.apache.usergrid.persistence.collection.serialization.SerializationFig;
import org.apache.usergrid.persistence.core.consistency.AsyncProcessor;
import org.apache.usergrid.persistence.core.consistency.AsynchronousMessage;
import org.apache.usergrid.persistence.core.consistency.ConsistencyFig;
import org.apache.usergrid.persistence.core.rx.ObservableIterator;
import org.apache.usergrid.persistence.model.entity.Entity;
import org.apache.usergrid.persistence.model.field.Field;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.usergrid.persistence.collection.CollectionScope;
import org.apache.usergrid.persistence.collection.exception.CollectionRuntimeException;
import org.apache.usergrid.persistence.collection.mvcc.MvccEntitySerializationStrategy;
import org.apache.usergrid.persistence.collection.mvcc.MvccLogEntrySerializationStrategy;
import org.apache.usergrid.persistence.collection.mvcc.entity.MvccEntity;
import org.apache.usergrid.persistence.collection.mvcc.entity.MvccLogEntry;
import org.apache.usergrid.persistence.collection.mvcc.entity.MvccValidationUtils;
import org.apache.usergrid.persistence.collection.mvcc.entity.impl.MvccLogEntryImpl;
import org.apache.usergrid.persistence.collection.mvcc.stage.CollectionIoEvent;
import org.apache.usergrid.persistence.model.entity.Id;

import com.google.common.base.Preconditions;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.netflix.astyanax.MutationBatch;
import com.netflix.astyanax.connectionpool.exceptions.ConnectionException;

import rx.Observable;
import rx.functions.Func1;
import rx.schedulers.Schedulers;


/**
 * This phase should invoke any finalization, and mark the entity 
 * as committed in the data store before returning
 */
@Singleton
public class MarkCommit implements Func1<CollectionIoEvent<MvccEntity>, Void> {

    private static final Logger LOG = LoggerFactory.getLogger( MarkCommit.class );

    private final MvccLogEntrySerializationStrategy logStrat;
    private final MvccEntitySerializationStrategy entityStrat;
    private final AsyncProcessor<MvccEntityEvent<MvccEntity>> entityEventAsyncProcessor;
    private final ConsistencyFig consistencyFig;
    private final UniqueValueSerializationStrategy uniqueValueStrat;
    private final SerializationFig serializationFig;

    @Inject
    public MarkCommit( final MvccLogEntrySerializationStrategy logStrat,
                       final MvccEntitySerializationStrategy entityStrat,
                       @MvccEntityDelete AsyncProcessor<MvccEntityEvent<MvccEntity>> entityEventAsyncProcessor,
                       final ConsistencyFig consistencyFig, final SerializationFig serializationFig,
                       final UniqueValueSerializationStrategy uniqueValueStrat) {

        Preconditions.checkNotNull( 
                logStrat, "logEntrySerializationStrategy is required" );
        Preconditions.checkNotNull( 
                entityStrat, "entitySerializationStrategy is required" );

        Preconditions.checkNotNull(
                uniqueValueStrat, "uniqueValueSerializationStrategy is required");

        this.logStrat = logStrat;
        this.entityStrat = entityStrat;
        this.entityEventAsyncProcessor = entityEventAsyncProcessor;
        this.consistencyFig = consistencyFig;
        this.uniqueValueStrat = uniqueValueStrat;
        this.serializationFig = serializationFig;
    }

    private long getTimeout() {
        return consistencyFig.getRepairTimeout() * 2;
    }

    @Override
    public Void call( final CollectionIoEvent<MvccEntity> idIoEvent ) {

        final MvccEntity entity = idIoEvent.getEvent();

        MvccValidationUtils.verifyMvccEntityOptionalEntity( entity );

        final Id entityId = entity.getId();
        final UUID version = entity.getVersion();


        final CollectionScope collectionScope = idIoEvent.getEntityCollection();


        final MvccLogEntry startEntry = new MvccLogEntryImpl( entityId, version,
                org.apache.usergrid.persistence.collection.mvcc.entity.Stage.COMMITTED, MvccLogEntry.Status.DELETED );

        final MutationBatch logMutation = logStrat.write( collectionScope, startEntry );

        //insert a "cleared" value into the versions.  Post processing should actually delete
        final MutationBatch entityMutation = entityStrat.mark( collectionScope, entityId, version );

        //
        Observable<List<Field>> deleteFieldsObservable = Observable.create(new ObservableIterator<Field>("deleteColumns") {
            @Override
            protected Iterator<Field> getIterator() {
                Iterator<MvccEntity> entities  = entityStrat.load(collectionScope, entityId, entity.getVersion(), 1);
                Iterator<Field> fieldIterator = Collections.emptyIterator();
                if(entities.hasNext()) {
                    Optional<Entity> oe = entities.next().getEntity();
                    if (oe.isPresent()) {
                        fieldIterator = oe.get().getFields().iterator();
                    }
                }
                return fieldIterator;
            }
        }).subscribeOn(Schedulers.io())
          .buffer(serializationFig.getBufferSize())
          .map(new Func1<List<Field>, List<Field>>() {
              @Override
              public List<Field> call(List<Field> fields) {
                  for (Field field : fields) {
                      try {
                          UniqueValue value = uniqueValueStrat.load(collectionScope, field);
                          if (value != null) {
                              logMutation.mergeShallow(uniqueValueStrat.delete(value));
                          }
                      } catch (ConnectionException ce) {
                          LOG.error("Failed to delete Unique Value", ce);
                      }
                  }
                  return fields;
              }
          });
        deleteFieldsObservable.toBlockingObservable().last();

        try {
            logMutation.execute();
        }
        catch ( ConnectionException e ) {
            LOG.error( "Failed to execute write asynchronously ", e );
            throw new CollectionRuntimeException( entity.getEntity().get(), collectionScope,
                    "Failed to execute write asynchronously ", e );
        }
        //merge the 2 into 1 mutation
        logMutation.mergeShallow( entityMutation );

        //set up the post processing queue
        final AsynchronousMessage<MvccEntityEvent<MvccEntity>> event = entityEventAsyncProcessor.setVerification( new MvccEntityEvent<MvccEntity>(collectionScope, version, entity ), getTimeout() );


        //fork post processing
        entityEventAsyncProcessor.start(event);

        /**
         * We're done executing.
         */

        return null;
    }
}
