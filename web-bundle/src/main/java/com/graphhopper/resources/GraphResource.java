package com.graphhopper.resources;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Stopwatch;
import com.graphhopper.graphtool.Edge;
import com.graphhopper.graphtool.Point;
import com.graphhopper.graphtool.Shortcut;
import com.graphhopper.routing.util.AllCHEdgesIterator;
import com.graphhopper.storage.CHGraphImpl;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.storage.NodeAccess;
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
import java.util.List;

@Path("graph")
public class GraphResource {

    private static final Logger logger = LoggerFactory.getLogger(GraphResource.class);
    private final GraphHopperStorage storage;

    @Inject
    public GraphResource(GraphHopperStorage storage) {
        this.storage = storage;
    }

    @GET
    @Produces({MediaType.APPLICATION_JSON})
    public Response doGet(
            @QueryParam("northEastLat") double northEastLat,
            @QueryParam("northEastLng") double northEastLng,
            @QueryParam("southWestLat") double southWestLat,
            @QueryParam("southWestLng") double southWestLng
    ) {
        Stopwatch sw = Stopwatch.createStarted();
        List<Edge> edges = new ArrayList<>();
        List<Shortcut> shortcuts = new ArrayList<>();
        NodeAccess na = storage.getNodeAccess();
        CHGraphImpl chGraph = storage.getGraph(CHGraphImpl.class);
        AllCHEdgesIterator iter = chGraph.getAllEdges();
        while (iter.next()) {
            int from = iter.getBaseNode();
            int to = iter.getAdjNode();
            Point pointFrom = new Point(from, chGraph.getLevel(from), na.getLat(from), na.getLon(from));
            Point pointTo = new Point(to, chGraph.getLevel(to), na.getLat(to), na.getLon(to));
            double maxLat = Math.max(pointFrom.lat, pointTo.lat);
            double maxLon = Math.max(pointFrom.lon, pointTo.lon);
            double minLat = Math.min(pointFrom.lat, pointTo.lat);
            double minLon = Math.min(pointFrom.lon, pointTo.lon);
            if (maxLat > northEastLat || maxLon > northEastLng || minLat < southWestLat || minLon < southWestLng) {
                continue;
            }
            if (iter.isShortcut()) {
                Shortcut shortcut = new Shortcut();
                shortcut.id = iter.getEdge();
                shortcut.from = pointFrom;
                shortcut.to = pointTo;
                shortcut.levelFrom = chGraph.getLevel(from);
                shortcut.levelTo = chGraph.getLevel(to);
                shortcut.weight = iter.getWeight();
                shortcuts.add(shortcut);
            } else {
                Edge edge = new Edge();
                edge.id = iter.getEdge();
                edge.from = pointFrom;
                edge.to = pointTo;
                edge.weight = iter.getWeight();
                edges.add(edge);
            }
        }

        ObjectNode json = JsonNodeFactory.instance.objectNode();
        json.putPOJO("edges", edges);
        json.putPOJO("shortcuts", shortcuts);
        logger.info("found " + edges.size() + " edges and " + shortcuts.size() + " shortcuts, took: " + sw);
        return Response.ok(json).build();
    }
}
