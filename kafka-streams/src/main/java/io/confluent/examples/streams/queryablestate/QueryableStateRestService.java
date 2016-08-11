/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements.  See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.  You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.confluent.examples.streams.queryablestate;

import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.apache.kafka.streams.state.ReadOnlyWindowStore;
import org.apache.kafka.streams.state.StreamsMetadata;
import org.apache.kafka.streams.state.WindowStoreIterator;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.ws.rs.GET;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

/**
 *  A simple REST proxy that runs embedded in the {@link QueryableStateExample}. This is used to
 *  demonstrate how a developer can use the Queryable State APIs exposed by Kafka Streams to
 *  locate and query the State Stores within a Kafka Streams Application.
 */
@Path("state")
public class QueryableStateRestService {

  private final KafkaStreams streams;
  private Server jettyServer;

  QueryableStateRestService(final KafkaStreams streams) {
    this.streams = streams;
  }

  /**
   * Get a key-value pair from a KeyValue Store
   * @param storeName   the store to look in
   * @param key         the key to get
   * @return {@link KeyValueBean} representing the key-value pair
   */
  @GET
  @Path("/keyvalue/{storeName}/{key}")
  @Produces(MediaType.APPLICATION_JSON)
  public KeyValueBean byKey(@PathParam("storeName") final String storeName,
                        @PathParam("key") final String key) {

    // Lookup the KeyValueStore with the provided storeName
    final ReadOnlyKeyValueStore<String, Long> store = streams.store(storeName, QueryableStoreTypes.<String, Long>keyValueStore());
    if (store == null) {
      throw new NotFoundException();
    }

    // Get the value from the store
    final Long value = store.get(key);
    if (value == null) {
      throw new NotFoundException();
    }
    return new KeyValueBean(key, value);
  }

  /**
   * Get all of the key-value pairs available in a store
   * @param storeName   store to query
   * @return A List of {@link KeyValueBean}s representing all of the key-values in the provided
   * store
   */
  @GET()
  @Path("/keyvalues/{storeName}/all")
  @Produces(MediaType.APPLICATION_JSON)
  public List<KeyValueBean> allForStore(@PathParam("storeName") final String storeName) {
    return rangeForKeyValueStore(storeName, ReadOnlyKeyValueStore::all);
  }


  /**
   * Get all of the key-value pairs that have keys within the range from...to
   * @param storeName   store to query
   * @param from        start of the range (inclusive)
   * @param to          end of the range (inclusive)
   * @return A List of {@link KeyValueBean}s representing all of the key-values in the provided
   * store that fall withing the given range.
   */
  @GET()
  @Path("/keyvalues/{storeName}/range/{from}/{to}")
  @Produces(MediaType.APPLICATION_JSON)
  public List<KeyValueBean> keyRangeForStore(@PathParam("storeName") final String storeName,
                                             @PathParam("from") final String from,
                                             @PathParam("to") final String to) {
    return rangeForKeyValueStore(storeName, store -> store.range(from, to));
  }

  /**
   * Query a window store for key-value pairs representing the value for a provided key within a
   * range of windows
   * @param storeName   store to query
   * @param key         key to look for
   * @param from        time of earliest window to query
   * @param to          time of latest window to query
   * @return A List of {@link KeyValueBean}s representing the key-values for the provided key
   * across the provided window range.
   */
  @GET()
  @Path("/windowed/{storeName}/{key}/{from}/{to}")
  @Produces(MediaType.APPLICATION_JSON)
  public List<KeyValueBean> windowedByKey(@PathParam("storeName") final String storeName,
                                          @PathParam("key") final String key,
                                          @PathParam("from") final Long from,
                                          @PathParam("to") final Long to) {

    // Lookup the WindowStore with the provided storeName
    final ReadOnlyWindowStore<String, Long> store = streams.store(storeName,
                                                                  QueryableStoreTypes.<String, Long>windowStore());
    if (store == null) {
      throw new NotFoundException();
    }

    // fetch the window results for the given key and time range
    final WindowStoreIterator<Long> results = store.fetch(key, from, to);

    final List<KeyValueBean> windowResults = new ArrayList<>();
    while (results.hasNext()) {
      final KeyValue<Long, Long> next = results.next();
      // convert the result to have the window time and the key (for display purposes)
      windowResults.add(new KeyValueBean(key + "@" + next.key, next.value));
    }
    return windowResults;
  }

