/*
 * Copyright 2014 - 2017 the original author or authors.
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

import akka.Done;
import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import org.apache.camel.TypeConversionException;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.impl.SimpleRegistry;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import streamz.camel.StreamContext;

import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;

public class JavaDslTest implements JavaDsl {
    private static SimpleRegistry camelRegistry =
            new SimpleRegistry();

    private static DefaultCamelContext camelContext =
            new DefaultCamelContext();

    private static ActorSystem actorSystem =
            ActorSystem.create("test");

    private static ActorMaterializer actorMaterializer =
            ActorMaterializer.create(actorSystem);

    private static StreamContext streamContext =
            new StreamContext(camelContext);

    private static List<Integer> numbers =
            asList(1, 2, 3);

    @SuppressWarnings("unchecked")
    private static <T, U extends Exception> U intercept(CompletionStage<T> completionStage, Class<U> clazz) {
        try {
            completionStage.toCompletableFuture().get();
            throw new AssertionError(null);
        } catch (ExecutionException e) {
            assertEquals(clazz, e.getCause().getClass());
            return (U) e.getCause();
        } catch (Exception e) {
            throw new AssertionError(null);
        }
    }

    @Override
    public StreamContext streamContext() {
        return streamContext;
    }

    @BeforeClass
    public static void beforeClass() throws Exception {
        camelRegistry.put("service", new JavaDslTestService());
        camelContext.setRegistry(camelRegistry);
        camelContext.start();
        streamContext.start();
    }

    @AfterClass
    public static void afterClass() throws Exception {
        streamContext.stop();
        camelContext.stop();
        actorSystem.terminate();
    }

    @Test
    public void receiveMustCreateStream() throws Exception {
        numbers.forEach(i -> streamContext.producerTemplate().sendBody("seda:q1", i));
        CompletionStage<List<Integer>> result = receiveBody("seda:q1", Integer.class).take(3).runWith(Sink.seq(), actorMaterializer);
        assertEquals(asList(1, 2, 3), result.toCompletableFuture().get());
    }

    @Test
    public void receiveMustCompleteWithErrorIfTypeConversionFails() throws Exception {
        streamContext.producerTemplate().sendBody("seda:q2", "a");
        CompletionStage<Done> result = receiveBody("seda:q2", Integer.class).runWith(Sink.ignore(), actorMaterializer);
        intercept(result, TypeConversionException.class);
    }

    @Test
    public void sendMustSendToEndpointAndContinueWithSentMessage() throws Exception {
        CompletionStage<List<Integer>> result = Source.from(numbers).via(sendBody("seda:q3")).take(3).runWith(Sink.seq(), actorMaterializer);
        numbers.forEach(i -> assertEquals(i, streamContext.consumerTemplate().receiveBody("seda:q3")));
        assertEquals(asList(1, 2, 3), result.toCompletableFuture().get());
    }

    @Test
    public void requestMustRequestFromEndpointAndContinueWithResponseMessage() throws Exception {
        CompletionStage<List<Integer>> result = Source.from(numbers).via(requestBody("bean:service?method=plusOne", Integer.class)).take(3).runWith(Sink.seq(), actorMaterializer);
        assertEquals(asList(2, 3, 4), result.toCompletableFuture().get());
    }

    @Test
    public void requestMustConvertResponseMessageTypesUsingCamelTypeConverter() throws Exception {
        CompletionStage<List<String>> result = Source.from(numbers).via(requestBody("bean:service?method=plusOne", String.class)).take(3).runWith(Sink.seq(), actorMaterializer);
        assertEquals(asList("2", "3", "4"), result.toCompletableFuture().get());
    }

    @Test
    public void requostMustCompleteWithErrorIfRequestFails() throws Exception {
        CompletionStage<List<Integer>> result = Source.from(asList(-1, 2, 3)).via(requestBody("bean:service?method=plusOne", Integer.class)).take(3).runWith(Sink.seq(), actorMaterializer);
        assertEquals("test", intercept(result, RuntimeException.class).getMessage());
    }
}

