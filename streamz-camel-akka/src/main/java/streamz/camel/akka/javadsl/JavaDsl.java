/*
 * Copyright 2014 - 2018 the original author or authors.
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

package streamz.camel.akka.javadsl;

import akka.NotUsed;
import akka.stream.FlowShape;
import akka.stream.Graph;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Source;
import streamz.camel.StreamContext;
import streamz.camel.StreamMessage;

public interface JavaDsl {
    /** Returns the {@link StreamContext} in scope. */
    StreamContext streamContext();

    /** Delegates to {@link streamz.camel.akka.scaladsl.receive scaladsl.receive} */
    default <A> Source<StreamMessage<A>, NotUsed> receive(String uri, Class<A> clazz) {
        return new JavaDslDef(streamContext()).receive(uri, clazz);
    }

    /** Delegates to {@link streamz.camel.akka.scaladsl.receiveBody scaladsl.receiveBody} */
    default <A> Source<A, NotUsed> receiveBody(String uri, Class<A> clazz) {
        return new JavaDslDef(streamContext()).receiveBody(uri, clazz);
    }

    /** Delegates to {@link streamz.camel.akka.scaladsl.receiveRequest scaladsl.receiveRequest} */
    default <A, B> Flow<StreamMessage<A>, StreamMessage<B>, NotUsed> receiveRequest(String uri, Integer capacity, Class<B> clazz) {
        return new JavaDslDef(streamContext()).receiveRequest(uri, capacity, clazz);
    }

    /** Delegates to {@link streamz.camel.akka.scaladsl.receiveRequest scaladsl.receiveRequest} */
    default <A, B> Flow<StreamMessage<A>, StreamMessage<B>, NotUsed> receiveRequest(String uri, Class<B> clazz) {
        return new JavaDslDef(streamContext()).receiveRequest(uri, clazz);
    }

    /** Delegates to {@link streamz.camel.akka.scaladsl.receiveRequestBody scaladsl.receiveRequestBody} */
    default <A, B> Flow<A, B, NotUsed> receiveRequestBody(String uri, Integer capacity, Class<B> clazz) {
        return new JavaDslDef(streamContext()).receiveRequestBody(uri, capacity, clazz);
    }

    /** Delegates to {@link streamz.camel.akka.scaladsl.receiveRequestBody scaladsl.receiveRequestBody} */
    default <A, B> Flow<A, B, NotUsed> receiveRequestBody(String uri, Class<B> clazz) {
        return new JavaDslDef(streamContext()).receiveRequestBody(uri, clazz);
    }

    /** Delegates to {@link streamz.camel.akka.scaladsl.send scaladsl.send} */
    default <A> Graph<FlowShape<StreamMessage<A>, StreamMessage<A>>, NotUsed> send(String uri, int parallelism) {
        return new JavaDslDef(streamContext()).send(uri, parallelism);
    }

    /** Delegates to {@link streamz.camel.akka.scaladsl.send scaladsl.send} */
    default <A> Graph<FlowShape<StreamMessage<A>, StreamMessage<A>>, NotUsed> send(String uri) {
        return send(uri, 1);
    }

    /** Delegates to {@link streamz.camel.akka.scaladsl.sendBody scaladsl.sendBody} */
    default <I> Graph<FlowShape<I, I>, NotUsed> sendBody(String uri, int parallelism) {
        return new JavaDslDef(streamContext()).sendBody(uri, parallelism);
    }

    /** Delegates to {@link streamz.camel.akka.scaladsl.sendBody scaladsl.sendBody} */
    default <A> Graph<FlowShape<A, A>, NotUsed> sendBody(String uri) {
        return sendBody(uri, 1);
    }

    /** Delegates to {@link streamz.camel.akka.scaladsl.sendRequest scaladsl.sendRequest} */
    default <A, B> Graph<FlowShape<StreamMessage<A>, StreamMessage<B>>, NotUsed> sendRequest(String uri, int parallelism, Class<B> clazz) {
        return new JavaDslDef(streamContext()).sendRequest(uri, parallelism, clazz);
    }

    /** Delegates to {@link streamz.camel.akka.scaladsl.sendRequest scaladsl.sendRequest} */
    default <A, B> Graph<FlowShape<StreamMessage<A>, StreamMessage<B>>, NotUsed> sendRequest(String uri, Class<B> clazz) {
        return new JavaDslDef(streamContext()).sendRequest(uri, clazz);
    }

    /** Delegates to {@link streamz.camel.akka.scaladsl.sendRequestBody scaladsl.sendRequestBody} */
    default <A, B> Graph<FlowShape<A, B>, NotUsed> sendRequestBody(String uri, int parallelism, Class<B> clazz) {
        return new JavaDslDef(streamContext()).sendRequestBody(uri, parallelism, clazz);
    }

    /** Delegates to {@link streamz.camel.akka.scaladsl.sendRequestBody scaladsl.sendRequestBody} */
    default <A, B> Graph<FlowShape<A, B>, NotUsed> sendRequestBody(String uri, Class<B> clazz) {
        return new JavaDslDef(streamContext()).sendRequestBody(uri, clazz);
    }

    default <A> Flow<A, A, NotUsed> reply() {
        return Flow.create();
    }
}
