/*
 * Copyright 2019 MarkLogic Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.marklogic.client.dataservices.impl;

import com.marklogic.client.DatabaseClient;
import com.marklogic.client.MarkLogicInternalException;
import com.marklogic.client.SessionState;
import com.marklogic.client.dataservices.OutputEndpoint;
import com.marklogic.client.io.marker.JSONWriteHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class OutputEndpointImpl extends IOEndpointImpl implements OutputEndpoint {
    private static Logger logger = LoggerFactory.getLogger(OutputEndpointImpl.class);
    private OutputCallerImpl caller;

    public OutputEndpointImpl(DatabaseClient client, JSONWriteHandle apiDecl) {
        this(client, new OutputCallerImpl(apiDecl));
    }
    private OutputEndpointImpl(DatabaseClient client, OutputCallerImpl caller) {
        super(client, caller);
        this.caller = caller;
    }
    private OutputCallerImpl getCaller() {
        return this.caller;
    }

    @Override
    public BulkOutputCaller bulkCaller() {
        return new BulkOutputCallerImpl(this);
    }

    final static class BulkOutputCallerImpl extends IOEndpointImpl.BulkIOEndpointCallerImpl
            implements OutputEndpoint.BulkOutputCaller {

        private OutputEndpointImpl endpoint;
        private Consumer<InputStream> outputConsumer;

        private BulkOutputCallerImpl(OutputEndpointImpl endpoint) {
            super(endpoint);
            this.endpoint = endpoint;
        }

        @Override
        public void awaitCompletion() {
            if(outputConsumer == null)
                throw new IllegalStateException("Consumer is null.");

            logger.trace("output endpoint running endpoint={} work={}", getEndpointPath(), getWorkUnit());

            if(getPhase() != WorkPhase.INITIALIZING) {
                throw new IllegalStateException(
                        "Cannot process output since current phase is  " + getPhase().name());
            }

            setPhase(WorkPhase.RUNNING);
            SessionState session = allowsSession() ? getEndpoint().getCaller().newSessionState() : null;

            calling: while (true) {
                Stream<InputStream> output = null;
                try {
                    logger.trace("output endpoint={} count={} state={}",
                            getEndpointPath(), getCallCount(), getEndpointState());

                    output = getEndpoint().getCaller().call(
                            getEndpoint().getClient(), getEndpointState(), session, getWorkUnit()
                    );
                    incrementCallCount();
                } catch(Throwable throwable) {
                    throw new RuntimeException("error while calling "+getEndpoint().getEndpointPath(), throwable);
                }

                if(output != null) {
                    InputStream[] result = output.toArray(size -> new InputStream[size]);

                    if (allowsEndpointState() && result.length>0) {
                            setEndpointState(result[0]);
                    }
                    for(int i= allowsEndpointState()?1:0; i<result.length; i++) {
                        outputConsumer.accept(result[i]);
                    }
                }

                switch(getPhase()) {
                    case INTERRUPTING:
                        setPhase(WorkPhase.INTERRUPTED);
                        logger.info("output interrupted endpoint={} count={} work={}",
                                getEndpointPath(), getCallCount(), getWorkUnit());
                        break calling;
                    case RUNNING:
                        if (output == null) {
                            setPhase(WorkPhase.COMPLETED);
                            logger.info("output completed endpoint={} count={} work={}",
                                    getEndpointPath(), getCallCount(), getWorkUnit());

                            break calling;
                        }
                    case INTERRUPTED:

                    case COMPLETED:
                        throw new IllegalStateException(
                                "cannot process more output as current phase is  " + getPhase().name());

                    default:
                        throw new MarkLogicInternalException(
                                "unexpected state for "+getEndpointPath()+" during loop: "+getPhase().name()
                        );
                }
            }
        }

        private OutputEndpointImpl getEndpoint() {
            return endpoint;
        }

        private Consumer<InputStream> getOutputConsumer() {
            return outputConsumer;
        }

        @Override
        public void forEachOutput(Consumer<InputStream> outputConsumer) {
            this.outputConsumer = outputConsumer;
        }
    }

    @Override
    public Stream<InputStream> call(InputStream workUnit) {
        return getCaller().call(getClient(), null, null, workUnit);
    }
}