package org.umlg.sqlg.structure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Preconditions;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.tinkerpop.gremlin.process.traversal.Path;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.umlg.sqlg.sql.dialect.SqlDialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;

import static org.umlg.sqlg.structure.SchemaManager.EDGE_PREFIX;
import static org.umlg.sqlg.structure.SchemaManager.VERTEX_PREFIX;
import static org.umlg.sqlg.structure.Topology.*;

/**
 * Date: 2016/09/04
 * Time: 8:49 AM
 */
public class Schema {

    private static Logger logger = LoggerFactory.getLogger(Schema.class.getName());
    private Topology topology;
    private String name;
    private Map<String, VertexLabel> vertexLabels = new HashMap<>();
    private Map<String, VertexLabel> uncommittedVertexLabels = new HashMap<>();

    private Map<String, EdgeLabel> outEdgeLabels = new HashMap<>();
    private Map<String, EdgeLabel> uncommittedOutEdgeLabels = new HashMap<>();

    /**
     * Creates the SqlgSchema. The sqlg_schema always exist and is created via sql in {@link SqlDialect#sqlgTopologyCreationScripts()}
     *
     * @param topology A reference to the {@link Topology} that contains the sqlg_schema schema.
     * @return The Schema that represents 'sqlg_schema'
     */
    static Schema instantiateSqlgSchema(Topology topology) {
        return new Schema(topology, SQLG_SCHEMA);
    }

    /**
     * Creates the 'public' schema that always already exist and is pre-loaded in {@link Topology()} @see {@link Topology#cacheTopology()}
     *
     * @param publicSchemaName The 'public' schema's name. Sometimes its upper case (Hsqldb) sometimes lower (Postgresql)
     * @param topology         The {@link Topology} that contains the public schema.
     * @return The Schema that represents 'public'
     */
    static Schema createPublicSchema(Topology topology, String publicSchemaName) {
        return new Schema(topology, publicSchemaName);
    }

    static Schema createSchema(SqlgGraph sqlgGraph, Topology topology, String name) {
        Schema schema = new Schema(topology, name);
        Preconditions.checkArgument(!name.equals(SQLG_SCHEMA) && !sqlgGraph.getSqlDialect().getPublicSchema().equals(name), "createSchema may not be called for 'sqlg_schema' or 'public'");
        schema.createSchemaOnDb(sqlgGraph);
        TopologyManager.addSchema(sqlgGraph, name);
        return schema;
    }

    static Schema instantiateSchema(Topology topology, String schemaName) {
        return new Schema(topology, schemaName);
    }

    private Schema(Topology topology, String name) {
        this.topology = topology;
        this.name = name;
    }

    VertexLabel ensureVertexLabelExist(final SqlgGraph sqlgGraph, final String label, final Map<String, PropertyType> columns) {
        Objects.requireNonNull(label, "Given table must not be null");
        Preconditions.checkArgument(!label.startsWith(VERTEX_PREFIX), String.format("label may not be prefixed with %s", VERTEX_PREFIX));

        Optional<VertexLabel> vertexLabelOptional = this.getVertexLabel(label);
        if (!vertexLabelOptional.isPresent()) {
            this.topology.lock();
            vertexLabelOptional = this.getVertexLabel(label);
            if (!vertexLabelOptional.isPresent()) {
                return this.createVertexLabel(sqlgGraph, label, columns);
            } else {
                return vertexLabelOptional.get();
            }
        } else {
            VertexLabel vertexLabel = vertexLabelOptional.get();
            //check if all the columns are there.
            vertexLabel.ensureColumnsExist(sqlgGraph, columns);
            return vertexLabel;
        }
    }

    EdgeLabel ensureEdgeLabelExist(final SqlgGraph sqlgGraph, final String edgeLabelName, final VertexLabel outVertexLabel, final VertexLabel inVertexLabel, Map<String, PropertyType> columns) {
        Objects.requireNonNull(edgeLabelName, "Given edgeLabelName may not be null");
        Objects.requireNonNull(outVertexLabel, "Given outVertexLabel may not be null");
        Objects.requireNonNull(inVertexLabel, "Given inVertexLabel may not be null");

        EdgeLabel edgeLabel;
        Optional<EdgeLabel> edgeLabelOptional = this.getEdgeLabel(edgeLabelName);
        SchemaTable foreignKeyOut = SchemaTable.of(this.name, outVertexLabel.getLabel());
        SchemaTable foreignKeyIn = SchemaTable.of(inVertexLabel.getSchema().name, inVertexLabel.getLabel());
        if (!edgeLabelOptional.isPresent()) {
            this.topology.lock();
            edgeLabelOptional = this.getEdgeLabel(edgeLabelName);
            if (!edgeLabelOptional.isPresent()) {
                edgeLabel = this.createEdgeLabel(sqlgGraph, edgeLabelName, outVertexLabel, inVertexLabel, columns);
                this.uncommittedOutEdgeLabels.put(edgeLabelName, edgeLabel);
                //nothing more to do as the edge did not exist and will have been created with the correct foreign keys.
            } else {
                edgeLabel = internalEnsureEdgeTableExists(sqlgGraph, foreignKeyOut, foreignKeyIn, edgeLabelOptional.get(), columns);
            }
        } else {
            edgeLabel = internalEnsureEdgeTableExists(sqlgGraph, foreignKeyOut, foreignKeyIn, edgeLabelOptional.get(), columns);
        }
        return edgeLabel;
    }