  /**
   * Get the metadata for all of the instances of this Kafka Streams application
   * @return List of {@link HostStoreInfo}
   */
  @GET()
  @Path("/instances")
  @Produces(MediaType.APPLICATION_JSON)
  public List<HostStoreInfo> streamsMetadata() {
    // Get metadata for all of the instances of this Kafka Streams application
    final Collection<StreamsMetadata> metadata = streams.allMetadata();
    return mapInstancesToHostStoreInfo(metadata);
  }

  /**
   * Get the metadata for all instances of this Kafka Streams application that currently
   * has the provided store.
   * @param store   The store to locate
   * @return  List of {@link HostStoreInfo}
   */
  @GET()
  @Path("/instances/{storeName}")
  @Produces(MediaType.APPLICATION_JSON)
  public List<HostStoreInfo> streamsMetadataForStore(@PathParam("storeName") String store) {
    // Get metadata for all of the instances of this Kafka Streams application hosting the store
    final Collection<StreamsMetadata> metadata = streams.allMetadataForStore(store);
    return mapInstancesToHostStoreInfo(metadata);
  }

  /**
   * Find the metadata for the instance of this Kafka Streams Application that has the given
   * store and would have the given key if it exists.
   * @param store   Store to find
   * @param key     The key to find
   * @return {@link HostStoreInfo}
   */
  @GET()
  @Path("/instance/{storeName}/{key}")
  @Produces(MediaType.APPLICATION_JSON)
  public HostStoreInfo streamsMetadataForStoreAndKey(@PathParam("storeName") String store,
                                                     @PathParam("key") String key) {
    // Get metadata for the instances of this Kafka Streams application hosting the store and
    // potentially the value for key
    final StreamsMetadata metadata = streams.metadataForKey(store, key, new StringSerializer());
    if (metadata == null) {
      throw new NotFoundException();
    }

    return new HostStoreInfo(metadata.host(),
                             metadata.port(),
                             metadata.stateStoreNames());
  }

  private List<HostStoreInfo> mapInstancesToHostStoreInfo(
      final Collection<StreamsMetadata> metadatas) {
    return metadatas.stream().map(metadata -> new HostStoreInfo(metadata.host(),
                                                                metadata.port(),
                                                                metadata.stateStoreNames()))
        .collect(Collectors.toList());
  }

  /**
   * Performs a range query on a KeyValue Store and converts the results into a List of
   * {@link KeyValueBean}
   * @param storeName       The store to query
   * @param rangeFunction   The range query to run, i.e., all, from(start, end)
   * @return  List of {@link KeyValueBean}
   */
  private List<KeyValueBean> rangeForKeyValueStore(final String storeName,
                                                   final Function<ReadOnlyKeyValueStore<String, Long>,
                                                       KeyValueIterator<String, Long>> rangeFunction) {

    // Get the KeyValue Store
    final ReadOnlyKeyValueStore<String, Long> store = streams.store(storeName, QueryableStoreTypes.keyValueStore());
    if (store == null) {
      throw new NotFoundException();
    }

    final List<KeyValueBean> results = new ArrayList<>();
    // Apply the function, i.e., query the store
    final KeyValueIterator<String, Long> range = rangeFunction.apply(store);

    // Convert the results
    while (range.hasNext()) {
      final KeyValue<String, Long> next = range.next();
      results.add(new KeyValueBean(next.key, next.value));
    }

    return results;
  }

  /**
   * Start an embedded Jetty Server on the given port
   * @param port    port to run the Server on
   * @throws Exception
   */
  void start(final int port) throws Exception {
    ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
    context.setContextPath("/");

    jettyServer = new Server(port);
    jettyServer.setHandler(context);

    ResourceConfig rc = new ResourceConfig();
    rc.register(this);

    ServletContainer sc = new ServletContainer(rc);
    ServletHolder holder = new ServletHolder(sc);
    context.addServlet(holder, "/*");

    jettyServer.start();
  }

  /**
   * Stop the Jetty Server
   * @throws Exception
   */
  void stop() throws Exception {
    if (jettyServer != null) {
      jettyServer.stop();
    }
  }

}

