package org.ezstack.denormalizer.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;
import org.apache.samza.application.StreamApplication;
import org.apache.samza.config.Config;
import org.apache.samza.operators.MessageStream;
import org.apache.samza.operators.StreamGraph;
import org.apache.samza.operators.functions.FlatMapFunction;
import org.apache.samza.operators.functions.SinkFunction;
import org.apache.samza.storage.kv.KeyValueStore;
import org.apache.samza.system.OutgoingMessageEnvelope;
import org.apache.samza.system.SystemStream;
import org.apache.samza.task.MessageCollector;
import org.apache.samza.task.TaskContext;
import org.apache.samza.task.TaskCoordinator;
import org.ezstack.denormalizer.model.DocumentMessage;
import org.ezstack.denormalizer.serde.JsonSerdeV3;
import org.ezstack.denormalizer.model.Document;
import org.ezstack.ezapp.datastore.api.KeyBuilder;
import org.ezstack.ezapp.datastore.api.Query;
import org.ezstack.ezapp.datastore.api.Update;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;

public class DocumentResolverApp implements StreamApplication {

    private static final Logger log = LoggerFactory.getLogger(DocumentResolverApp.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    Query query = createSampleQuery();
    List queries = new ArrayList<Query>();
    Map<String, Query> queryMap = new HashMap<>();

    public void init(StreamGraph streamGraph, Config config) {

        // TODO: move input stream name into properties
        MessageStream<Update> updates = streamGraph.getInputStream("documents", new JsonSerdeV3<>(Update.class));
        
        MessageStream<Document> documents = updates.flatMap(new ResolveFunction());

        IndexToESFunction indexToESFunction = new IndexToESFunction();

        documents.sink(indexToESFunction);

        documents.flatMap(new FanoutFunction()).flatMap(new JoinFunction()).sink(indexToESFunction);
//        updates
//                .flatMap(new ResolveFunction())
//                .sink(new IndexToESFunction());
    }

    private void buildQueryMap() {

    }

    private Query createSampleQuery() {
//        String jsonObject = "{\n" +
//                "  \"searchType\" : [],\n" +
//                "  \"table\" : \"teachers\",\n" +
//                "  \"join\" : {\n" +
//                "    \"table\": \"students\"\n" +
//                "  },\n" +
//                "  \"joinAttributeName\" : \"students\",\n" +
//                "  \"joinAttributes\" : [\n" +
//                "    {\n" +
//                "      \"outerAttribute\" : \"id\",\n" +
//                "      \"innerAttribute\" : \"teacher_id\"\n" +
//                "    }\n" +
//                "  ]\n" +
//                "}";
        String jsonObject = "{\n" +
                "  \"searchType\" : [],\n" +
                "  \"table\" : \"students\",\n" +
                "  \"join\" : {\n" +
                "    \"table\": \"teachers\"\n" +
                "  },\n" +
                "  \"joinAttributeName\" : \"teacher\",\n" +
                "  \"joinAttributes\" : [\n" +
                "    {\n" +
                "      \"outerAttribute\" : \"teacher_id\",\n" +
                "      \"innerAttribute\" : \"id\"\n" +
                "    }\n" +
                "  ]\n" +
                "}";
        try {
            return mapper.readValue(jsonObject, Query.class);
        } catch (Exception e) {
            log.error(e.toString());
            return null;
        }
    }

    private class KeyPartitionFunction implements Function<DocumentMessage, String> {

        @Override
        public String apply(DocumentMessage documentMessage) {
            return documentMessage.getPartitionKey();
        }
    }

    private class FanoutFunction implements FlatMapFunction<Document, DocumentMessage> {

        // This is a simple pass through implementation for the time being
        @Override
        public Collection<DocumentMessage> apply(Document document) {
            return ImmutableSet.of(new DocumentMessage(document, document.getKey()));
        }
    }

    private class JoinFunction implements FlatMapFunction<DocumentMessage, Document> {

        private KeyValueStore<String, Map<String, Object>> store;

        @Override
        public void init(Config config, TaskContext context) {
            store = (KeyValueStore<String, Map<String, Object>>) context.getStore("join-store");
        }

//        @Override
//        public Collection<Document> apply(org.ezstack.denormalizer.model.DocumentMessage message) {
//
//            if (query.getJoin() == null) {
//                log.info("join is null");
//                return ImmutableSet.of();
//            }
//
//            Document document = message.getDocument();
//            String joinAttName = query.getJoinAttributeName();
//
//            if (query.getTable().equals(document.getTable())) {
//                String dbKey = document.getData().get(query.getJoinAttributes().get(0).getOuterAttribute()).toString();
//                if (dbKey == null) return ImmutableSet.of(document);
//                Document storedDoc = mapper.convertValue(store.get(dbKey), Document.class);
//                document.getData().put(joinAttName, storedDoc != null ? storedDoc.getData().get(joinAttName) : ImmutableSet.of());
//                document.setTable(("denormalizedtablename"));
//                store.put(dbKey, mapper.convertValue(document, Map.class));
//                return ImmutableSet.of(document);
//            }
//
//            else if (document.getTable().equals(query.getJoin().getTable())) {
//                String dbKey = document.getData().get(query.getJoinAttributes().get(0).getInnerAttribute()).toString();
//                if (dbKey == null) return ImmutableSet.of();
//                Document storedDoc = mapper.convertValue(store.get(dbKey), Document.class);
//
//                if (storedDoc == null) {
//                    storedDoc = new Document(document.getTable(), null, null,
//                            null, new HashMap<String, Object>(), 0);
//                    storedDoc.getData().put(joinAttName, ImmutableSet.of(document.getData()));
//                    store.put(dbKey, mapper.convertValue(document, Map.class));
//                    return ImmutableSet.of();
//                }
//
//                List<Document> results = mapper.convertValue(storedDoc.getData().get(joinAttName), new TypeReference<List<Document>>(){});
//                boolean objectAdded = false;
//                for (int i = 0; i < results.size(); i++) {
//                    if (results.get(i).getKey().equals(document.getKey())) {
//                        results.set(i, document);
//                        objectAdded = true;
//                        break;
//                    }
//                }
//
//                if (!objectAdded) {
//                    results.add(document);
//                }
//
//                storedDoc.getData().put(joinAttName, mapper.convertValue(results, new TypeReference<List<Map<String, Object>>>(){}));
//
//                store.put(dbKey, mapper.convertValue(storedDoc, Map.class));
//
//                if (storedDoc.getKey() != null) {
//                    storedDoc.setTable("denormalizedtablename");
//                    return ImmutableSet.of(storedDoc);
//                }
//
//                return ImmutableSet.of();
//
//
//            }
//
//            return ImmutableSet.of();
//        }

        private Map<String, Map<String, Document>> getJoinDataStructure(Query query) {
            Query currentQuery = query;
            Integer index = 0;
            Map<String, Map<String, Document>> indexMap = new LinkedHashMap<>();
            do {
                indexMap.put(index.toString(), new HashMap<>());
                index++;
            } while (query.getJoin() != null);

            return indexMap;
        }

        public Collection<Document> apply2(DocumentMessage message) {
            if (query.getJoin() == null) {
                log.info("join is null");
                return ImmutableSet.of();
            }

            Document document = message.getDocument();
            String joinAttName = query.getJoinAttributeName();

            if (query.getTable().equals(document.getTable())) {
                String dbKey = document.getData().get(query.getJoinAttributes().get(0).getOuterAttribute()).toString();

                if (dbKey == null) return ImmutableSet.of(document);

                Map<String, Object> storedIndex = store.get(dbKey);

                if (storedIndex == null) {

                }

            }

        }
    }


    private class ResolveFunction implements FlatMapFunction<Update, Document> {

        private KeyValueStore<String, Map<String, Object>> store;

        @Override
        public void init(Config config, TaskContext context) {
            store = (KeyValueStore<String, Map<String, Object>>) context.getStore("document-resolver");
        }

        @Override
        public Collection<Document> apply(Update update) {
            String storeKey = KeyBuilder.hashKey(update.getTable(), update.getKey());
            Document storedDocument = mapper.convertValue(store.get(storeKey), Document.class);

            if (storedDocument == null) {
                storedDocument = new Document(update);
                store.put(storeKey, mapper.convertValue(storedDocument, Map.class));
                return ImmutableSet.of(storedDocument);
            }

            int versionBeforeUpdate = storedDocument.getVersion();
            storedDocument.addUpdate(update);
            if (storedDocument.getVersion() != versionBeforeUpdate) {
                store.put(storeKey, mapper.convertValue(storedDocument, Map.class));
                return ImmutableSet.of(storedDocument);
            }

            return ImmutableSet.of();
        }
    }

    private class IndexToESFunction implements SinkFunction<Document> {

        @Override
        public void apply(Document document, MessageCollector messageCollector, TaskCoordinator taskCoordinator) {
            messageCollector.send(new OutgoingMessageEnvelope(new SystemStream("elasticsearch", document.getTable() + "/" + document.getTable()),
                    document.getKey(), document.getData()));
        }
    }

}