    private EdgeLabel internalEnsureEdgeTableExists(SqlgGraph sqlgGraph, SchemaTable foreignKeyOut, SchemaTable foreignKeyIn, EdgeLabel edgeLabel, Map<String, PropertyType> columns) {
        //need to check that the out foreign keys exist.
        Optional<VertexLabel> outVertexLabelOptional = this.getVertexLabel(foreignKeyOut.getTable());
        Preconditions.checkState(outVertexLabelOptional.isPresent(), "Out vertex label not found for %s.%s", foreignKeyIn.getSchema(), foreignKeyIn.getTable());

        //need to check that the in foreign keys exist.
        //The in vertex might be in a different schema so search on the topology
        Optional<VertexLabel> inVertexLabelOptional = this.topology.getVertexLabel(foreignKeyIn.getSchema(), foreignKeyIn.getTable());
        Preconditions.checkState(inVertexLabelOptional.isPresent(), "In vertex label not found for %s.%s", foreignKeyIn.getSchema(), foreignKeyIn.getTable());

        //noinspection OptionalGetWithoutIsPresent
        edgeLabel.ensureEdgeForeignKeysExist(sqlgGraph, false, outVertexLabelOptional.get(), foreignKeyOut);
        //noinspection OptionalGetWithoutIsPresent
        edgeLabel.ensureEdgeForeignKeysExist(sqlgGraph, true, inVertexLabelOptional.get(), foreignKeyIn);
        edgeLabel.ensureColumnsExist(sqlgGraph, columns);
        return edgeLabel;
    }

    @SuppressWarnings("OptionalGetWithoutIsPresent")
    private EdgeLabel createEdgeLabel(final SqlgGraph sqlgGraph, final String edgeLabelName, final VertexLabel outVertexLabel, final VertexLabel inVertexLabel, final Map<String, PropertyType> columns) {
        Preconditions.checkArgument(this.topology.isWriteLockHeldByCurrentThread(), "Lock must be held by the thread to call createEdgeLabel");
        Preconditions.checkArgument(!edgeLabelName.startsWith(EDGE_PREFIX), "edgeLabelName may not start with " + EDGE_PREFIX);
        Preconditions.checkState(!this.isSqlgSchema(), "createEdgeLabel may not be called for \"%s\"", SQLG_SCHEMA);

        Schema inVertexSchema = inVertexLabel.getSchema();

        //Edge may not already exist.
        Preconditions.checkState(!getEdgeLabel(edgeLabelName).isPresent(), "BUG: Edge \"%s\" already exists!", edgeLabelName);

        SchemaTable foreignKeyOut = SchemaTable.of(this.name, outVertexLabel.getLabel());
        SchemaTable foreignKeyIn = SchemaTable.of(inVertexSchema.name, inVertexLabel.getLabel());

        TopologyManager.addEdgeLabel(sqlgGraph, this.getName(), EDGE_PREFIX + edgeLabelName, foreignKeyOut, foreignKeyIn, columns);
        return outVertexLabel.addEdgeLabel(sqlgGraph, edgeLabelName, inVertexLabel, columns);
    }

    VertexLabel createSqlgSchemaVertexLabel(String vertexLabelName, Map<String, PropertyType> columns) {
        Preconditions.checkState(this.isSqlgSchema(), "createSqlgSchemaVertexLabel may only be called for \"%s\"", SQLG_SCHEMA);
        Preconditions.checkArgument(!vertexLabelName.startsWith(VERTEX_PREFIX), "vertex label may not start with " + VERTEX_PREFIX);
        VertexLabel vertexLabel = VertexLabel.createSqlgSchemaVertexLabel(this, vertexLabelName, columns);
        this.vertexLabels.put(vertexLabelName, vertexLabel);
        return vertexLabel;
    }

    VertexLabel createVertexLabel(SqlgGraph sqlgGraph, String vertexLabelName, Map<String, PropertyType> columns) {
        Preconditions.checkState(!this.isSqlgSchema(), "createVertexLabel may not be called for \"%s\"", SQLG_SCHEMA);
        Preconditions.checkArgument(!vertexLabelName.startsWith(VERTEX_PREFIX), "vertex label may not start with " + VERTEX_PREFIX);
        VertexLabel vertexLabel = VertexLabel.createVertexLabel(sqlgGraph, this, vertexLabelName, columns);
        this.uncommittedVertexLabels.put(vertexLabelName, vertexLabel);
        return vertexLabel;
    }

    public void ensureVertexColumnsExist(SqlgGraph sqlgGraph, String label, Map<String, PropertyType> columns) {
        Preconditions.checkArgument(!label.startsWith(VERTEX_PREFIX), "label may not start with \"%s\"", VERTEX_PREFIX);
        Preconditions.checkState(!isSqlgSchema(), "Schema.ensureVertexLabelPropertiesExist may not be called for \"%s\"", SQLG_SCHEMA);

        Optional<VertexLabel> vertexLabel = getVertexLabel(label);
        Preconditions.checkState(vertexLabel.isPresent(), String.format("BUG: vertexLabel \"%s\" must exist", label));

        //noinspection OptionalGetWithoutIsPresent
        vertexLabel.get().ensureColumnsExist(sqlgGraph, columns);
    }

    public void ensureEdgeColumnsExist(SqlgGraph sqlgGraph, String label, Map<String, PropertyType> columns) {
        Preconditions.checkArgument(!label.startsWith(EDGE_PREFIX), "label may not start with \"%s\"", EDGE_PREFIX);
        Preconditions.checkState(!isSqlgSchema(), "Schema.ensureEdgePropertiesExist may not be called for \"%s\"", SQLG_SCHEMA);

        Optional<EdgeLabel> edgeLabel = getEdgeLabel(label);
        Preconditions.checkState(edgeLabel.isPresent(), "BUG: edgeLabel \"%s\" must exist", label);
        //noinspection OptionalGetWithoutIsPresent
        edgeLabel.get().ensureColumnsExist(sqlgGraph, columns);
    }

