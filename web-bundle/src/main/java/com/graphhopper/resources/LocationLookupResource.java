package com.graphhopper.resources;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.graphhopper.GraphHopper;
import com.graphhopper.graphtool.Edge;
import com.graphhopper.graphtool.Point;
import com.graphhopper.routing.QueryGraph;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.storage.Graph;
import com.graphhopper.storage.NodeAccess;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.storage.index.QueryResult;
import com.graphhopper.util.EdgeIterator;
import com.graphhopper.util.EdgeIteratorState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Path("location-lookup")
public class LocationLookupResource {

    private static final Logger logger = LoggerFactory.getLogger(LocationLookupResource.class);
    private final GraphHopper hopper;

    @Inject
    public LocationLookupResource(GraphHopper hopper) {
        this.hopper = hopper;
    }

    @GET
    @Produces({MediaType.APPLICATION_JSON})
    public Response doGet(
            @QueryParam("lat") double lat,
            @QueryParam("lng") double lng
    ) {
        LocationIndex index = hopper.getLocationIndex();

        // on CH graph (only changes virtual edge ids ?)
        // Weighting weighting = hopper.getCHFactoryDecorator().getWeightings().get(0);
        // Graph routingGraph = hopper.getGraphHopperStorage().getGraph(CHGraph.class, weighting);
        // QueryResult queryResult = index.findClosest(lat, lng, EdgeFilter.ALL_EDGES);
        // QueryGraph queryGraph = new QueryGraph(routingGraph);
        // queryGraph.lookup(Collections.singletonList(queryResult));

        Graph routingGraph = hopper.getGraphHopperStorage();
        QueryResult queryResult = index.findClosest(lat, lng, EdgeFilter.ALL_EDGES);
        QueryGraph queryGraph = new QueryGraph(routingGraph);
        queryGraph.lookup(Collections.singletonList(queryResult));

        List<Edge> virtualEdges = new ArrayList<>();
        NodeAccess virtualNodeAccess = queryGraph.getNodeAccess();
        int closestNode = queryResult.getClosestNode();
        EdgeIterator iter = queryGraph.createEdgeExplorer().setBaseNode(closestNode);
        while (iter.next()) {
            Edge edge = new Edge();
            edge.from = getPoint(iter.getBaseNode(), virtualNodeAccess);
            edge.to = getPoint(iter.getAdjNode(), virtualNodeAccess);
            virtualEdges.add(edge);
        }

        ObjectNode json = JsonNodeFactory.instance.objectNode();

        EdgeIteratorState closestEdge = queryResult.getClosestEdge();
        ObjectNode closestEdgeNode = json.putObject("closestEdge");
        int closestEdgeFrom = closestEdge.getBaseNode();
        int closestEdgeTo = closestEdge.getAdjNode();
        closestEdgeNode.put("edge", closestEdge.getEdge());
        NodeAccess nodeAccess = hopper.getGraphHopperStorage().getNodeAccess();
        closestEdgeNode.putPOJO("from", getPoint(closestEdgeFrom, nodeAccess));
        closestEdgeNode.putPOJO("to", getPoint(closestEdgeTo, nodeAccess));

        json.putPOJO("virtualEdges", virtualEdges);
        json.putPOJO("valid", queryResult.isValid());
        json.putPOJO("queryPoint", queryResult.getQueryPoint());
        json.putPOJO("snappedPoint", queryResult.getSnappedPoint());
        json.put("snappedPosition", queryResult.getSnappedPosition().toString());
        // mostly redundant information, but added for debugging
        json.putPOJO("result", queryResult);
        logger.info(json.toString());
        return Response.ok(json).build();
    }

    private Point getPoint(int nodeId, NodeAccess na) {
        return new Point(nodeId, -1, na.getLat(nodeId), na.getLon(nodeId));
    }
}