    /**
     * Creates a new schema on the database. i.e. 'CREATE SCHEMA...' sql statement.
     *
     * @param sqlgGraph The graph.
     */
    private void createSchemaOnDb(SqlgGraph sqlgGraph) {
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE SCHEMA ");
        sql.append(sqlgGraph.getSqlDialect().maybeWrapInQoutes(this.name));
        if (sqlgGraph.getSqlDialect().needsSemicolon()) {
            sql.append(";");
        }
        if (logger.isDebugEnabled()) {
            logger.debug(sql.toString());
        }
        Connection conn = sqlgGraph.tx().getConnection();
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql.toString());
        } catch (SQLException e) {
            logger.error("schema creation failed " + sqlgGraph.toString(), e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Loads the existing schema from the topology.
     *
     * @param topology   The {@link Topology} that contains this schema.
     * @param schemaName The schema's name.
     * @return The loaded Schema.
     */
    static Schema loadUserSchema(Topology topology, String schemaName) {
        return new Schema(topology, schemaName);
    }


    public String getName() {
        return name;
    }

    public Topology getTopology() {
        return topology;
    }

    public Map<String, VertexLabel> getVertexLabels() {
        return this.vertexLabels;
    }

    public Map<String, VertexLabel> getUncommittedVertexLabels() {
        return this.uncommittedVertexLabels;
    }

    public boolean existVertexLabel(String vertexLabelName) {
        return getVertexLabel(vertexLabelName).isPresent();
    }

    Optional<VertexLabel> getVertexLabel(String vertexLabelName) {
        Preconditions.checkArgument(!vertexLabelName.startsWith(SchemaManager.VERTEX_PREFIX), "vertex label may not start with \"%s\"", SchemaManager.VERTEX_PREFIX);
        VertexLabel vertexLabel = this.vertexLabels.get(vertexLabelName);
        if (vertexLabel != null) {
            return Optional.of(vertexLabel);
        } else {
            if (this.topology.isWriteLockHeldByCurrentThread()) {
                vertexLabel = this.uncommittedVertexLabels.get(vertexLabelName);
                if (vertexLabel != null) {
                    return Optional.of(vertexLabel);
                } else {
                    return Optional.empty();
                }
            } else {
                return Optional.empty();
            }
        }
    }

    Map<String, EdgeLabel> getEdgeLabels() {
        Map<String, EdgeLabel> result = new HashMap<>();
        result.putAll(this.outEdgeLabels);
        if (this.topology.isWriteLockHeldByCurrentThread()) {
            for (VertexLabel vertexLabel : this.uncommittedVertexLabels.values()) {
                result.putAll(vertexLabel.getOutEdgeLabels());
            }
            for (VertexLabel vertexLabel : this.vertexLabels.values()) {
                result.putAll(vertexLabel.getOutEdgeLabels());
            }
        }
        return result;
    }

    Map<String, EdgeLabel> getUncommittedOutEdgeLabels() {
        Map<String, EdgeLabel> result = new HashMap<>();
        for (VertexLabel vertexLabel : this.vertexLabels.values()) {
            result.putAll(vertexLabel.getUncommittedOutEdgeLabels());
        }
        if (this.topology.isWriteLockHeldByCurrentThread()) {
            for (VertexLabel vertexLabel : this.uncommittedVertexLabels.values()) {
                result.putAll(vertexLabel.getUncommittedOutEdgeLabels());
            }
        }
        return result;
    }

    Optional<EdgeLabel> getEdgeLabel(String edgeLabelName) {
        Preconditions.checkArgument(!edgeLabelName.startsWith(SchemaManager.EDGE_PREFIX), "edge label may not start with \"%s\"", SchemaManager.EDGE_PREFIX);
        EdgeLabel edgeLabel = this.outEdgeLabels.get(edgeLabelName);
        if (edgeLabel != null) {
            return Optional.of(edgeLabel);
        }
        if (this.topology.isWriteLockHeldByCurrentThread()) {
            edgeLabel = this.uncommittedOutEdgeLabels.get(edgeLabelName);
            if (edgeLabel != null) {
                return Optional.of(edgeLabel);
            }
        }
        return Optional.empty();
    }

//    public Map<String, Map<String, PropertyType>> getAllTablesWithout(List<String> filter) {
//        Map<String, Map<String, PropertyType>> result = new HashMap<>();
//        for (Map.Entry<String, VertexLabel> vertexLabelEntry : this.vertexLabels.entrySet()) {
//            Preconditions.checkState(!vertexLabelEntry.getValue().getLabel().startsWith(VERTEX_PREFIX), "vertexLabel may not start with %s", VERTEX_PREFIX);
//            String vertexLabelQualifiedName = this.name + "." + VERTEX_PREFIX + vertexLabelEntry.getValue().getLabel();
//            if (!filter.contains(vertexLabelQualifiedName)) {
//                result.put(vertexLabelQualifiedName, vertexLabelEntry.getValue().getPropertyColumnMap());
//            }
//        }
//        if (this.topology.isWriteLockHeldByCurrentThread()) {
//            for (Map.Entry<String, VertexLabel> vertexLabelEntry : this.uncommittedVertexLabels.entrySet()) {
//                Preconditions.checkState(!vertexLabelEntry.getValue().getLabel().startsWith(VERTEX_PREFIX), "vertexLabel may not start with %s", VERTEX_PREFIX);
//                String vertexLabelQualifiedName = this.name + "." + VERTEX_PREFIX + vertexLabelEntry.getValue().getLabel();
//                if (!filter.contains(vertexLabelQualifiedName)) {
//                    result.put(vertexLabelQualifiedName, vertexLabelEntry.getValue().getPropertyColumnMap());
//                }
//            }
//        }
//        for (EdgeLabel edgeLabel : this.getEdgeLabels()) {
//            Preconditions.checkState(!edgeLabel.getLabel().startsWith(EDGE_PREFIX), "edgeLabel may not start with %s", EDGE_PREFIX);
//            String edgeLabelQualifiedName = edgeLabel.getSchema().getName() + "." + EDGE_PREFIX + edgeLabel.getLabel();
//            if (!filter.contains(edgeLabelQualifiedName)) {
//                result.put(edgeLabelQualifiedName, edgeLabel.getPropertyColumnMap());
//            }
//        }
//        return result;
//    }

//    Map<String, PropertyType> getAllTables() {
//        Map<String, AbstractLabel> result = new HashMap<>();
//        result.putAll(this.vertexLabels);
//        if (this.topology.isWriteLockHeldByCurrentThread()) {
//            result.putAll(this.uncommittedVertexLabels);
//        }
//        result.putAll(getEdgeLabels());
//        return result;
//    }

//    Map<String, AbstractLabel> getAllTables() {
//        Map<String, AbstractLabel> result = new HashMap<>();
//        result.putAll(this.vertexLabels);
//        if (this.topology.isWriteLockHeldByCurrentThread()) {
//            result.putAll(this.uncommittedVertexLabels);
//        }
//        result.putAll(getEdgeLabels());
//        return result;
//    }

    //remove in favour of PropertyColumn
    Map<String, Map<String, PropertyType>> getAllTables() {
        Map<String, Map<String, PropertyType>> result = new HashMap<>();
        for (Map.Entry<String, VertexLabel> vertexLabelEntry : this.vertexLabels.entrySet()) {
            String vertexQualifiedName = this.name + "." + VERTEX_PREFIX + vertexLabelEntry.getValue().getLabel();
            result.put(vertexQualifiedName, vertexLabelEntry.getValue().getPropertyTypeMap());
        }
        if (this.topology.isWriteLockHeldByCurrentThread()) {
            for (Map.Entry<String, VertexLabel> vertexLabelEntry : this.uncommittedVertexLabels.entrySet()) {
                String vertexQualifiedName = this.name + "." + VERTEX_PREFIX + vertexLabelEntry.getValue().getLabel();
                result.put(vertexQualifiedName, vertexLabelEntry.getValue().getPropertyTypeMap());
            }
        }
        for (EdgeLabel edgeLabel : this.getEdgeLabels().values()) {
            String edgeQualifiedName = this.name + "." + EDGE_PREFIX + edgeLabel.getLabel();
            result.put(edgeQualifiedName, edgeLabel.getPropertyTypeMap());
        }
        return result;
    }

    Map<String, AbstractLabel> getUncommittedLabels() {
        Map<String, AbstractLabel> result = new HashMap<>();
        for (Map.Entry<String, VertexLabel> vertexLabelEntry : this.vertexLabels.entrySet()) {
            String vertexQualifiedName = this.name + "." + VERTEX_PREFIX + vertexLabelEntry.getValue().getLabel();
            Map<String, PropertyColumn> uncommittedPropertyColumnMap = vertexLabelEntry.getValue().getUncommittedPropertyTypeMap();
            if (!uncommittedPropertyColumnMap.isEmpty()) {
                result.put(vertexQualifiedName, vertexLabelEntry.getValue());
            }
        }
        if (this.topology.isWriteLockHeldByCurrentThread()) {
            for (VertexLabel vertexLabel : this.uncommittedVertexLabels.values()) {
                result.put(this.name + "." + VERTEX_PREFIX + vertexLabel.getLabel(), vertexLabel);
            }
        }
        for (EdgeLabel edgeLabel : this.getUncommittedOutEdgeLabels().values()) {
            result.put(this.name + "." + EDGE_PREFIX + edgeLabel.getLabel(), edgeLabel);
        }
        return result;
    }


//    public Map<String, Map<String, PropertyType>> getAllTablesFrom(List<String> selectFrom) {
//        Map<String, Map<String, PropertyType>> result = new HashMap<>();
//        for (Map.Entry<String, VertexLabel> vertexLabelEntry : this.vertexLabels.entrySet()) {
//            Preconditions.checkState(!vertexLabelEntry.getValue().getLabel().startsWith(VERTEX_PREFIX), "vertexLabel may not start with %s", VERTEX_PREFIX);
//            String vertexQualifiedName = this.name + "." + VERTEX_PREFIX + vertexLabelEntry.getValue().getLabel();
//            if (selectFrom.contains(vertexQualifiedName)) {
//                result.put(vertexQualifiedName, vertexLabelEntry.getValue().getPropertyColumnMap());
//            }
//        }
//        if (this.topology.isWriteLockHeldByCurrentThread()) {
//            for (Map.Entry<String, VertexLabel> vertexLabelEntry : this.uncommittedVertexLabels.entrySet()) {
//                Preconditions.checkState(!vertexLabelEntry.getValue().getLabel().startsWith(VERTEX_PREFIX), "vertexLabel may not start with %s", VERTEX_PREFIX);
//                String vertexQualifiedName = this.name + "." + VERTEX_PREFIX + vertexLabelEntry.getValue().getLabel();
//                if (selectFrom.contains(vertexQualifiedName)) {
//                    result.put(vertexQualifiedName, vertexLabelEntry.getValue().getPropertyColumnMap());
//                }
//            }
//        }
//        for (EdgeLabel edgeLabel : this.getEdgeLabels()) {
//            Preconditions.checkState(!edgeLabel.getLabel().startsWith(EDGE_PREFIX), "edgeLabel may not start with %s", EDGE_PREFIX);
//            String edgeQualifiedName = this.name + "." + EDGE_PREFIX + edgeLabel.getLabel();
//            if (selectFrom.contains(edgeQualifiedName)) {
//                result.put(edgeQualifiedName, edgeLabel.getPropertyColumnMap());
//            }
//        }
//        return result;
//    }

//    Map<String, PropertyColumn> getTableFor(SchemaTable schemaTable) {
//        Optional<VertexLabel> vertexLabelOptional = getVertexLabel(schemaTable.getTable());
//        if (vertexLabelOptional.isPresent()) {
//            return vertexLabelOptional.get().getPropertyColumnMap();
//        } else {
//            Optional<EdgeLabel> edgeLabelOptional = getEdgeLabel(schemaTable.getTable());
//            if (edgeLabelOptional.isPresent()) {
//                return edgeLabelOptional.get().getPropertyColumnMap();
//            }
//        }
//        return Collections.emptyMap();
//    }

    //TODO remove this method. PropertyColumn must go all the way up the stack.
    Map<String, PropertyType> getTableFor(SchemaTable schemaTable) {
        Optional<VertexLabel> vertexLabelOptional = getVertexLabel(schemaTable.getTable());
        if (vertexLabelOptional.isPresent()) {
            return vertexLabelOptional.get().getPropertyTypeMap();
        } else {
            Optional<EdgeLabel> edgeLabelOptional = getEdgeLabel(schemaTable.getTable());
            if (edgeLabelOptional.isPresent()) {
                return edgeLabelOptional.get().getPropertyTypeMap();
            }
        }
        return Collections.emptyMap();
    }

    Map<SchemaTable, Pair<Set<SchemaTable>, Set<SchemaTable>>> getTableLabels() {
        Map<SchemaTable, Pair<Set<SchemaTable>, Set<SchemaTable>>> result = new HashMap<>();
        for (Map.Entry<String, VertexLabel> vertexLabelEntry : this.vertexLabels.entrySet()) {
            Preconditions.checkState(!vertexLabelEntry.getValue().getLabel().startsWith(VERTEX_PREFIX), "vertexLabel may not start with " + VERTEX_PREFIX);
            String prefixedVertexName = VERTEX_PREFIX + vertexLabelEntry.getValue().getLabel();
            SchemaTable schemaTable = SchemaTable.of(this.getName(), prefixedVertexName);
            result.put(schemaTable, vertexLabelEntry.getValue().getTableLabels());
        }
        Map<SchemaTable, Pair<Set<SchemaTable>, Set<SchemaTable>>> uncommittedResult = new HashMap<>();
        if (this.topology.isWriteLockHeldByCurrentThread()) {
            for (Map.Entry<String, VertexLabel> vertexLabelEntry : this.uncommittedVertexLabels.entrySet()) {
                Preconditions.checkState(!vertexLabelEntry.getValue().getLabel().startsWith(VERTEX_PREFIX), "vertexLabel may not start with " + VERTEX_PREFIX);
                String prefixedVertexName = VERTEX_PREFIX + vertexLabelEntry.getValue().getLabel();
                SchemaTable schemaTable = SchemaTable.of(this.getName(), prefixedVertexName);
                uncommittedResult.put(schemaTable, vertexLabelEntry.getValue().getTableLabels());
            }
        }
        //need to fromNotifyJson in the uncommitted table labels in.
        for (Map.Entry<SchemaTable, Pair<Set<SchemaTable>, Set<SchemaTable>>> schemaTablePairEntry : uncommittedResult.entrySet()) {
            SchemaTable schemaTable = schemaTablePairEntry.getKey();
            Pair<Set<SchemaTable>, Set<SchemaTable>> uncommittedForeignKeys = schemaTablePairEntry.getValue();
            Pair<Set<SchemaTable>, Set<SchemaTable>> foreignKeys = result.get(schemaTable);
            if (foreignKeys != null) {
                foreignKeys.getLeft().addAll(uncommittedForeignKeys.getLeft());
                foreignKeys.getRight().addAll(uncommittedForeignKeys.getRight());
            } else {
                result.put(schemaTable, uncommittedForeignKeys);
            }
        }
        return result;
    }

    public Optional<Pair<Set<SchemaTable>, Set<SchemaTable>>> getTableLabels(SchemaTable schemaTable) {
        Pair<Set<SchemaTable>, Set<SchemaTable>> result = null;
        for (Map.Entry<String, VertexLabel> vertexLabelEntry : this.vertexLabels.entrySet()) {
            Preconditions.checkState(!vertexLabelEntry.getValue().getLabel().startsWith(VERTEX_PREFIX), "vertexLabel may not start with " + VERTEX_PREFIX);
            String prefixedVertexName = VERTEX_PREFIX + vertexLabelEntry.getValue().getLabel();
            if (schemaTable.getTable().equals(prefixedVertexName)) {
                result = vertexLabelEntry.getValue().getTableLabels();
                break;
            }
        }
        Pair<Set<SchemaTable>, Set<SchemaTable>> uncommittedResult = null;
        if (this.topology.isWriteLockHeldByCurrentThread()) {
            for (Map.Entry<String, VertexLabel> vertexLabelEntry : this.uncommittedVertexLabels.entrySet()) {
                Preconditions.checkState(!vertexLabelEntry.getValue().getLabel().startsWith(VERTEX_PREFIX), "vertexLabel may not start with " + VERTEX_PREFIX);
                String prefixedVertexName = VERTEX_PREFIX + vertexLabelEntry.getValue().getLabel();
                if (schemaTable.getTable().equals(prefixedVertexName)) {
                    uncommittedResult = vertexLabelEntry.getValue().getTableLabels();
                    break;
                }
            }
        }
        //need to fromNotifyJson in the uncommitted table labels in.
        if (result != null && uncommittedResult != null) {
            result.getLeft().addAll(uncommittedResult.getLeft());
            result.getRight().addAll(uncommittedResult.getRight());
            return Optional.of(result);
        } else if (result != null) {
            return Optional.of(result);
        } else if (uncommittedResult != null) {
            return Optional.of(uncommittedResult);
        } else {
            return Optional.empty();
        }
    }

    public Map<String, Set<String>> getAllEdgeForeignKeys() {
        Map<String, Set<String>> result = new HashMap<>();
        for (EdgeLabel edgeLabel : this.getEdgeLabels().values()) {
            Preconditions.checkState(!edgeLabel.getLabel().startsWith(EDGE_PREFIX), "edgeLabel may not start with %s", EDGE_PREFIX);
            result.put(this.getName() + "." + EDGE_PREFIX + edgeLabel.getLabel(), edgeLabel.getAllEdgeForeignKeys());
        }
        return result;
    }

    void afterCommit() {
        if (this.getTopology().isWriteLockHeldByCurrentThread()) {
            for (Iterator<Map.Entry<String, VertexLabel>> it = this.uncommittedVertexLabels.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, VertexLabel> entry = it.next();
                this.vertexLabels.put(entry.getKey(), entry.getValue());
                it.remove();
            }
        }
        for (VertexLabel vertexLabel : this.vertexLabels.values()) {
            vertexLabel.afterCommit();
        }
        this.uncommittedOutEdgeLabels.clear();
    }

    void afterRollback() {
        if (this.getTopology().isWriteLockHeldByCurrentThread()) {
            for (Iterator<Map.Entry<String, VertexLabel>> it = this.uncommittedVertexLabels.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, VertexLabel> entry = it.next();
                entry.getValue().afterRollbackForInEdges();
                it.remove();
            }
            for (Iterator<Map.Entry<String, VertexLabel>> it = this.uncommittedVertexLabels.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, VertexLabel> entry = it.next();
                entry.getValue().afterRollbackForOutEdges();
                it.remove();
            }
        }
        for (VertexLabel vertexLabel : this.vertexLabels.values()) {
            vertexLabel.afterRollbackForInEdges();
        }
        for (VertexLabel vertexLabel : this.vertexLabels.values()) {
            vertexLabel.afterRollbackForOutEdges();
        }
        this.uncommittedOutEdgeLabels.clear();
    }

    public boolean isSqlgSchema() {
        return this.name.equals(SQLG_SCHEMA);
    }

    void loadVertexOutEdgesAndProperties(GraphTraversalSource traversalSource, Vertex schemaVertex) {
        //First load the vertex and its properties
        List<Path> vertices = traversalSource
                .V(schemaVertex)
                .out(SQLG_SCHEMA_SCHEMA_VERTEX_EDGE).as("vertex")
                //a vertex does not necessarily have properties so use optional.
                .optional(
                        __.out(SQLG_SCHEMA_VERTEX_PROPERTIES_EDGE).as("property")
                )
                .path()
                .toList();
        for (Path vertexProperties : vertices) {
            Vertex vertexVertex = null;
            Vertex propertyVertex = null;
            List<Set<String>> labelsList = vertexProperties.labels();
            for (Set<String> labels : labelsList) {
                for (String label : labels) {
                    switch (label) {
                        case "vertex":
                            vertexVertex = vertexProperties.get("vertex");
                            break;
                        case "property":
                            propertyVertex = vertexProperties.get("property");
                            break;
                        case "sqlgPathFakeLabel":
                            break;
                        default:
                            throw new IllegalStateException(String.format("BUG: Only \"vertex\" and \"property\" is expected as a label. Found %s", label));
                    }
                }
            }
            Preconditions.checkState(vertexVertex != null, "BUG: Topology vertex not found.");
            String tableName = vertexVertex.value("name");
            VertexLabel vertexLabel = this.vertexLabels.get(tableName);
            if (vertexLabel == null) {
                vertexLabel = new VertexLabel(this, tableName);
                this.vertexLabels.put(tableName, vertexLabel);
            }
            if (propertyVertex != null) {
                vertexLabel.addProperty(propertyVertex);
            }
        }

        //Load the out edges. This will load all edges as all edges have a out vertex.
        List<Path> outEdges = traversalSource
                .V(schemaVertex)
                .out(SQLG_SCHEMA_SCHEMA_VERTEX_EDGE).as("vertex")
                //a vertex does not necessarily have properties so use optional.
                .optional(
                        __.out(SQLG_SCHEMA_OUT_EDGES_EDGE).as("outEdgeVertex")
                                .optional(
                                        __.out(SQLG_SCHEMA_EDGE_PROPERTIES_EDGE).as("property")
                                )
                )
                .path()
                .toList();
        for (Path outEdgePath : outEdges) {
            List<Set<String>> labelsList = outEdgePath.labels();
            Vertex vertexVertex = null;
            Vertex outEdgeVertex = null;
            Vertex edgePropertyVertex = null;
            for (Set<String> labels : labelsList) {
                for (String label : labels) {
                    switch (label) {
                        case "vertex":
                            vertexVertex = outEdgePath.get("vertex");
                            break;
                        case "outEdgeVertex":
                            outEdgeVertex = outEdgePath.get("outEdgeVertex");
                            break;
                        case "property":
                            edgePropertyVertex = outEdgePath.get("property");
                            break;
                        case "sqlgPathFakeLabel":
                            break;
                        default:
                            throw new IllegalStateException(String.format("BUG: Only \"vertex\", \"outEdgeVertex\" and \"property\" is expected as a label. Found \"%s\"", label));
                    }
                }
            }
            Preconditions.checkState(vertexVertex != null, "BUG: Topology vertex not found.");
            String tableName = vertexVertex.value(SQLG_SCHEMA_VERTEX_LABEL_NAME);
            VertexLabel vertexLabel = this.vertexLabels.get(tableName);
            Preconditions.checkState(vertexLabel != null, "vertexLabel must be present when loading outEdges. Not found for \"%s\"", tableName);
            if (outEdgeVertex != null) {
                //load the EdgeLabel
                String edgeLabelName = outEdgeVertex.value(SQLG_SCHEMA_EDGE_LABEL_NAME);
                Optional<EdgeLabel> edgeLabelOptional = this.getEdgeLabel(edgeLabelName);
                EdgeLabel edgeLabel;
                if (!edgeLabelOptional.isPresent()) {
                    edgeLabel = EdgeLabel.loadFromDb(vertexLabel.getSchema().getTopology(), edgeLabelName);
                    vertexLabel.addToOutEdgeLabels(edgeLabel);
                } else {
                    edgeLabel = edgeLabelOptional.get();
                    vertexLabel.addToOutEdgeLabels(edgeLabel);
                }
                if (edgePropertyVertex != null) {
                    //load the property
                    edgeLabel.addProperty(edgePropertyVertex);
                }
                this.outEdgeLabels.put(edgeLabelName, edgeLabel);
            }
        }
    }

    void loadInEdgeLabels(GraphTraversalSource traversalSource, Vertex schemaVertex) {
        //Load the in edges via the out edges. This is necessary as the out vertex is needed to know the schema the edge is in.
        //As all edges are already loaded via the out edges this will only set the in edge association.
        List<Path> inEdges = traversalSource
                .V(schemaVertex)
                .out(SQLG_SCHEMA_SCHEMA_VERTEX_EDGE).as("vertex")
                //a vertex does not necessarily have properties so use optional.
                .optional(
                        __.out(SQLG_SCHEMA_OUT_EDGES_EDGE).as("outEdgeVertex")
                                .in(SQLG_SCHEMA_IN_EDGES_EDGE).as("inVertex")
                                .in(SQLG_SCHEMA_SCHEMA_VERTEX_EDGE).as("inSchema")
                )
                .path()
                .toList();
        for (Path inEdgePath : inEdges) {
            List<Set<String>> labelsList = inEdgePath.labels();
            Vertex vertexVertex = null;
            Vertex outEdgeVertex = null;
            Vertex inVertex = null;
            Vertex inSchemaVertex = null;
            for (Set<String> labels : labelsList) {
                for (String label : labels) {
                    switch (label) {
                        case "vertex":
                            vertexVertex = inEdgePath.get("vertex");
                            break;
                        case "outEdgeVertex":
                            outEdgeVertex = inEdgePath.get("outEdgeVertex");
                            break;
                        case "inVertex":
                            inVertex = inEdgePath.get("inVertex");
                            break;
                        case "inSchema":
                            inSchemaVertex = inEdgePath.get("inSchema");
                            break;
                        case "sqlgPathFakeLabel":
                            break;
                        default:
                            throw new IllegalStateException(String.format("BUG: Only \"vertex\", \"outEdgeVertex\" and \"inVertex\" are expected as a label. Found %s", label));
                    }
                }
            }
            Preconditions.checkState(vertexVertex != null, "BUG: Topology vertex not found.");
            String tableName = vertexVertex.value(SQLG_SCHEMA_VERTEX_LABEL_NAME);
            VertexLabel vertexLabel = this.vertexLabels.get(tableName);
            Preconditions.checkState(vertexLabel != null, "vertexLabel must be present when loading inEdges. Not found for %s", tableName);
            if (outEdgeVertex != null) {
                String edgeLabelName = outEdgeVertex.value(SQLG_SCHEMA_EDGE_LABEL_NAME);

                //inVertex and inSchema must be present.
                Preconditions.checkState(inVertex != null, "BUG: In vertex not found edge for \"%s\"", edgeLabelName);
                Preconditions.checkState(inSchemaVertex != null, "BUG: In schema vertex not found for edge \"%s\"", edgeLabelName);

                Optional<EdgeLabel> outEdgeLabelOptional = this.topology.getEdgeLabel(getName(), edgeLabelName);
                Preconditions.checkState(outEdgeLabelOptional.isPresent(), "BUG: EdgeLabel for \"%s\" should already be loaded", getName() + "." + edgeLabelName);
                //noinspection OptionalGetWithoutIsPresent
                EdgeLabel outEdgeLabel = outEdgeLabelOptional.get();

                String inVertexLabelName = inVertex.value(SQLG_SCHEMA_VERTEX_LABEL_NAME);
                String inSchemaVertexLabelName = inSchemaVertex.value(SQLG_SCHEMA_SCHEMA_NAME);
                Optional<VertexLabel> vertexLabelOptional = this.topology.getVertexLabel(inSchemaVertexLabelName, inVertexLabelName);
                Preconditions.checkState(vertexLabelOptional.isPresent(), "BUG: VertexLabel not found for schema %s and label %s", inSchemaVertexLabelName, inVertexLabelName);
                //noinspection OptionalGetWithoutIsPresent
                VertexLabel inVertexLabel = vertexLabelOptional.get();

                inVertexLabel.addToInEdgeLabels(outEdgeLabel);
            }
        }
    }

    public JsonNode toJson() {
        ObjectNode schemaNode = new ObjectNode(Topology.OBJECT_MAPPER.getNodeFactory());
        schemaNode.put("name", this.getName());
        ArrayNode vertexLabelArrayNode = new ArrayNode(Topology.OBJECT_MAPPER.getNodeFactory());
        for (VertexLabel vertexLabel : this.getVertexLabels().values()) {
            vertexLabelArrayNode.add(vertexLabel.toJson());
        }
        schemaNode.set("vertexLabels", vertexLabelArrayNode);
        return schemaNode;
    }

    public Optional<JsonNode> toNotifyJson() {
        boolean foundVertexLabels = false;
        ObjectNode schemaNode = new ObjectNode(Topology.OBJECT_MAPPER.getNodeFactory());
        schemaNode.put("name", this.getName());
        if (this.getTopology().isWriteLockHeldByCurrentThread() && !this.getUncommittedVertexLabels().isEmpty()) {
            ArrayNode vertexLabelArrayNode = new ArrayNode(Topology.OBJECT_MAPPER.getNodeFactory());
            for (VertexLabel vertexLabel : this.getUncommittedVertexLabels().values()) {
                //VertexLabel toNotifyJson always returns something even though its an Optional.
                //This is because it extends AbstractElement's toNotifyJson that does not always return something.
                @SuppressWarnings("OptionalGetWithoutIsPresent")
                JsonNode jsonNode = vertexLabel.toNotifyJson().get();
                vertexLabelArrayNode.add(jsonNode);
            }
            schemaNode.set("uncommittedVertexLabels", vertexLabelArrayNode);
            foundVertexLabels = true;
        }
        if (!this.getVertexLabels().isEmpty()) {
            ArrayNode vertexLabelArrayNode = new ArrayNode(Topology.OBJECT_MAPPER.getNodeFactory());
            for (VertexLabel vertexLabel : this.getVertexLabels().values()) {
                JsonNode notifyJson = vertexLabel.toNotifyJson().get();
                if (notifyJson.get("uncommittedProperties") != null ||
                        notifyJson.get("uncommittedOutEdgeLabels") != null ||
                        notifyJson.get("uncommittedInEdgeLabels") != null ||
                        notifyJson.get("outEdgeLabels") != null ||
                        notifyJson.get("inEdgeLabels") != null) {

                    vertexLabelArrayNode.add(notifyJson);
                    foundVertexLabels = true;
                }
            }
            if (vertexLabelArrayNode.size() > 0) {
                schemaNode.set("vertexLabels", vertexLabelArrayNode);
            }
        }
        if (foundVertexLabels) {
            return Optional.of(schemaNode);
        } else {
            return Optional.empty();
        }
    }

    void fromNotifyJsonOutEdges(JsonNode jsonSchema) {
        for (String s : Arrays.asList("vertexLabels", "uncommittedVertexLabels")) {
            JsonNode vertexLabels = jsonSchema.get(s);
            if (vertexLabels != null) {
                for (JsonNode vertexLabelJson : vertexLabels) {
                    String vertexLabelName = vertexLabelJson.get("label").asText();
                    Optional<VertexLabel> vertexLabelOptional = getVertexLabel(vertexLabelName);
                    VertexLabel vertexLabel;
                    if (vertexLabelOptional.isPresent()) {
                        vertexLabel = vertexLabelOptional.get();
                    } else {
                        vertexLabel = new VertexLabel(this, vertexLabelName);
                        this.vertexLabels.put(vertexLabelName, vertexLabel);
                    }
                    vertexLabel.fromNotifyJsonOutEdge(vertexLabelJson);
                    this.getTopology().addToAllTables(this.getName() + "." + VERTEX_PREFIX + vertexLabelName, vertexLabel.getPropertyTypeMap());
                }
            }
        }
    }

    void fromNotifyJsonInEdges(JsonNode jsonSchema) {
        for (String s : Arrays.asList("vertexLabels", "uncommittedVertexLabels")) {
            JsonNode vertexLabels = jsonSchema.get(s);
            if (vertexLabels != null) {
                for (JsonNode vertexLabelJson : vertexLabels) {
                    String vertexLabelName = vertexLabelJson.get("label").asText();
                    Optional<VertexLabel> vertexLabelOptional = getVertexLabel(vertexLabelName);
                    Preconditions.checkState(vertexLabelOptional.isPresent(), "VertexLabel must be present");
                    @SuppressWarnings("OptionalGetWithoutIsPresent")
                    VertexLabel vertexLabel = vertexLabelOptional.get();
                    vertexLabel.fromNotifyJsonInEdge(vertexLabelJson);
                }
            }
        }
    }

    @Override
    public int hashCode() {
        return this.name.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (o == null) {
            return false;
        }
        if (!(o instanceof Schema)) {
            return false;
        }
        //only equals on the name of the schema. it is assumed that the VertexLabels (tables) are the same.
        Schema other = (Schema) o;
        return this.name.equals(other.name);
    }

    public boolean deepEquals(Schema other) {
        Preconditions.checkState(this.name.equals(other.name), "deepEquals is called after the regular equals. i.e. the names must be equals");
        if (!(this.vertexLabels.equals(other.getVertexLabels()))) {
            return false;
        } else {
            if (!this.vertexLabels.equals(other.getVertexLabels())) {
                return false;
            }
            for (Map.Entry<String, VertexLabel> vertexLabelEntry : this.vertexLabels.entrySet()) {
                VertexLabel vertexLabel = vertexLabelEntry.getValue();
                VertexLabel otherVertexLabel = other.getVertexLabels().get(vertexLabelEntry.getKey());
                if (!vertexLabel.deepEquals(otherVertexLabel)) {
                    return false;
                }
            }
            return true;
        }
    }

    @Override
    public String toString() {
        return "schema: " + this.name;
    }

    void cacheEdgeLabels() {
        for (Iterator<Map.Entry<String, VertexLabel>> it = this.vertexLabels.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, VertexLabel> entry = it.next();
            for (EdgeLabel edgeLabel : entry.getValue().getOutEdgeLabels().values()) {
                this.outEdgeLabels.put(edgeLabel.getLabel(), edgeLabel);
            }
        }
    }

    void addToAllEdgeCache(EdgeLabel edgeLabel) {
        if (!this.outEdgeLabels.containsKey(edgeLabel.getLabel())) {
            this.outEdgeLabels.put(edgeLabel.getLabel(), edgeLabel);
        } else {
            this.outEdgeLabels.put(edgeLabel.getLabel(), edgeLabel);
        }
    }
}